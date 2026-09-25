// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Conversation directe avec l'agent : le chemin manuel, quand la supervision ne suffit pas à
// comprendre ce qui se passe.

import {
  $, ago, api, busy, dismissDrawer, el, empty, failure, headers, openDrawer, registerDrawer, report,
  params, setDrawerParam, setParams,
} from './core.js';
import { wireProcessWizard } from './process-creation.js';

const CONVERSATION_STORAGE = 'kex.agent.conversation';

// Le serveur ne garde ni la liste des conversations ni leur transcription — seule sa mémoire de
// modèle (ChatMemory) persiste, sans dimension par principal. Reprendre un échange passé n'a donc
// de sens que pour la transcription déjà vue dans ce navigateur : `localStorage`, jamais une source
// de vérité, seulement un moyen de la réafficher telle quelle.
const HISTORY_INDEX = 'kex.agent.conversations';
const TRANSCRIPT_PREFIX = 'kex.agent.conversation.';
const CONTEXT_CONVERSATIONS = 'kex.agent.context-conversations';
const MAX_HISTORY = 20;
const MAX_TURNS_STORED = 40;

const PROCESS_STARTERS = [
  {
    label: 'Définir un processus',
    prompt: 'Aide-moi à définir un nouveau processus d’intégration à surveiller. Pose-moi les questions nécessaires sur son objectif, ses étapes, ses sources et destinations, et les preuves disponibles avant de proposer sa déclaration dans kex.agent.supervision.processes.',
  },
  {
    label: 'Suivre un flux Kafka',
    prompt: 'Prépare une entrée YAML sous kex.agent.supervision.processes pour un flux Kafka allant de [topic source] à [topic destination], consommé par [consumer group]. Propose id, name, description et hint fondés sur ces informations. Signale ce qu’il faut encore vérifier.',
  },
  {
    label: 'Suivre plusieurs étapes',
    prompt: 'Aide-moi à décrire un processus d’intégration en plusieurs étapes : [étapes et topics dans l’ordre]. Identifie les preuves à contrôler à chaque étape, puis propose les champs id, name, description et hint de son entrée YAML sous kex.agent.supervision.processes.',
  },
  {
    label: 'Surveiller les échecs',
    prompt: 'Prépare une entrée YAML sous kex.agent.supervision.processes pour [flux métier] avec sa DLQ [topic DLQ] et son consumer group [groupe]. Décris comment détecter un retard ou des échecs et propose id, name, description et hint sans supposer de seuils non fournis.',
  },
];

let conversationId = null;
let inFlight = null;
let unauthorized = () => {};
let contextualPayload = '';
let contextualTitle = '';
let activeContextId = null;
let contextualConversationId = null;

try {
  conversationId = sessionStorage.getItem(CONVERSATION_STORAGE);
} catch {
  /* sans stockage, la conversation reste valide pour la durée de la page */
}

function setConversation(id) {
  conversationId = id || null;
  $('#conversation-id').textContent = id || '—';
  $('#clear-conversation').disabled = !id;
  try {
    if (id) sessionStorage.setItem(CONVERSATION_STORAGE, id);
    else sessionStorage.removeItem(CONVERSATION_STORAGE);
  } catch {
    /* idem */
  }
}

function readIndex() {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_INDEX) || '[]');
  } catch {
    return [];
  }
}

function writeIndex(list) {
  try {
    localStorage.setItem(HISTORY_INDEX, JSON.stringify(list.slice(0, MAX_HISTORY)));
  } catch {
    /* navigation privée ou quota dépassé : l'historique reste valide pour la session en cours */
  }
}

function touchIndex(id, label) {
  if (!id) return;
  const list = readIndex();
  const existing = list.find((entry) => entry.id === id);
  const remaining = list.filter((entry) => entry.id !== id);
  // Le libellé se fixe au premier message et ne bouge plus : le faire suivre le dernier message
  // rendrait une entrée méconnaissable d'un envoi à l'autre dans la liste.
  remaining.unshift({ id, label: existing?.label || label || 'Conversation', updatedAt: new Date().toISOString() });
  writeIndex(remaining);
}

