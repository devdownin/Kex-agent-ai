// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Coque du Control Center : en-tête d'état, pilotage de l'agent, routage et jeton.
// Aucun outillage de build : le projet est construit par Maven, ajouter npm ferait vivre deux
// chaînes pour une poignée de fichiers statiques.

import {
  $, ago, api, confirmAction, credentials, drawerOpen, el, onCredentialChange, onUnauthorized, refreshFreshnessTag,
  report, restoreDrawerFromUrl, stamp, toast, viewName,
} from './core.js';
import * as automations from './automations.js';
import * as channels from './channels.js';
import * as chat from './chat.js';
import * as llm from './llm.js';
import * as mcpServer from './mcp-server.js';
import * as skills from './skills.js';
import * as supervision from './supervision.js';
import * as tools from './tools.js';

const BASE = '/api/agent/supervision';

const VIEWS = {
  overview: { title: 'Vue d’ensemble', load: supervision.overview },
  attention: { title: 'À traiter', load: supervision.attentionView },
  incidents: { title: 'Cockpit incident', load: supervision.incidents },
  activity: { title: 'Activité', load: supervision.activity },
  // La gouvernance (charte, compétences) est une lecture indépendante de l'état de l'agent :
  // l'une ne doit pas retarder l'autre, comme pour la configuration plus bas.
  agent: { title: 'Agent', load: () => Promise.all([supervision.agent(), skills.governance(),
    automations.panel(), channels.status()]) },
  processes: { title: 'Processus', load: supervision.processes },
  decisions: { title: 'Décisions', load: supervision.decisions },
  // La configuration réunit deux lectures indépendantes : les seuils, modifiables, et le modèle,
  // qui ne l'est pas. Un seul écran, deux requêtes — celle du modèle ne doit pas retarder les seuils.
  settings: {
    title: 'Configuration',
    load: () => Promise.all([supervision.settings(), llm.view(), mcpServer.view()]),
  },
  alerts: { title: 'Alertes', load: supervision.alerts },
  audit: { title: 'Audit', load: supervision.audit },
  integrations: { title: 'Intégrations', load: tools.integrationsView },
  knowledge: { title: 'Connaissance', load: tools.knowledgeView },
  system: { title: 'Système', load: tools.systemView },
  chat: { title: 'Conversation', load: chat.prefill },
};

/* ── En-tête ───────────────────────────────────────────────────────────── */

let status = null;

function renderStatus(next) {
  status = next;
  const state = supervision.agentState(next?.state);
  const pill = $('#agent-status');
  pill.dataset.state = next ? state.tag : 'unknown';
  // Glyphe et libellé en plus de la couleur : un état ne se transmet jamais par la teinte seule.
  pill.querySelector('.status-mark').textContent = state.mark;
  $('#agent-status-label').textContent = state.label;
  $('#toggle-pause').textContent = next?.paused ? 'Reprendre l’agent' : 'Pause agent';
  $('#run-cycle').disabled = Boolean(next?.paused || next?.analysing);

  const freshness = $('#freshness');
  // Le motif prime sur la fraîcheur : « DÉGRADÉ » sans explication envoie chercher la cause dans
  // les journaux, alors qu'elle est connue au moment où l'état est calculé.
  if (next?.stateReason && !next.lastCycleAt) {
    freshness.textContent = next.stateReason;
    freshness.dataset.state = 'unknown';
  } else if (!next?.lastCycleAt) {
    freshness.textContent = 'Aucune analyse exécutée';
    freshness.dataset.state = 'unknown';
  } else if (next.stateReason && !next.staleSince) {
    freshness.textContent = `${next.stateReason} — dernière analyse ${ago(next.lastCycleAt)}`;
    freshness.dataset.state = 'stale';
  } else if (next.staleSince) {
    // La fraîcheur est une règle P0 : des données vieilles de vingt minutes ressemblent à des
    // données fraîches, et c'est exactement ce qui fait rater une panne.
    freshness.textContent = `⚠ Données potentiellement obsolètes — dernière synchronisation ${ago(next.staleSince)}`;
    freshness.dataset.state = 'stale';
  } else {
    freshness.textContent = `Dernière analyse : ${ago(next.lastCycleAt)} (${stamp(next.lastCycleAt)})`;
    freshness.dataset.state = 'fresh';
  }
}

/** @param background un sondage périodique ne notifie pas : la pastille d'état porte l'échec. */
async function refreshStatus(background = false) {
  try {
    renderStatus(await api(`${BASE}/status`));
  } catch (error) {
    renderStatus(null);
    if (!background && error.status !== 401) report(error);
  }
}

