// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Les vues de supervision : observer → comprendre → décider → agir → vérifier.

import {
  $, ago, api, busy, circuitBreakersValue, clockTime, confirmAction, definition, dismissDrawer,
  downloadCsv, drawerOpen, duration, el, empty, errorState, frag, freshnessTag, inOperatorPeriod, loading,
  openDrawer, operatorContext, params, percent, registerDrawer, render, report, setOperatorContext, setParams, skeleton, sortable, sparkline, stamp, stateMark,
  stateTag, toast,
} from './core.js';

const BASE = '/api/agent/supervision';
const PROCESS_FILTER_STORAGE = 'kex.agent.filters.processes';
const DECISION_FILTER_STORAGE = 'kex.agent.filters.decisions';
const FAVORITES_STORAGE = 'kex.agent.favorite-processes';
const SAVED_VIEWS_STORAGE = 'kex.agent.saved-process-views';

function stored(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key)) || fallback;
  } catch {
    return fallback;
  }
}

function remember(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* Le filtrage reste utilisable sans stockage persistant. */
  }
}

const CAPABILITIES = {
  NOTIFY: 'Notifier',
  CREATE_INCIDENT: 'Créer un incident',
  RESTART_CONSUMER: 'Redémarrer un consumer',
  REPLAY_MESSAGES: 'Rejouer des messages',
  MODIFY_CONFIGURATION: 'Modifier une configuration',
};

const AUTONOMY = {
  AUTOMATIC: 'Automatique',
  SUPERVISED: 'Validation humaine',
  FORBIDDEN: 'Interdit',
};

const STOP_REASONS = {
  TIME_BUDGET: 'budget de temps épuisé',
  TOPIC_LIMIT: 'plafond de topics atteint',
  RECORD_LIMIT: 'plafond d’enregistrements atteint',
  CANCELLED: 'relevé annulé',
  PARTIAL_FAILURE: 'une source a échoué',
  NOT_REPORTED: 'aucune enveloppe de couverture rendue',
};

const DECISION_LABELS = {
  PENDING_APPROVAL: 'Validation requise',
  EXECUTED: 'Exécutée',
  REJECTED: 'Refusée',
  FAILED: 'Échec',
  EXPIRED: 'Expirée sans réponse',
  BLOCKED: 'Recommandation seule',
  SIMULATED: 'Simulée (aucun outil lié)',
};

const DECISION_STATES = {
  PENDING_APPROVAL: 'PENDING',
  EXECUTED: 'OK',
  REJECTED: 'UNKNOWN',
  FAILED: 'ERROR',
  EXPIRED: 'WARNING',
  BLOCKED: 'WARNING',
  SIMULATED: 'WARNING',
};

const AGENT_STATES = {
  OPERATIONAL: { tag: 'OK', label: 'OPÉRATIONNEL', mark: '●' },
  // Distinct d'OPÉRATIONNEL et distinct de DÉGRADÉ : rien n'a été mesuré, ce qui n'affirme ni
  // que tout va bien, ni que quelque chose va mal.
  UNKNOWN: { tag: 'UNKNOWN', label: 'EN ATTENTE D’ANALYSE', mark: '?' },
  DEGRADED: { tag: 'WARNING', label: 'DÉGRADÉ', mark: '▲' },
  ERROR: { tag: 'ERROR', label: 'EN ERREUR', mark: '✕' },
  PAUSED: { tag: 'PAUSED', label: 'EN PAUSE', mark: '⏸' },
  ANALYSING: { tag: 'RUNNING', label: 'ANALYSE EN COURS', mark: '◌' },
};

/** Un seul dictionnaire d'états : l'en-tête et la fiche Agent ne peuvent pas diverger. */
export const agentState = (state) =>
  AGENT_STATES[state] || { tag: 'UNKNOWN', label: 'EN ATTENTE D’ANALYSE', mark: '?' };

// Cache du dernier overview : plusieurs vues en dépendent, et deux requêtes concurrentes
// afficheraient des compteurs qui se contredisent d'un panneau à l'autre.
let snapshot = null;
let previousCycle = null;
let comparedCycle = null;
let animateCycleChanges = false;
let lastHeroState = null;
const watchers = new Set();

export const onSnapshot = (watcher) => watchers.add(watcher);

export async function refresh() {
  const next = await api(`${BASE}/overview`);
  const cycle = next.agent?.lastCycleId || next.agent?.lastCycleAt;
  const oldCycle = snapshot?.agent?.lastCycleId || snapshot?.agent?.lastCycleAt;
  animateCycleChanges = Boolean(cycle && oldCycle && cycle !== oldCycle);
  if (animateCycleChanges) {
    previousCycle = snapshot;
    comparedCycle = cycle;
  }
  snapshot = next;
  watchers.forEach((watcher) => watcher(snapshot));
  return snapshot;
}

export const current = () => snapshot;

function matchesOperatorProcess(item, context) {
  if (!context.process) return true;
  return item.processId === context.process || item.id === context.process;
}

function operatorTimestamp(item) {
  return item.lastRun || item.lastSeenAt || item.firstSeenAt || item.decidedAt || item.resolvedAt
    || item.createdAt || item.at || item.expiresAt;
}

function contextual(data) {
  const context = operatorContext();
  const processes = (data.processes || []).filter((item) =>
    matchesOperatorProcess(item, context) && inOperatorPeriod(item.lastRun, context));
  const alerts = (data.alerts || []).filter((item) =>
    matchesOperatorProcess(item, context) && inOperatorPeriod(operatorTimestamp(item), context));
  const pending = (data.pending || []).filter((item) =>
    matchesOperatorProcess(item, context) && inOperatorPeriod(operatorTimestamp(item), context));
  if (!context.process && context.period === 'all') return data;
  return {
    ...data, processes, alerts, pending,
    processesMonitored: processes.length,
    processesOk: processes.filter((item) => item.state === 'OK').length,
    processesWarning: processes.filter((item) => item.state === 'WARNING').length,
    processesError: processes.filter((item) => item.state === 'ERROR').length,
    processesUnknown: processes.filter((item) => item.state === 'UNKNOWN').length,
    anomaliesDetected: alerts.length,
    pendingApprovals: pending.length,
  };
}

/* ── Vue d'ensemble ────────────────────────────────────────────────────── */

export async function overview() {
  const host = $('#kpis');
  if (!host.querySelector('.kpis-grid')) host.replaceChildren(skeleton('kpis', 'Analyse des processus…'));
  try {
    const data = contextual(await refresh());
    renderOverviewHero(data);
    $('#agent-brief').replaceChildren(agentBrief(data));
    host.replaceChildren(kpis(data, cycleBaseline(data)));
    $('#incident-banner').replaceChildren(...incidentBanners(data.incidents));
    $('#overview-processes').replaceChildren(processTable(prioritized(data.processes), openProcess, 8, COMPACT));
    $('#overview-attention').replaceChildren(attention(data));
    // Un panneau qui attend une décision n'a pas à ressembler à un panneau qui n'a rien à signaler.
    const pendingCount = (data.pending || []).length;
    const attentionPanel = $('#overview-attention').closest('.panel');
    if (pendingCount) attentionPanel.dataset.state = 'PENDING';
    else delete attentionPanel.dataset.state;
    $('#overview-timeline').replaceChildren(timeline(data.lastCycle));
  } catch (error) {
    host.replaceChildren(errorState(error, overview));
    $('#overview-processes').replaceChildren();
    $('#overview-attention').replaceChildren();
    $('#overview-timeline').replaceChildren();
  }
}

function agentBrief(data) {
  const wrap = el('div', 'agent-brief');
  const alert = data.alerts?.[0];
  const pending = data.pending?.length || 0;
  if (!alert) {
    const message = data.processesMonitored
      ? `Le dernier cycle n’a relevé aucune anomalie active sur les ${data.processesMonitored} processus surveillés.`
      : 'Aucun processus n’est encore configuré : l’agent ne peut pas établir de diagnostic.';
    wrap.append(el('p', 'brief-lead', message));
    wrap.append(el('p', 'hint', pending ? `${pending} décision(s) reste(nt) néanmoins à traiter.`
      : 'Aucune intervention humaine n’est requise.'));
    return wrap;
  }
  const severity = alert.severity === 'ERROR' ? 'critique' : 'à surveiller';
  wrap.append(el('p', 'brief-lead',
    `${alert.processName} est ${severity} : ${alert.analysis || alert.title}.`));
  if (alert.probableCause) wrap.append(el('p', null, `Cause probable : ${alert.probableCause}`));
  if (alert.recommendation) wrap.append(el('p', 'hint', `Recommandation : ${alert.recommendation}`));
  const actions = el('div', 'brief-actions');
  const inspect = el('button', 'ghost', 'Examiner l’alerte');
  inspect.type = 'button';
  inspect.addEventListener('click', () => openAnomaly(alert));
  actions.append(inspect);
  if (alert.pendingDecisionId) {
    const decide = el('button', 'primary', 'Ouvrir la décision');
    decide.type = 'button';
    decide.addEventListener('click', () => openDecision(alert.pendingDecisionId));
    actions.append(decide);
  }
  wrap.append(actions);
  return wrap;
}

export function liveCycle(progress) {
  const host = $('#cycle-live');
  const hero = $('#overview-cycle');
  if (!progress) {
    host.hidden = true;
    host.replaceChildren();
    hero.classList.remove('is-running');
    $('#overview-cycle-label').textContent = snapshot?.agent?.lastCycleAt
      ? `Dernier cycle · ${stamp(snapshot.agent.lastCycleAt)}` : 'En attente du premier cycle';
    return;
  }
  hero.classList.add('is-running');
  $('#overview-cycle-label').textContent = `Analyse en cours · ${progress.events?.length || 0} étape(s) atteinte(s)`;
  host.hidden = false;
  const heading = el('div', 'cycle-live-head');
  heading.append(el('span', 'live-pulse'));
  heading.append(el('strong', null, 'Analyse en cours'));
  heading.append(el('span', 'muted', `${progress.events?.length || 0} étape(s) atteinte(s)`));
  host.replaceChildren(heading, timeline(progress));
}

/* ── File d'action ─────────────────────────────────────────────────────── */

export async function attentionView() {
  await render($('#attention-workspace'), async () => contextual(await refresh()), (data) => {
    const severity = { ERROR: 0, WARNING: 1, UNKNOWN: 2, OK: 3 };
    const pending = [...(data.pending || [])].sort((a, b) => {
      const left = a.expiresAt ? new Date(a.expiresAt).getTime() : Number.MAX_SAFE_INTEGER;
      const right = b.expiresAt ? new Date(b.expiresAt).getTime() : Number.MAX_SAFE_INTEGER;
      return left - right || (b.confidence || 0) - (a.confidence || 0);
    });
    const alerts = [...(data.alerts || [])].sort((a, b) =>
      (severity[a.severity] ?? 9) - (severity[b.severity] ?? 9)
      || new Date(a.firstSeenAt || a.lastSeenAt || 0) - new Date(b.firstSeenAt || b.lastSeenAt || 0));
    const degraded = (data.processes || [])
      .filter((process) => ['WARNING', 'ERROR', 'UNKNOWN'].includes(process.state))
      .sort((a, b) => (severity[a.state] ?? 9) - (severity[b.state] ?? 9)
        || new Date(a.lastRun || 0) - new Date(b.lastRun || 0));

    const total = pending.length + alerts.length + degraded.length;
    const critical = alerts.filter((item) => item.severity === 'ERROR').length
      + degraded.filter((item) => item.state === 'ERROR').length;
    const workspace = el('div', 'attention-grid');
    if (!data.processesMonitored && !(current()?.processes?.length)) {
      workspace.append(empty('Aucun processus sous surveillance.',
        'Déclarez votre premier processus pour remplir cette file avec des observations réelles.',
        { href: '#/chat?creation=process', label: 'Préparer un processus' }));
      return workspace;
    }
    const summary = el('section', 'attention-summary');
    const copy = el('div', 'attention-summary-copy');
    copy.append(el('strong', null, `${total} élément${total === 1 ? '' : 's'} à traiter`),
      el('span', 'muted', 'Triés par impact, urgence puis ancienneté.'));
    const metrics = el('div', 'attention-summary-metrics');
    metrics.append(
      el('span', critical ? 'attention-pill critical' : 'attention-pill', `${critical} critique${critical === 1 ? '' : 's'}`),
      el('span', 'attention-pill', `${pending.length} décision${pending.length === 1 ? '' : 's'}`),
      el('span', 'attention-pill', `${alerts.length} alerte${alerts.length === 1 ? '' : 's'}`),
      el('span', 'attention-pill', `${degraded.length} processus`),
    );
    summary.append(copy, metrics);
    workspace.append(summary);
    workspace.append(attentionGroup('Décisions à valider', pending, approvalCard,
      'Aucune validation en attente.', '#/decisions', 'Voir les décisions'));
    workspace.append(attentionGroup('Alertes actives', alerts, alertCard,
      'Aucune alerte active.', '#/alerts', 'Voir les alertes'));
    workspace.append(attentionGroup('Processus dégradés', degraded, processAttentionCard,
      'Tous les processus mesurés sont opérationnels.', '#/processes', 'Voir les processus'));
    return workspace;
  });
}

