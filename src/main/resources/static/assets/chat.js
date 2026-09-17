// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Conversation directe avec l'agent : le chemin manuel, quand la supervision ne suffit pas à
// comprendre ce qui se passe.

import { $, api, el, failure, headers, report } from './core.js';

const CONVERSATION_STORAGE = 'kex.agent.conversation';

let conversationId = null;
let inFlight = null;
let unauthorized = () => {};

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
  if (response.status === 401) unauthorized();
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
  renderFinishReason(turn, answer.finishReason);
  renderToolLog(answer.tools || []);
}

export function wire(onUnauthorized) {
  unauthorized = onUnauthorized;
  setConversation(conversationId);

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
    } catch (error) {
      report(error);
    }
  });
}
