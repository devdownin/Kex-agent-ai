// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Les vues de supervision : observer → comprendre → décider → agir → vérifier.

import {
  $, ago, api, clockTime, confirmAction, duration, el, empty, errorState, frag, loading, percent,
  render, report, stamp, stateTag, toast,
} from './core.js';

const BASE = '/api/agent/supervision';

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

const DECISION_LABELS = {
  PENDING_APPROVAL: 'Validation requise',
  EXECUTED: 'Exécutée',
  REJECTED: 'Refusée',
  FAILED: 'Échec',
  BLOCKED: 'Recommandation seule',
};

const DECISION_STATES = {
  PENDING_APPROVAL: 'PENDING',
  EXECUTED: 'OK',
  REJECTED: 'UNKNOWN',
  FAILED: 'ERROR',
  BLOCKED: 'WARNING',
};

const AGENT_STATES = {
  OPERATIONAL: { tag: 'OK', label: 'OPÉRATIONNEL', mark: '●' },
  DEGRADED: { tag: 'WARNING', label: 'DÉGRADÉ', mark: '▲' },
  ERROR: { tag: 'ERROR', label: 'EN ERREUR', mark: '✕' },
  PAUSED: { tag: 'PAUSED', label: 'EN PAUSE', mark: '⏸' },
  ANALYSING: { tag: 'RUNNING', label: 'ANALYSE EN COURS', mark: '◌' },
};

/** Un seul dictionnaire d'états : l'en-tête et la fiche Agent ne peuvent pas diverger. */
export const agentState = (state) =>
  AGENT_STATES[state] || { tag: 'UNKNOWN', label: 'ÉTAT INCONNU', mark: '●' };

// Cache du dernier overview : plusieurs vues en dépendent, et deux requêtes concurrentes
// afficheraient des compteurs qui se contredisent d'un panneau à l'autre.
let snapshot = null;
const watchers = new Set();

export const onSnapshot = (watcher) => watchers.add(watcher);

export async function refresh() {
  snapshot = await api(`${BASE}/overview`);
  watchers.forEach((watcher) => watcher(snapshot));
  return snapshot;
}

export const current = () => snapshot;

/* ── Vue d'ensemble ────────────────────────────────────────────────────── */

export async function overview() {
  const host = $('#kpis');
  host.replaceChildren(loading('Analyse des processus…'));
  try {
    const data = await refresh();
    host.replaceChildren(kpis(data));
    $('#overview-processes').replaceChildren(processTable(data.processes, openProcess, 8, COMPACT));
    $('#overview-attention').replaceChildren(attention(data));
    $('#overview-timeline').replaceChildren(timeline(data.lastCycle));
  } catch (error) {
    host.replaceChildren(errorState(error, overview));
    $('#overview-processes').replaceChildren();
    $('#overview-attention').replaceChildren();
    $('#overview-timeline').replaceChildren();
  }
}

function kpis(data) {
  const wrap = el('div', 'kpis-grid');
  wrap.append(
    kpi('Processus surveillés', data.processesMonitored, subtitle(data), '#/processes'),
    kpi('Dernière analyse', clockTime(data.agent.lastCycleAt),
      data.agent.staleSince ? 'Données potentiellement obsolètes' : ago(data.agent.lastCycleAt) || 'Jamais',
      '#/audit', data.agent.staleSince ? 'WARNING' : null),
    kpi('Anomalies détectées', data.anomaliesDetected,
      data.anomaliesDetected ? 'Sur le dernier cycle' : 'Aucune sur le dernier cycle',
      '#/alerts', data.anomaliesDetected ? 'WARNING' : null),
    kpi('Actions en attente', data.pendingApprovals,
      data.pendingApprovals ? 'À valider' : 'Rien à valider',
      '#/decisions', data.pendingApprovals ? 'PENDING' : null),
  );
  return wrap;
}

const subtitle = (data) =>
  [`${data.processesOk} OK`, data.processesWarning && `${data.processesWarning} warn`,
    data.processesError && `${data.processesError} erreur`,
    data.processesUnknown && `${data.processesUnknown} inconnu`]
    .filter(Boolean).join(' · ') || 'Aucun déclaré';