/* ── Cockpit incident et explorateur de preuves ─────────────────────────────── */

export async function incidents() {
  await render($('#incident-workspace'), async () => {
    const data = contextual(await refresh());
    const context = operatorContext();
    const history = (await api(`${BASE}/decisions`).catch(() => data.pending || []))
      .filter((item) => matchesOperatorProcess(item, context) && inOperatorPeriod(operatorTimestamp(item), context));
    return { data, history };
  }, ({ data, history }) => incidentWorkspace(data, history));
}

function incidentWorkspace(data, decisionHistory) {
  const candidates = incidentCandidates(data);
  if (!candidates.length) {
    if (!data.processesMonitored) return empty('Aucun processus à examiner.',
      'Préparez un processus surveillé pour que l’agent puisse rechercher des anomalies.',
      { href: '#/chat?creation=process', label: 'Préparer un processus' });
    if (!data.agent?.lastCycleAt) return empty('Aucun cycle exécuté.',
      'L’agent pourra afficher les incidents après sa première analyse.',
      { href: '#/overview', label: 'Lancer une analyse' });
    return empty('Aucun incident actif.',
      'Les processus surveillés n’ont pas d’incident actif au dernier cycle.',
      { href: '#/overview', label: 'Voir la synthèse' });
  }
  const selectedId = params().get('incident');
  const selected = candidates.find((candidate) => candidate.id === selectedId) || candidates[0];
  if (selected.id !== selectedId) setParams({ incident: selected.id }, true);

  const layout = el('div', 'incident-cockpit');
  const queue = el('nav', 'incident-queue');
  queue.setAttribute('aria-label', 'Incidents actifs');
  queue.append(el('h2', null, `Incidents actifs · ${candidates.length}`));
  candidates.forEach((candidate) => {
    const button = el('button', candidate.id === selected.id ? 'incident-choice selected' : 'incident-choice');
    button.type = 'button';
    button.setAttribute('aria-pressed', String(candidate.id === selected.id));
    button.append(stateTag(candidate.severity), el('strong', null, candidate.title),
      el('span', 'muted', `${candidate.alerts.length} symptôme(s) · ${ago(candidate.detectedAt) || 'à l’instant'}`));
    const evidenceCount = candidate.alerts.reduce((count, alert) => count + (alert.observations?.length || 0), 0);
    button.append(el('span', 'incident-choice-evidence',
      `${evidenceCount} observation(s) · ${candidate.severity === 'ERROR' ? 'État en erreur' : 'À examiner'}`));
    button.dataset.state = candidate.severity;
    button.addEventListener('click', () => {
      setParams({ incident: candidate.id });
      $('#incident-workspace').replaceChildren(incidentWorkspace(data, decisionHistory));
    });
    queue.append(button);
  });
  layout.append(queue, incidentDetail(selected, data, decisionHistory));
  return layout;
}

function incidentCandidates(data) {
  const alerts = data.alerts || [];
  const claimed = new Set();
  const correlated = (data.incidents || []).map((incident) => {
    const alertIds = new Set(incident.alertIds || []);
    const related = alerts.filter((alert) => alertIds.has(alert.id));
    related.forEach((alert) => claimed.add(alert.id));
    return {
      id: `cycle:${incident.cycleId}`,
      cycleId: incident.cycleId,
      title: `${incident.processCount} processus touchés simultanément`,
      severity: incident.severity,
      detectedAt: incident.detectedAt,
      alerts: related,
      processNames: incident.processNames,
      hypothesis: 'La concomitance signale une cause commune possible ; elle ne l’établit pas.',
    };
  });
  const standalone = alerts.filter((alert) => !claimed.has(alert.id)).map((alert) => ({
    id: `alert:${alert.id}`,
    cycleId: null,
    title: alert.title,
    severity: alert.severity,
    detectedAt: alert.lastSeenAt,
    alerts: [alert],
    processNames: [alert.processName],
    hypothesis: alert.analysis || 'Analyse en attente.',
  }));
  return [...correlated, ...standalone].sort((left, right) =>
    Number(right.severity === 'ERROR') - Number(left.severity === 'ERROR')
      || String(right.detectedAt).localeCompare(String(left.detectedAt)));
}

function incidentDetail(incident, data, decisionHistory) {
  const detail = el('article', 'incident-detail');
  detail.dataset.state = incident.severity;
  const head = el('header', 'incident-detail-head');
  const copy = el('div');
  copy.append(el('span', 'panel-kicker', incident.cycleId ? `Cycle ${incident.cycleId}` : 'Alerte active'));
  copy.append(el('h2', null, incident.title));
  copy.append(el('p', 'muted', `${incident.processNames.join(', ')} · détecté ${ago(incident.detectedAt) || 'à l’instant'}`));
  head.append(copy, contextChatButton('Interroger l’agent', `Incident · ${incident.title}`,
    incidentContext(incident), incident.id, 'primary'));
  detail.append(head, el('p', 'incident-hypothesis', incident.hypothesis));

  const related = relatedDecisions(incident, decisionHistory);
  const evidenceCount = incident.alerts.reduce((count, alert) => count + (alert.observations?.length || 0), 0);
  const facts = el('div', 'incident-facts');
  facts.append(el('span', null, `${incident.alerts.length} symptôme(s) actif(s)`),
    el('span', null, `${evidenceCount} observation(s) documentée(s)`),
    el('span', null, related.some((decision) => decision.status === 'PENDING_APPROVAL')
      ? 'Décision à valider' : incident.alerts.some((alert) => alert.recommendation)
        ? 'Recommandation disponible' : 'Investigation à poursuivre'));
  detail.append(facts);
  detail.append(recommendedActionSection(incident, related));

  const symptoms = incidentSection('Symptômes actifs', 'Ce que le dernier état confirme');
  if (incident.alerts.length) incident.alerts.forEach((alert) => symptoms.append(incidentSymptom(alert)));
  else symptoms.append(empty('Aucune alerte active correspondante.',
    'La corrélation du cycle reste visible, mais ses alertes ne sont plus actives.'));
  detail.append(symptoms);

  const evidence = incidentSection('Explorateur de preuves',
    'Chaque conclusion reste reliée à ses observations, sa fraîcheur et sa couverture.');
  if (incident.alerts.length) incident.alerts.forEach((alert) =>
    evidence.append(evidenceNode(alert, data.processes || [])));
  else evidence.append(empty('Aucune preuve active à explorer.'));
  detail.append(evidence);

  const decisionsSection = incidentSection('Décisions liées',
    'Actions proposées ou déjà tranchées pour les processus concernés.');
  if (!related.length) decisionsSection.append(empty('Aucune décision liée.'));
  related.slice(0, 6).forEach((decision) => decisionsSection.append(incidentDecision(decision)));
  detail.append(decisionsSection);

  const chronology = incidentSection('Chronologie', 'Du premier symptôme au dernier relevé connu.');
  chronology.append(incidentTimeline(incident, data.lastCycle));
  detail.append(chronology);
  return detail;
}

function recommendedActionSection(incident, related) {
  const section = incidentSection('Prochaine action recommandée',
    'Une proposition exploitable, avec son fondement, son impact et le niveau de confiance.');
  const pending = (related || []).find((decision) => decision.status === 'PENDING_APPROVAL');
  const recommendedAlert = [...incident.alerts]
    .filter((alert) => alert.recommendation)
    .sort((left, right) => (right.confidence || 0) - (left.confidence || 0))[0];

  if (!pending && !recommendedAlert) {
    section.append(empty('Aucune action proposée.',
      'L’incident reste observable sans inventer de remédiation : poursuivez l’investigation avec l’agent.'));
    section.append(contextChatButton('Investiguer avec l’agent', `Incident · ${incident.title}`,
      incidentContext(incident), incident.id, 'primary'));
    return section;
  }

  const card = el('article', 'recommended-action');
  if (pending) {
    card.dataset.state = 'PENDING';
    card.append(
      stateTag('PENDING', 'Validation requise'),
      el('h4', null, pending.action),
      definition('Pourquoi', el('span', null, pending.context || pending.objective || incident.hypothesis || '—')),
      definition('Impact estimé', el('span', null, pending.estimatedImpact || 'Non renseigné')),
      confidenceBar(pending.confidence, pending.observations?.length),
    );
    card.append(decisionActions(pending));
  } else {
    card.dataset.state = recommendedAlert.severity;
    card.append(
      stateTag(recommendedAlert.severity, 'Recommandation'),
      el('h4', null, recommendedAlert.recommendation),
      definition('Pourquoi', el('span', null, recommendedAlert.analysis || recommendedAlert.title)),
      definition('Impact estimé', el('span', null, 'À confirmer avant exécution')),
      confidenceBar(recommendedAlert.confidence, recommendedAlert.observations?.length),
    );
    const actions = el('div', 'row-end');
    const inspect = el('button', 'ghost', 'Voir l’alerte');
    inspect.type = 'button';
    inspect.addEventListener('click', () => openAnomaly(recommendedAlert));
    actions.append(inspect, contextChatButton('Investiguer', `Alerte · ${recommendedAlert.title}`,
      alertContext(recommendedAlert), `alert:${recommendedAlert.id}`, 'primary'));
    card.append(actions);
  }
  section.append(card);
  return section;
}

function incidentSection(title, subtitleText) {
  const section = el('section', 'incident-section');
  section.append(el('h3', null, title), el('p', 'hint', subtitleText));
  return section;
}

function incidentSymptom(alert) {
  const item = el('div', 'incident-symptom');
  item.append(stateTag(alert.severity), el('strong', null, alert.title),
    el('span', 'muted', `${alert.processName} · ${alert.occurrences || 1} relevé(s)`));
  const inspect = el('button', 'ghost', 'Détail');
  inspect.type = 'button';
  inspect.addEventListener('click', () => openAnomaly(alert));
  item.append(inspect);
  return item;
}

function evidenceNode(alert, processes) {
  const process = processes.find((candidate) => candidate.processId === alert.processId);
  const node = el('details', 'evidence-node');
  const summary = el('summary');
  summary.append(stateTag(alert.severity), el('strong', null, alert.title),
    el('span', 'muted', `${alert.observations?.length || 0} observation(s)`));
  node.append(summary);
  const body = el('div', 'evidence-body');
  const provenance = el('div', 'evidence-provenance');
  provenance.append(
    definition('Source', el('span', null, `Supervision · ${alert.processName}`)),
    definition('Fraîcheur', el('span', null, `${ago(alert.lastSeenAt) || 'à l’instant'} (${stamp(alert.lastSeenAt)})`)),
    definition('Couverture', process?.coverage ? coverageTag(process.coverage) : el('span', 'muted', 'Non renseignée')),
    definition('Nature', el('span', null, alert.observations?.length ? 'Faits observés + analyse' : 'Analyse sans mesure structurée')),
  );
  body.append(provenance);
  if (alert.observations?.length) {
    const observations = el('ul', 'observations');
    alert.observations.forEach((observation) => {
      observations.append(el('li', null, `${observation.label} : ${observation.value}`));
    });
    body.append(observations);
  } else {
    body.append(empty('Aucune mesure structurée.', 'La conclusion ne doit pas être lue comme une preuve mesurée.'));
  }
  body.append(el('p', null, alert.analysis || 'Aucune analyse fournie.'));
  if (alert.probableCause) body.append(el('p', 'muted', `Cause probable : ${alert.probableCause}`));
  const raw = el('details', 'evidence-raw technical-id');
  raw.append(el('summary', null, 'Données brutes'), el('pre', 'dump', JSON.stringify({
    id: alert.id, processId: alert.processId, observations: alert.observations,
    firstSeenAt: alert.firstSeenAt, lastSeenAt: alert.lastSeenAt, confidence: alert.confidence,
    probableCause: alert.probableCause, recommendation: alert.recommendation,
    knowledgeReference: alert.knowledgeReference, pendingDecisionId: alert.pendingDecisionId,
    decisionIds: alert.decisionIds,
  }, null, 2)));
  body.append(raw);
  node.append(body);
  return node;
}

function relatedDecisions(incident, decisions) {
  const decisionIds = new Set(incident.alerts.flatMap((alert) => [
    ...(alert.decisionIds || []),
    alert.pendingDecisionId,
  ].filter(Boolean)));
  return (decisions || []).filter((decision) => decisionIds.has(decision.id))
    .sort((left, right) => String(right.decidedAt).localeCompare(String(left.decidedAt)));
}

function incidentDecision(decision) {
  const card = el('div', 'incident-decision');
  card.append(stateTag(DECISION_STATES[decision.status], DECISION_LABELS[decision.status] || decision.status),
    el('strong', null, decision.action), el('span', 'muted', `${decision.processName} · ${stamp(decision.decidedAt)}`));
  const open = el('button', 'ghost', 'Comprendre');
  open.type = 'button';
  open.addEventListener('click', () => openDecision(decision.id));
  card.append(open);
  return card;
}