function renderBadges(data) {
  badge('#nav-pending', data?.pendingApprovals);
  badge('#nav-alerts', data?.anomaliesDetected);
  badge('#nav-attention', (data?.pendingApprovals || 0) + (data?.anomaliesDetected || 0));
  if (data?.agent) renderStatus(data.agent);
  renderOnboarding(data);
  renderNotifications(data);
  renderComparison(data);
  applyDashboardState();
}

/* ── Notifications et comparaison de cycles ──────────────────────────── */

let notificationItems = [];

function readJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}

function writeJson(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* préférence locale facultative */ }
}

function renderNotifications(data) {
  const read = new Set(readJson('kex.agent.notifications.read', []));
  const lastAction = readJson('kex.agent.last-action', null);
  notificationItems = [
    ...(data?.pending || []).map((item) => ({ id: `decision:${item.id}`, label: item.action,
      detail: `Décision à valider · ${item.processName}`, href: `#/decisions?decision=${encodeURIComponent(item.id)}` })),
    ...(data?.alerts || []).map((item) => ({ id: `alert:${item.id}`, label: item.title,
      detail: `Alerte ${item.severity === 'ERROR' ? 'critique' : 'active'} · ${item.processName}`,
      href: `#/alerts?alerte=${encodeURIComponent(item.id)}` })),
    ...(lastAction ? [{ id: `action:${lastAction.at}`, label: lastAction.message,
      detail: `Action terminée · ${ago(lastAction.at)}`, href: '#/audit' }] : []),
  ];
  const unread = notificationItems.filter((item) => !read.has(item.id)).length;
  $('#notification-count').textContent = String(unread);
  $('#notification-count').hidden = unread === 0;
  const list = $('#notifications-list');
  list.replaceChildren(...notificationItems.map((item) => {
    const link = el('a', read.has(item.id) ? 'notification-item read' : 'notification-item');
    link.href = item.href;
    link.append(el('strong', null, item.label), el('span', 'muted', item.detail));
    link.addEventListener('click', () => $('#notifications').close());
    return link;
  }));
  if (!notificationItems.length) list.append(el('p', 'state empty', 'Aucune notification active.'));
}

function renderComparison(data) {
  if (!data?.agent?.lastCycleAt) return;
  const previous = readJson('kex.agent.previous-cycle', null);
  const currentCycle = {
    at: data.agent.lastCycleAt,
    alerts: (data.alerts || []).map((item) => item.id),
    processes: Object.fromEntries((data.processes || []).map((item) => [item.processId, item.state])),
  };
  if (previous?.at && previous.at !== currentCycle.at) {
    const previousAlerts = previous.alerts || [];
    const newAlerts = currentCycle.alerts.filter((id) => !previousAlerts.includes(id)).length;
    const resolved = previousAlerts.filter((id) => !currentCycle.alerts.includes(id)).length;
    const changed = Object.entries(currentCycle.processes)
      .filter(([id, state]) => previous.processes?.[id] && previous.processes[id] !== state).length;
    $('#comparison-grid').replaceChildren(
      comparisonMetric('Nouvelles alertes', newAlerts, newAlerts ? 'WARNING' : 'OK'),
      comparisonMetric('Alertes résolues', resolved, resolved ? 'OK' : null),
      comparisonMetric('États modifiés', changed, changed ? 'WARNING' : null),
    );
    $('#comparison-time').textContent = stamp(previous.at);
    $('#cycle-comparison').hidden = false;
  }
  if (!previous || previous.at !== currentCycle.at) writeJson('kex.agent.previous-cycle', currentCycle);
}

function comparisonMetric(label, value, state) {
  const node = el('div', 'comparison-metric');
  if (state) node.dataset.state = state;
  node.append(el('strong', null, value), el('span', null, label));
  return node;
}

