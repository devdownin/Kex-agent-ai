// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Les vues de supervision : observer → comprendre → décider → agir → vérifier.

import {
  $, ago, api, busy, circuitBreakersValue, clockTime, confirmAction, definition, dismissDrawer,
  drawerOpen, duration, el, empty, errorState, frag, loading, openDrawer, params, percent,
  registerDrawer, render, report, setParams, sortable, stamp, stateMark, stateTag, toast,
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
};

const DECISION_STATES = {
  PENDING_APPROVAL: 'PENDING',
  EXECUTED: 'OK',
  REJECTED: 'UNKNOWN',
  FAILED: 'ERROR',
  EXPIRED: 'WARNING',
  BLOCKED: 'WARNING',
};

const AGENT_STATES = {
  OPERATIONAL: { tag: 'OK', label: 'OPÉRATIONNEL', mark: '●' },
  // Distinct d'OPÉRATIONNEL et distinct de DÉGRADÉ : rien n'a été mesuré, ce qui n'affirme ni
  // que tout va bien, ni que quelque chose va mal.
  UNKNOWN: { tag: 'UNKNOWN', label: 'ÉTAT INCONNU', mark: '?' },
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

function kpis(data) {
  const wrap = el('div', 'kpis-grid');
  wrap.append(
    kpi('Processus surveillés', data.processesMonitored, subtitle(data), '#/processes'),
    kpi('Dernière analyse', clockTime(data.agent.lastCycleAt),
      data.agent.staleSince ? 'Données potentiellement obsolètes' : ago(data.agent.lastCycleAt) || 'Jamais',
      '#/audit', data.agent.staleSince ? 'WARNING' : null),
    kpi('Alertes actives', data.anomaliesDetected,
      data.anomaliesDetected ? 'Encore vues au dernier cycle' : 'Aucune au dernier cycle',
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
  const valueLine = el('span', 'kpi-value-line');
  // Même règle que les pastilles : la couleur de la bordure ne porte jamais le sens seule,
  // le glyphe l'accompagne jusque dans le chiffre.
  if (state) {
    card.dataset.state = state;
    valueLine.append(el('span', 'kpi-mark', stateMark(state)));
  }
  valueLine.append(el('strong', 'kpi-value', value));
  card.append(el('span', 'kpi-label', label), valueLine, el('span', 'kpi-detail', detail));
  return card;
}

function attention(data) {
  const pending = data.pending || [];
  const alerts = data.alerts || [];
  if (!pending.length && !alerts.length) {
    return empty('Aucune anomalie détectée.', `Dernière analyse : ${clockTime(data.agent.lastCycleAt)}`);
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

export async function processes() {
  await render($('#processes-table'), refresh, (data) => processTable(filtered(data.processes), openProcess));
}

// Lus dans l'URL, pas dans une variable de module : un rechargement ou un lien partagé retrouve
// l'écran tel qu'il était.
const processFilter = () => params().get('etat') || 'ALL';
const processQuery = () => (params().get('q') || '').toLowerCase();

function filtered(rows) {
  const state = processFilter();
  const query = processQuery();
  return (rows || []).filter((row) => {
    const matchesState = state === 'ALL'
      || (state === 'ATTENTION' ? row.state === 'WARNING' || row.state === 'ERROR' : row.state === state);
    const matchesQuery = !query || row.name.toLowerCase().includes(query);
    return matchesState && matchesQuery;
  });
}

/** Colonnes de la vue d'ensemble : l'essentiel d'abord, le relevé technique au second niveau. */
const COMPACT = ['Processus', 'État', 'Dernière exécution', 'Retard', 'Couverture'];
const FULL = ['Processus', 'État', 'Dernière exécution', 'Durée', 'Retard', 'Couverture', 'Relevé'];

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
  const body = frag(
    definition('État', stateTag(row.state)),
    definition('Dernière exécution', el('span', null, stamp(row.lastRun))),
    definition('Durée', el('span', null, duration(row.durationMillis))),
    definition('Retard', el('span', null, duration(row.delayMillis))),
    definition('Relevé', el('span', null, row.note || '—')),
    definition('Couverture', coverageTag(row.coverage)),
  );
  const extra = el('div');
  if (row.coverage && !row.coverage.complete && row.coverage.stopReason !== 'NOT_REPORTED') {
    const banner = el('p', 'banner',
      `Relevé partiel : ${coverageReason(row.coverage)}. Une passe incomplète peut prouver une `
      + 'présence, jamais une absence — l’état est donc inconnu, pas sain.');
    extra.append(banner);
  }
  if (alerts.length) {
    extra.append(el('h3', 'drawer-sub', 'Alertes actives'));
    alerts.forEach((alert) => extra.append(alertCard(alert)));
  } else {
    extra.append(empty('Aucune alerte sur ce processus.'));
  }
  setParams({ processus: row.processId, alerte: null, decision: null }, true);
  openDrawer(row.name, frag(body, extra));
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
  card.append(open);
  return card;
}

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
  if (decision.expiresAt) {
    card.append(el('p', 'hint', `La demande expire ${ago(decision.expiresAt)}`));
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
    // Le paramètre part avec le panneau : sinon un rechargement rouvrirait une décision tranchée.
    dismissDrawer();
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
  open.setAttribute('aria-label', `Détail : ${decision.action}`);
  open.addEventListener('click', () => openDecision(decision.id));
  card.append(open);
  return card;
}

async function openDecision(id) {
  setParams({ decision: id, processus: null, alerte: null }, true);
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
  await render($('#alerts-list'), () => api(`${BASE}/alerts`), (items) => {
    if (!items.length) {
      return empty('Aucune alerte active.',
        'Une alerte que le dernier cycle ne revoit plus a cessé d’être vraie et sort de cette liste.');
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
  await render($('#audit-table'), () => api(`${BASE}/audit`), (rows) => {
    const matching = rows.filter((row) => !query || JSON.stringify(row).toLowerCase().includes(query));
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
  await performance();
}

/**
 * Mesure de l'agent lui-même. Ce qui n'est pas mesurable n'est pas estimé : le taux de pertinence
 * reste absent tant qu'aucun humain n'a tranché, et la durée d'un cycle n'est pas présentée comme
 * un délai de détection — celui-ci se compterait depuis le début de l'incident, que rien ne connaît.
 */
export async function performance() {
  await render($('#performance'), () => api(`${BASE}/performance`), (data) => {
    const wrap = el('div', 'perf');

    wrap.append(perfGroup('Détections', [
      ['Cycles exécutés', data.cycles],
      ['Cycles en échec', data.cyclesFailed, data.cyclesFailed ? 'ko' : null],
      ['Relevés d’anomalie', data.anomaliesDetected],
      ['Alertes actives', data.activeAlerts],
      ['Durée moyenne d’un cycle', duration(data.averageCycleMillis)],
    ]));

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
  document.querySelectorAll('.chip-toggle').forEach((button) =>
    button.setAttribute('aria-pressed', String(button.dataset.filter === state)));
  const query = params().get('q') || '';
  if ($('#process-search').value !== query) $('#process-search').value = query;
  if ($('#audit-search').value !== query) $('#audit-search').value = query;
}

export function wire() {
  registerDrawers();
  $('#drawer-close').addEventListener('click', dismissDrawer);
  addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && drawerOpen()) dismissDrawer();
  });

  $('#refresh-decisions').addEventListener('click', decisions);
  $('#refresh-performance').addEventListener('click', performance);
  $('#refresh-audit').addEventListener('click', audit);

  $('#audit-search').addEventListener('input', (event) => {
    setParams({ q: event.target.value.trim() });
    audit();
  });

  $('#process-search').addEventListener('input', (event) => {
    setParams({ q: event.target.value.trim() });
    processes();
  });

  document.querySelectorAll('.chip-toggle').forEach((button) => {
    button.addEventListener('click', () => {
      setParams({ etat: button.dataset.filter === 'ALL' ? null : button.dataset.filter });
      syncFilters();
      processes();
    });
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
          ['Plancher global', `${Math.round(threshold * 100)} %`],
          ['Planchers propres', Object.entries(confidenceThresholds)
            .map(([capability, value]) => `${CAPABILITIES[capability]} ${Math.round(value * 100)} %`)
            .join(', ') || 'aucun'],
          ['Conséquence', 'Ces actions pourront s’exécuter sans validation humaine dès que la '
            + 'confiance atteint leur plancher.'],
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