function incidentTimeline(incident, lastCycle) {
  const events = incident.alerts.flatMap((alert) => [
    { at: alert.firstSeenAt, label: 'Premier symptôme', detail: `${alert.processName} · ${alert.title}` },
    ...(alert.lastSeenAt !== alert.firstSeenAt
      ? [{ at: alert.lastSeenAt, label: 'Symptôme encore actif', detail: alert.processName }]
      : []),
  ]);
  if (lastCycle && (!incident.cycleId || lastCycle.id === incident.cycleId)) events.push(...lastCycle.events);
  return timeline({ events: events.sort((left, right) => String(left.at).localeCompare(String(right.at))), failure: null });
}

const incidentContext = (incident) => [
  `Analyse l’incident « ${incident.title} ».`,
  `Gravité : ${incident.severity}. Processus : ${incident.processNames.join(', ')}.`,
  incident.cycleId && `Cycle : ${incident.cycleId}.`,
  ...incident.alerts.map((alert) => [
    `Symptôme : ${alert.title} sur ${alert.processName}.`,
    alert.analysis && `Analyse actuelle : ${alert.analysis}`,
    alert.observations?.length && `Observations : ${alert.observations.map((item) => `${item.label}=${item.value}`).join(', ')}.`,
  ].filter(Boolean).join(' ')),
  'Distingue les faits, les hypothèses et les prochaines actions.',
].filter(Boolean).join('\n');

function attentionGroup(title, items, card, emptyMessage, href, label) {
  const section = el('section', 'attention-group');
  const head = el('header');
  head.append(el('h2', null, title), el('span', 'attention-count', String(items.length)));
  section.append(head);
  if (!items.length) {
    section.append(empty(emptyMessage, null, { href, label }));
  } else {
    const list = el('div', 'attention-list');
    items.forEach((item) => list.append(card(item)));
    section.append(list);
  }
  return section;
}

function processAttentionCard(process) {
  const card = el('article', 'card compact-card');
  card.dataset.state = process.state;
  const before = cycleBaseline(current())?.processes?.find((row) => row.processId === process.processId);
  if (before && before.state !== process.state) {
    card.append(el('span', 'process-change-label', `${before.state} → ${process.state} au dernier cycle`));
    if (animateCycleChanges) card.classList.add('just-changed');
  }
  const head = el('header');
  head.append(el('h3', null, process.name), stateTag(process.state));
  card.append(head, el('p', 'muted', process.note || `Dernier relevé : ${clockTime(process.lastRun)}`));
  const inspect = el('button', 'ghost', 'Examiner');
  inspect.type = 'button';
  inspect.addEventListener('click', () => openProcess(process));
  card.append(inspect);
  return card;
}

function renderOverviewHero(data) {
  const agent = agentState(data.agent?.state);
  const total = data.processesMonitored || 0;
  const healthy = data.processesOk || 0;
  const attention = (data.processesWarning || 0) + (data.processesError || 0);
  const summary = total
    ? `${healthy} processus opérationnel${healthy === 1 ? '' : 's'} sur ${total}`
      + (attention ? ` · ${attention} nécessite${attention > 1 ? 'nt' : ''} votre attention` : ' · aucune intervention requise')
    : 'Aucun processus n’est encore déclaré pour la supervision.';
  $('#overview-summary').textContent = summary;
  const health = $('#overview-health');
  health.dataset.state = agent.tag;
  health.querySelector('.hero-health-mark').textContent = agent.mark;
  $('#overview-health-label').textContent = agent.label;
  if (lastHeroState && lastHeroState !== agent.tag) {
    health.classList.remove('just-changed');
    // Relancer l'animation seulement après un changement d'état mesuré.
    void health.offsetWidth;
    health.classList.add('just-changed');
  }
  lastHeroState = agent.tag;
  const running = Boolean(data.agent?.analysing);
  $('#overview-cycle').classList.toggle('is-running', running);
  $('#overview-cycle-label').textContent = running ? 'Analyse en cours'
    : data.agent?.lastCycleAt ? `Dernier cycle · ${stamp(data.agent.lastCycleAt)}`
      : 'En attente du premier cycle';
}

function cycleBaseline(data) {
  const cycle = data.agent?.lastCycleId || data.agent?.lastCycleAt;
  return cycle && cycle === comparedCycle && previousCycle ? contextual(previousCycle) : null;
}

function delta(value, previous) {
  if (!Number.isFinite(value) || !Number.isFinite(previous)) return 'Comparaison indisponible';
  const difference = value - previous;
  return difference === 0 ? 'Stable depuis le cycle précédent'
    : `${difference > 0 ? '+' : '−'}${Math.abs(difference)} depuis le cycle précédent`;
}

function kpis(data, before) {
  const wrap = el('div', 'kpis-grid');
  wrap.append(
    kpi('Processus surveillés', data.processesMonitored, subtitle(data), '#/processes', null, 'processes',
      before && delta(data.processesMonitored, before.processesMonitored)),
    kpi('Dernière analyse', clockTime(data.agent.lastCycleAt),
      data.agent.staleSince ? 'Données potentiellement obsolètes' : ago(data.agent.lastCycleAt) || 'Jamais',
      '#/audit', data.agent.staleSince ? 'WARNING' : null, 'cycle'),
    kpi('Alertes actives', data.anomaliesDetected,
      data.anomaliesDetected ? 'Encore vues au dernier cycle' : 'Aucune au dernier cycle',
      '#/alerts', data.anomaliesDetected ? 'WARNING' : null, 'alerts',
      before && delta(data.anomaliesDetected, before.anomaliesDetected)),
    kpi('Actions en attente', data.pendingApprovals,
      data.pendingApprovals ? 'À valider' : 'Rien à valider',
      '#/decisions', data.pendingApprovals ? 'PENDING' : null, 'decisions',
      before && delta(data.pendingApprovals, before.pendingApprovals)),
  );
  return wrap;
}

const subtitle = (data) =>
  [`${data.processesOk} OK`, data.processesWarning && `${data.processesWarning} warn`,
    data.processesError && `${data.processesError} erreur`,
    data.processesUnknown && `${data.processesUnknown} inconnu`]
    .filter(Boolean).join(' · ') || 'Aucun déclaré';

function kpi(label, value, detail, href, state, kind, change) {
  // Chaque KPI conduit au détail qu'il annonce : un compteur sans issue oblige à chercher.
  const card = el('a', 'kpi');
  card.href = href;
  card.dataset.kind = kind;
  card.append(kpiIcon(kind));
  const valueLine = el('span', 'kpi-value-line');
  // Même règle que les pastilles : la couleur de la bordure ne porte jamais le sens seule,
  // le glyphe l'accompagne jusque dans le chiffre.
  if (state) {
    card.dataset.state = state;
    valueLine.append(el('span', 'kpi-mark', stateMark(state)));
  }
  valueLine.append(el('strong', 'kpi-value', value));
  card.append(el('span', 'kpi-label', label), valueLine, el('span', 'kpi-detail', detail));
  if (change) {
    const changeTag = el('span', 'kpi-change', change);
    if (animateCycleChanges && !change.startsWith('Stable')) changeTag.classList.add('just-changed');
    card.append(changeTag);
  }
  return card;
}

function kpiIcon(kind) {
  const paths = {
    processes: ['M5 6.5h14v11H5z', 'M5 10.2h14M5 13.8h14'],
    cycle: ['M12 7v5l3 2', 'M19 12a7 7 0 1 1-2.05-4.95'],
    alerts: ['M12 4 21 19H3Z', 'M12 9.5v4M12 16.5h.01'],
    decisions: ['M12 4 20 12 12 20 4 12Z', 'm9.5 12 1.6 1.6 3.4-3.7'],
  };
  const wrap = el('span', 'kpi-icon');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  for (const value of paths[kind] || []) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', value);
    svg.append(path);
  }
  wrap.append(svg);
  return wrap;
}

/**
 * Plusieurs processus distincts en anomalie au même cycle, groupés en un seul signal — voir
 * SupervisionService.incidents. Une heuristique grossière et assumée : la seule concomitance,
 * jamais une cause établie, ce que le libellé dit explicitement plutôt que de suggérer un diagnostic.
 */
function incidentBanners(incidents) {
  return (incidents || []).map((incident) => {
    const banner = el('div', incident.severity === 'ERROR' ? 'banner danger' : 'banner');
    banner.append(`Incident probable : ${incident.processCount} processus en anomalie au même cycle `
      + `(${incident.processNames.join(', ')}) — signale une cause commune possible, pas un diagnostic.`);
    const open = el('a', 'ghost', 'Ouvrir le cockpit');
    open.href = `#/incidents?incident=${encodeURIComponent(`cycle:${incident.cycleId}`)}`;
    banner.append(open);
    return banner;
  });
}

function attention(data) {
  const pending = data.pending || [];
  const alerts = data.alerts || [];
  if (!pending.length && !alerts.length) {
    if (!data.processesMonitored) return empty('Aucun processus sous surveillance.',
      'Déclarez un processus pour commencer à recevoir des alertes et des décisions.',
      { href: '#/chat?creation=process', label: 'Préparer un processus' });
    if (!data.agent?.lastCycleAt) return empty('Premier relevé en attente.',
      'Lancez une analyse pour connaître l’état de vos processus.',
      { href: '#/agent', label: 'Piloter l’agent' });
    return empty('Aucune alerte ni décision en attente.',
      `Dernière analyse : ${clockTime(data.agent.lastCycleAt)}. Les processus restent consultables.`,
      { href: '#/processes', label: 'Voir les processus' });
  }
  const list = el('div', 'cards');
  pending.forEach((decision) => list.append(approvalCard(decision)));
  // Une alerte déjà portée par une demande de validation n'est pas répétée en dessous.
  alerts
    .filter((alert) => !pending.some((decision) => decision.id === alert.pendingDecisionId))
    .forEach((alert) => list.append(alertCard(alert)));
  return list;
}

function timeline(cycle) {
  if (!cycle) {
    if (!current()?.processes?.length) return empty('Le premier cycle attend un processus.',
      'Déclarez un processus avant de lancer son analyse.',
      { href: '#/chat?creation=process', label: 'Préparer un processus' });
    return empty('Aucun cycle exécuté.', 'Le premier relevé donnera ici les étapes observées.',
      { href: '#/agent', label: 'Piloter l’agent' });
  }
  const list = el('ol', 'timeline');
  for (const event of cycle.events) {
    const item = el('li');
    item.append(el('span', 'time', clockTime(event.at)));
    item.append(el('span', 'label', event.label));
    if (event.detail) item.append(el('span', 'detail', event.detail));
    list.append(item);
  }
  if (cycle.failure) list.classList.add('failed');
  return list;
}

/* ── Processus ─────────────────────────────────────────────────────────── */

export async function processes() {
  await render($('#processes-table'), async () => contextual(await refresh()),
    (data) => processTable(filtered(data.processes), openProcess));
}

// Lus dans l'URL, pas dans une variable de module : un rechargement ou un lien partagé retrouve
// l'écran tel qu'il était.
const processFilter = () => params().get('etat') || stored(PROCESS_FILTER_STORAGE, {}).state || 'ALL';
const processQuery = () => (params().get('q') ?? stored(PROCESS_FILTER_STORAGE, {}).query ?? '').toLowerCase();

function filtered(rows) {
  const state = processFilter();
  const query = processQuery();
  const favorites = new Set(stored(FAVORITES_STORAGE, []));
  return (rows || []).filter((row) => {
    const matchesState = state === 'ALL'
      || (state === 'ATTENTION' ? row.state === 'WARNING' || row.state === 'ERROR'
        : state === 'FAVORITES' ? favorites.has(row.processId) : row.state === state);
    const matchesQuery = !query || row.name.toLowerCase().includes(query)
      || row.processId?.toLowerCase().includes(query);
    return matchesState && matchesQuery;
  }).sort(favoriteFirst);
}

function favoriteFirst(left, right) {
  const favorites = new Set(stored(FAVORITES_STORAGE, []));
  return Number(favorites.has(right.processId)) - Number(favorites.has(left.processId));
}

const prioritized = (rows) => [...(rows || [])].sort(favoriteFirst);

export function processShortcuts() {
  const views = stored(SAVED_VIEWS_STORAGE, []).map((view, index) => ({
    type: 'view',
    label: view.name,
    href: `#/processes?${new URLSearchParams({
      ...(view.state && view.state !== 'ALL' ? { etat: view.state } : {}),
      ...(view.query ? { q: view.query } : {}),
    }).toString()}`.replace(/\?$/, ''),
    keywords: `${view.state || 'ALL'} ${view.query || ''}`,
    index,
  }));
  const favorites = new Set(stored(FAVORITES_STORAGE, []));
  const rows = current()?.processes || [];
  const favoriteItems = rows.filter((row) => favorites.has(row.processId)).map((row) => ({
    type: 'favorite',
    label: row.name,
    href: `#/processes?processus=${encodeURIComponent(row.processId)}`,
    keywords: `${row.processId} ${row.state || ''}`,
  }));
  return [...views, ...favoriteItems];
}