async function renderOnboarding(data) {
  const host = $('#onboarding');
  if (!host || !data) return;

  const [mcpConnections, automationsState, kafkaState] = await Promise.all([
    api('/api/agent/mcp/servers').catch(() => []),
    api('/api/agent/automations').then((rows) => ({ enabled: true, rows })).catch((error) =>
      error.status === 404 ? { enabled: false, rows: [] } : { enabled: true, rows: [] }),
    api('/api/agent/kafka/topics').then((value) => ({ available: !value?.unavailable }))
      .catch(() => ({ available: false })),
  ]);

  const steps = [
    { missing: !credentials.get(), label: 'Jeton API non connecté', detail: 'Authentifiez la console pour agir au nom de votre compte.', action: openCredentials },
    { missing: !(data.processesMonitored > 0), label: 'Aucun processus surveillé', detail: 'Déclarez au moins un processus pour obtenir un diagnostic.', href: '#/settings' },
    { missing: !(mcpConnections?.length > 0), label: 'Aucune connexion MCP externe', detail: 'Ajoutez une intégration pour étendre les capacités de l’agent.', href: '#/integrations' },
    { missing: !kafkaState.available, label: 'Kafka non configuré ou indisponible', detail: 'Vérifiez la connexion Kafka avant de vous fier aux mesures de lag.', href: '#/integrations' },
    { missing: automationsState.enabled && !automationsState.rows.length, label: 'Aucune automatisation', detail: 'Planifiez les contrôles récurrents utiles à votre exploitation.', href: '#/agent' },
    { missing: !data.agent?.lastCycleAt, label: 'Aucune analyse exécutée', detail: 'Lancez un premier cycle pour établir l’état de référence.', action: runCycle },
  ].filter((step) => step.missing);

  host.hidden = steps.length === 0;
  if (host.hidden) return;
  $('#onboarding-progress').textContent = `${steps.length} à configurer`;
  const list = $('#onboarding-steps');
  list.replaceChildren(...steps.map((step) => {
    const item = el('div', 'onboarding-step');
    item.append(el('span', 'onboarding-mark', '!'));
    const copy = el('div', 'onboarding-copy');
    copy.append(el('strong', null, step.label), el('span', 'hint', step.detail));
    item.append(copy);
    const action = el(step.href ? 'a' : 'button', 'ghost', 'Configurer');
    if (step.href) action.href = step.href;
    else {
      action.type = 'button';
      action.addEventListener('click', step.action);
    }
    item.append(action);
    return item;
  }));
  applyDashboardState();
}

const DASHBOARD_STORAGE = 'kex.agent.dashboard';
const DASHBOARD_BLOCKS = {
  onboarding: 'Onboarding',
  incidents: 'Incidents',
  comparison: 'Comparaison de cycles',
  brief: 'Synthèse de l’agent',
  operations: 'Processus et décisions',
  timeline: 'Déroulé du cycle',
};

function dashboardState() {
  const fallback = Object.keys(DASHBOARD_BLOCKS).map((id) => ({ id, visible: true }));
  const saved = readJson(DASHBOARD_STORAGE, null);
  if (!Array.isArray(saved)) return fallback;
  const known = new Map(saved.map((item) => [item.id, item]));
  return fallback.map((item) => ({ ...item, ...(known.get(item.id) || {}) }))
    .sort((a, b) => (saved.findIndex((item) => item.id === a.id) + 1 || 999)
      - (saved.findIndex((item) => item.id === b.id) + 1 || 999));
}

function applyDashboardState() {
  const state = dashboardState();
  const overview = $('#view-overview');
  const toolbar = overview?.querySelector('.overview-toolbar');
  let anchor = toolbar;
  state.forEach((item) => {
    const block = overview?.querySelector(`[data-dashboard-block="${item.id}"]`);
    if (!block) return;
    block.dataset.userHidden = String(!item.visible);
    if (!item.visible) block.hidden = true;
    else if (item.id !== 'onboarding' && item.id !== 'comparison') block.hidden = false;
    if (anchor && block.previousElementSibling !== anchor) anchor.after(block);
    anchor = block;
  });
}

function renderDashboardCustomizer() {
  const host = $('#dashboard-customizer-list');
  if (!host) return;
  const state = dashboardState();
  host.replaceChildren(...state.map((item, index) => {
    const row = el('div', 'dashboard-customizer-row');
    const toggle = el('input');
    toggle.type = 'checkbox';
    toggle.checked = item.visible;
    toggle.setAttribute('aria-label', `Afficher ${DASHBOARD_BLOCKS[item.id]}`);
    toggle.addEventListener('change', () => {
      item.visible = toggle.checked;
      writeJson(DASHBOARD_STORAGE, state);
      applyDashboardState();
    });
    row.append(toggle, el('span', null, DASHBOARD_BLOCKS[item.id]));
    const up = el('button', 'ghost compact', '↑');
    up.type = 'button'; up.disabled = index === 0; up.setAttribute('aria-label', `Monter ${DASHBOARD_BLOCKS[item.id]}`);
    up.addEventListener('click', () => {
      [state[index - 1], state[index]] = [state[index], state[index - 1]];
      writeJson(DASHBOARD_STORAGE, state); renderDashboardCustomizer(); applyDashboardState();
    });
    const down = el('button', 'ghost compact', '↓');
    down.type = 'button'; down.disabled = index === state.length - 1; down.setAttribute('aria-label', `Descendre ${DASHBOARD_BLOCKS[item.id]}`);
    down.addEventListener('click', () => {
      [state[index + 1], state[index]] = [state[index], state[index + 1]];
      writeJson(DASHBOARD_STORAGE, state); renderDashboardCustomizer(); applyDashboardState();
    });
    row.append(up, down);
    return row;
  }));
}