function kpi(label, value, detail, href, state) {
  // Chaque KPI conduit au détail qu'il annonce : un compteur sans issue oblige à chercher.
  const card = el('a', 'kpi');
  card.href = href;
  if (state) card.dataset.state = state;
  card.append(el('span', 'kpi-label', label), el('strong', 'kpi-value', value), el('span', 'kpi-detail', detail));
  return card;
}

function attention(data) {
  const pending = data.pending || [];
  const anomalies = data.anomalies || [];
  if (!pending.length && !anomalies.length) {
    return empty('Aucune anomalie détectée.', `Dernière analyse : ${clockTime(data.agent.lastCycleAt)}`);
  }
  const list = el('div', 'cards');
  pending.forEach((decision) => list.append(approvalCard(decision)));
  anomalies
    .filter((anomaly) => !pending.some((decision) => decision.anomalyId === anomaly.id))
    .forEach((anomaly) => list.append(anomalyCard(anomaly)));
  return list;
}

function timeline(cycle) {
  if (!cycle) return empty('Aucun cycle exécuté.', 'Lancez une analyse depuis l’en-tête.');
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

let processFilter = 'ALL';
let processQuery = '';

export async function processes() {
  await render($('#processes-table'), refresh, (data) => processTable(filtered(data.processes), openProcess));
}

function filtered(rows) {
  return (rows || []).filter((row) => {
    const matchesState = processFilter === 'ALL'
      || (processFilter === 'ATTENTION' ? row.state === 'WARNING' || row.state === 'ERROR' : row.state === processFilter);
    const matchesQuery = !processQuery || row.name.toLowerCase().includes(processQuery);
    return matchesState && matchesQuery;
  });
}

/** Colonnes de la vue d'ensemble : l'essentiel d'abord, le relevé technique au second niveau. */
const COMPACT = ['Processus', 'État', 'Dernière exécution', 'Retard'];
const FULL = ['Processus', 'État', 'Dernière exécution', 'Durée', 'Retard', 'Relevé'];

function processTable(rows, onSelect, limit, columns = FULL) {
  if (!rows || !rows.length) {
    return empty('Aucun processus surveillé.',
      'Déclarez-les dans kex.agent.supervision.processes — rien n’est inventé pour remplir l’écran.');
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
    'Dernière exécution': (row) => el('td', null, clockTime(row.lastRun)),
    Durée: (row) => el('td', null, duration(row.durationMillis)),
    Retard: (row) => el('td', row.delayMillis ? 'warn' : null, duration(row.delayMillis)),
    Relevé: (row) => el('td', 'muted', row.note || '—'),
  };

  const body = el('tbody');
  for (const row of (limit ? rows.slice(0, limit) : rows)) {
    const line = el('tr');
    columns.forEach((column) => line.append(cells[column](row)));
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
  scroll.append(table);
  return scroll;
}

function openProcess(row) {
  const data = current();
  const anomalies = (data?.anomalies || []).filter((anomaly) => anomaly.processId === row.processId);
  const body = frag(
    definition('État', stateTag(row.state)),
    definition('Dernière exécution', el('span', null, stamp(row.lastRun))),
    definition('Durée', el('span', null, duration(row.durationMillis))),
    definition('Retard', el('span', null, duration(row.delayMillis))),
    definition('Relevé', el('span', null, row.note || '—')),
  );
  const extra = el('div');
  if (anomalies.length) {
    extra.append(el('h3', 'drawer-sub', 'Anomalies du dernier cycle'));
    anomalies.forEach((anomaly) => extra.append(anomalyCard(anomaly)));
  } else {
    extra.append(empty('Aucune anomalie sur ce processus.'));
  }
  openDrawer(row.name, frag(body, extra));
}

/* ── Anomalies et décisions ────────────────────────────────────────────── */

function anomalyCard(anomaly) {
  const card = el('article', 'card');
  card.dataset.state = anomaly.severity;
  const head = el('header');
  head.append(el('h3', null, anomaly.title));
  head.append(stateTag(anomaly.severity));
  card.append(head);
  card.append(el('p', 'muted', anomaly.processName));
  card.append(confidenceBar(anomaly.confidence, anomaly.observations?.length));
  const open = el('button', 'ghost', 'Examiner');
  open.type = 'button';
  open.addEventListener('click', () => openAnomaly(anomaly));
  card.append(open);
  return card;
}

function openAnomaly(anomaly) {
  const body = el('div');
  // Le titre est déjà celui du panneau : la pastille n'y ajoute que la gravité, en français.
  body.append(stateTag(anomaly.severity));
  body.append(el('p', 'muted', anomaly.processName));

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

  body.append(el('h3', 'drawer-sub', 'Confiance'));
  body.append(confidenceBar(anomaly.confidence, anomaly.observations?.length));

  if (anomaly.recommendation) {
    body.append(el('h3', 'drawer-sub', 'Action recommandée'));
    body.append(el('p', null, anomaly.recommendation));
  }

  const decision = (current()?.pending || []).find((item) => item.anomalyId === anomaly.id);
  if (decision) body.append(approvalCard(decision));

  openDrawer(anomaly.title, body);
}

/**
 * La confiance ne s'affiche jamais seule : le nombre d'observations qui la fondent l'accompagne,
 * parce qu'un pourcentage rendu par un modèle n'est pas une probabilité mesurée.
 */
function confidenceBar(confidence, observations) {
  const wrap = el('div', 'confidence');
  const bar = el('div', 'bar');
  const fill = el('span');
  fill.style.width = `${Math.round((confidence ?? 0) * 100)}%`;
  bar.append(fill);
  wrap.append(el('span', 'label', `Confiance ${percent(confidence)}`), bar);
  wrap.append(el('span', 'hint', observations
    ? `Fondée sur ${observations} observation${observations > 1 ? 's' : ''}`
    : 'Aucune observation à l’appui'));
  return wrap;
}

function approvalCard(decision) {
  const card = el('article', 'card approval');
  card.append(el('h3', null, 'Action requise'));
  card.append(el('p', 'strong', decision.action));
  card.append(definition('Pourquoi', el('span', null, decision.context || decision.objective || '—')));
  card.append(definition('Impact estimé', el('span', null, decision.estimatedImpact || '—')));
  card.append(confidenceBar(decision.confidence, decision.observations?.length));
  if (decision.expiresAt) {
    card.append(el('p', 'hint', `La demande expire ${ago(decision.expiresAt)}`));
  }

  const actions = el('div', 'row-end');
  const reject = el('button', 'ghost danger', 'Refuser');
  reject.type = 'button';
  reject.addEventListener('click', () => resolveDecision(decision, false));
  const approve = el('button', 'primary', 'Approuver');
  approve.type = 'button';
  approve.addEventListener('click', () => resolveDecision(decision, true));
  actions.append(reject, approve);
  card.append(actions);
  return card;
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
    const body = approve ? undefined : { reason: 'Refusée depuis la console' };
    const result = await api(`${BASE}/decisions/${encodeURIComponent(decision.id)}/${approve ? 'approve' : 'reject'}`,
      { method: 'POST', body });
    toast(`${decision.action} — ${DECISION_LABELS[result.status] || result.status}`,
      result.status === 'FAILED' ? 'error' : undefined);
    closeDrawer();
    await overview();
    if (!$('#view-decisions').hidden) await decisions();
  } catch (error) {
    report(error);
  }
}

export async function decisions() {
  await render($('#decisions-list'), () => api(`${BASE}/decisions`), (rows) => {
    if (!rows.length) return empty('Aucune décision.', 'Elles apparaîtront après un cycle d’analyse.');
    const list = el('div', 'cards wide');
    rows.forEach((decision) => list.append(decisionRow(decision)));
    return list;
  });
}

function decisionRow(decision) {
  const card = el('article', 'card');
  card.dataset.state = DECISION_STATES[decision.status] || 'UNKNOWN';
  const head = el('header');
  head.append(el('span', 'time', clockTime(decision.decidedAt)));
  head.append(el('h3', null, decision.action));
  head.append(stateTag(DECISION_STATES[decision.status], DECISION_LABELS[decision.status] || decision.status));
  card.append(head);
  card.append(el('p', 'muted', `${decision.processName} · confiance ${percent(decision.confidence)}`));
  const open = el('button', 'ghost', 'Détail');
  open.type = 'button';
  open.addEventListener('click', () => openDecision(decision.id));
  card.append(open);
  return card;
}

async function openDecision(id) {
  openDrawer('Décision', loading());
  try {
    const decision = await api(`${BASE}/decisions/${encodeURIComponent(id)}`);
    const body = el('div');
    body.append(el('code', 'muted', `Décision ${decision.id}`));
    body.append(definition('Objectif', el('span', null, decision.objective || '—')));
    body.append(definition('Contexte', el('span', null, decision.context || '—')));
    body.append(definition('Décision', el('span', null, decision.action)));
    body.append(definition('Capacité', el('span', null, CAPABILITIES[decision.capability] || decision.capability)));
    body.append(definition('Impact estimé', el('span', null, decision.estimatedImpact || '—')));
    body.append(confidenceBar(decision.confidence, decision.observations?.length));

    if (decision.observations?.length) {
      body.append(el('h3', 'drawer-sub', 'Observations'));
      const list = el('ul', 'observations');
      decision.observations.forEach((observation) => {
        const item = el('li');
        item.append(el('span', 'label', observation.label), el('span', 'value', observation.value));
        list.append(item);
      });
      body.append(list);
    }

    body.append(el('h3', 'drawer-sub', 'Résultat'));
    body.append(definition('État', stateTag(DECISION_STATES[decision.status],
      DECISION_LABELS[decision.status] || decision.status)));
    body.append(definition('Détail', el('span', null, decision.result || '—')));
    body.append(definition('Politique', el('span', null, decision.policyVersion)));
    body.append(definition('Corrélation', el('code', null, decision.correlationId)));
    body.append(definition('Tranchée', el('span', null, stamp(decision.resolvedAt))));

    if (decision.status === 'PENDING_APPROVAL') body.append(approvalCard(decision));
    openDrawer(decision.action, body);
  } catch (error) {
    openDrawer('Décision', errorState(error, () => openDecision(id)));
  }
}

/* ── Alertes ───────────────────────────────────────────────────────────── */

export async function alerts() {
  await render($('#alerts-list'), refresh, (data) => {
    const items = data.anomalies || [];
    if (!items.length) {
      return empty('Aucune alerte active.', `Dernière analyse : ${clockTime(data.agent.lastCycleAt)}`);
    }
    // Regroupées par processus : dix alertes sur le même processus sont un incident, pas dix.
    const groups = new Map();
    items.forEach((anomaly) => {
      const bucket = groups.get(anomaly.processId) || [];
      bucket.push(anomaly);
      groups.set(anomaly.processId, bucket);
    });
    const list = el('div', 'cards wide');
    for (const [, group] of groups) {
      const card = el('article', 'card');
      const worst = group.some((anomaly) => anomaly.severity === 'ERROR') ? 'ERROR' : 'WARNING';
      card.dataset.state = worst;
      const head = el('header');
      head.append(el('h3', null, group[0].processName));
      head.append(stateTag(worst, `${group.length} signalement${group.length > 1 ? 's' : ''}`));
      card.append(head);
      group.forEach((anomaly) => {
        const line = el('div', 'alert-line');
        line.append(el('strong', null, anomaly.title));
        line.append(el('span', 'muted', anomaly.recommendation || anomaly.analysis || ''));
        const examine = el('button', 'ghost', 'Examiner');
        examine.type = 'button';
        examine.addEventListener('click', () => openAnomaly(anomaly));
        line.append(examine);
        card.append(line);
      });
      list.append(card);
    }
    return list;
  });
}

/* ── Audit ─────────────────────────────────────────────────────────────── */

let auditQuery = '';

export async function audit() {
  await render($('#audit-table'), () => api(`${BASE}/audit`), (rows) => {
    const matching = rows.filter((row) => !auditQuery || JSON.stringify(row).toLowerCase().includes(auditQuery));
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
      line.append(el('td', null, stamp(row.at)));
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
    scroll.append(table);
    return scroll;
  });
}

/* ── Agent et configuration ────────────────────────────────────────────── */

let policy = null;

export async function agent() {
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
}

function agentSummary(status) {
  const wrap = el('div', 'summary');
  wrap.append(definition('État', stateTag(agentState(status.state).tag, agentState(status.state).label)));
  wrap.append(definition('Mode', el('span', null, status.mode)));
  wrap.append(definition('Politique', el('span', null, status.policyVersion)));
  wrap.append(definition('Dernier cycle', el('span', null, stamp(status.lastCycleAt))));
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
    row.append(name, select);
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
  ['Action', 'Déclarée', 'Effective'].forEach((label) => headRow.append(el('th', null, label)));
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
    body.append(line);
  }
  table.append(body);
  const scroll = el('div', 'scroll-x');
  scroll.append(table);
  return scroll;
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

function definition(label, value) {
  const row = el('dl', 'definition');
  row.append(el('dt', null, label));
  const dd = el('dd');
  dd.append(value);
  row.append(dd);
  return row;
}

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

/* ── Panneau latéral ───────────────────────────────────────────────────── */

let lastFocused = null;

export function openDrawer(title, body) {
  lastFocused = document.activeElement;
  $('#drawer-title').textContent = title;
  $('#drawer-body').replaceChildren(body);
  $('#drawer').hidden = false;
  $('#drawer-close').focus();
}

export function closeDrawer() {
  $('#drawer').hidden = true;
  // Le focus revient d'où il vient : sans ça, la navigation clavier repart du haut de la page.
  lastFocused?.focus();
}

/* ── Câblage ───────────────────────────────────────────────────────────── */

export function wire() {
  $('#drawer-close').addEventListener('click', closeDrawer);
  addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('#drawer').hidden) closeDrawer();
  });

  $('#refresh-decisions').addEventListener('click', decisions);
  $('#refresh-audit').addEventListener('click', audit);

  $('#audit-search').addEventListener('input', (event) => {
    auditQuery = event.target.value.trim().toLowerCase();
    audit();
  });

  $('#process-search').addEventListener('input', (event) => {
    processQuery = event.target.value.trim().toLowerCase();
    processes();
  });

  document.querySelectorAll('.chip-toggle').forEach((button) => {
    button.addEventListener('click', () => {
      processFilter = button.dataset.filter;
      document.querySelectorAll('.chip-toggle').forEach((other) =>
        other.setAttribute('aria-pressed', String(other === button)));
      processes();
    });
  });

  $('#confidence').addEventListener('input', (event) => {
    $('#confidence-output').textContent = `${event.target.value} %`;
  });

  $('#agent-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const mode = document.querySelector('input[name="mode"]:checked')?.value;
    const autonomy = {};
    Object.keys(CAPABILITIES).forEach((capability) => {
      autonomy[capability] = $(`#autonomy-${capability}`).value;
    });
    const threshold = Number($('#confidence').value) / 100;

    // Passer une capacité en automatique est un changement à impact : il s'annonce avant, pas après.
    const opened = Object.entries(autonomy)
      .filter(([capability, value]) => value === 'AUTOMATIC' && policy?.autonomy?.[capability] !== 'AUTOMATIC')
      .map(([capability]) => CAPABILITIES[capability]);
    if (opened.length) {
      const confirmed = await confirmAction({
        title: 'Confirmer l’élargissement de l’autonomie',
        accept: 'Confirmer la nouvelle politique',
        lines: [
          ['Passent en automatique', opened.join(', ')],
          ['Mode', mode],
          ['Seuil de confiance', `${Math.round(threshold * 100)} %`],
          ['Conséquence', 'Ces actions pourront s’exécuter sans validation humaine.'],
        ],
      });
      if (!confirmed) return;
    }
    await savePolicy({ mode, autonomy, confidenceThreshold: threshold, reason: $('#policy-reason').value });
    $('#policy-reason').value = '';
  });

  $('#thresholds-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const processing = shortToIso($('#th-processing').value);
    const window = shortToIso($('#th-window').value);
    if (!processing || !window) {
      toast('Les durées s’écrivent 30s, 5m ou 2h.', 'error');
      return;
    }
    savePolicy({
      thresholds: {
        consumerLag: Number($('#th-lag').value),
        errorRatePercent: Number($('#th-error').value),
        processingTime: processing,
        blockedMessages: Number($('#th-blocked').value),
        observationWindow: window,
      },
      reason: $('#thresholds-reason').value,
    });
  });
}