/** Colonnes de la vue d'ensemble : l'essentiel d'abord, le relevé technique au second niveau. */
const COMPACT = ['Processus', 'État', 'Dernière exécution', 'Retard', 'Couverture'];
const FULL = ['Processus', 'État', 'Dernière exécution', 'Durée', 'Retard', 'Couverture', 'Relevé'];

function processTable(rows, onSelect, limit, columns = FULL) {
  if (!rows || !rows.length) {
    if (current()?.processes?.length) return empty('Aucun processus dans cette sélection.',
      'Élargissez vos filtres ou affichez tous les processus surveillés.',
      { onClick: () => {
        remember(PROCESS_FILTER_STORAGE, { state: 'ALL', query: '' });
        setOperatorContext({ process: '', period: 'all' });
        $('#context-process').value = '';
        $('#context-period').value = 'all';
        if (params().has('q') || params().has('etat')) setParams({ q: null, etat: null });
        syncFilters();
        location.hash = '#/processes';
        processes();
      }, label: 'Afficher tous les processus' });
    const guide = empty('Votre premier processus commence ici.',
      'Renseignez son nom et son topic d’entrée. Vous pourrez vérifier sa déclaration avant l’enregistrement.',
      { href: '#/chat?creation=process', label: 'Préparer un processus' });
    guide.classList.add('empty-guide');
    const steps = el('ol', 'empty-steps');
    ['Décrire le processus', 'Choisir les topics à suivre', 'Vérifier la déclaration']
      .forEach((label) => steps.append(el('li', null, label)));
    guide.querySelector('.empty-action').before(steps);
    return guide;
  }
  const table = el('table', 'grid');
  const head = el('thead');
  const headRow = el('tr');
  columns.forEach((label) => headRow.append(el('th', null, label)));
  head.append(headRow);
  table.append(head);

  const cells = {
    Processus: (row) => el('td', 'strong', row.name),
    État: (row) => {
      const cell = el('td');
      cell.append(stateTag(row.state));
      return cell;
    },
    'Dernière exécution': (row) => sortKey(el('td', null, clockTime(row.lastRun)), row.lastRun),
    Durée: (row) => sortKey(el('td', null, duration(row.durationMillis)), row.durationMillis),
    Retard: (row) => sortKey(el('td', row.delayMillis ? 'warn' : null, duration(row.delayMillis)),
      row.delayMillis),
    Couverture: (row) => {
      const cell = el('td');
      cell.append(coverageTag(row.coverage));
      return cell;
    },
    Relevé: (row) => el('td', 'muted', row.note || '—'),
  };

  const body = el('tbody');
  const previousProcesses = new Map((cycleBaseline(current())?.processes || [])
    .map((item) => [item.processId, item]));
  for (const row of (limit ? rows.slice(0, limit) : rows)) {
    const line = el('tr');
    columns.forEach((column) => line.append(cells[column](row)));
    const before = previousProcesses.get(row.processId);
    if (before && before.state !== row.state) {
      line.dataset.stateChange = row.state;
      if (animateCycleChanges) line.classList.add('just-changed');
      const name = line.querySelector('td');
      name.append(el('span', 'process-change-label', ` ${before.state} → ${row.state}`));
    }
    if (onSelect) {
      line.tabIndex = 0;
      line.classList.add('clickable');
      line.addEventListener('click', () => onSelect(row));
      line.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') onSelect(row);
      });
    }
    body.append(line);
  }
  table.append(body);

  const scroll = el('div', 'scroll-x');
  scroll.append(sortable(table));
  return scroll;
}

/**
 * Une clé de tri brute quand l'affiché ne se trie pas : « il y a 4 min » ou « 14:32 » rangés par
 * ordre alphabétique donneraient un ordre qui a l'air juste, ce qui est pire que pas de tri.
 */
function sortKey(cell, value) {
  if (value !== null && value !== undefined) cell.dataset.sort = String(value);
  return cell;
}

/**
 * Un relevé partiel se voit dans le tableau, pas seulement dans le détail : c'est là que naissent
 * les conclusions fausses, et une case vide ne les signale pas.
 */
function coverageTag(coverage) {
  if (!coverage) return el('span', 'muted', '—');
  if (coverage.complete) return stateTag('OK', 'Complète');
  if (coverage.stopReason === 'NOT_REPORTED') {
    const tag = stateTag('UNKNOWN', 'Non rendue');
    tag.title = 'Aucun outil n’a rendu d’enveloppe de couverture : ni complète, ni déclarée incomplète.';
    return tag;
  }
  const tag = stateTag('WARNING', 'Partielle');
  tag.title = coverageReason(coverage);
  return tag;
}

function coverageReason(coverage) {
  const reason = STOP_REASONS[coverage.stopReason] || coverage.stopReason;
  const missed = coverage.notReached?.length
    ? ` — non lu : ${coverage.notReached.join(', ')}`
    : '';
  return `${reason}${coverage.detail ? ` (${coverage.detail})` : ''}${missed}`;
}

function openProcess(row) {
  const data = current();
  const alerts = (data?.alerts || []).filter((alert) => alert.processId === row.processId);
  const maintenance = (data?.maintenance || []).find((window) => window.processId === row.processId);
  const body = frag(
    definition('État', stateTag(row.state)),
    definition('Dernière exécution', el('span', null, stamp(row.lastRun))),
    definition('Fraîcheur', freshnessTag(row.lastRun, 'Mesuré')),
    definition('Durée', el('span', null, duration(row.durationMillis))),
    definition('Retard', el('span', null, duration(row.delayMillis))),
    definition('Relevé', el('span', null, row.note || '—')),
    definition('Couverture', coverageTag(row.coverage)),
  );
  const extra = el('div');
  const actions = el('div', 'context-actions context-actions-standard');
  const favorites = new Set(stored(FAVORITES_STORAGE, []));
  const favorite = el('button', 'ghost', favorites.has(row.processId) ? '★ Retirer des favoris' : '☆ Ajouter aux favoris');
  favorite.type = 'button';
  favorite.addEventListener('click', () => {
    if (favorites.has(row.processId)) favorites.delete(row.processId); else favorites.add(row.processId);
    remember(FAVORITES_STORAGE, [...favorites]);
    favorite.textContent = favorites.has(row.processId) ? '★ Retirer des favoris' : '☆ Ajouter aux favoris';
    toast(favorites.has(row.processId) ? `${row.name} ajouté aux favoris.` : `${row.name} retiré des favoris.`);
    if (!$('#view-processes').hidden) processes();
  });
  const standard = contextualActionBar({
    title: `Processus · ${row.name}`,
    context: processContext(row, alerts),
    contextId: `process:${row.processId}`,
    auditQuery: row.processId,
  });
  actions.append(favorite, ...standard.children);
  extra.append(actions);
  if (row.coverage && !row.coverage.complete && row.coverage.stopReason !== 'NOT_REPORTED') {
    const banner = el('p', 'banner',
      `Relevé partiel : ${coverageReason(row.coverage)}. Une passe incomplète peut prouver une `
      + 'présence, jamais une absence — l’état est donc inconnu, pas sain.');
    extra.append(banner);
  }

  extra.append(el('h3', 'drawer-sub', 'Tendance'));
  const trend = el('div');
  trend.append(el('p', 'muted', 'Chargement…'));
  extra.append(trend);
  loadProcessTrend(row.processId, trend);

  extra.append(el('h3', 'drawer-sub', 'Maintenance'));
  extra.append(maintenanceControls(row, maintenance));

  if (alerts.length) {
    extra.append(el('h3', 'drawer-sub', 'Alertes actives'));
    alerts.forEach((alert) => extra.append(alertCard(alert)));
  } else {
    extra.append(empty('Aucune alerte sur ce processus.'));
  }
  setParams({ processus: row.processId, alerte: null, decision: null }, true);
  openDrawer(row.name, frag(body, extra));
}

/** Un point par cycle qui a relevé ce processus précis — voir SupervisionService.processHistory. */
async function loadProcessTrend(processId, host) {
  try {
    const points = await api(`${BASE}/processes/${encodeURIComponent(processId)}/history`);
    const chronological = [...points].reverse();
    const graphic = sparkline(chronological.map((point) => point.delayMillis));
    host.replaceChildren(graphic
      ? frag(el('span', 'muted', `Retard sur les ${chronological.length} derniers relevés · `), graphic)
      : empty('Pas encore de tendance.', 'Il faut au moins deux relevés pour en tracer une.'));
  } catch (error) {
    host.replaceChildren(errorState(error));
  }
}

/**
 * Un déploiement connu ne doit pas se lire comme un incident : pendant la fenêtre, ce processus ne
 * produit ni alerte ni décision, même en anomalie réelle — son état relevé reste affiché tel quel.
 */
function maintenanceControls(row, maintenance) {
  const wrap = el('div');
  if (maintenance) {
    wrap.append(el('p', null, `En maintenance jusqu’à ${stamp(maintenance.until)}`
      + (maintenance.reason ? ` — ${maintenance.reason}` : '')));
    const end = el('button', 'ghost', 'Lever la maintenance');
    end.type = 'button';
    end.addEventListener('click', () => busy(end, async () => {
      try {
        await api(`${BASE}/processes/${encodeURIComponent(row.processId)}/maintenance`, { method: 'DELETE' });
        toast('Maintenance levée');
        dismissDrawer();
        await refresh();
      } catch (error) {
        report(error);
      }
    }));
    wrap.append(end);
    return wrap;
  }
  wrap.append(el('p', 'hint', 'Pendant une fenêtre de maintenance, ce processus ne produit ni '
    + 'alerte ni décision — son état réel reste affiché.'));
  const declare = el('button', 'ghost', 'Mettre en maintenance (2 h)');
  declare.type = 'button';
  declare.addEventListener('click', () => busy(declare, async () => {
    try {
      await api(`${BASE}/processes/${encodeURIComponent(row.processId)}/maintenance`,
        { method: 'POST', body: { duration: 'PT2H', reason: 'Déclarée depuis la console' } });
      toast('Maintenance déclarée pour 2 h', undefined, {
        label: 'Annuler',
        run: async () => {
          await api(`${BASE}/processes/${encodeURIComponent(row.processId)}/maintenance`, { method: 'DELETE' });
          toast('Maintenance annulée.');
          await refresh();
        },
      });
      dismissDrawer();
      await refresh();
    } catch (error) {
      report(error);
    }
  }));
  wrap.append(declare);
  return wrap;
}

/* ── Anomalies et décisions ────────────────────────────────────────────── */

function alertCard(alert) {
  const card = el('article', 'card');
  card.dataset.state = alert.severity;
  const head = el('header');
  head.append(el('h3', null, alert.title));
  head.append(stateTag(alert.severity));
  card.append(head);
  card.append(el('p', 'muted', alert.processName));
  card.append(recurrence(alert));
  card.append(confidenceBar(alert.confidence, alert.observations?.length));
  const open = el('button', 'ghost', 'Examiner');
  open.type = 'button';
  // Répété une fois par carte : sans libellé, un lecteur d'écran n'entend qu'« Examiner ».
  open.setAttribute('aria-label', `Examiner : ${alert.title}`);
  open.addEventListener('click', () => openAnomaly(alert));
  const actions = el('div', 'card-actions');
  const ask = contextChatButton('Demander à l’agent', `Alerte · ${alert.title}`,
    alertContext(alert), `alert:${alert.id}`);
  actions.append(open, ask);
  card.append(actions);
  return card;
}

function contextualActionBar({ title, context, contextId, auditQuery, primaryLabel = 'Interroger l’agent' }) {
  const actions = el('div', 'context-actions context-actions-standard');
  actions.append(contextChatButton(primaryLabel, title, context, contextId, 'primary'));
  const audit = el('button', 'ghost', 'Voir l’audit');
  audit.type = 'button';
  audit.addEventListener('click', () => {
    dismissDrawer();
    location.hash = `#/audit?q=${encodeURIComponent(auditQuery || title)}`;
  });
  const copy = el('button', 'ghost', 'Copier le lien');
  copy.type = 'button';
  copy.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(location.href); toast('Lien copié'); }
    catch { toast('Copie indisponible dans ce navigateur', 'error'); }
  });
  actions.append(audit, copy);
  return actions;
}