function removeFromIndex(id) {
  writeIndex(readIndex().filter((entry) => entry.id !== id));
  const contexts = readContextConversations();
  Object.entries(contexts).forEach(([contextId, conversation]) => {
    if (conversation === id) delete contexts[contextId];
  });
  writeContextConversations(contexts);
  if (contextualConversationId === id) contextualConversationId = null;
  try {
    localStorage.removeItem(TRANSCRIPT_PREFIX + id);
  } catch {
    /* idem */
  }
}

function readContextConversations() {
  try {
    return JSON.parse(sessionStorage.getItem(CONTEXT_CONVERSATIONS) || '{}');
  } catch {
    return {};
  }
}

function writeContextConversations(contexts) {
  try {
    sessionStorage.setItem(CONTEXT_CONVERSATIONS, JSON.stringify(contexts));
  } catch {
    /* sans stockage, l'isolation reste valable tant que la page n'est pas rechargée */
  }
}

function rememberContextConversation(contextId, id) {
  if (!contextId || !id) return;
  const contexts = readContextConversations();
  contexts[contextId] = id;
  writeContextConversations(contexts);
}

function readTranscript(id) {
  try {
    return JSON.parse(localStorage.getItem(TRANSCRIPT_PREFIX + id) || '[]');
  } catch {
    return [];
  }
}

function appendTranscript(id, turn) {
  if (!id) return;
  const turns = readTranscript(id);
  turns.push(turn);
  try {
    localStorage.setItem(TRANSCRIPT_PREFIX + id, JSON.stringify(turns.slice(-MAX_TURNS_STORED)));
  } catch {
    /* idem */
  }
}

/** Le composer grandit avec son contenu, jusqu'à `max-height` (`console.css`) puis défile. */
function resizeComposer(field) {
  field.style.height = 'auto';
  field.style.height = `${field.scrollHeight}px`;
}

/** À cette distance du bas ou moins, on considère que l'utilisateur suit le flux en direct. */
function nearBottom(container, threshold = 56) {
  return container.scrollHeight - container.scrollTop - container.clientHeight <= threshold;
}

