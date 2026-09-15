// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Console d'exploitation servie par l'agent lui-même. Aucun outillage de build : le projet est
// construit par Maven, ajouter npm ferait diverger deux chaînes pour trois fichiers statiques.

const KEY_STORAGE = 'kex.agent.api-key';
const CONVERSATION_STORAGE = 'kex.agent.conversation';

const $ = (selector) => document.querySelector(selector);
const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  // textContent et jamais innerHTML : la réponse du modèle et les contenus MCP sont des
  // données non fiables, les injecter en HTML serait un XSS stocké côté serveur MCP.
  if (text !== undefined) node.textContent = text;
  return node;
};

/* ── Jeton ─────────────────────────────────────────────────────────────── */

const credentials = {
  // sessionStorage et non localStorage : le jeton ne survit pas à la fermeture de l'onglet.
  get() {
    try {
      return sessionStorage.getItem(KEY_STORAGE) || '';
    } catch {
      return '';
    }
  },
  set(value) {
    try {
      if (value) sessionStorage.setItem(KEY_STORAGE, value);
      else sessionStorage.removeItem(KEY_STORAGE);
    } catch {
      /* navigation privée : le jeton reste en mémoire pour la durée de la page */
    }
    memoryKey = value;
    renderCredentialLabel();
  },
};

let memoryKey = credentials.get();

function renderCredentialLabel() {
  $('#credential-label').textContent = memoryKey ? 'Jeton actif' : 'Jeton absent';
}

/* ── Appels HTTP ───────────────────────────────────────────────────────── */

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function headers(extra) {
  const base = memoryKey ? { Authorization: `Bearer ${memoryKey}` } : {};
  return Object.assign(base, extra || {});
}

async function failure(response) {
  const fallback = {
    401: 'Jeton refusé.',
    403: 'Accès refusé.',
    404: 'Ressource inconnue.',
    429: 'Débit de chat dépassé, réessayer dans un instant.',
    501: 'Capacité non supportée par ce serveur MCP.',
    503: "Service indisponible — kex.agent.api-key n'est peut-être pas configuré côté serveur.",
    504: "L'agent n'a pas répondu dans le délai imparti.",
  }[response.status];
  let detail = '';
  try {
    const body = await response.json();
    detail = body.detail || body.message || '';
  } catch {
    /* ProblemDetail absent : le statut suffit */
  }
  return new ApiError(response.status, detail || fallback || `HTTP ${response.status}`);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers: headers(options.body === undefined ? undefined : { 'Content-Type': 'application/json' }),
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });
  if (response.status === 401) openCredentials();
  if (!response.ok) throw await failure(response);
  if (response.status === 204) return null;
  const type = response.headers.get('content-type') || '';
  return type.includes('json') ? response.json() : response.text();
}

/* ── Notifications ─────────────────────────────────────────────────────── */

function toast(message, kind) {
  const node = el('div', kind === 'error' ? 'toast error' : 'toast', message);
  $('#toasts').append(node);
  setTimeout(() => node.remove(), 6000);
}

const report = (error) => toast(error instanceof Error ? error.message : String(error), 'error');

/* ── Conversation ──────────────────────────────────────────────────────── */

let conversationId = sessionStorage.getItem(CONVERSATION_STORAGE) || null;
let inFlight = null;

function setConversation(id) {
  conversationId = id || null;
  $('#conversation-id').textContent = id || '—';
  $('#clear-conversation').disabled = !id;
  try {
    if (id) sessionStorage.setItem(CONVERSATION_STORAGE, id);
    else sessionStorage.removeItem(CONVERSATION_STORAGE);
  } catch {
    /* sans stockage, la conversation reste valide pour la durée de la page */
  }
}