function contextChatButton(label, title, context, contextId, className = 'ghost') {
  const ask = el('button', className, label);
  ask.type = 'button';
  ask.addEventListener('click', () => {
    // Un détail ouvert est modal ; le fermer évite de laisser une seconde couche marquée modale
    // derrière le chat, qui lui reste volontairement non modal à côté du cockpit.
    if (drawerOpen()) dismissDrawer();
    dispatchEvent(new CustomEvent('kex:context-chat', { detail: { title, context, contextId } }));
  });
  return ask;
}
const alertContext = (alert) => [
  `Analyse l’alerte « ${alert.title} » sur ${alert.processName}.`,
  `Gravité : ${alert.severity}. Confiance : ${percent(alert.confidence)}. Occurrences : ${alert.occurrences || 1}.`,
  alert.analysis && `Analyse actuelle : ${alert.analysis}`,
  alert.probableCause && `Cause probable : ${alert.probableCause}`,
  alert.recommendation && `Recommandation actuelle : ${alert.recommendation}`,
  alert.observations?.length && `Observations : ${alert.observations.map((item) => `${item.label}=${item.value}`).join(', ')}.`,
  'Explique le diagnostic, les risques et les prochaines étapes prioritaires.',
].filter(Boolean).join('\n');

const processContext = (row, alerts) => [
  `Analyse le processus « ${row.name} » (${row.processId}).`,
  `État : ${row.state}. Dernière exécution : ${stamp(row.lastRun)}. Retard : ${duration(row.delayMillis)}.`,
  row.note && `Dernier relevé : ${row.note}`,
  alerts.length && `Alertes actives : ${alerts.map((item) => item.title).join(', ')}.`,
  'Explique la situation, son évolution probable et les actions recommandées.',
].filter(Boolean).join('\n');

/** Un symptôme qui revient n'est pas un incident de plus : c'est le même, qui dure. */
function recurrence(alert) {
  if (!alert.occurrences || alert.occurrences < 2) {
    return el('p', 'hint', `Premier relevé ${ago(alert.lastSeenAt)}`);
  }
  return el('p', 'hint',
    `${alert.occurrences} relevés depuis ${clockTime(alert.firstSeenAt)} — dernier ${ago(alert.lastSeenAt)}`);
}

function openAnomaly(anomaly) {
  const body = el('div');
  // Le titre est déjà celui du panneau : la pastille n'y ajoute que la gravité, en français.
  body.append(stateTag(anomaly.severity));
  body.append(el('p', 'muted', anomaly.processName));
  body.append(contextualActionBar({
    title: `Alerte · ${anomaly.title}`,
    context: alertContext(anomaly),
    contextId: `alert:${anomaly.id}`,
    auditQuery: anomaly.processId || anomaly.id,
  }));
  if (anomaly.occurrences) body.append(recurrence(anomaly));

  body.append(el('h3', 'drawer-sub', 'Ce que l’agent observe'));
  if (anomaly.observations?.length) {
    const list = el('ul', 'observations');
    anomaly.observations.forEach((observation) => {
      const item = el('li');
      item.append(el('span', 'label', observation.label), el('span', 'value', observation.value));
      list.append(item);
    });
    body.append(list);
  } else {
    body.append(empty('Aucune mesure rendue.'));
  }

  body.append(el('h3', 'drawer-sub', 'Analyse'));
  body.append(el('p', null, anomaly.analysis || 'Aucune analyse fournie.'));
  if (anomaly.probableCause) {
    body.append(el('p', 'muted', `Cause probable : ${anomaly.probableCause}`));
  }
  if (anomaly.knowledgeReference) {
    // Rapporté tel quel : le modèle cite, rien ici ne vérifie ni ne retrouve la note elle-même.
    body.append(el('p', 'muted', `Connaissance mobilisée : ${anomaly.knowledgeReference}`));
  }

  body.append(el('h3', 'drawer-sub', 'Confiance'));
  body.append(confidenceBar(anomaly.confidence, anomaly.observations?.length));

  if (anomaly.recommendation) {
    body.append(el('h3', 'drawer-sub', 'Action recommandée'));
    body.append(el('p', null, anomaly.recommendation));
  }

  const decision = (current()?.pending || []).find((item) => item.id === anomaly.pendingDecisionId);
  if (decision) body.append(approvalCard(decision));

  if (anomaly.id) setParams({ alerte: anomaly.id, processus: null, decision: null }, true);
  openDrawer(anomaly.title, body);
}

/** Une jauge ne s'affiche jamais seule : ce qui la fonde l'accompagne toujours. */
function rateBar(label, ratio, footnote) {
  const wrap = el('div', 'confidence');
  const bar = el('div', 'bar');
  const fill = el('span');
  fill.style.width = `${Math.round((ratio ?? 0) * 100)}%`;
  bar.append(fill);
  wrap.append(el('span', 'label', `${label} ${percent(ratio)}`), bar);
  wrap.append(el('span', 'hint', footnote));
  return wrap;
}

/**
 * La confiance ne s'affiche jamais seule : le nombre d'observations qui la fondent l'accompagne,
 * parce qu'un pourcentage rendu par un modèle n'est pas une probabilité mesurée.
 */
function confidenceBar(confidence, observations) {
  return rateBar('Confiance', confidence, observations
    ? `Fondée sur ${observations} observation${observations > 1 ? 's' : ''}`
    : 'Aucune observation à l’appui');
}

function approvalCard(decision) {
  const card = el('article', 'card approval');
  card.append(el('h3', null, 'Action requise'));
  card.append(el('p', 'strong', decision.action));
  card.append(definition('Pourquoi', el('span', null, decision.context || decision.objective || '—')));
  card.append(definition('Impact estimé', el('span', null, decision.estimatedImpact || '—')));
  card.append(confidenceBar(decision.confidence, decision.observations?.length));
  card.append(decisionActions(decision));
  return card;
}

function decisionActions(decision) {
  const wrap = el('div', 'decision-actions');
  if (decision.expiresAt) {
    wrap.append(el('p', 'hint', `La demande expire ${ago(decision.expiresAt)}`));
  }
  const actions = el('div', 'row-end');
  const reject = el('button', 'ghost danger', 'Refuser');
  reject.type = 'button';
  reject.setAttribute('aria-label', `Refuser : ${decision.action}`);
  reject.addEventListener('click', () => busy(reject, () => resolveDecision(decision, false)));
  const approve = el('button', 'primary', 'Approuver');
  approve.type = 'button';
  approve.setAttribute('aria-label', `Approuver : ${decision.action}`);
  approve.addEventListener('click', () => busy(approve, () => resolveDecision(decision, true)));
  actions.append(reject, approve);
  wrap.append(actions);
  return wrap;
}

async function resolveDecision(decision, approve) {
  const confirmed = await confirmAction({
    title: approve ? 'Confirmer cette action' : 'Confirmer le refus',
    accept: approve ? `Confirmer : ${decision.action}` : `Refuser : ${decision.action}`,
    lines: [
      ['Action', decision.action],
      ['Processus', decision.processName],
      ['Pourquoi', decision.context || decision.objective || '—'],
      ['Impact estimé', decision.estimatedImpact || '—'],
      ['Confiance', percent(decision.confidence)],
      ['Conséquence', approve
        ? 'L’action part immédiatement vers l’outil MCP lié à la capacité.'
        : 'Aucune action ne sera exécutée ; le refus est conservé dans l’audit.'],
    ],
  });
  if (!confirmed) return;

  try {
    const before = approve ? actionBaseline(decision) : null;
    const body = approve ? undefined : { reason: 'Refusée depuis la console' };
    const result = await api(`${BASE}/decisions/${encodeURIComponent(decision.id)}/${approve ? 'approve' : 'reject'}`,
      { method: 'POST', body });
    toast(`${decision.action} — ${DECISION_LABELS[result.status] || result.status}`,
      result.status === 'FAILED' ? 'error' : undefined);
    if (approve && result.status === 'EXECUTED' && before) {
      remember('kex.agent.action-verification', {
        decisionId: decision.id,
        processId: decision.processId,
        processName: decision.processName,
        action: decision.action,
        executedAt: new Date().toISOString(),
        before,
      });
    }
    // Le paramètre part avec le panneau : sinon un rechargement rouvrirait une décision tranchée.
    dismissDrawer();
    await overview();
    if (!$('#view-decisions').hidden) await decisions();
  } catch (error) {
    report(error);
  }
}

function actionBaseline(decision) {
  const data = current();
  const process = (data?.processes || []).find((row) => row.processId === decision.processId);
  const alerts = (data?.alerts || []).filter((alert) => alert.processId === decision.processId);
  return {
    cycleAt: data?.agent?.lastCycleAt || null,
    state: process?.state || 'UNKNOWN',
    delayMillis: process?.delayMillis ?? null,
    alertIds: alerts.map((alert) => alert.id),
    alertCount: alerts.length,
  };
}

export async function decisions() {
  const context = operatorContext();
  await render($('#decisions-list'), () => api(`${BASE}/decisions`), (rows) => {
    const state = params().get('decisionEtat') || stored(DECISION_FILTER_STORAGE, 'ALL');
    const scoped = rows.filter((decision) =>
      matchesOperatorProcess(decision, context) && inOperatorPeriod(operatorTimestamp(decision), context));
    const matching = scoped.filter((decision) => state === 'ALL'
      || (state === 'PENDING' && decision.status === 'PENDING_APPROVAL')
      || (state === 'FAILED' && ['FAILED', 'EXPIRED'].includes(decision.status))
      || (state === 'RESOLVED' && ['EXECUTED', 'REJECTED', 'BLOCKED', 'SIMULATED'].includes(decision.status)));
    if (!scoped.length) return empty('Aucune décision dans ce contexte.', 'Élargissez la période ou choisissez tous les processus.',
      { href: '#/overview', label: 'Lancer une analyse' });
    if (!matching.length) return empty('Aucune décision dans cette vue.', 'Choisissez un autre filtre.');
    const list = el('div', 'cards wide');
    matching.forEach((decision) => list.append(decisionRow(decision)));
    return list;
  });
  // Un rendu neuf n'a aucune case cochée : la barre doit redevenir cachée avec lui, pas rester
  // affichée pour une sélection qui n'existe plus dans le DOM qui vient de la remplacer.
  updateBulkBar();
}

function decisionRow(decision) {
  const card = el('article', 'card decision-card');
  card.dataset.state = DECISION_STATES[decision.status] || 'UNKNOWN';
  const head = el('header');
  // Seule une décision encore en attente se sélectionne : les autres sont déjà tranchées, cocher
  // une décision exécutée ou refusée n'aurait rien à faire dans une action groupée.
  if (decision.status === 'PENDING_APPROVAL') {
    const checkbox = el('input');
    checkbox.type = 'checkbox';
    checkbox.className = 'bulk-select';
    checkbox.dataset.decisionId = decision.id;
    checkbox.setAttribute('aria-label', `Sélectionner : ${decision.action}`);
    checkbox.addEventListener('change', updateBulkBar);
    head.append(checkbox);
  }
  head.append(el('span', 'time', clockTime(decision.decidedAt)));
  head.append(el('h3', null, decision.objective || decision.action));
  head.append(stateTag(DECISION_STATES[decision.status], DECISION_LABELS[decision.status] || decision.status));
  card.append(head);
  const meta = el('div', 'decision-meta');
  meta.append(el('span', null, decision.processName));
  meta.append(el('span', null, CAPABILITIES[decision.capability] || decision.capability));
  meta.append(el('span', null, `Confiance ${percent(decision.confidence)}`));
  card.append(meta);
  const preview = el('div', 'decision-preview');
  preview.append(decisionFact('Diagnostic', decision.context || 'Aucun diagnostic détaillé fourni.'));
  preview.append(decisionFact('Action proposée', decision.action));
  preview.append(decisionFact('Impact estimé', decision.estimatedImpact || 'Non renseigné'));
  card.append(preview);
  const open = el('button', 'ghost', 'Comprendre la décision');
  open.type = 'button';
  open.setAttribute('aria-label', `Comprendre la décision : ${decision.action}`);
  open.addEventListener('click', () => openDecision(decision.id));
  card.append(open);
  return card;
}

function decisionFact(label, value) {
  const fact = el('div', 'decision-fact');
  fact.append(el('span', 'label', label), el('span', null, value));
  return fact;
}

function updateBulkBar() {
  const boxes = [...document.querySelectorAll('#decisions-list .bulk-select:checked')];
  const bar = $('#decisions-bulk-bar');
  bar.hidden = boxes.length === 0;
  $('#decisions-bulk-count').textContent = boxes.length
    ? `${boxes.length} décision${boxes.length > 1 ? 's' : ''} sélectionnée${boxes.length > 1 ? 's' : ''}`
    : '';
}

/**
 * Une boucle d'appels au chemin déjà existant, pas un nouvel endpoint de lot : chaque décision
 * reste individuellement auditée et gardée par son propre verrou d'état côté serveur — une
 * approbation groupée n'a besoin de rien de plus que ce que `resolveDecision` fait déjà une à une.
 */