function badge(selector, count) {
  const node = $(selector);
  node.textContent = String(count ?? 0);
  node.hidden = !count;
}

/* ── Pilotage ──────────────────────────────────────────────────────────── */

async function runCycle() {
  const button = $('#run-cycle');
  button.disabled = true;
  button.textContent = 'Analyse en cours…';
  const stopProgress = pollCycleProgress();
  try {
    const report_ = await api(`${BASE}/cycles`, { method: 'POST' });
    toast(`Cycle terminé : ${report_.anomaliesDetected} anomalie(s), ${report_.pendingApprovals} à valider.`);
    await supervision.overview();
    await refreshStatus();
  } catch (error) {
    report(error);
    await refreshStatus();
  } finally {
    stopProgress();
    supervision.liveCycle(null);
    button.textContent = 'Exécuter maintenant';
  }
}

function pollCycleProgress() {
  let active = true;
  const read = async () => {
    try {
      const progress = await api(`${BASE}/cycles/current`);
      if (active && progress) supervision.liveCycle(progress);
    } catch {
      // Le POST principal porte l'échec ; un sondage d'affichage ne crée pas un second message.
    }
  };
  read();
  const timer = setInterval(read, 700);
  return () => {
    active = false;
    clearInterval(timer);
  };
}

async function togglePause() {
  const paused = Boolean(status?.paused);
  if (!paused) {
    const confirmed = await confirmAction({
      title: 'Confirmer la mise en pause',
      accept: 'Confirmer la mise en pause',
      lines: [
        ['Effet', 'Aucune analyse ne sera exécutée tant que l’agent n’aura pas été repris.'],
        ['Détection', 'Les anomalies survenant pendant la pause ne seront pas détectées.'],
        ['Validations', 'Les demandes déjà en attente expirent normalement.'],
      ],
    });
    if (!confirmed) return;
  }
  try {
    renderStatus(await api(`${BASE}/${paused ? 'resume' : 'pause'}`, { method: 'POST' }));
    toast(paused ? 'Agent repris.' : 'Agent en pause.');
  } catch (error) {
    report(error);
  }
}

/* ── Jeton ─────────────────────────────────────────────────────────────── */

function openCredentials() {
  const dialog = $('#credentials');
  if (dialog.open) return;
  $('#api-key').value = credentials.get();
  dialog.showModal();
}

onUnauthorized(openCredentials);
onCredentialChange((value) => {
  $('#credential-label').textContent = value ? 'Jeton actif' : 'Jeton absent';
  if (value) skills.whoami();
  else $('#whoami-label').hidden = true;
});

/* ── Navigation latérale ─────────────────────────────────────────────── */

const railToggle = $('#rail-toggle');
try {
  const storedRail = localStorage.getItem('kex.agent.rail');
  const compactViewport = matchMedia('(min-width: 861px) and (max-width: 1100px)').matches;
  document.documentElement.dataset.rail = storedRail || (compactViewport ? 'collapsed' : 'expanded');
} catch {
  document.documentElement.dataset.rail = 'expanded';
}

function syncRailButton() {
  if (!railToggle) return;
  const collapsed = document.documentElement.dataset.rail === 'collapsed';
  railToggle.setAttribute('aria-pressed', String(collapsed));
  railToggle.setAttribute('aria-label', collapsed ? 'Déplier la navigation' : 'Replier la navigation');
  railToggle.title = collapsed ? 'Déplier la navigation' : 'Replier la navigation';
  const label = railToggle.querySelector('.rail-toggle-label');
  if (label) label.textContent = collapsed ? 'Déplier' : 'Replier';
}

railToggle?.addEventListener('click', () => {
  const next = document.documentElement.dataset.rail === 'collapsed' ? 'expanded' : 'collapsed';
  document.documentElement.dataset.rail = next;
  try { localStorage.setItem('kex.agent.rail', next); } catch { /* préférence locale facultative */ }
  syncRailButton();
});

document.querySelectorAll('.nav-item[aria-label]').forEach((item) => {
  if (!item.title) item.title = item.getAttribute('aria-label');
});

syncRailButton();

/* ── Thème ─────────────────────────────────────────────────────────────── */