export function renderMarkdown(markdownText) {
  const container = document.createElement('div');
  container.className = 'markdown-body';
  if (!markdownText) return container;

  const codeBlockRegex = /```([a-z0-9_-]*)\n?([\s\S]*?)```/g;
  let lastIndex = 0;
  let match;

  while ((match = codeBlockRegex.exec(markdownText)) !== null) {
    const textBefore = markdownText.slice(lastIndex, match.index);
    if (textBefore) parseBlocks(textBefore, container);

    const lang = match[1].trim();
    const codeContent = match[2];

    const pre = document.createElement('pre');
    pre.className = 'code-block';
    if (lang) pre.setAttribute('data-lang', lang);
    const code = document.createElement('code');
    code.textContent = codeContent;
    pre.append(code);
    container.append(pre);

    lastIndex = codeBlockRegex.lastIndex;
  }

  const remaining = markdownText.slice(lastIndex);
  if (remaining) {
    const unclosed = remaining.match(/^```([a-z0-9_-]*)\n?([\s\S]*)$/);
    if (unclosed) {
      const lang = unclosed[1].trim();
      const codeContent = unclosed[2];

      const pre = document.createElement('pre');
      pre.className = 'code-block streaming';
      if (lang) pre.setAttribute('data-lang', lang);
      const code = document.createElement('code');
      code.textContent = codeContent;
      pre.append(code);
      container.append(pre);
    } else {
      parseBlocks(remaining, container);
    }
  }

  return container;
}

function parseBlocks(text, parent) {
  const paragraphs = text.split(/\n{2,}/);
  for (const para of paragraphs) {
    const trimmed = para.trim();
    if (!trimmed) continue;

    const lines = trimmed.split('\n');
    const isList = lines.length > 0 && lines.every((l) => /^\s*[-*•]\s+/.test(l));

    if (isList) {
      const ul = document.createElement('ul');
      for (const line of lines) {
        const itemText = line.replace(/^\s*[-*•]\s+/, '');
        const li = document.createElement('li');
        parseInline(itemText, li);
        ul.append(li);
      }
      parent.append(ul);
    } else {
      const headerMatch = trimmed.match(/^(#{1,3})\s+(.*)$/);
      if (headerMatch && lines.length === 1) {
        const level = headerMatch[1].length;
        const hTag = level === 1 ? 'h3' : level === 2 ? 'h4' : 'h5';
        const header = document.createElement(hTag);
        header.className = 'chat-header';
        parseInline(headerMatch[2], header);
        parent.append(header);
      } else {
        const p = document.createElement('p');
        p.className = 'chat-paragraph';
        lines.forEach((line, idx) => {
          if (idx > 0) p.append(document.createElement('br'));
          parseInline(line, p);
        });
        parent.append(p);
      }
    }
  }
}

function parseInline(text, parent) {
  const regex = /(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__)/g;
  let lastIdx = 0;
  let match;

  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIdx) {
      parent.append(document.createTextNode(text.slice(lastIdx, match.index)));
    }
    const token = match[0];
    if (token.startsWith('`') && token.endsWith('`')) {
      const code = document.createElement('code');
      code.className = 'inline-code';
      code.textContent = token.slice(1, -1);
      parent.append(code);
    } else if ((token.startsWith('**') && token.endsWith('**')) || (token.startsWith('__') && token.endsWith('__'))) {
      const strong = document.createElement('strong');
      strong.textContent = token.slice(2, -2);
      parent.append(strong);
    }
    lastIdx = regex.lastIndex;
  }
  if (lastIdx < text.length) {
    parent.append(document.createTextNode(text.slice(lastIdx)));
  }
}

function updateBubble(bubble, role, text) {
  if (role === 'error') {
    bubble.textContent = text || '';
  } else {
    bubble.replaceChildren(renderMarkdown(text || ''));
  }
}

function addTurn(role, text) {
  const turn = el('li', `turn ${role}`);
  turn.append(el('span', 'who', role === 'user' ? 'vous' : role === 'error' ? 'erreur' : 'agent'));
  const bubble = el('div', 'bubble');
  updateBubble(bubble, role, text);
  turn.append(bubble);
  const transcript = $('#transcript');
  transcript.append(turn);
  transcript.scrollTop = transcript.scrollHeight;
  return { turn, bubble };
}

function addContextTurn(role, text) {
  const turn = el('li', `turn ${role}`);
  turn.append(el('span', 'who', role === 'user' ? 'vous' : role === 'error' ? 'erreur' : 'agent'));
  const bubble = el('div', 'bubble');
  updateBubble(bubble, role, text);
  turn.append(bubble);
  const transcript = $('#context-chat-transcript');
  transcript.append(turn);
  transcript.scrollTop = transcript.scrollHeight;
  return turn;
}

function renderSentContext(turn, saved) {
  if (!saved.context) return;
  const detail = el('details', 'sent-context');
  detail.append(el('summary', null, saved.contextTitle || 'Contexte transmis'),
    el('pre', 'dump', saved.context));
  turn.append(detail);
}

function replayContext(id) {
  $('#context-chat-transcript').replaceChildren();
  if (!id) return;
  readTranscript(id).forEach((saved) => {
    const turn = addContextTurn(saved.role, saved.text);
    if (saved.role === 'agent') {
      renderToolChips(turn, saved.tools || []);
      renderFinishReason(turn, saved.finishReason);
    }
  });
}

/** Ouvre l'assistant à côté du contexte opérationnel, sans changer de route. */
export function openContextual({ title = 'Interroger l’agent', context = '', contextId = null } = {}) {
  contextualPayload = context.trim();
  contextualTitle = title;
  activeContextId = contextId || `${title}\n${contextualPayload}`;
  contextualConversationId = readContextConversations()[activeContextId] || null;
  $('#context-chat-title').textContent = title;
  $('#context-chat-payload').textContent = contextualPayload || 'Aucun contexte structuré.';
  replayContext(contextualConversationId);
  $('#context-chat').hidden = false;
  document.documentElement.dataset.contextChat = 'open';
  $('#context-chat-prompt').focus();
}

function closeContextual() {
  $('#context-chat').hidden = true;
  delete document.documentElement.dataset.contextChat;
}

async function sendContextual(question) {
  const contextId = activeContextId;
  const context = contextualPayload;
  const title = contextualTitle;
  const ownConversationId = contextualConversationId;
  const message = [context, `Question de l’opérateur : ${question}`].filter(Boolean).join('\n\n');
  const answer = await api('/api/agent/chat', {
    method: 'POST', body: { conversationId: ownConversationId, message },
  });
  if (activeContextId === contextId) contextualConversationId = answer.conversationId;
  rememberContextConversation(contextId, answer.conversationId);
  const savedQuestion = { role: 'user', text: question, context, contextTitle: title };
  appendTranscript(answer.conversationId, savedQuestion);
  touchIndex(answer.conversationId, question.slice(0, 48));
  appendTranscript(answer.conversationId,
    { role: 'agent', text: answer.content, tools: answer.tools, finishReason: answer.finishReason });

  if (activeContextId === contextId) {
    // La conversation complète reste la continuité de ce panneau : en l'ouvrant ensuite, les tours
    // effectués ici ne disparaissent pas de l'écran ni de la transcription locale. La rejouer évite
    // aussi de juxtaposer à l'écran les tours de deux contextes dont les conversations sont isolées.
    setConversation(answer.conversationId);
    replay(answer.conversationId);
    const contextualTurn = addContextTurn('agent', answer.content);
    renderToolChips(contextualTurn, answer.tools || []);
    renderFinishReason(contextualTurn, answer.finishReason);
  }
}

function renderToolChips(turn, calls) {
  if (!calls.length) return;
  let chips = turn.querySelector('.chips');
  if (!chips) {
    chips = el('div', 'chips');
    turn.append(chips);
  }
  chips.replaceChildren(...calls.map((call) => {
    const chip = el('span', call.failed ? 'chip failed' : 'chip');
    chip.append(el('span', null, call.tool), el('span', null, `${call.durationMillis} ms`));
    return chip;
  }));
}

/**
 * Les fins normales restent muettes ; le plafond de jetons atteint, ou un motif que la console ne
 * connaît pas, se voient. Sans ce repère, une réponse coupée en plein milieu s'affiche exactement
 * comme une réponse complète — et le vocabulaire varie d'un fournisseur à l'autre, si bien qu'un
 * motif inconnu vaut un doute affiché, jamais un silence.
 */
const NORMAL_ENDINGS = new Set(['end_turn', 'stop', 'stop_sequence', 'tool_use', 'tool_calls']);
const TRUNCATED = new Set(['max_tokens', 'length']);

function renderFinishReason(turn, finishReason) {
  const reason = (finishReason || '').toLowerCase();
  if (!reason || NORMAL_ENDINGS.has(reason)) return;
  const note = el('div', 'chips');
  note.append(el('span', 'chip failed', TRUNCATED.has(reason)
    ? `réponse coupée au plafond de jetons (${finishReason})`
    : `fin inhabituelle (${finishReason})`));
  turn.append(note);
}

function renderToolLog(calls) {
  $('#tool-log').replaceChildren(...calls.map((call) => {
    const line = el('li');
    line.append(el('span', call.failed ? 'ko' : null, call.tool), el('span', 'dur', `${call.durationMillis} ms`));
    return line;
  }));
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
        else if (line.startsWith('data:')) data.push(line.slice(5));
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
  if (response.status === 401) unauthorized();
  if (!response.ok) throw await failure(response);

  const { turn, bubble } = addTurn('agent', '');
  bubble.classList.add('caret');
  const calls = [];
  // Capturé plutôt que relu sur `conversationId` au moment de persister : reprendre une autre
  // conversation pendant que ce flux tourne encore réassigne la variable partagée, et le flux
  // interrompu ne doit pas écrire son tour incomplet dans la conversation qui vient de le remplacer.
  let ownConversationId = null;
  let accumulatedText = '';
  try {
    for await (const event of serverSentEvents(response)) {
      if (event.name === 'conversation') {
        setConversation(event.data);
        ownConversationId = event.data;
        appendTranscript(ownConversationId, { role: 'user', text: message });
        touchIndex(ownConversationId, message.slice(0, 48));
      } else if (event.name === 'token') {
        // Capturé avant d'ajouter le texte : une fois le contenu ajouté, `scrollHeight` a déjà
        // grandi et la distance au bas ne dit plus si l'utilisateur y était avant ce jeton.
        const transcript = $('#transcript');
        const stick = nearBottom(transcript);
        accumulatedText += event.data;
        updateBubble(bubble, 'agent', accumulatedText);
        if (stick) transcript.scrollTop = transcript.scrollHeight;
      } else if (event.name === 'tool') {
        calls.push(JSON.parse(event.data));
        renderToolChips(turn, calls);
        renderToolLog(calls);
      } else if (event.name === 'error') {
        turn.classList.add('error');
        accumulatedText += (accumulatedText ? '\n\n' : '') + event.data;
        updateBubble(bubble, 'error', accumulatedText);
      }
    }
  } finally {
    bubble.classList.remove('caret');
    // Le motif d'arrêt n'arrive pas sur ce chemin (voir OBSERVABILITE.md) : rien à consigner ici,
    // contrairement au chemin bloquant.
    if (ownConversationId) {
      appendTranscript(ownConversationId, { role: 'agent', text: accumulatedText, tools: calls });
    }
    inFlight = null;
  }
}

async function sendBlocking(message) {
  const answer = await api('/api/agent/chat', { method: 'POST', body: { conversationId, message } });
  setConversation(answer.conversationId);
  appendTranscript(answer.conversationId, { role: 'user', text: message });
  touchIndex(answer.conversationId, message.slice(0, 48));
  const { turn } = addTurn('agent', answer.content);
  renderToolChips(turn, answer.tools || []);
  renderFinishReason(turn, answer.finishReason);
  renderToolLog(answer.tools || []);
  appendTranscript(answer.conversationId,
    { role: 'agent', text: answer.content, tools: answer.tools, finishReason: answer.finishReason });
}

/** Redessine une conversation déjà connue depuis sa transcription locale, sans appel réseau. */
function replay(id) {
  $('#transcript').replaceChildren();
  let lastTools = [];
  readTranscript(id).forEach((saved) => {
    const { turn } = addTurn(saved.role, saved.text);
    if (saved.role === 'user') renderSentContext(turn, saved);
    if (saved.role === 'agent') {
      if (saved.tools?.length) {
        renderToolChips(turn, saved.tools);
        lastTools = saved.tools;
      }
      if (saved.finishReason) renderFinishReason(turn, saved.finishReason);
    }
  });
  renderToolLog(lastTools);
}

function historyRow(entry) {
  const card = el('article', 'card');
  const head = el('header');
  head.append(el('h3', null, entry.label || 'Conversation'));
  if (entry.id === conversationId) head.append(el('span', 'muted', 'en cours'));
  card.append(head);
  card.append(el('p', 'muted', `${ago(entry.updatedAt) || '—'} · ${entry.id}`));

  const actions = el('div', 'row-end');
  const remove = el('button', 'ghost danger', 'Supprimer');
  remove.type = 'button';
  remove.setAttribute('aria-label', `Supprimer : ${entry.label}`);
  remove.addEventListener('click', () => busy(remove, async () => {
    try {
      await api(`/api/agent/conversations/${encodeURIComponent(entry.id)}`, { method: 'DELETE' });
    } catch (error) {
      // Une conversation déjà purgée côté serveur (expiration, redémarrage) n'empêche pas de
      // nettoyer la trace locale : seul un échec réseau mérite d'être signalé ici.
      if (error.status !== 404) report(error);
    }
    removeFromIndex(entry.id);
    if (entry.id === conversationId) {
      setConversation(null);
      $('#transcript').replaceChildren();
      renderToolLog([]);
    }
    openHistory();
  }));
  const resume = el('button', 'primary', 'Reprendre');
  resume.type = 'button';
  resume.setAttribute('aria-label', `Reprendre : ${entry.label}`);
  resume.addEventListener('click', () => {
    inFlight?.abort();
    setConversation(entry.id);
    replay(entry.id);
    dismissDrawer();
  });
  actions.append(remove, resume);
  card.append(actions);
  return card;
}

function openHistory() {
  setDrawerParam('historique', '1');
  const list = readIndex();
  const body = el('div');
  if (!list.length) {
    body.append(empty('Aucune conversation récente.', 'Elle apparaît ici après le premier message envoyé.'));
  } else {
    const cards = el('div', 'cards wide');
    list.forEach((entry) => cards.append(historyRow(entry)));
    body.append(cards);
  }
  openDrawer('Conversations récentes', body);
}

/** Reprend un diagnostic contextuel préparé depuis une alerte ou un processus. */
export function prefill() {
  const draft = params().get('draft');
  if (!draft) return;
  const field = $('#prompt');
  if (!field.value) {
    field.value = draft;
    // Posé par script, donc sans l'événement `input` qui grandit d'habitude le composer : sans cet
    // appel, un brouillon de plusieurs lignes reste coincé dans une zone d'une seule ligne tant que
    // l'opérateur n'a pas lui-même tapé un caractère.
    resizeComposer(field);
  }
  setParams({ draft: null });
  field.focus();
}

export function wire(onUnauthorized) {
  unauthorized = onUnauthorized;
  setConversation(conversationId);
  wireProcessWizard();
  $('#chat-process-starters').replaceChildren(...PROCESS_STARTERS.map(({ label, prompt }) => {
    const button = el('button', 'ghost', label);
    button.type = 'button';
    button.addEventListener('click', () => {
      const field = $('#prompt');
      field.value = field.value.trim() ? `${field.value.trimEnd()}\n\n${prompt}` : prompt;
      resizeComposer(field);
      field.focus();
      field.setSelectionRange(field.value.length, field.value.length);
    });
    return button;
  }));
  registerDrawer('historique', openHistory);
  addEventListener('kex:context-chat', (event) => openContextual(event.detail));
  $('#context-chat-close').addEventListener('click', closeContextual);
  $('#context-chat-full').addEventListener('click', () => {
    if (contextualConversationId) {
      setConversation(contextualConversationId);
      replay(contextualConversationId);
    }
    closeContextual();
  });
  addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('#context-chat').hidden) closeContextual();
  });
  $('#context-chat-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const field = $('#context-chat-prompt');
    const question = field.value.trim();
    if (!question) return;
    addContextTurn('user', question);
    field.value = '';
    const send = $('#context-chat-send');
    send.disabled = true;
    try {
      await sendContextual(question);
    } catch (error) {
      addContextTurn('error', error.message);
      report(error);
    } finally {
      send.disabled = false;
      field.focus();
    }
  });

  $('#composer').addEventListener('submit', async (event) => {
    event.preventDefault();
    const field = $('#prompt');
    const message = field.value.trim();
    if (!message) return;

    addTurn('user', message);
    field.value = '';
    resizeComposer(field);
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

  $('#prompt').addEventListener('input', (event) => resizeComposer(event.target));

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
      removeFromIndex(conversationId);
      setConversation(null);
      $('#transcript').replaceChildren();
      renderToolLog([]);
    } catch (error) {
      report(error);
    }
  });

  $('#open-history').addEventListener('click', openHistory);
}