async function bulkResolve(approve) {
  const ids = [...document.querySelectorAll('#decisions-list .bulk-select:checked')]
    .map((box) => box.dataset.decisionId);
  if (!ids.length) return;

  const confirmed = await confirmAction({
    title: approve ? `Confirmer ${ids.length} approbation${ids.length > 1 ? 's' : ''}`
      : `Confirmer ${ids.length} refus`,
    accept: approve ? 'Confirmer les approbations' : 'Confirmer les refus',
    lines: [
      ['Décisions concernées', String(ids.length)],
      ['Conséquence', approve
        ? 'Chaque action part immédiatement vers l’outil MCP lié à sa capacité.'
        : 'Aucune action ne sera exécutée ; chaque refus est conservé dans l’audit.'],
    ],
  });
  if (!confirmed) return;

  const results = await Promise.allSettled(ids.map((id) => api(
    `${BASE}/decisions/${encodeURIComponent(id)}/${approve ? 'approve' : 'reject'}`,
    { method: 'POST', body: approve ? undefined : { reason: 'Refusée depuis la console (sélection groupée)' } })));
  const failed = results.filter((result) => result.status === 'rejected').length;
  toast(failed
    ? `${ids.length - failed}/${ids.length} décision(s) traitée(s), ${failed} en échec`
    : `${ids.length} décision(s) ${approve ? 'approuvée(s)' : 'refusée(s)'}`,
    failed ? 'error' : undefined);
  await overview();
  await decisions();
}

async function openDecision(id) {
  setParams({ decision: id, processus: null, alerte: null }, true);
  openDrawer('Décision', loading());
  try {
    const decision = await api(`${BASE}/decisions/${encodeURIComponent(id)}`);
    const body = el('div', 'decision-detail');
    const hero = el('section', 'decision-hero');
    const meta = el('div', 'decision-meta');
    meta.append(stateTag(DECISION_STATES[decision.status], DECISION_LABELS[decision.status] || decision.status));
    meta.append(el('span', null, decision.processName));
    meta.append(el('span', null, stamp(decision.decidedAt)));
    hero.append(meta, el('h3', null, decision.objective || decision.action));
    if (decision.context) hero.append(el('p', null, decision.context));
    body.append(hero);
    body.append(contextualActionBar({
      title: `Décision · ${decision.action}`,
      context: [
        `Processus : ${decision.processName || '—'}`,
        `Objectif : ${decision.objective || '—'}`,
        `Action : ${decision.action}`,
        `Contexte : ${decision.context || '—'}`,
        `Impact : ${decision.estimatedImpact || '—'}`,
        `État : ${DECISION_LABELS[decision.status] || decision.status}`,
      ].join('\n'),
      contextId: `decision:${decision.id}`,
      auditQuery: decision.correlationId || decision.processName || decision.id,
    }));

    const evidence = decisionSection('Ce que l’agent a observé');
    if (decision.observations?.length) {
      const list = el('ul', 'observations');
      decision.observations.forEach((observation) => {
        const item = el('li');
        item.append(el('span', 'label', observation.label), el('span', 'value', observation.value));
        list.append(item);
      });
      evidence.append(list);
    } else {
      evidence.append(el('p', 'hint', 'Aucune mesure structurée n’accompagne cette décision.'));
    }
    body.append(evidence);

    const proposed = decisionSection('Action proposée');
    proposed.append(el('p', 'decision-action-text', decision.action));
    proposed.append(definition('Capacité', el('span', null,
      CAPABILITIES[decision.capability] || decision.capability)));
    body.append(proposed);

    const impact = decisionSection('Impact et risques');
    impact.append(el('p', null, decision.estimatedImpact || 'Aucun impact estimé n’a été fourni.'));
    impact.append(el('p', 'hint', 'Les risques spécifiques ne sont pas structurés séparément par '
      + 'le contrat actuel ; ils ne sont donc pas déduits par l’interface.'));
    body.append(impact);

    const confidence = decisionSection('Niveau de confiance');
    confidence.append(confidenceBar(decision.confidence, decision.observations?.length));
    body.append(confidence);

    if (decision.status === 'PENDING_APPROVAL') {
      const consequence = el('section', 'decision-consequence');
      consequence.append(el('strong', null, 'Conséquence de l’approbation'));
      consequence.append(el('p', null,
        'L’action est transmise immédiatement à l’outil MCP lié à cette capacité.'));
      consequence.append(decisionActions(decision));
      body.append(consequence);
    }

    if (decision.status !== 'PENDING_APPROVAL') {
      const result = decisionSection('Résultat');
      result.append(el('p', null, decision.result || 'Aucun détail fourni.'));
      if (decision.resolvedAt) result.append(el('p', 'hint', `Tranchée ${stamp(decision.resolvedAt)}`));
      body.append(result);
    }

    const technical = el('details', 'decision-technical technical-id');
    technical.append(el('summary', null, 'Données techniques'));
    const technicalBody = el('div');
    technicalBody.append(definition('Identifiant', el('code', null, decision.id)));
    technicalBody.append(definition('Politique', el('span', null, decision.policyVersion)));
    technicalBody.append(definition('Corrélation', el('code', null, decision.correlationId)));
    technicalBody.append(definition('Cycle', el('code', null, decision.cycleId)));
    technical.append(technicalBody);
    body.append(technical);
    openDrawer(decision.action, body);
  } catch (error) {
    openDrawer('Décision', errorState(error, () => openDecision(id)));
  }
}

function decisionSection(title) {
  const section = el('section', 'decision-section');
  section.append(el('h3', null, title));
  return section;
}

/* ── Alertes ───────────────────────────────────────────────────────────── */

export async function alerts() {
  const context = operatorContext();
  await render($('#alerts-list'), () => api(`${BASE}/alerts`), (items) => {
    items = items.filter((item) =>
      matchesOperatorProcess(item, context) && inOperatorPeriod(operatorTimestamp(item), context));
    if (!items.length) {
      return empty('Aucune alerte active.',
        'Une alerte que le dernier cycle ne revoit plus a cessé d’être vraie et sort de cette liste.',
        { href: '#/overview', label: 'Voir la synthèse' });
    }
    // Le serveur les rend déjà dédupliquées et triées — gravité, puis récurrence, puis fraîcheur.
    // Ne restent ici que le regroupement par processus et l'action à portée de clic.
    const groups = new Map();
    items.forEach((alert) => {
      const bucket = groups.get(alert.processId) || [];
      bucket.push(alert);
      groups.set(alert.processId, bucket);
    });
    const list = el('div', 'cards wide');
    for (const [, group] of groups) {
      const card = el('article', 'card');
      const worst = group.some((alert) => alert.severity === 'ERROR') ? 'ERROR' : 'WARNING';
      card.dataset.state = worst;
      const head = el('header');
      head.append(el('h3', null, group[0].processName));
      head.append(stateTag(worst, `${group.length} alerte${group.length > 1 ? 's' : ''}`));
      card.append(head);
      group.forEach((alert) => {
        const line = el('div', 'alert-line');
        const text = el('div', 'alert-text');
        text.append(el('strong', null, alert.title));
        text.append(el('span', 'muted', alert.recommendation || alert.analysis || ''));
        text.append(recurrence(alert));
        line.append(text);
        const examine = el('button', 'ghost', 'Examiner');
        examine.type = 'button';
        examine.setAttribute('aria-label', `Examiner : ${alert.title}`);
        examine.addEventListener('click', () => openAnomaly(alert));
        line.append(examine);
        // Une alerte actionnable porte son action : la chercher ailleurs coûte un aller-retour.
        if (alert.pendingDecisionId) {
          const decide = el('button', 'primary', 'Décider');
          decide.type = 'button';
          decide.setAttribute('aria-label', `Décider : ${alert.title}`);
          decide.addEventListener('click', () => openDecision(alert.pendingDecisionId));
          line.append(decide);
        }
        card.append(line);
      });
      list.append(card);
    }
    return list;
  });
}

/* ── Audit ─────────────────────────────────────────────────────────────── */

export async function audit() {
  const query = (params().get('q') || '').toLowerCase();
  const context = operatorContext();
  await render($('#audit-table'), () => api(`${BASE}/audit`), (rows) => {
    const matching = rows.filter((row) =>
      matchesOperatorProcess(row, context)
      && inOperatorPeriod(row.at, context)
      && (!query || JSON.stringify(row).toLowerCase().includes(query)));
    if (!matching.length) return empty('Aucune entrée d’audit.', 'Chaque décision et chaque changement y laisse une trace.');
    const table = el('table', 'grid');
    const head = el('thead');
    const headRow = el('tr');
    ['Horodatage', 'Acteur', 'Action', 'Processus', 'Motif', 'Politique', 'Résultat', 'Corrélation']
      .forEach((label) => headRow.append(el('th', null, label)));
    head.append(headRow);
    table.append(head);
    const body = el('tbody');
    matching.forEach((row) => {
      const line = el('tr');
      line.append(sortKey(el('td', null, stamp(row.at)), row.at));
      line.append(el('td', null, row.actor));
      line.append(el('td', 'strong', row.action));
      line.append(el('td', null, row.processId || '—'));
      line.append(el('td', 'muted', row.reason || '—'));
      line.append(el('td', null, row.policyVersion));
      line.append(el('td', null, row.result || '—'));
      line.append(el('td', 'mono muted', (row.correlationId || '').slice(0, 8)));
      body.append(line);
    });
    table.append(body);
    const scroll = el('div', 'scroll-x');
    scroll.append(sortable(table));
    return scroll;
  });
}

/* ── Activité opérationnelle ─────────────────────────────────────────── */

export async function activity() {
  const host = $('#activity-timeline');
  await render(host, async () => {
    const [data, auditRows] = await Promise.all([refresh(), api(`${BASE}/audit`).catch(() => [])]);
    const scoped = contextual(data);
    const context = operatorContext();
    return { data: scoped, audit: auditRows.filter((row) =>
      matchesOperatorProcess(row, context) && inOperatorPeriod(row.at, context)) };
  }, ({ data, audit: auditRows }) => {
    const events = [
      ...(data.alerts || []).map((item) => ({
        at: item.lastSeenAt || item.firstSeenAt, state: item.severity,
        title: item.title, detail: `Alerte · ${item.processName || item.processId || 'processus'}`,
        href: `#/alerts?alerte=${encodeURIComponent(item.id)}`,
      })),
      ...(data.pending || []).map((item) => ({
        at: item.createdAt || item.decidedAt || item.expiresAt, state: 'PENDING',
        title: item.action || item.objective || 'Décision à valider',
        detail: `Décision · ${item.processName || item.processId || 'processus'}`,
        href: `#/decisions?decision=${encodeURIComponent(item.id)}`,
      })),
      ...auditRows.map((row) => ({
        at: row.at, state: row.result === 'FAILED' ? 'ERROR' : 'OK',
        title: row.action || 'Action opérateur',
        detail: [row.actor, row.processId, row.result].filter(Boolean).join(' · '),
        href: '#/audit',
      })),
    ].filter((item) => item.at)
      .sort((a, b) => new Date(b.at) - new Date(a.at))
      .slice(0, 100);

    if (!events.length) {
      return empty('Aucune activité dans ce contexte.',
        'Élargissez la période ou choisissez tous les processus.');
    }
    const list = el('ol', 'activity-stream');
    events.forEach((event) => {
      const item = el('li', 'activity-event');
      item.append(stateTag(event.state || 'UNKNOWN'));
      const copy = el('div', 'activity-event-copy');
      const link = el('a', 'strong', event.title); link.href = event.href;
      copy.append(link, el('span', 'muted', event.detail || '—'));
      item.append(copy, el('time', 'muted', ago(event.at) || stamp(event.at)));
      list.append(item);
    });
    return list;
  });
}

/* ── Agent et configuration ────────────────────────────────────────────── */

let policy = null;
let selectedAgentTab = 'general';

export async function agent() {
  selectAgentTab(selectedAgentTab);
  const summary = $('#agent-summary');
  summary.replaceChildren(loading());
  try {
    const [status, loaded] = await Promise.all([api(`${BASE}/status`), api(`${BASE}/policy`)]);
    policy = loaded;
    summary.replaceChildren(agentSummary(status));
    fillAgentForm(loaded);
    $('#capability-matrix').replaceChildren(capabilityMatrix(loaded));
  } catch (error) {
    summary.replaceChildren(errorState(error, agent));
  }
  await performance();
}

function selectAgentTab(tab) {
  selectedAgentTab = tab;
  document.querySelectorAll('[data-agent-tab]').forEach((button) =>
    button.setAttribute('aria-pressed', String(button.dataset.agentTab === tab)));
  const general = document.querySelector('[data-agent-section="general"]');
  const collection = $('#agent-sections');
  general.hidden = tab !== 'general';
  collection.hidden = tab === 'general';
  collection.querySelectorAll('[data-agent-section]').forEach((section) => {
    section.hidden = section.dataset.agentSection !== tab;
  });
}

/**
 * Mesure de l'agent lui-même. Ce qui n'est pas mesurable n'est pas estimé : le taux de pertinence
 * reste absent tant qu'aucun humain n'a tranché, et la durée d'un cycle n'est pas présentée comme
 * un délai de détection — celui-ci se compterait depuis le début de l'incident, que rien ne connaît.
 */