const themeToggle = $('#theme-toggle');
try {
  const stored = localStorage.getItem('kex.agent.theme');
  if (stored) document.documentElement.dataset.theme = stored;
} catch {
  /* sans stockage, on suit la préférence du système */
}

function syncThemeButton() {
  const dark = document.documentElement.dataset.theme === 'dark'
    || (!document.documentElement.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches);
  themeToggle.setAttribute('aria-pressed', String(dark));
  const preference = $('#preference-theme');
  if (preference) preference.value = document.documentElement.dataset.theme || 'system';
}

themeToggle.addEventListener('click', () => {
  const next = themeToggle.getAttribute('aria-pressed') === 'true' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  try {
    localStorage.setItem('kex.agent.theme', next);
  } catch {
    /* le thème reste valable pour la durée de la page */
  }
  syncThemeButton();
});

/* ── Densité ──────────────────────────────────────────────────────────── */

const densityToggle = $('#density-toggle');
try {
  const stored = localStorage.getItem('kex.agent.density');
  if (stored === 'compact') document.documentElement.dataset.density = stored;
} catch {
  /* sans stockage, l'affichage confortable reste la valeur par défaut */
}

function syncDensityButton() {
  const compact = document.documentElement.dataset.density === 'compact';
  densityToggle.setAttribute('aria-pressed', String(compact));
  $('#density-label').textContent = compact ? 'Affichage compact' : 'Affichage confortable';
  const preference = $('#preference-density');
  if (preference) preference.value = compact ? 'compact' : 'comfortable';
}

densityToggle.addEventListener('click', () => {
  const compact = densityToggle.getAttribute('aria-pressed') !== 'true';
  if (compact) document.documentElement.dataset.density = 'compact';
  else delete document.documentElement.dataset.density;
  try {
    localStorage.setItem('kex.agent.density', compact ? 'compact' : 'comfortable');
  } catch {
    /* le choix reste valable pour la durée de la page */
  }
  syncDensityButton();
});

/* ── Préférences d’apparence et d’usage ──────────────────────────────── */

const preference = {
  get(key, fallback) {
    try {
      return localStorage.getItem(`kex.agent.${key}`) ?? fallback;
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(`kex.agent.${key}`, value);
    } catch {
      /* Le choix reste actif pour la page courante. */
    }
  },
};

function applyPreferences() {
  const autoRefresh = preference.get('auto-refresh', 'true') !== 'false';
  const identifiers = preference.get('identifiers', 'true') === 'true';
  const reducedMotion = preference.get('reduced-motion', 'false') === 'true';
  const dateFormat = preference.get('date-format', 'absolute');
  document.documentElement.dataset.identifiers = identifiers ? 'visible' : 'hidden';
  document.documentElement.dataset.motion = reducedMotion ? 'reduced' : 'full';
  $('#preference-auto-refresh').checked = autoRefresh;
  $('#preference-identifiers').checked = identifiers;
  $('#preference-reduced-motion').checked = reducedMotion;
  $('#preference-date-format').value = dateFormat;
  syncThemeButton();
  syncDensityButton();
}

