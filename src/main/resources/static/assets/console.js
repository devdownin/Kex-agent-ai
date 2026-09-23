// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Coque du Control Center : en-tête d'état, pilotage de l'agent, routage et jeton.
// Aucun outillage de build : le projet est construit par Maven, ajouter npm ferait vivre deux
// chaînes pour une poignée de fichiers statiques.

import {
  $, ago, api, confirmAction, credentials, drawerOpen, el, onCredentialChange, onUnauthorized, report,
  restoreDrawerFromUrl, stamp, toast, viewName,
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
  tools: { title: 'Technique', load: tools.view },
  chat: { title: 'Conversation', load: chat.prefill },
};

const mcpServer = (() => {
  const PATH = '/api/agent/mcp-server';
  let sessionId = null;
  let catalog = { tools: [], resources: [], templates: [], prompts: [] };
  async function rpc(method, params = {}) {
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-06-18' };
    const token = credentials.get();
    if (token) headers.Authorization = `Bearer ${token}`;
    if (sessionId) headers['Mcp-Session-Id'] = sessionId;
    const response = await fetch(PATH, { method: 'POST', headers, body: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method, params }) });
    if (!response.ok) throw new Error(`Serveur MCP indisponible (HTTP ${response.status})`);
    if (!sessionId) sessionId = response.headers.get('Mcp-Session-Id');
    const payload = await response.json();
    if (payload.error) throw new Error(payload.error.message || 'Erreur MCP');
    return payload.result;
  }
  async function ensureSession() {
    if (sessionId) return null;
    return rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'kex-control-center', version: '1' } });
  }
  function setState(ok, initialize) {
    const pill = $('#mcp-server-status'); if (!pill) return;
    pill.dataset.state = ok ? 'ok' : 'critical';
    $('#mcp-server-status-label').textContent = ok ? 'OPÉRATIONNEL · LECTURE SEULE' : 'INDISPONIBLE';
    if (initialize) { $('#mcp-protocol').textContent = initialize.protocolVersion || '—'; $('#mcp-server-version').textContent = `Kex MCP ${initialize.serverInfo?.version || '—'}`; }
  }
  function renderCatalog() {
    const kind = $('#mcp-catalog-kind')?.value || 'tools', host = $('#mcp-catalog-list'); if (!host) return;
    const entries = catalog[kind] || [];
    host.replaceChildren(...entries.map((item) => {
      const card = el('article', 'mcp-catalog-item');
      card.append(el('strong', null, item.name || item.uri || item.uriTemplate || 'Élément MCP'), el('p', 'hint', item.description || item.uri || ''));
      const schema = item.inputSchema || item.outputSchema;
      if (schema) { const details = document.createElement('details'); details.append(el('summary', null, 'Schéma'), el('pre', null, JSON.stringify(schema, null, 2))); card.append(details); }
      return card;
    }));
    if (!entries.length) host.append(el('p', 'hint', 'Aucun élément exposé.'));
  }
  function targets() {
    const op = $('#mcp-playground-operation')?.value || 'tool';
    if (op === 'tool') return catalog.tools.map((x) => [x.name, x.name]);
    if (op === 'resource') return catalog.resources.map((x) => [x.uri, x.name || x.uri]);
    return catalog.prompts.map((x) => [x.name, x.name]);
  }
  function renderTargets() {
    const select = $('#mcp-playground-target'); if (!select) return;
    select.replaceChildren(...targets().map(([value, label]) => { const option = document.createElement('option'); option.value = value; option.textContent = label; return option; }));
  }
  async function view() {
    if (!$('#settings-mcp-server')) return;
    try {
      const initialize = await ensureSession();
      const [t, r, rt, p] = await Promise.all([rpc('tools/list'), rpc('resources/list'), rpc('resources/templates/list'), rpc('prompts/list')]);
      catalog = { tools: t.tools || [], resources: r.resources || [], templates: rt.resourceTemplates || [], prompts: p.prompts || [] };
      $('#mcp-tools-count').textContent = catalog.tools.length; $('#mcp-resources-count').textContent = catalog.resources.length;
      $('#mcp-templates-count').textContent = catalog.templates.length; $('#mcp-prompts-count').textContent = catalog.prompts.length;
      setState(true, initialize); renderCatalog(); renderTargets();
    } catch (error) { setState(false); $('#mcp-playground-result').textContent = error.message; }
  }
  async function execute(event) {
    event.preventDefault(); const resultNode = $('#mcp-playground-result');
    try {
      const args = JSON.parse($('#mcp-playground-arguments').value || '{}'), op = $('#mcp-playground-operation').value, target = $('#mcp-playground-target').value;
      const result = op === 'tool' ? await rpc('tools/call', { name: target, arguments: args })
        : op === 'resource' ? await rpc('resources/read', { uri: target }) : await rpc('prompts/get', { name: target, arguments: args });
      resultNode.textContent = JSON.stringify(result, null, 2);
    } catch (error) { resultNode.textContent = error.message; }
  }
  function bind() {
    document.querySelectorAll('[data-mcp-tab]').forEach((button) => button.addEventListener('click', () => {
      document.querySelectorAll('[data-mcp-tab]').forEach((x) => x.classList.toggle('active', x === button));
      document.querySelectorAll('[data-mcp-panel]').forEach((x) => { x.hidden = x.dataset.mcpPanel !== button.dataset.mcpTab; });
    }));
    $('#mcp-catalog-kind')?.addEventListener('change', renderCatalog); $('#mcp-playground-operation')?.addEventListener('change', renderTargets);
    $('#mcp-playground-form')?.addEventListener('submit', execute);
  }
  return { bind, view };
})();

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