export async function performance() {
  await render($('#performance'),
    () => Promise.all([api(`${BASE}/performance`), api(`${BASE}/cycles`)]),
    ([data, cycles]) => {
      const wrap = el('div', 'perf');

      wrap.append(perfGroup('Détections', [
        ['Cycles exécutés', data.cycles],
        ['Cycles en échec', data.cyclesFailed, data.cyclesFailed ? 'ko' : null],
        ['Relevés d’anomalie', data.anomaliesDetected],
        ['Alertes actives', data.activeAlerts],
        ['Durée moyenne d’un cycle', duration(data.averageCycleMillis)],
      ]));

      wrap.append(trendGroup(cycles));

      wrap.append(perfGroup('Décisions', [
        ['Prises', data.decisionsTaken],
        ['Exécutées seules', data.autonomousDecisions],
        ['Approuvées par un humain', data.humanApprovals],
        ['Refusées', data.humanRejections],
        ['Expirées sans réponse', data.approvalsExpired, data.approvalsExpired ? 'ko' : null],
      ]));

      wrap.append(perfGroup('Actions', [
        ['Réussies', data.actionsExecuted],
        ['En échec', data.actionsFailed, data.actionsFailed ? 'ko' : null],
        ['Bloquées par la politique', data.actionsBlocked],
        ['Simulées (aucun outil lié)', data.actionsSimulated],
        ['Délai moyen de dénouement', duration(data.averageResolutionMillis)],
      ]));

      const relevance = el('div', 'perf-group');
      relevance.append(el('h3', 'drawer-sub', 'Pertinence'));
      if (data.relevanceRate == null) {
        // Un taux calculé sur zéro verdict serait un chiffre inventé : on dit pourquoi il manque.
        relevance.append(empty('Pas encore mesurable.',
          'Le taux se calcule sur les recommandations qu’un humain a tranchées. Aucune ne l’a été.'));
      } else {
        const ruled = data.humanApprovals + data.humanRejections;
        // Ni une confiance ni des observations : un taux, fondé sur des verdicts humains.
        relevance.append(rateBar('Taux de pertinence', data.relevanceRate,
          `${data.humanApprovals} approuvée(s) sur ${ruled} tranchée(s) par un humain`));
        relevance.append(el('p', 'hint',
          'Les exécutions autonomes n’y entrent pas : l’agent ne se confirme pas lui-même.'));
      }
      wrap.append(relevance);
      return wrap;
    });
}

/**
 * Un compteur agrégé ne dit pas si ça empire : une tendance sur les derniers cycles, avant qu'elle
 * ne devienne un chiffre inquiétant dans les groupes ci-dessus. `cycles` arrive du plus récent au
 * plus ancien (voir `History`, côté serveur) — inversé ici pour un tracé chronologique.
 */
function trendGroup(cycles) {
  const group = el('div', 'perf-group');
  group.append(el('h3', 'drawer-sub', 'Tendance'));
  const chronological = [...cycles].reverse().slice(-20);
  const graphic = sparkline(chronological.map((cycle) => cycle.anomaliesDetected));
  if (!graphic) {
    group.append(empty('Pas encore de tendance.',
      'Il faut au moins deux cycles exécutés pour en tracer une.'));
    return group;
  }
  const row = el('div', 'perf-row');
  row.append(el('span', 'label', `Anomalies par cycle (${chronological.length} derniers)`));
  row.append(graphic);
  group.append(row);
  return group;
}

function perfGroup(title, rows) {
  const group = el('div', 'perf-group');
  group.append(el('h3', 'drawer-sub', title));
  rows.forEach(([label, value, kind]) => {
    const row = el('div', 'perf-row');
    row.append(el('span', 'label', label));
    row.append(el('span', kind === 'ko' ? 'value ko' : 'value', value ?? '—'));
    group.append(row);
  });
  return group;
}

function agentSummary(status) {
  const wrap = el('div', 'summary');
  wrap.append(definition('État', stateTag(agentState(status.state).tag, agentState(status.state).label)));
  wrap.append(definition('Mode', el('span', null, status.mode)));
  wrap.append(definition('Politique', el('span', null, status.policyVersion)));
  wrap.append(definition('Dernier cycle', el('span', null, stamp(status.lastCycleAt))));
  wrap.append(definition('Disjoncteurs', circuitBreakersValue(status.circuitBreakers)));
  if (status.paused) {
    wrap.append(el('p', 'banner', 'Agent en pause : aucune analyse n’est exécutée.'));
  }
  return wrap;
}

function fillAgentForm(loaded) {
  const mode = document.querySelector(`input[name="mode"][value="${loaded.mode}"]`);
  if (mode) mode.checked = true;

  const rows = $('#autonomy-rows');
  rows.replaceChildren();
  const global = Math.round(loaded.confidenceThreshold * 100);
  for (const [capability, label] of Object.entries(CAPABILITIES)) {
    const row = el('div', 'autonomy-row');
    const id = `autonomy-${capability}`;
    const name = el('label', null, label);
    name.htmlFor = id;

    const select = el('select');
    select.id = id;
    select.name = capability;
    for (const [value, text] of Object.entries(AUTONOMY)) {
      const option = el('option', null, text);
      option.value = value;
      select.append(option);
    }
    select.value = loaded.autonomy?.[capability] || 'FORBIDDEN';

    // Le plancher propre à la capacité ne peut que relever le plancher global : le champ le dit
    // en le prenant pour minimum, plutôt que de laisser saisir une valeur qui sera ignorée.
    const floor = el('input');
    floor.type = 'number';
    floor.id = `floor-${capability}`;
    floor.className = 'floor';
    floor.min = String(global);
    floor.max = '100';
    floor.step = '1';
    floor.placeholder = `${global} %`;
    // On affiche le plancher qui s'applique, pas celui qui a été saisi : un réglage sous le
    // plancher global n'a aucun effet, et le laisser à l'écran rendrait le champ invalide au
    // regard de son propre `min` — formulaire insoumettable, sans rien pour l'expliquer.
    const declared = loaded.confidenceThresholds?.[capability];
    const applied = declared == null ? null : Math.round(Math.max(loaded.confidenceThreshold, declared) * 100);
    floor.value = applied == null ? '' : String(applied);
    floor.title = declared != null && Math.round(declared * 100) < global
      ? `Réglé à ${Math.round(declared * 100)} %, relevé au plancher global de ${global} % : `
        + 'un plancher par capacité ne peut que durcir le plancher global.'
      : `Plancher de confiance pour « ${label} ». Vide : le plancher global de ${global} %.`;
    // Seule une capacité automatique consulte un plancher : ailleurs il ne changerait rien.
    floor.disabled = select.value !== 'AUTOMATIC';
    select.addEventListener('change', () => {
      floor.disabled = select.value !== 'AUTOMATIC';
    });

    const floorLabel = el('label', 'sr-only', `Plancher de confiance pour ${label}`);
    floorLabel.htmlFor = floor.id;
    row.append(name, select, floorLabel, floor);
    rows.append(row);
  }

  const slider = $('#confidence');
  slider.value = String(Math.round(loaded.confidenceThreshold * 100));
  $('#confidence-output').textContent = `${slider.value} %`;

  const thresholds = loaded.thresholds || {};
  $('#th-lag').value = thresholds.consumerLag ?? '';
  $('#th-error').value = thresholds.errorRatePercent ?? '';
  $('#th-processing').value = isoToShort(thresholds.processingTime);
  $('#th-blocked').value = thresholds.blockedMessages ?? '';
  $('#th-window').value = isoToShort(thresholds.observationWindow);
}

/**
 * Matrice de ce que l'agent peut et ne peut pas faire, mode compris. Elle est la seule à montrer
 * l'autonomie *effective* : la déclarer AUTOMATIC sous un mode supervisé ne l'ouvre pas.
 */
function capabilityMatrix(loaded) {
  const table = el('table', 'grid');
  const head = el('thead');
  const headRow = el('tr');
  ['Action', 'Déclarée', 'Effective', 'À partir de'].forEach((label) =>
    headRow.append(el('th', null, label)));
  head.append(headRow);
  table.append(head);
  const body = el('tbody');
  for (const [capability, label] of Object.entries(CAPABILITIES)) {
    const declared = loaded.autonomy?.[capability] || 'FORBIDDEN';
    const effective = effectiveAutonomy(loaded.mode, declared);
    const line = el('tr');
    line.append(el('td', null, label));
    line.append(el('td', 'muted', AUTONOMY[declared]));
    const cell = el('td');
    cell.append(stateTag(effective === 'AUTOMATIC' ? 'OK' : effective === 'SUPERVISED' ? 'PENDING' : 'ERROR',
      AUTONOMY[effective]));
    line.append(cell);
    // Le plancher n'a de sens que pour ce qui peut partir seul : ailleurs, un humain tranche.
    line.append(el('td', 'mono', effective === 'AUTOMATIC'
      ? percent(effectiveFloor(loaded, capability))
      : '—'));
    body.append(line);
  }
  table.append(body);
  const scroll = el('div', 'scroll-x');
  scroll.append(table);
  return scroll;
}

// Miroir de SupervisionPolicy.confidenceThresholdOf : un plancher par capacité ne peut que
// relever le plancher global.
function effectiveFloor(loaded, capability) {
  const declared = loaded.confidenceThresholds?.[capability];
  return declared == null ? loaded.confidenceThreshold : Math.max(loaded.confidenceThreshold, declared);
}

// Miroir de SupervisionPolicy.effectiveAutonomy : le serveur reste l'autorité, l'interface se
// contente d'annoncer d'avance ce qu'il décidera, pour qu'on ne règle pas à l'aveugle.
function effectiveAutonomy(mode, declared) {
  if (declared === 'FORBIDDEN') return 'FORBIDDEN';
  if (mode === 'MANUAL') return 'SUPERVISED';
  if (mode === 'SUPERVISED' && declared === 'AUTOMATIC') return 'SUPERVISED';
  return declared;
}

export async function settings() {
  const host = $('#settings-monitoring');
  host.replaceChildren(loading());
  try {
    const [loaded, processesList] = await Promise.all([api(`${BASE}/policy`), api(`${BASE}/processes`)]);
    policy = loaded;
    fillAgentForm(loaded);
    const summary = el('div', 'summary');
    summary.append(definition('Déclenchement', el('span', null, 'Manuel — aucun planificateur')));
    summary.append(definition('Processus déclarés', el('span', null, String(processesList.length))));
    summary.append(definition('Politique en vigueur', el('span', null, loaded.version)));
    host.replaceChildren(summary);

    const advanced = el('div', 'summary');
    advanced.append(definition('Fenêtre d’observation',
      el('span', null, isoToShort(loaded.thresholds?.observationWindow))));
    advanced.append(definition('Seuil de confiance', el('span', null, percent(loaded.confidenceThreshold))));
    advanced.append(el('p', 'hint',
      'Les processus surveillés et la liaison des capacités aux outils MCP sont dans la configuration '
      + 'du serveur (kex.agent.supervision) : les changer depuis la console laisserait l’agent agir '
      + 'sur des cibles qu’aucun fichier de configuration ne documente.'));
    $('#settings-advanced').replaceChildren(advanced);
  } catch (error) {
    host.replaceChildren(errorState(error, settings));
  }
}

async function savePolicy(update) {
  try {
    policy = await api(`${BASE}/policy`, { method: 'PUT', body: update });
    toast(`Politique enregistrée : ${policy.version}`);
    fillAgentForm(policy);
    $('#capability-matrix').replaceChildren(capabilityMatrix(policy));
  } catch (error) {
    report(error);
  }
}

/* ── Utilitaires de vue ────────────────────────────────────────────────── */


/** Les durées arrivent au format ISO-8601 (PT5M) : l'écran parle en 5m. */
function isoToShort(iso) {
  if (!iso) return '';
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(iso);
  if (!match) return iso;
  const [, hours, minutes, seconds] = match;
  if (hours) return `${hours}h`;
  if (minutes) return `${minutes}m`;
  return `${Math.round(Number(seconds || 0))}s`;
}

const shortToIso = (value) => {
  const match = /^(\d+)([smh])$/.exec((value || '').trim());
  if (!match) return null;
  return `PT${match[1]}${match[2].toUpperCase()}`;
};

/**
 * Rouvre le panneau que l'adresse désigne. Appelée après chaque rendu de vue et à chaque
 * changement d'adresse : c'est ce qui rend un lien vers une décision précise utilisable.
 */
/** Les panneaux de la supervision, retrouvés depuis l'adresse. L'ordre fixe leur priorité. */
function registerDrawers() {
  registerDrawer('decision', openDecision);
  registerDrawer('alerte', async (id) => {
    const alert = (await snapshotFor())?.alerts?.find((item) => item.id === id);
    if (alert) openAnomaly(alert);
  });
  registerDrawer('processus', async (id) => {
    const row = (await snapshotFor())?.processes?.find((item) => item.processId === id);
    if (row) openProcess(row);
  });
}