$('#preference-theme').addEventListener('change', (event) => {
  const value = event.target.value;
  if (value === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = value;
  try {
    if (value === 'system') localStorage.removeItem('kex.agent.theme');
    else localStorage.setItem('kex.agent.theme', value);
  } catch { /* le thème reste actif pour la page */ }
  syncThemeButton();
});

$('#preference-density').addEventListener('change', (event) => {
  const compact = event.target.value === 'compact';
  if (compact) document.documentElement.dataset.density = 'compact';
  else delete document.documentElement.dataset.density;
  preference.set('density', event.target.value);
  syncDensityButton();
});

$('#preference-date-format').addEventListener('change', (event) => {
  preference.set('date-format', event.target.value);
  reload();
});

$('#preference-auto-refresh').addEventListener('change', (event) =>
  preference.set('auto-refresh', String(event.target.checked)));

$('#preference-identifiers').addEventListener('change', (event) => {
  preference.set('identifiers', String(event.target.checked));
  document.documentElement.dataset.identifiers = event.target.checked ? 'visible' : 'hidden';
});

$('#preference-reduced-motion').addEventListener('change', (event) => {
  preference.set('reduced-motion', String(event.target.checked));
  document.documentElement.dataset.motion = event.target.checked ? 'reduced' : 'full';
});

$('#reset-preferences').addEventListener('click', () => {
  for (const key of ['theme', 'density', 'date-format', 'auto-refresh', 'identifiers', 'reduced-motion']) {
    try { localStorage.removeItem(`kex.agent.${key}`); } catch { /* stockage indisponible */ }
  }
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.density;
  applyPreferences();
  reload();
  toast('Préférences réinitialisées.');
});

/* ── Recherche globale ────────────────────────────────────────────────── */

const commandDialog = $('#command-palette');
const commandQuery = $('#command-query');

function commandItems() {
  const snapshot = supervision.current();
  const commands = [
    { label: 'Vue d’ensemble', group: 'Navigation', kind: 'Navigation', href: '#/overview', keywords: 'accueil dashboard' },
    { label: 'À traiter', group: 'Navigation', kind: 'Navigation', href: '#/attention', keywords: 'priorités actions' },
    { label: 'Activité', group: 'Navigation', kind: 'Navigation', href: '#/activity', keywords: 'timeline historique événements' },
    { label: 'Incidents', group: 'Navigation', kind: 'Navigation', href: '#/incidents', keywords: 'investigation' },
    { label: 'Processus', group: 'Navigation', kind: 'Navigation', href: '#/processes', keywords: 'supervision' },
    { label: 'Décisions', group: 'Navigation', kind: 'Navigation', href: '#/decisions', keywords: 'approbation validation' },
    { label: 'Conversation', group: 'Navigation', kind: 'Navigation', href: '#/chat', keywords: 'agent chat' },
    { label: 'Connexions MCP', group: 'MCP', kind: 'Intégrations', href: '#/integrations', keywords: 'serveur outils externe' },
    { label: 'Serveur MCP Kex', group: 'MCP', kind: 'Configuration', href: '#/settings', keywords: 'sessions catalogue playground' },
    { label: 'Exécuter une analyse maintenant', group: 'Actions', kind: 'Action', action: runCycle, keywords: 'cycle lancer run' },
    { label: 'Nouvelle automatisation', group: 'Actions', kind: 'Action', href: '#/agent', keywords: 'planifier schedule cron' },
    { label: 'Afficher les alertes critiques', group: 'Actions', kind: 'Filtre', href: '#/alerts', keywords: 'erreur critique error' },
    { label: 'Afficher les processus en erreur', group: 'Actions', kind: 'Filtre', href: '#/processes?etat=ERROR', keywords: 'critique panne' },
    { label: 'Réinitialiser le contexte global', group: 'Actions', kind: 'Action', action: () => $('#context-reset')?.click(), keywords: 'filtres environnement processus période' },
    { label: 'Ouvrir les notifications', group: 'Actions', kind: 'Action', action: () => $('#notifications').showModal(), keywords: 'activité alertes' },
    { label: 'Activer le mode présentation', group: 'Actions', kind: 'Action', action: togglePresentation, keywords: 'plein écran' },
  ];
  for (const process of snapshot?.processes || []) {
    commands.push({ label: process.name, group: 'Processus', kind: `Processus · ${process.state}`,
      href: `#/processes?processus=${encodeURIComponent(process.processId)}`,
      keywords: [process.processId, process.environment, process.env, process.stage].filter(Boolean).join(' ') });
  }
  for (const alert of snapshot?.alerts || []) {
    commands.push({ label: alert.title, group: 'Alertes', kind: `Alerte · ${alert.processName}`,
      href: `#/alerts?alerte=${encodeURIComponent(alert.id)}`,
      keywords: `${alert.severity || ''} ${alert.processName || ''}` });
  }
  for (const decision of snapshot?.pending || []) {
    commands.push({ label: decision.action, group: 'Décisions', kind: `Décision · ${decision.processName}`,
      href: `#/decisions?decision=${encodeURIComponent(decision.id)}`,
      keywords: `${decision.objective || ''} ${decision.processName || ''}` });
  }
  const savedViews = readJson('kex.agent.process-views', []);
  savedViews.forEach((view) => commands.push({
    label: view.name, group: 'Vues enregistrées', kind: 'Vue Processus',
    href: `#/processes?${new URLSearchParams({
      ...(view.state && view.state !== 'ALL' ? { etat: view.state } : {}),
      ...(view.query ? { q: view.query } : {}),
    }).toString()}`,
    keywords: 'vue enregistrée favoris',
  }));
  return commands;
}

function renderCommands() {
  const query = commandQuery.value.trim().toLowerCase();
  const matches = commandItems().filter((item) => !query
    || `${item.label} ${item.kind} ${item.group} ${item.keywords || ''}`.toLowerCase().includes(query)).slice(0, 18);
  const results = $('#command-results');
  if (!matches.length) {
    results.replaceChildren(el('p', 'command-empty', 'Aucun résultat.'));
    return;
  }
  const nodes = [];
  let group = null;
  matches.forEach((item, index) => {
    if (item.group !== group) {
      group = item.group;
      nodes.push(el('div', 'command-group', group));
    }
    const button = el('button', 'command-result');
    button.type = 'button';
    button.dataset.index = String(index);
    button.append(el('span', 'strong', item.label), el('span', 'hint', item.kind));
    button.addEventListener('click', () => {
      commandDialog.close();
      if (item.href) location.hash = item.href;
      else item.action?.();
    });
    nodes.push(button);
  });
  results.replaceChildren(...nodes);
  results.querySelector('button')?.classList.add('selected');
}

function openCommand() {
  renderCommands();
  commandDialog.showModal();
  commandQuery.focus();
}

$('#open-command').addEventListener('click', openCommand);
commandQuery.addEventListener('input', renderCommands);
commandQuery.addEventListener('keydown', (event) => {
  const buttons = [...$('#command-results').querySelectorAll('button')];
  if (!buttons.length) return;
  const current = Math.max(0, buttons.findIndex((button) => button.classList.contains('selected')));
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    buttons[current].classList.remove('selected');
    const next = (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
    buttons[next].classList.add('selected');
    buttons[next].scrollIntoView({ block: 'nearest' });
  } else if (event.key === 'Enter') {
    event.preventDefault();
    buttons[current].click();
  }
});
addEventListener('keydown', (event) => {
  const editing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName);
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    if (!commandDialog.open) openCommand();
  } else if (event.key === '/' && !editing && !commandDialog.open) {
    event.preventDefault();
    openCommand();
  }
});

