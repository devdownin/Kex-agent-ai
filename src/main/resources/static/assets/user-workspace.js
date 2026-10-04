// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { $, api, credentials, el, failure, headers } from './core.js';
import { events } from './user-stream.js';
import { activity, parameterFields, parseResponse, responsePrompt, skillSignature } from './user-experience.js';

const STARTERS = [
  { title: 'Vérifier un processus', description: 'Comprendre son état et repérer les retards.', prompt: 'Vérifie le bon fonctionnement de ce processus. Présente les faits observés, les limites et les points à examiner.' },
  { title: 'Retrouver une commande', description: 'Comprendre où en est son traitement.', prompt: 'Recherche le parcours de cet élément. Indique son dernier point de traitement connu sans inventer les étapes manquantes.' },
  { title: 'Comprendre une anomalie', description: 'Examiner les faits et les pistes de résolution.', prompt: 'Analyse cette anomalie. Distingue les faits, les hypothèses et les vérifications nécessaires avant toute action.' },
  { title: 'Préparer un bilan', description: 'Résumer les incidents et les actions à suivre.', prompt: 'Prépare un bilan clair de la situation. Indique les incidents observés, les sources datées et les actions restant à suivre.' },
];
const LABELS = { RUNNING: 'En cours', NEEDS_INPUT: 'Votre réponse est nécessaire', COMPLETE: 'Réponse reçue', PARTIAL: 'Résultat partiel', ERROR: 'Traitement interrompu', INTERRUPTED: 'Réception interrompue' };
let identity = null;
let history = [];
let current = null;
let active = null;
let selected = null;
let fields = [];
let preparedSkill = null;
let preparing = false;
let epoch = 0;
let skillEpoch = 0;
const storageKey = () => `kex.agent.user.requests.${encodeURIComponent(identity)}`;
function save() {
  if (!identity) return;
  try { sessionStorage.setItem(storageKey(), JSON.stringify(history.slice(0, 20))); }
  catch { $('#notice').textContent = 'L’historique reste en mémoire : le stockage de cet onglet est indisponible.'; }
}
function load() {
  try {
    const rows = JSON.parse(sessionStorage.getItem(storageKey()) || '[]');
    history = Array.isArray(rows) ? rows.filter(r => r && typeof r.id === 'string' && Array.isArray(r.turns)).slice(0, 20) : [];
    history.forEach(r => { if (r.status === 'RUNNING') r.status = 'INTERRUPTED'; });
  } catch { history = []; }
}
function button(label, action, className = '') {
  const b = el('button', className, label); b.type = 'button'; b.addEventListener('click', action); return b;
}
function date(at) { return at ? new Date(at).toLocaleString('fr-FR') : 'Date non fournie'; }
function route() {
  const name = location.hash.slice(2) || 'new';
  const request = name.startsWith('request/') ? history.find(r => r.id === name.slice(8)) : null;
  const page = request ? 'detail' : ['new', 'actions', 'requests'].includes(name) ? name : 'new';
  ['new', 'actions', 'requests', 'detail'].forEach(id => { $('#' + id).hidden = id !== page; });
  document.querySelectorAll('[data-page]').forEach(a => {
    if (a.dataset.page === (page === 'detail' ? 'requests' : page)) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
  current = request;
  if (request) drawDetail();
  if (page === 'requests') drawHistory();
}
function drawHistory() {
  const host = $('#request-list'); host.replaceChildren();
  if (!history.length) { host.append(el('p', 'muted', 'Aucune demande pour le moment. Commencez par décrire votre besoin.')); return; }
  history.forEach(r => {
    const card = el('article', 'request-card'); const text = el('div');
    text.append(el('strong', null, r.title), el('small', null, `${LABELS[r.status] || 'État non disponible'} · ${date(r.updatedAt)}`));
    card.append(text, button('Consulter', () => { location.hash = '#/request/' + r.id; })); host.append(card);
  });
}
function drawDetail() {
  if (!current) return;
  $('#detail-title').textContent = current.title;
  $('#run-status').textContent = LABELS[current.status] || 'État non disponible';
  const running = active?.request === current;
  $('#stop').hidden = !running;
  $('#followup-send').disabled = !!active || !identity || !current.conversationId;
  $('#followup-form').hidden = !current.conversationId;
  $('#open-expert').disabled = !current.conversationId || !!active;
  const steps = [current.conversationId ? 'Demande reçue par Kex' : 'Envoi de la demande'];
  current.tools.slice(-8).forEach(t => steps.push(activity(t)));
  if (current.status === 'RUNNING') steps.push(current.answerStarted ? 'Rédaction de la réponse en cours' : 'Traitement en cours, en attente des premiers résultats');
  else if (current.status === 'NEEDS_INPUT') steps.push('Choisissez une réponse ou écrivez votre précision, puis envoyez-la.');
  else if (current.status === 'COMPLETE') steps.push('Réponse reçue. La réussite de l’objectif reste à vérifier.');
  else steps.push('Réception incomplète. Vérifiez ce qui a été accompli avant de poursuivre.');
  $('#progress').replaceChildren(...steps.map(s => el('li', null, s)));
  const turns = $('#turns'); turns.replaceChildren();
  current.turns.forEach(t => {
    const article = el('article', 'turn'); article.append(el('h2', null, t.role === 'user' ? 'Votre demande' : 'Réponse de Kex'));
    const result = t.role === 'agent' && t.completed ? parseResponse(t.text) : null;
    if (result?.kind === 'result') {
      [['Ce que j’ai constaté', result.observations], ['Ce qui reste incertain', result.uncertainties], ['Prochaine action', result.nextAction]].forEach(([title, content]) => {
        article.append(el('h3', null, title), el('div', 'answer', content));
      });
    } else if (result?.kind === 'clarification') {
      article.append(el('h3', null, result.question));
      const choices = el('div', 'row');
      result.choices.forEach(c => { const choice = button(c.label, () => { $('#followup').value = c.value; $('#followup').focus(); }); choice.disabled = !!active || !current.conversationId; choices.append(choice); });
      article.append(choices, el('p', 'muted', 'Le choix prépare votre réponse. Cliquez sur Envoyer pour poursuivre ; vous pouvez aussi écrire une autre réponse.'));
    } else article.append(el('div', 'answer', t.role === 'agent' && current.status === 'RUNNING' && t === current.turns.at(-1) ? 'Kex prépare votre réponse…' : t.text || 'En attente de la réponse…'));
    if (t.error) article.append(el('p', 'error', t.error));
    if (t.sources?.length) {
      const d = el('details'); d.append(el('summary', null, 'Sources consultées'));
      t.sources.forEach(s => { d.append(el('p', null, `${s.source || s.id} · ${date(s.observedAt)}`), el('pre', 'muted', s.excerpt)); });
      article.append(d);
    }
    turns.append(article);
  });
  if (current.tools.length) {
    const details = el('details'); details.append(el('summary', null, 'Voir les détails du traitement'));
    current.tools.forEach(t => details.append(el('p', null, `${t.tool} : ${t.failed ? 'échec signalé' : 'appel terminé'} (${t.durationMillis} ms)`)));
    turns.append(details);
  }
}
function card(action) {
  const b = button('', () => openAction(action), 'action-card'); b.append(el('strong', null, action.title), el('span', null, action.description || 'Utiliser cette procédure approuvée avec votre contexte.')); return b;
}
function openAction(action) {
  selected = action;
  $('#action-title').textContent = action.title;
  $('#action-description').textContent = action.description || 'Précisez votre contexte. Kex vérifiera les préconditions avant de poursuivre.';
  $('#action-subject').value = ''; $('#action-period').value = ''; $('#action-error').textContent = '';
  $('#skill-details').hidden = !action.id;
  $('#skill-procedure').textContent = action.markdown || '';
  const meta = $('#skill-context'); meta.replaceChildren();
  const parameters = $('#skill-parameters'); parameters.replaceChildren(); fields = [];
  if (action.id) {
    const verification = action.verification;
    const expected = verification?.checks?.filter(v => typeof v === 'string') || [];
    const preconditions = verification?.preconditions?.filter(v => typeof v === 'string') || [];
    meta.append(el('h3', null, 'Résultat attendu'), el('p', null, expected.join(' ; ') || 'Une réponse documentée selon la procédure. Les critères de réussite ne sont pas renseignés.'));
    meta.append(el('h3', null, 'Conditions à vérifier'), el('p', null, preconditions.join(' ; ') || 'Aucune condition explicite fournie. Kex devra préciser les limites avant de poursuivre.'));
    const descriptors = parameterFields(verification?.parameters);
    fields = descriptors.fields;
    fields.forEach((field, i) => {
      const id = `skill-parameter-${i}`;
      const labels = { topic: 'Sujet', process: 'Processus', processId: 'Identifiant du processus', from: 'Début de période', to: 'Fin de période', orderId: 'Identifiant de commande', limit: 'Nombre de résultats' };
      const name = labels[field.path.at(-1)] || field.path.at(-1);
      parameters.append(el('label', null, name));
      parameters.lastChild.htmlFor = id;
      const input = el(typeof field.value === 'boolean' ? 'select' : 'input'); input.id = id; input.required = true;
      if (typeof field.value === 'boolean') {
        [['', 'Choisir'], ['true', 'Oui'], ['false', 'Non']].forEach(([value, label]) => { const option = el('option', null, label); option.value = value; input.append(option); });
      }
      if (input.tagName === 'INPUT') input.type = typeof field.value === 'number' ? 'number' : 'text';
      if (input.type === 'number') input.step = 'any';
      input.maxLength = 1000; input.placeholder = `Exemple : ${field.value}`;
      parameters.append(el('small', 'muted', `Exemple fourni : ${typeof field.value === 'boolean' ? (field.value ? 'Oui' : 'Non') : field.value}`));
      parameters.append(input);
    });
    if (descriptors.omitted) parameters.append(el('p', 'muted', 'Certains paramètres complexes restent dans la procédure. Décrivez-les dans le contexte ; Kex devra les clarifier.'));
  }
  $('#action-dialog').showModal();
}
async function skills() {
  const version = ++skillEpoch; const ownEpoch = epoch;
  const host = $('#skills'); host.replaceChildren(el('p', 'muted', identity ? 'Chargement des compétences…' : 'Connectez-vous pour consulter les compétences de votre équipe.'));
  if (!identity) return;
  try {
    const rows = await api('/api/agent/skills/available');
    if (version !== skillEpoch || ownEpoch !== epoch) return;
    host.replaceChildren(...rows.map(card));
    if (!rows.length) host.append(el('p', 'muted', 'Aucune compétence approuvée disponible. Vous pouvez utiliser les demandes guidées ou écrire votre demande.'));
  } catch (e) {
    if (version !== skillEpoch || ownEpoch !== epoch) return;
    host.replaceChildren(el('p', 'muted', e.status === 404 ? 'Les compétences ne sont pas activées sur ce serveur. Les demandes guidées restent disponibles.' : 'Les compétences ne sont pas disponibles. Réessayez ou contactez votre administrateur.'));
  }
}
function clearIdentity() {
  // Invalide toutes les réponses tardives avant de changer l'espace affiché.
  epoch++; skillEpoch++; active?.controller.abort(); active = null;
  identity = null; history = []; current = null; selected = null; preparedSkill = null;
  $('#turns').replaceChildren(); $('#request-list').replaceChildren(); $('#skills').replaceChildren();
  $('#identity').textContent = ''; $('#prompt').value = ''; $('#followup').value = '';
  $('#selected-action').hidden = true; $('#send').disabled = true;
  if ($('#action-dialog').open) $('#action-dialog').close();
  $('#account').textContent = 'Se connecter'; route();
}
async function authenticate() {
  const draft = identity ? '' : $('#prompt').value;
  clearIdentity(); const ownEpoch = epoch;
  if (!credentials.get()) { skills(); return false; }
  try {
    const me = await api('/api/agent/whoami');
    if (ownEpoch !== epoch) return false;
    identity = JSON.stringify([me.tenant, me.name]); load();
    $('#prompt').value = draft;
    $('#identity').textContent = `Connecté : ${me.name}`; $('#account').textContent = 'Mon accès';
    $('#send').disabled = false; $('#notice').textContent = ''; route(); skills(); return true;
  } catch (e) {
    if (ownEpoch === epoch) $('#connection-error').textContent = e.status === 401 ? 'Ce code d’accès n’est pas reconnu.' : 'Connexion impossible. Vérifiez votre accès ou contactez votre administrateur.';
    return false;
  }
}
async function send(message, request = null) {
  if (active || !identity) return;
  if (!request) {
    request = { id: crypto.randomUUID(), title: message.slice(0, 100), turns: [], tools: [], conversationId: null };
    history.unshift(request); history = history.slice(0, 20);
  }
  const ownEpoch = epoch; const controller = new AbortController();
  active = { request, controller };
  request.turns = request.turns.slice(-38); request.turns.push({ role: 'user', text: message });
  const answer = { role: 'agent', text: '', sources: [] }; request.turns.push(answer);
  request.status = 'RUNNING'; request.answerStarted = false; request.updatedAt = new Date().toISOString();
  save(); current = request; location.hash = '#/request/' + request.id; route(); $('#send').disabled = true;
  let complete = false;
  const firstCall = request.tools.length;
  try {
    const response = await fetch('/api/agent/chat/stream', { method: 'POST', headers: headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }), body: JSON.stringify({ message: responsePrompt(message), conversationId: request.conversationId }), signal: controller.signal });
    if (!response.ok) throw await failure(response);
    for await (const event of events(response)) {
      if (ownEpoch !== epoch) return;
      if (event.name === 'conversation') request.conversationId = event.data;
      else if (event.name === 'token') { answer.text += event.data; request.answerStarted = true; }
      else if (event.name === 'tool') request.tools.push(JSON.parse(event.data));
      else if (event.name === 'sources') answer.sources = JSON.parse(event.data);
      else if (event.name === 'error') { answer.error = event.data; request.status = 'ERROR'; }
      else if (event.name === 'done') complete = true;
      request.updatedAt = new Date().toISOString(); save();
      if (current === request) drawDetail();
    }
    if (request.status !== 'ERROR') request.status = complete && answer.text ? (request.tools.slice(firstCall).some(t => t.failed) ? 'PARTIAL' : 'COMPLETE') : 'PARTIAL';
    answer.completed = complete && !answer.error;
    if (request.status === 'COMPLETE' && parseResponse(answer.text)?.kind === 'clarification') request.status = 'NEEDS_INPUT';
    if (!complete && !answer.error) answer.error = 'La fin de la réponse n’a pas été confirmée. Aucun nouvel envoi automatique n’a été effectué.';
  } catch (e) {
    if (ownEpoch !== epoch) return;
    request.status = e.name === 'AbortError' ? 'INTERRUPTED' : 'ERROR';
    answer.error = e.name === 'AbortError' ? 'Réception arrêtée. Cela ne garantit pas l’annulation des actions déjà engagées.' : e.message;
    if (e.status === 401) $('#notice').textContent = 'Votre accès a été refusé. Reconnectez-vous avant de poursuivre.';
  } finally {
    if (ownEpoch === epoch) {
      active = null; save(); $('#send').disabled = !identity; if (current === request) drawDetail(); drawHistory();
    }
  }
}
function expert() {
  if (!current?.conversationId || active) return;
  try {
    sessionStorage.setItem('kex.agent.user.handoff', JSON.stringify({ identity, requestId: current.id, conversationId: current.conversationId, title: current.title, turns: current.turns }));
    location.href = '/#/chat?userConversation=' + encodeURIComponent(current.conversationId);
  } catch { $('#notice').textContent = 'Le stockage de cet onglet est indisponible. Le transfert vers la console experte n’a pas été effectué.'; }
}
$('#starters').replaceChildren(...STARTERS.slice(0, 2).map(card)); $('#guided').replaceChildren(...STARTERS.map(card));
$('#account').addEventListener('click', () => { $('#connection-error').textContent = ''; $('#access-key').value = ''; $('#connection').showModal(); });
$('#close-connection').addEventListener('click', () => $('#connection').close());
$('#disconnect').addEventListener('click', () => { credentials.set(''); clearIdentity(); skills(); $('#connection').close(); });
$('#connection-form').addEventListener('submit', async e => {
  e.preventDefault(); $('#connect').disabled = true; credentials.set($('#access-key').value.trim()); $('#access-key').value = '';
  try { if (await authenticate()) $('#connection').close(); } finally { $('#connect').disabled = false; }
});
$('#close-action').addEventListener('click', () => $('#action-dialog').close());
$('#action-form').addEventListener('submit', async e => {
  e.preventDefault(); if (!selected) return;
  const action = selected; const ownEpoch = epoch; $('#prepare').disabled = true;
  try {
    let procedure = action.prompt;
    if (action.id) {
      if (!identity) throw new Error('Connectez-vous pour utiliser cette compétence.');
      const rows = await api('/api/agent/skills/available');
      if (ownEpoch !== epoch) return;
      const fresh = rows.find(r => skillSignature(r) === skillSignature(action));
      if (!fresh) throw new Error('Cette compétence a changé ou n’est plus disponible. Actualisez le catalogue.');
      procedure = `Utilise la procédure approuvée « ${fresh.title} » comme guide. Vérifie ses préconditions et signale toute limite. Les règles d’autorisation restent applicables.\n\n${fresh.markdown}\n\nConditions déclarées à vérifier : ${(fresh.verification?.preconditions || []).join(' ; ') || 'Non renseignées'}\nVérifications attendues : ${(fresh.verification?.checks || []).join(' ; ') || 'Non renseignées'}`;
    }
    const parameterText = fields.map((f, i) => `${f.path.join(' / ')} : ${$('#skill-parameter-' + i).value.trim()}`).join('\n');
    $('#prompt').value = `${procedure}\n\nÉlément concerné : ${$('#action-subject').value.trim()}\nContexte : ${$('#action-period').value.trim() || 'À préciser si nécessaire'}\nParamètres renseignés :\n${parameterText || 'Aucun paramètre spécifique renseigné'}\n\nPrésente le résultat en français accessible, avec les sources disponibles. N’affirme pas de réussite sans vérification.`;
    preparedSkill = action.id ? action : null;
    $('#selected-action').textContent = `Demande préparée : ${action.title}. Vous pouvez la modifier avant de la lancer.`; $('#selected-action').hidden = false;
    $('#action-dialog').close(); location.hash = '#/new'; route(); $('#prompt').focus();
  } catch (err) { $('#action-error').textContent = err.message; }
  finally { $('#prepare').disabled = false; }
});
$('#request-form').addEventListener('submit', async e => {
  e.preventDefault();
  const text = $('#prompt').value.trim(); const ownEpoch = epoch;
  if (!text || preparing || active || !identity) return;
  preparing = true; $('#send').disabled = true;
  try {
    if (preparedSkill) {
      const rows = await api('/api/agent/skills/available');
      if (ownEpoch !== epoch) return;
      if (!rows.some(r => skillSignature(r) === skillSignature(preparedSkill))) {
        throw new Error('La compétence préparée n’est plus disponible ou a changé. Actualisez les compétences avant de lancer la demande.');
      }
    }
    $('#selected-action').hidden = true;
    await send(text);
  } catch (err) { if (ownEpoch === epoch) $('#notice').textContent = err.message; }
  finally { preparing = false; $('#send').disabled = !!active || !identity; }
});
$('#followup-form').addEventListener('submit', e => { e.preventDefault(); const text = $('#followup').value.trim(); if (text && current && !active) { $('#followup').value = ''; send(text, current); } });
$('#stop').addEventListener('click', () => active?.controller.abort());
$('#open-expert').addEventListener('click', expert);
$('#refresh-skills').addEventListener('click', skills);
addEventListener('hashchange', route);
addEventListener('pagehide', () => { if (active) { active.request.status = 'INTERRUPTED'; save(); active.controller.abort(); } });
route(); authenticate();