/** Le cache d'abord : rouvrir un panneau depuis un lien ne doit pas relancer une requête pour rien. */
const snapshotFor = () => Promise.resolve(current() || refresh().catch(() => null));

/* ── Câblage ───────────────────────────────────────────────────────────── */

/** Remet les contrôles en accord avec l'adresse : c'est l'URL qui fait foi, pas l'inverse. */
export function syncFilters() {
  const state = processFilter();
  document.querySelectorAll('.chip-toggle[data-filter]').forEach((button) =>
    button.setAttribute('aria-pressed', String(button.dataset.filter === state)));
  const decisionState = params().get('decisionEtat') || stored(DECISION_FILTER_STORAGE, 'ALL');
  document.querySelectorAll('.chip-toggle[data-decision-filter]').forEach((button) =>
    button.setAttribute('aria-pressed', String(button.dataset.decisionFilter === decisionState)));
  const processSearch = params().get('q') ?? stored(PROCESS_FILTER_STORAGE, {}).query ?? '';
  if ($('#process-search').value !== processSearch) $('#process-search').value = processSearch;
  const auditSearch = params().get('q') || '';
  if ($('#audit-search').value !== auditSearch) $('#audit-search').value = auditSearch;
}

function applySavedProcessView(view) {
  if (!view) return;
  remember(PROCESS_FILTER_STORAGE, { state: view.state, query: view.query });
  setParams({ etat: view.state === 'ALL' ? null : view.state, q: view.query || null });
  syncFilters();
  processes();
}

function syncSavedViews() {
  const select = $('#saved-process-view');
  const views = stored(SAVED_VIEWS_STORAGE, []);
  const placeholder = el('option', null, 'Vues enregistrées');
  placeholder.value = '';
  select.replaceChildren(placeholder, ...views.map((view, index) => {
    const option = el('option', null, view.name);
    option.value = String(index);
    return option;
  }));
  $('#delete-process-view').disabled = true;
}

export function wire() {
  registerDrawers();
  $('#drawer-close').addEventListener('click', dismissDrawer);
  addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && drawerOpen()) dismissDrawer();
  });

  $('#refresh-decisions').addEventListener('click', decisions);
  $('#refresh-attention').addEventListener('click', attentionView);
  $('#refresh-incidents').addEventListener('click', incidents);
  $('#refresh-performance').addEventListener('click', performance);
  $('#refresh-audit').addEventListener('click', audit);

  document.querySelectorAll('[data-agent-tab]').forEach((button) => {
    button.addEventListener('click', () => selectAgentTab(button.dataset.agentTab));
  });
  document.querySelectorAll('[data-settings-target]').forEach((button) => {
    button.addEventListener('click', () =>
      $(`#${button.dataset.settingsTarget}`).scrollIntoView({ behavior: 'smooth', block: 'start' }));
  });

  $('#decisions-bulk-approve').addEventListener('click', (event) => busy(event.currentTarget, () => bulkResolve(true)));
  $('#decisions-bulk-reject').addEventListener('click', (event) => busy(event.currentTarget, () => bulkResolve(false)));

  $('#export-decisions').addEventListener('click', (event) => busy(event.currentTarget, async () => {
    try {
      const rows = await api(`${BASE}/decisions`);
      downloadCsv('decisions-kex-agent.csv', [
        ['Décidée le', (row) => row.decidedAt],
        ['Action', (row) => row.action],
        ['Processus', (row) => row.processName],
        ['Capacité', (row) => row.capability],
        ['Confiance', (row) => row.confidence],
        ['État', (row) => row.status],
        ['Résultat', (row) => row.result],
        ['Politique', (row) => row.policyVersion],
        ['Corrélation', (row) => row.correlationId],
      ], rows);
    } catch (error) {
      report(error);
    }
  }));

  $('#export-audit').addEventListener('click', (event) => busy(event.currentTarget, async () => {
    try {
      const rows = await api(`${BASE}/audit`);
      const query = (params().get('q') || '').toLowerCase();
      const matching = rows.filter((row) => !query || JSON.stringify(row).toLowerCase().includes(query));
      downloadCsv('audit-kex-agent.csv', [
        ['Horodatage', (row) => row.at],
        ['Acteur', (row) => row.actor],
        ['Action', (row) => row.action],
        ['Processus', (row) => row.processId],
        ['Motif', (row) => row.reason],
        ['Politique', (row) => row.policyVersion],
        ['Résultat', (row) => row.result],
        ['Corrélation', (row) => row.correlationId],
      ], matching);
    } catch (error) {
      report(error);
    }
  }));

  $('#audit-search').addEventListener('input', (event) => {
    setParams({ q: event.target.value.trim() });
    audit();
  });

  $('#process-search').addEventListener('input', (event) => {
    const query = event.target.value.trim();
    remember(PROCESS_FILTER_STORAGE, { state: processFilter(), query });
    setParams({ q: query || null });
    processes();
  });

  document.querySelectorAll('.chip-toggle[data-filter]').forEach((button) => {
    button.addEventListener('click', () => {
      const state = button.dataset.filter;
      remember(PROCESS_FILTER_STORAGE, { state, query: $('#process-search').value.trim() });
      setParams({ etat: state === 'ALL' ? null : state });
      syncFilters();
      processes();
    });
  });

  document.querySelectorAll('.chip-toggle[data-decision-filter]').forEach((button) => {
    button.addEventListener('click', () => {
      const state = button.dataset.decisionFilter;
      remember(DECISION_FILTER_STORAGE, state);
      setParams({ decisionEtat: state === 'ALL' ? null : state });
      syncFilters();
      decisions();
    });
  });

  syncSavedViews();
  $('#save-process-view').addEventListener('click', () => {
    const name = prompt('Nom de cette vue');
    if (!name?.trim()) return;
    const views = stored(SAVED_VIEWS_STORAGE, []);
    views.push({ name: name.trim(), state: processFilter(), query: $('#process-search').value.trim() });
    remember(SAVED_VIEWS_STORAGE, views);
    syncSavedViews();
    $('#saved-process-view').value = String(views.length - 1);
    $('#delete-process-view').disabled = false;
    toast(`Vue « ${name.trim()} » enregistrée.`);
  });
  $('#saved-process-view').addEventListener('change', (event) => {
    const view = stored(SAVED_VIEWS_STORAGE, [])[Number(event.target.value)];
    $('#delete-process-view').disabled = !view;
    applySavedProcessView(view);
  });
  $('#delete-process-view').addEventListener('click', () => {
    const value = $('#saved-process-view').value;
    if (value === '') return;
    const views = stored(SAVED_VIEWS_STORAGE, []);
    const [removed] = views.splice(Number(value), 1);
    remember(SAVED_VIEWS_STORAGE, views);
    syncSavedViews();
    if (removed) toast(`Vue « ${removed.name} » supprimée.`);
  });

  $('#confidence').addEventListener('input', (event) => {
    $('#confidence-output').textContent = `${event.target.value} %`;
  });

  $('#test-webhook').addEventListener('click', (event) => {
    busy(event.currentTarget, async () => {
      const output = $('#webhook-test-result');
      output.replaceChildren(loading('Envoi…'));
      try {
        const result = await api(`${BASE}/notify/test`, { method: 'POST' });
        output.replaceChildren(result.success
          ? stateTag('OK', 'Webhook joignable')
          : errorState(new Error(result.detail || 'Échec du webhook.')));
      } catch (error) {
        output.replaceChildren(errorState(error));
        report(error);
      }
    });
  });

  $('#agent-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const mode = document.querySelector('input[name="mode"]:checked')?.value;
    const autonomy = {};
    const confidenceThresholds = {};
    Object.keys(CAPABILITIES).forEach((capability) => {
      autonomy[capability] = $(`#autonomy-${capability}`).value;
      const floor = $(`#floor-${capability}`).value.trim();
      // Champ vide : la capacité suit le plancher global, on n'envoie pas de réglage propre.
      if (floor !== '') confidenceThresholds[capability] = Number(floor) / 100;
    });
    const threshold = Number($('#confidence').value) / 100;

    // Passer une capacité en automatique est un changement à impact, pas comme les autres : sa
    // conséquence s'annonce en plus du diff, jamais à sa place.
    const opened = Object.entries(autonomy)
      .filter(([capability, value]) => value === 'AUTOMATIC' && policy?.autonomy?.[capability] !== 'AUTOMATIC')
      .map(([capability]) => CAPABILITIES[capability]);
    const diff = policyDiff(policy, { mode, autonomy, confidenceThreshold: threshold, confidenceThresholds });
    if (diff.length) {
      const confirmed = await confirmAction({
        title: 'Confirmer la mise à jour de la politique',
        accept: 'Confirmer la nouvelle politique',
        lines: [
          ...diff,
          opened.length ? ['Conséquence', `${opened.join(', ')} pourra`
            + `${opened.length > 1 ? 'nt' : ''} s’exécuter sans validation humaine dès que la `
            + 'confiance atteint son plancher.'] : null,
        ],
      });
      if (!confirmed) return;
    }
    await savePolicy({
      mode, autonomy, confidenceThreshold: threshold, confidenceThresholds,
      reason: $('#policy-reason').value,
    });
    $('#policy-reason').value = '';
  });

  $('#thresholds-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const processing = shortToIso($('#th-processing').value);
    const window = shortToIso($('#th-window').value);
    if (!processing || !window) {
      toast('Les durées s’écrivent 30s, 5m ou 2h.', 'error');
      return;
    }
    const next = {
      consumerLag: Number($('#th-lag').value),
      errorRatePercent: Number($('#th-error').value),
      processingTime: processing,
      blockedMessages: Number($('#th-blocked').value),
      observationWindow: window,
    };
    const diff = thresholdsDiff(policy, next);
    if (diff.length) {
      const confirmed = await confirmAction({
        title: 'Confirmer les nouveaux seuils',
        accept: 'Confirmer les seuils',
        lines: diff,
      });
      if (!confirmed) return;
    }
    await savePolicy({ thresholds: next, reason: $('#thresholds-reason').value });
  });
}

/**
 * Ce qui change entre la politique chargée et ce que le formulaire s'apprête à envoyer, en
 * `[intitulé, "avant → après"]` — directement les lignes que `confirmAction` sait déjà afficher.
 * Rien ne s'affiche pour un champ resté identique : un diff qui répète l'inchangé noierait ce qui
 * compte vraiment.
 */
function policyDiff(before, after) {
  if (!before) return [];
  const lines = [];
  if (before.mode !== after.mode) lines.push(['Mode', `${before.mode} → ${after.mode}`]);
  if (Math.round(before.confidenceThreshold * 100) !== Math.round(after.confidenceThreshold * 100)) {
    lines.push(['Plancher global',
      `${Math.round(before.confidenceThreshold * 100)} % → ${Math.round(after.confidenceThreshold * 100)} %`]);
  }
  Object.keys(CAPABILITIES).forEach((capability) => {
    const was = before.autonomy?.[capability] || 'FORBIDDEN';
    const now = after.autonomy[capability];
    if (was !== now) lines.push([CAPABILITIES[capability], `${AUTONOMY[was]} → ${AUTONOMY[now]}`]);

    const wasFloor = before.confidenceThresholds?.[capability] ?? null;
    const nowFloor = after.confidenceThresholds[capability] ?? null;
    if (wasFloor !== nowFloor) {
      const from = wasFloor == null ? 'plancher global' : `${Math.round(wasFloor * 100)} %`;
      const to = nowFloor == null ? 'plancher global' : `${Math.round(nowFloor * 100)} %`;
      lines.push([`Plancher — ${CAPABILITIES[capability]}`, `${from} → ${to}`]);
    }
  });
  return lines;
}

/** Même principe que policyDiff, pour le formulaire de seuils de détection. */
function thresholdsDiff(before, after) {
  if (!before) return [];
  const was = before.thresholds || {};
  const lines = [];
  if (was.consumerLag !== after.consumerLag) {
    lines.push(['Consumer lag', `${was.consumerLag ?? '—'} → ${after.consumerLag}`]);
  }
  if (was.errorRatePercent !== after.errorRatePercent) {
    lines.push(['Taux d’erreur', `${was.errorRatePercent ?? '—'} % → ${after.errorRatePercent} %`]);
  }
  if (was.processingTime !== after.processingTime) {
    lines.push(['Temps de traitement', `${isoToShort(was.processingTime) || '—'} → ${isoToShort(after.processingTime)}`]);
  }
  if (was.blockedMessages !== after.blockedMessages) {
    lines.push(['Messages bloqués', `${was.blockedMessages ?? '—'} → ${after.blockedMessages}`]);
  }
  if (was.observationWindow !== after.observationWindow) {
    lines.push(['Fenêtre d’observation',
      `${isoToShort(was.observationWindow) || '—'} → ${isoToShort(after.observationWindow)}`]);
  }
  return lines;
}