const notificationsDialog = $('#notifications');
$('#open-notifications').addEventListener('click', () => notificationsDialog.showModal());
$('#close-notifications').addEventListener('click', () => notificationsDialog.close());
$('#mark-notifications-read').addEventListener('click', () => {
  writeJson('kex.agent.notifications.read', notificationItems.map((item) => item.id));
  renderNotifications(supervision.current());
});

function togglePresentation() {
  const enabled = document.documentElement.dataset.presentation !== 'true';
  document.documentElement.dataset.presentation = String(enabled);
  $('#presentation-toggle').setAttribute('aria-pressed', String(enabled));
  $('#presentation-toggle').textContent = enabled ? 'Quitter la présentation' : 'Mode présentation';
  toast(enabled ? 'Mode présentation activé.' : 'Mode présentation désactivé.');
}

$('#presentation-toggle').addEventListener('click', togglePresentation);

function showActionFeedback(detail) {
  const host = $('#action-feedback');
  if (!detail?.message) return;
  host.replaceChildren(el('strong', null, 'Dernière action · '), document.createTextNode(detail.message),
    el('span', 'muted', ` ${ago(detail.at) || ''}`));
  host.hidden = false;
}
addEventListener('kex:action', (event) => {
  showActionFeedback(event.detail);
  renderNotifications(supervision.current());
});
showActionFeedback(readJson('kex.agent.last-action', null));

/* ── Rafraîchissement ──────────────────────────────────────────────────── */

// Sans lui, un onglet laissé ouvert affiche des données de dix minutes sous un libellé qui dit
// « il y a 4 secondes » : l'écran ment précisément sur ce que tout le reste s'attache à dire.
const REFRESH_MS = 15_000;
const TICK_MS = 1_000;

// Les écrans qui portent un formulaire ne se rafraîchissent pas : un rendu par-dessus effacerait
// ce que quelqu'un est en train de saisir. Le chat non plus, pour la même raison.
const SELF_REFRESHING = new Set([
  'overview', 'attention', 'processes', 'decisions', 'alerts', 'incidents', 'activity', 'audit',
  'integrations', 'knowledge', 'system',
]);

/**
 * Un sondage est suspendu quand l'onglet est caché — il n'y a personne pour lire et chaque cycle
 * coûte au budget de l'agent — et quand un panneau ou un dialogue est ouvert : re-rendre sous
 * quelqu'un qui lit une décision avant de l'approuver est hostile.
 */
function paused() {
  return document.hidden || drawerOpen() || !$('#context-chat').hidden
    || document.querySelector('dialog[open]') !== null;
}

async function backgroundRefresh() {
  if (paused() || preference.get('auto-refresh', 'true') === 'false') return;
  await refreshStatus(true);
  const view = currentView();
  if (SELF_REFRESHING.has(view)) await VIEWS[view].load?.();
}

// Le libellé de fraîcheur vieillit tout seul, sans requête : c'est lui qui doit dire la vérité
// entre deux sondages.
function tick() {
  if (document.hidden) return;
  if (status) renderStatus(status);
  document.querySelectorAll('.data-freshness[data-at]').forEach((node) => refreshFreshnessTag(node));
}