function addTurn(role, text) {
  const turn = el('li', `turn ${role}`);
  turn.append(el('span', 'who', role === 'user' ? 'vous' : role === 'error' ? 'erreur' : 'agent'));
  const bubble = el('div', 'bubble', text || '');
  turn.append(bubble);
  const transcript = $('#transcript');
  transcript.append(turn);
  transcript.scrollTop = transcript.scrollHeight;
  return { turn, bubble };
}

function renderToolChips(turn, calls) {
  if (!calls.length) return;
  let chips = turn.querySelector('.chips');
  if (!chips) {
    chips = el('div', 'chips');
    turn.append(chips);
  }
  chips.replaceChildren(
    ...calls.map((call) => {
      const chip = el('span', call.failed ? 'chip failed' : 'chip');
      chip.append(el('span', null, call.tool), el('span', null, `${call.durationMillis} ms`));
      return chip;
    }),
  );
}

function renderToolLog(calls) {
  const log = $('#tool-log');
  log.replaceChildren(
    ...calls.map((call) => {
      const line = el('li');
      line.append(el('span', call.failed ? 'ko' : null, call.tool), el('span', 'dur', `${call.durationMillis} ms`));
      return line;
    }),
  );
  $('#tool-log-empty').hidden = calls.length > 0;
}

/**
 * Découpe un flux SSE. EventSource est inutilisable ici : /chat/stream est un POST porteur d'un
 * bearer, deux choses qu'EventSource ne sait pas émettre.
 */
async function* serverSentEvents(response) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
    let boundary;
    while ((boundary = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      let name = 'message';
      const data = [];
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) name = line.slice(6).trim();
        // Une seule espace après le deux-points est un délimiteur, les suivantes sont du contenu.
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length) yield { name, data: data.join('\n') };
    }
  }
}

async function sendStreaming(message) {
  const controller = new AbortController();
  inFlight = controller;
  const response = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
    body: JSON.stringify({ conversationId, message }),
    signal: controller.signal,
  });
  if (response.status === 401) openCredentials();
  if (!response.ok) throw await failure(response);

  const { turn, bubble } = addTurn('agent', '');
  bubble.classList.add('caret');
  const calls = [];
  try {
    for await (const event of serverSentEvents(response)) {
      if (event.name === 'conversation') {
        setConversation(event.data);
      } else if (event.name === 'token') {
        bubble.textContent += event.data;
        $('#transcript').scrollTop = $('#transcript').scrollHeight;
      } else if (event.name === 'tool') {
        calls.push(JSON.parse(event.data));
        renderToolChips(turn, calls);
        renderToolLog(calls);
      } else if (event.name === 'error') {
        turn.classList.add('error');
        bubble.textContent += (bubble.textContent ? '\n\n' : '') + event.data;
      }
    }
  } finally {
    bubble.classList.remove('caret');
    inFlight = null;
  }
}

async function sendBlocking(message) {
  const answer = await api('/api/agent/chat', { method: 'POST', body: { conversationId, message } });
  setConversation(answer.conversationId);
  const { turn } = addTurn('agent', answer.content);
  renderToolChips(turn, answer.tools || []);
  renderToolLog(answer.tools || []);
}

$('#composer').addEventListener('submit', async (event) => {
  event.preventDefault();
  const field = $('#prompt');
  const message = field.value.trim();
  if (!message) return;

  addTurn('user', message);
  field.value = '';
  field.style.height = 'auto';
  renderToolLog([]);

  const streaming = $('#stream-mode').checked;
  $('#send').disabled = true;
  $('#abort').hidden = !streaming;
  try {
    await (streaming ? sendStreaming(message) : sendBlocking(message));
  } catch (error) {
    if (error.name === 'AbortError') addTurn('error', 'Flux interrompu.');
    else {
      addTurn('error', error.message);
      report(error);
    }
  } finally {
    $('#send').disabled = false;
    $('#abort').hidden = true;
    field.focus();
  }
});

$('#abort').addEventListener('click', () => inFlight?.abort());