function renderOnboarding(data) {
  const host = $('#onboarding');
  if (!host || !data) return;
  const steps = [
    { done: Boolean(credentials.get()), label: 'Connecter le jeton API', href: null, action: openCredentials },
    { done: data.processesMonitored > 0, label: 'Déclarer les processus surveillés', href: '#/settings' },
    { done: Boolean(data.agent?.lastCycleAt), label: 'Exécuter le premier cycle', action: runCycle },
  ];
  const complete = steps.filter((step) => step.done).length;
  host.hidden = complete === steps.length;
  if (host.hidden) return;
  $('#onboarding-progress').textContent = `${complete}/${steps.length}`;
  const list = $('#onboarding-steps');
  list.replaceChildren(...steps.map((step, index) => {
    const item = el('div', step.done ? 'onboarding-step done' : 'onboarding-step');
    item.append(el('span', 'onboarding-mark', step.done ? '✓' : String(index + 1)));
    item.append(el('span', 'strong', step.label));
    if (!step.done) {
      const action = el(step.href ? 'a' : 'button', 'ghost', 'Configurer');
      if (step.href) action.href = step.href;
      else {
        action.type = 'button';
        action.addEventListener('click', step.action);
      }
      item.append(action);
    }
    return item;
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
    ['Vue d’ensemble', 'Navigation', '#/overview'],
    ['À traiter', 'Navigation', '#/attention'],
    ['Conversation', 'Navigation', '#/chat'],
    ['Processus', 'Navigation', '#/processes'],
    ['Décisions', 'Navigation', '#/decisions'],
    ['Configuration', 'Navigation', '#/settings'],
    ['Ouvrir les notifications', 'Action', null],
    ['Activer le mode présentation', 'Action', null],
  ].map(([label, kind, href]) => ({ label, kind, href }));
  commands[6].action = () => $('#notifications').showModal();
  commands[7].action = togglePresentation;
  for (const process of snapshot?.processes || []) {
    commands.push({ label: process.name, kind: `Processus · ${process.state}`,
      href: `#/processes?processus=${encodeURIComponent(process.processId)}` });
  }
  for (const alert of snapshot?.alerts || []) {
    commands.push({ label: alert.title, kind: `Alerte · ${alert.processName}`,
      href: `#/alerts?alerte=${encodeURIComponent(alert.id)}` });
  }
  for (const decision of snapshot?.pending || []) {
    commands.push({ label: decision.action, kind: `Décision · ${decision.processName}`,
      href: `#/decisions?decision=${encodeURIComponent(decision.id)}` });
  }
  commands.unshift({ label: 'Exécuter une analyse maintenant', kind: 'Action', action: runCycle });
  return commands;
}

function renderCommands() {
  const query = commandQuery.value.trim().toLowerCase();
  const matches = commandItems().filter((item) => !query
    || `${item.label} ${item.kind}`.toLowerCase().includes(query)).slice(0, 12);
  const results = $('#command-results');
  results.replaceChildren(...matches.map((item, index) => {
    const button = el('button', 'command-result');
    button.type = 'button';
    button.dataset.index = String(index);
    button.append(el('span', 'strong', item.label), el('span', 'hint', item.kind));
    button.addEventListener('click', () => {
      commandDialog.close();
      if (item.href) location.hash = item.href;
      else item.action?.();
    });
    return button;
  }));
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
  'overview', 'attention', 'processes', 'decisions', 'alerts', 'incidents', 'audit', 'tools',
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
  if (!document.hidden && status) renderStatus(status);
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
$('#credential-label').textContent = credentials.get() ? 'Jeton actif' : 'Jeton absent';
// Une ligne directe, pas onCredentialChange : ce jeton vient de sessionStorage, il ne passe
// jamais par credentials.set() ici, donc l'écouteur ne se déclencherait pas de lui-même.
if (credentials.get()) skills.whoami();
route();
refreshStatus();
if (!credentials.get()) openCredentials();
