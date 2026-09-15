// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Coque du Control Center : en-tête d'état, pilotage de l'agent, routage et jeton.
// Aucun outillage de build : le projet est construit par Maven, ajouter npm ferait vivre deux
// chaînes pour une poignée de fichiers statiques.

import {
  $, ago, api, confirmAction, credentials, drawerOpen, onCredentialChange, onUnauthorized, report,
  restoreDrawerFromUrl, stamp, toast, viewName,
} from './core.js';
import * as chat from './chat.js';
import * as llm from './llm.js';
import * as supervision from './supervision.js';
import * as tools from './tools.js';

const BASE = '/api/agent/supervision';

const VIEWS = {
  overview: { title: 'Vue d’ensemble', load: supervision.overview },
  agent: { title: 'Agent', load: supervision.agent },
  processes: { title: 'Processus', load: supervision.processes },
  decisions: { title: 'Décisions', load: supervision.decisions },
  // La configuration réunit deux lectures indépendantes : les seuils, modifiables, et le modèle,
  // qui ne l'est pas. Un seul écran, deux requêtes — celle du modèle ne doit pas retarder les seuils.
  settings: {
    title: 'Configuration',
    load: () => Promise.all([supervision.settings(), llm.view()]),
  },
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

/* ── Rafraîchissement ──────────────────────────────────────────────────── */

// Sans lui, un onglet laissé ouvert affiche des données de dix minutes sous un libellé qui dit
// « il y a 4 secondes » : l'écran ment précisément sur ce que tout le reste s'attache à dire.
const REFRESH_MS = 15_000;
const TICK_MS = 1_000;

// Les écrans qui portent un formulaire ne se rafraîchissent pas : un rendu par-dessus effacerait
// ce que quelqu'un est en train de saisir. Le chat non plus, pour la même raison.
const SELF_REFRESHING = new Set(['overview', 'processes', 'decisions', 'alerts', 'audit', 'tools']);

/**
 * Un sondage est suspendu quand l'onglet est caché — il n'y a personne pour lire et chaque cycle
 * coûte au budget de l'agent — et quand un panneau ou un dialogue est ouvert : re-rendre sous
 * quelqu'un qui lit une décision avant de l'approuver est hostile.
 */
function paused() {
  return document.hidden || drawerOpen() || document.querySelector('dialog[open]') !== null;
}

async function backgroundRefresh() {
  if (paused()) return;
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
    supervision.syncFilters();
    await VIEWS[view].load?.();
  }
  else {
    supervision.syncFilters();
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
chat.wire(openCredentials);

$('#run-cycle').addEventListener('click', runCycle);
$('#toggle-pause').addEventListener('click', togglePause);
$('#open-credentials').addEventListener('click', openCredentials);

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

addEventListener('hashchange', route);

syncThemeButton();
syncConnectivity();
$('#credential-label').textContent = credentials.get() ? 'Jeton actif' : 'Jeton absent';
route();
refreshStatus();
if (!credentials.get()) openCredentials();
