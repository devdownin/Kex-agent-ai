// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Coque du Control Center : en-tête d'état, pilotage de l'agent, routage et jeton.
// Aucun outillage de build : le projet est construit par Maven, ajouter npm ferait vivre deux
// chaînes pour une poignée de fichiers statiques.

import {
  $, ago, api, confirmAction, credentials, onCredentialChange, onUnauthorized, report, stamp, toast,
} from './core.js';
import * as chat from './chat.js';
import * as supervision from './supervision.js';
import * as tools from './tools.js';

const BASE = '/api/agent/supervision';

const VIEWS = {
  overview: { title: 'Vue d’ensemble', load: supervision.overview },
  agent: { title: 'Agent', load: supervision.agent },
  processes: { title: 'Processus', load: supervision.processes },
  decisions: { title: 'Décisions', load: supervision.decisions },
  settings: { title: 'Configuration', load: supervision.settings },
  alerts: { title: 'Alertes', load: supervision.alerts },
  audit: { title: 'Audit', load: supervision.audit },
  tools: { title: 'Technique', load: tools.view },
  chat: { title: 'Conversation', load: null },
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
  if (!next?.lastCycleAt) {
    freshness.textContent = 'Aucune analyse exécutée';
    freshness.dataset.state = 'unknown';
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

async function refreshStatus() {
  try {
    renderStatus(await api(`${BASE}/status`));
  } catch (error) {
    renderStatus(null);
    if (error.status !== 401) report(error);
  }
}

function renderBadges(data) {
  badge('#nav-pending', data?.pendingApprovals);
  badge('#nav-alerts', data?.anomaliesDetected);
  if (data?.agent) renderStatus(data.agent);
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
  try {
    const report_ = await api(`${BASE}/cycles`, { method: 'POST' });
    toast(`Cycle terminé : ${report_.anomaliesDetected} anomalie(s), ${report_.pendingApprovals} à valider.`);
    await supervision.overview();
    await refreshStatus();
  } catch (error) {
    report(error);
    await refreshStatus();
  } finally {
    button.textContent = 'Exécuter maintenant';
  }
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

/* ── Routage ───────────────────────────────────────────────────────────── */

function route() {
  const name = location.hash.replace('#/', '') || 'overview';
  const view = VIEWS[name] ? name : 'overview';
  for (const key of Object.keys(VIEWS)) {
    $(`#view-${key}`).hidden = key !== view;
    const link = document.querySelector(`.nav-item[data-view="${key}"]`);
    if (key === view) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
  $('#crumb').textContent = VIEWS[view].title;
  VIEWS[view].load?.();
}

/* ── Démarrage ─────────────────────────────────────────────────────────── */

supervision.wire();
supervision.onSnapshot(renderBadges);
tools.wire();
chat.wire(openCredentials);

$('#run-cycle').addEventListener('click', runCycle);
$('#toggle-pause').addEventListener('click', togglePause);
$('#open-credentials').addEventListener('click', openCredentials);

$('#credentials-form').addEventListener('submit', () => {
  credentials.set($('#api-key').value.trim());
  toast(credentials.get() ? 'Jeton enregistré.' : 'Jeton effacé.');
  refreshStatus();
  route();
});

$('#forget-key').addEventListener('click', () => {
  credentials.set('');
  $('#credentials').close();
});

addEventListener('hashchange', route);

syncThemeButton();
$('#credential-label').textContent = credentials.get() ? 'Jeton actif' : 'Jeton absent';
route();
refreshStatus();
if (!credentials.get()) openCredentials();