setInterval(backgroundRefresh, REFRESH_MS);
setInterval(tick, TICK_MS);
// Un onglet qu'on retrouve doit être à jour tout de suite, pas au prochain sondage.
addEventListener('visibilitychange', () => {
  if (!document.hidden) backgroundRefresh();
});

/* ── Connectivité ──────────────────────────────────────────────────────── */

// `navigator.onLine` ne prouve pas qu'on atteint l'agent — un réseau sans route vers lui se dit
// « en ligne ». Il prouve en revanche l'inverse : hors ligne, plus rien n'est à jour, et l'écran
// continuerait de se lire comme d'habitude. L'échec de sondage reste porté par la pastille d'état.
function syncConnectivity() {
  $('#offline').hidden = navigator.onLine;
}

addEventListener('online', () => {
  syncConnectivity();
  backgroundRefresh();
});
addEventListener('offline', syncConnectivity);

/* ── Routage ───────────────────────────────────────────────────────────── */

const currentView = () => (VIEWS[viewName()] ? viewName() : 'overview');

let rendered = null;

async function route() {
  const view = currentView();
  // Un changement de paramètre — filtre, panneau ouvert — n'est pas un changement de vue : le
  // recharger referait une requête et écraserait ce que l'utilisateur vient d'ouvrir.
  if (view !== rendered) {
    rendered = view;
    for (const key of Object.keys(VIEWS)) {
      $(`#view-${key}`).hidden = key !== view;
      const link = document.querySelector(`.nav-item[data-view="${key}"]`);
      if (key === view) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
    $('#crumb').textContent = VIEWS[view].title;
    // Le titre suit la vue : un onglet parmi dix ne se retrouve pas, et un signet pris sur un
    // écran précis reviendrait avec le nom de l'application pour seul repère.
    document.title = `${VIEWS[view].title} — Kex Agent Control Center`;
    $('#announcer').textContent = VIEWS[view].title;
    // `main` garde son défilement d'un écran à l'autre — ce n'est qu'un attribut `hidden` qui
    // change, pas un nouveau document. Sans ça, quitter un long tableau de processus scrollé
    // en bas ouvre l'écran suivant déjà scrollé, avec son en-tête hors champ.
    $('#main').scrollTop = 0;
    supervision.syncFilters();
    tools.syncFilters();
    await VIEWS[view].load?.();
  }
  else {
    supervision.syncFilters();
    tools.syncFilters();
  }
  await restoreDrawerFromUrl();
}

/**
 * Recharge l'écran courant, que {@link route} laisserait intact faute de changement de vue. Sans
 * cela, l'écran affiché pendant la saisie du jeton reste sur son « Jeton refusé » : le sondage de
 * fond rattrape les vues qui s'auto-rafraîchissent, jamais Configuration ni Agent, qui portent un
 * formulaire et en sont exclues.
 */
function reload() {
  rendered = null;
  return route();
}

/* ── Démarrage ─────────────────────────────────────────────────────────── */

supervision.wire();
supervision.onSnapshot(renderBadges);
tools.wire();
llm.wire();
skills.wire();
automations.wire();
chat.wire(openCredentials);

$('#run-cycle').addEventListener('click', runCycle);
$('#toggle-pause').addEventListener('click', togglePause);
$('#open-credentials').addEventListener('click', openCredentials);
document.querySelector('.action-menu').addEventListener('click', (event) => {
  if (event.target.closest('.menu-action')) event.currentTarget.removeAttribute('open');
});

$('#credentials-form').addEventListener('submit', () => {
  credentials.set($('#api-key').value.trim());
  toast(credentials.get() ? 'Jeton enregistré.' : 'Jeton effacé.');
  refreshStatus();
  reload();
});

$('#forget-key').addEventListener('click', () => {
  credentials.set('');
  $('#credentials').close();
});

mcpServer.bind();
addEventListener('hashchange', route);

syncThemeButton();
syncDensityButton();
applyPreferences();
syncConnectivity();
renderDashboardCustomizer();
applyDashboardState();
$('#credential-label').textContent = credentials.get() ? 'Jeton actif' : 'Jeton absent';
// Une ligne directe, pas onCredentialChange : ce jeton vient de sessionStorage, il ne passe
// jamais par credentials.set() ici, donc l'écouteur ne se déclencherait pas de lui-même.
if (credentials.get()) skills.whoami();
route();
refreshStatus();
if (!credentials.get()) openCredentials();