$('#prompt').addEventListener('input', (event) => {
  event.target.style.height = 'auto';
  event.target.style.height = `${event.target.scrollHeight}px`;
});

$('#prompt').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    $('#composer').requestSubmit();
  }
});

$('#clear-conversation').addEventListener('click', async () => {
  if (!conversationId) return;
  try {
    await api(`/api/agent/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE' });
    setConversation(null);
    $('#transcript').replaceChildren();
    renderToolLog([]);
    toast('Conversation purgée.');
  } catch (error) {
    report(error);
  }
});

/* ── Serveurs MCP ──────────────────────────────────────────────────────── */

function serverCard(server) {
  const card = el('article', 'server');
  const head = el('header');
  head.append(el('h3', null, server.connection));
  head.append(el('span', server.initialized ? 'tag ok' : 'tag ko', server.initialized ? 'initialisé' : 'non initialisé'));
  card.append(head);

  // Le nom annoncé n'existe qu'après le handshake : la clé de connexion reste le titre.
  const meta = [server.serverName, server.version, server.protocolVersion].filter(Boolean).join(' · ');
  card.append(el('p', 'meta', meta || 'Aucun handshake abouti pour l’instant'));

  const tools = server.tools || [];
  if (tools.length) {
    const list = el('ul', 'tool-list');
    for (const tool of tools) {
      const item = el('li');
      const button = el('button');
      button.type = 'button';
      button.append(el('span', 'name', tool.name));
      if (tool.description) button.append(el('span', 'desc', tool.description));
      button.addEventListener('click', () => openInvoke(card, server.connection, tool));
      item.append(button);
      list.append(item);
    }
    card.append(list);
  } else {
    card.append(el('p', 'empty', 'Aucun outil exposé.'));
  }

  const actions = el('div', 'server-actions');
  const resourcesButton = el('button', 'ghost', 'Ressources');
  resourcesButton.type = 'button';
  resourcesButton.addEventListener('click', () => loadResources(card, server.connection));
  actions.append(resourcesButton);
  card.append(actions);

  return card;
}

function openInvoke(card, connection, tool) {
  card.querySelector('.invoke')?.remove();
  const panel = el('section', 'invoke');
  panel.append(el('h4', null, tool.name));

  const args = el('textarea');
  args.rows = 4;
  args.spellcheck = false;
  args.value = '{}';
  panel.append(args);

  const run = el('button', 'primary', 'Invoquer');
  run.type = 'button';
  const output = el('pre', 'dump', '—');

  run.addEventListener('click', async () => {
    let parsed;
    try {
      parsed = JSON.parse(args.value || '{}');
    } catch {
      output.textContent = 'Arguments JSON invalides.';
      return;
    }
    run.disabled = true;
    output.textContent = '…';
    try {
      const result = await api(
        `/api/agent/mcp/servers/${encodeURIComponent(connection)}/tools/${encodeURIComponent(tool.name)}`,
        { method: 'POST', body: { arguments: parsed } },
      );
      output.textContent = JSON.stringify(result, null, 2);
    } catch (error) {
      output.textContent = error.message;
      report(error);
    } finally {
      run.disabled = false;
    }
  });

  const row = el('div', 'server-actions');
  row.append(run);
  panel.append(row, output);
  card.append(panel);
  args.focus();
}

async function loadResources(card, connection) {
  card.querySelector('.invoke')?.remove();
  const panel = el('section', 'invoke');
  panel.append(el('h4', null, 'Ressources'));
  const output = el('pre', 'dump', '…');
  panel.append(output);
  card.append(panel);
  try {
    const resources = await api(`/api/agent/mcp/servers/${encodeURIComponent(connection)}/resources`);
    if (!resources.length) {
      output.textContent = 'Aucune ressource exposée.';
      return;
    }
    output.textContent = '';
    const list = el('ul', 'tool-list');
    for (const resource of resources) {
      const button = el('button');
      button.type = 'button';
      button.append(el('span', 'name', resource.name || resource.uri));
      button.append(el('span', 'desc', resource.mimeType || resource.uri));
      button.addEventListener('click', async () => {
        output.textContent = '…';
        try {
          const content = await api(
            `/api/agent/mcp/servers/${encodeURIComponent(connection)}/resource?uri=${encodeURIComponent(resource.uri)}`,
          );
          output.textContent = JSON.stringify(content, null, 2);
        } catch (error) {
          output.textContent = error.message;
        }
      });
      const item = el('li');
      item.append(button);
      list.append(item);
    }
    panel.insertBefore(list, output);
  } catch (error) {
    output.textContent = error.message;
    report(error);
  }
}

async function loadServers() {
  const host = $('#servers');
  host.replaceChildren(el('p', 'empty', 'Chargement…'));
  try {
    const servers = await api('/api/agent/mcp/servers');
    host.replaceChildren(
      ...(servers.length ? servers.map(serverCard) : [el('p', 'empty', 'Aucune connexion MCP configurée.')]),
    );
  } catch (error) {
    host.replaceChildren(el('p', 'empty', error.message));
  }
}

$('#refresh-servers').addEventListener('click', loadServers);

/* ── Connaissance ──────────────────────────────────────────────────────── */

$('#knowledge-search').addEventListener('submit', async (event) => {
  event.preventDefault();
  const query = $('#kb-query').value.trim();
  const topK = $('#kb-topk').value;
  const list = $('#kb-matches');
  list.replaceChildren(el('li', 'empty', 'Recherche…'));
  try {
    const params = new URLSearchParams({ query });
    if (topK) params.set('topK', topK);
    const matches = await api(`/api/agent/knowledge?${params}`);
    if (!matches.length) {
      list.replaceChildren(el('li', 'empty', 'Aucune correspondance.'));
      return;
    }
    list.replaceChildren(
      ...matches.map((match) => {
        const item = el('li');
        if (match.score != null) item.append(el('span', 'score', match.score.toFixed(3)));
        item.append(el('code', null, match.id));
        item.append(el('p', null, match.text));
        return item;
      }),
    );
  } catch (error) {
    list.replaceChildren(el('li', 'empty', knowledgeMessage(error)));
  }
});

$('#knowledge-add').addEventListener('submit', async (event) => {
  event.preventDefault();
  let metadata;
  try {
    const raw = $('#kb-metadata').value.trim();
    metadata = raw ? JSON.parse(raw) : undefined;
  } catch {
    toast('Métadonnées JSON invalides.', 'error');
    return;
  }
  try {
    const ids = await api('/api/agent/knowledge', {
      method: 'POST',
      body: [{ text: $('#kb-text').value, metadata }],
    });
    $('#kb-text').value = '';
    toast(`Document ingéré : ${ids.join(', ')}`);
  } catch (error) {
    toast(knowledgeMessage(error), 'error');
  }
});

// Le contrôleur est conditionné sur kex.agent.knowledge.enabled : absent, il rend 404 et non 501.
const knowledgeMessage = (error) =>
  error.status === 404 ? 'Base de connaissance désactivée (kex.agent.knowledge.enabled).' : error.message;

/* ── Santé ─────────────────────────────────────────────────────────────── */

function tile(label, value) {
  const node = el('div', 'tile');
  node.append(el('div', 'label', label), el('div', 'value', value));
  return node;
}

async function metric(name) {
  try {
    const body = await api(`/actuator/metrics/${encodeURIComponent(name)}`);
    const measurement = body.measurements?.find((m) => m.statistic === 'VALUE' || m.statistic === 'COUNT');
    return measurement ? measurement.value : null;
  } catch {
    // Une métrique absente (modèle jamais appelé, endpoint filtré) n'est pas une panne.
    return null;
  }
}

async function loadHealth() {
  const tiles = $('#metric-tiles');
  const dump = $('#health-dump');
  try {
    const health = await fetch('/actuator/health').then((r) => r.json());
    setHealthPill(health.status);
    dump.textContent = JSON.stringify(health, null, 2);
  } catch (error) {
    setHealthPill('DOWN');
    dump.textContent = error.message;
  }

  const [tokens, requests, memory] = await Promise.all([
    metric('gen_ai.client.token.usage'),
    metric('http.server.requests'),
    metric('jvm.memory.used'),
  ]);

  const format = (value, unit) => (value == null ? '—' : `${Math.round(value).toLocaleString('fr-FR')}${unit || ''}`);
  tiles.replaceChildren(
    tile('Jetons consommés', format(tokens)),
    tile('Requêtes HTTP', format(requests)),
    tile('Mémoire JVM', memory == null ? '—' : `${Math.round(memory / 1048576)} Mio`),
  );

  try {
    const info = await api('/actuator/info');
    if (info?.build) tiles.append(tile('Version', `${info.build.version}`));
  } catch {
    /* /actuator/info exige le jeton : son absence ne casse pas la vue */
  }
}

function setHealthPill(status) {
  const pill = $('#health-pill');
  const state = status === 'UP' ? 'up' : status ? 'down' : 'unknown';
  pill.dataset.state = state;
  $('#health-label').textContent = status || 'état inconnu';
}

$('#refresh-health').addEventListener('click', loadHealth);

/* ── Dialogue du jeton ─────────────────────────────────────────────────── */

function openCredentials() {
  const dialog = $('#credentials');
  if (dialog.open) return;
  $('#api-key').value = memoryKey;
  dialog.showModal();
}

$('#open-credentials').addEventListener('click', openCredentials);

$('#credentials-form').addEventListener('submit', () => {
  credentials.set($('#api-key').value.trim());
  toast(memoryKey ? 'Jeton enregistré.' : 'Jeton effacé.');
  route();
});

$('#forget-key').addEventListener('click', () => {
  credentials.set('');
  $('#credentials').close();
});

/* ── Thème ─────────────────────────────────────────────────────────────── */

const themeToggle = $('#theme-toggle');
const storedTheme = localStorage.getItem('kex.agent.theme');
if (storedTheme) document.documentElement.dataset.theme = storedTheme;

function syncThemeButton() {
  const dark =
    document.documentElement.dataset.theme === 'dark' ||
    (!document.documentElement.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches);
  themeToggle.setAttribute('aria-pressed', String(dark));
}
syncThemeButton();

themeToggle.addEventListener('click', () => {
  const next = themeToggle.getAttribute('aria-pressed') === 'true' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('kex.agent.theme', next);
  syncThemeButton();
});

/* ── Routage ───────────────────────────────────────────────────────────── */

const VIEWS = {
  chat: { title: 'Conversation', load: null },
  mcp: { title: 'Serveurs MCP', load: loadServers },
  knowledge: { title: 'Connaissance', load: null },
  health: { title: 'Santé & métriques', load: loadHealth },
};

function route() {
  const name = location.hash.replace('#/', '') || 'chat';
  const view = VIEWS[name] ? name : 'chat';
  for (const key of Object.keys(VIEWS)) {
    $(`#view-${key}`).hidden = key !== view;
    const link = document.querySelector(`.nav-item[data-view="${key}"]`);
    if (key === view) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
  $('#crumb').textContent = VIEWS[view].title;
  VIEWS[view].load?.();
}

addEventListener('hashchange', route);

renderCredentialLabel();
setConversation(conversationId);
route();
// La pastille d'état est dans la barre du haut, visible depuis toutes les vues : elle est
// alimentée au démarrage sans rapatrier les métriques, qui n'intéressent que la vue Santé.
fetch('/actuator/health')
  .then((response) => response.json())
  .then((health) => setHealthPill(health.status))
  .catch(() => setHealthPill('DOWN'));
if (!memoryKey) openCredentials();
