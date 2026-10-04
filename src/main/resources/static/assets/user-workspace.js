// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { $, api, credentials, el, failure, headers } from './core.js';
import { userNotifications } from './user-notifications.js';
import { userApprovals } from './user-approvals.js';
import { readAttachments, contextText, skillCategory, summary } from './user-context.js';
import { events } from './user-stream.js';
import { activity, parameterFields, parseResponse, responsePrompt, skillSignature } from './user-experience.js';

const STARTERS = [
  { title: 'Vérifier un processus', description: 'Comprendre son état et repérer les retards.', prompt: 'Vérifie le bon fonctionnement de ce processus. Présente les faits observés, les limites et les points à examiner.' },
  { title: 'Retrouver une commande', description: 'Comprendre où en est son traitement.', prompt: 'Recherche le parcours de cet élément. Indique son dernier point de traitement connu sans inventer les étapes manquantes.' },
  { title: 'Comprendre une anomalie', description: 'Examiner les faits et les pistes de résolution.', prompt: 'Analyse cette anomalie. Distingue les faits, les hypothèses et les vérifications nécessaires avant toute action.' },
  { title: 'Préparer un bilan', description: 'Résumer les incidents et les actions à suivre.', prompt: 'Prépare un bilan clair de la situation. Indique les incidents observés, les sources datées et les actions restant à suivre.' },
];
const LABELS = { RUNNING: 'En cours', NEEDS_INPUT: 'Votre réponse est nécessaire', COMPLETE: 'Réponse reçue', PARTIAL: 'Résultat partiel', ERROR: 'Traitement interrompu', INTERRUPTED: 'Réception interrompue' };
let identity = null; let roleScope = '[]';
let history = [];
let current = null;
let active = null;
let selected = null;
let fields = [];
let preparedSkill = null;
let preparing = false;
let preparedDraft = null;
let favorites = [];
let attachments = []; let contextVersion = 0; let contextLoading = false;
let historyTimer = null;
let availableSkills = []; let serverHistory = false; let historyVersion = 0; let attentionTasks = [];
const examinedKey = () => `kex.agent.user.examined.${encodeURIComponent(identity)}`;
let examined = [];
function contextValue() { return { process: $('#context-process').value.trim(), period: $('#context-period').value.trim(), environment: $('#context-environment').value.trim(), files: structuredClone(attachments) }; }
function drawContext() {
  const context = contextValue(); const host = $('#context-preview'); host.replaceChildren();
  context.files.forEach((file, i) => { const item = el('details'); item.append(el('summary', null, file.name), el('pre', null, file.text), button('Retirer ce fichier', () => { attachments.splice(i, 1); drawContext(); })); host.append(item); });
  $('#context-summary').textContent = [context.process && `Processus : ${context.process}`, context.period && `Période : ${context.period}`, context.environment && `Environnement : ${context.environment}`, context.files.length && `${context.files.length} fichier(s) joint(s)`].filter(Boolean).join(' · ');
}
function restoreContext(context) { for (const name of ['process', 'period', 'environment']) $('#context-' + name).value = context?.[name] || ''; attachments = structuredClone(context?.files || []); $('#context-files').value = ''; drawContext(); }
function drawSkillCatalogue() {
  const term = $('#skill-search').value.trim().toLowerCase(); const category = $('#skill-category').value;
  const rows = availableSkills.filter(s => (!term || `${s.title} ${s.description || ''} ${s.markdown || ''}`.toLowerCase().includes(term)) && (category === 'all' || skillCategory(s) === category));
  const host = $('#skills'); host.replaceChildren(...rows.map(card));
  if (!rows.length) host.append(el('p', 'muted', availableSkills.length ? 'Aucune compétence ne correspond à cette recherche.' : 'Aucune compétence approuvée disponible. Vous pouvez utiliser les demandes guidées ou écrire votre demande.'));
}
async function refreshHistory() {
  if (!identity || active) return;
  clearTimeout(historyTimer);
  const own = epoch; const version = ++historyVersion;
  try {
    const rows = await api('/api/agent/workspace/requests');
    if (own !== epoch || version !== historyVersion) return;
    serverHistory = true;
    const local = history.filter(r => !r.serverId);
    const saved = new Map(history.filter(r => r.serverId).map(r => [r.serverId, r]));
    history = [...rows.map(r => ({ ...saved.get(r.id), ...r, id: saved.get(r.id)?.id || r.id, serverId: r.id, summaryOnly: true })), ...local].slice(0, 100);
    $('#history-note').textContent = 'Demandes serveur privées à votre compte et votre espace, disponibles sur vos autres appareils. Les anciennes demandes locales restent propres à cet onglet.';
    if (current) { current = history.find(r => r.id === current.id) || null; if (current) drawDetail(); }
    save(); drawHistory(); drawAttention();
    if (history.some(r => r.status === 'RUNNING')) historyTimer = setTimeout(refreshHistory, 5000);
  } catch (error) {
    if (own !== epoch || version !== historyVersion) return;
    if (error.status === 404 && !serverHistory) $('#history-note').textContent = 'Historique local de cet onglet : la reprise serveur n’est pas disponible sur cette version du serveur.';
    else $('#notice').textContent = 'Historique serveur indisponible. Les demandes affichées peuvent être anciennes ; actualisez avant de poursuivre.';
  }
}
function drawAttention() {
  const host = $('#attention-list'); host.replaceChildren(); let count = 0;
  function item(title, detail, target) { count++; const card = el('article', 'panel'); const link = el('a', null, title); link.href = target; card.append(link, el('p', null, detail)); host.append(card); }
  history.forEach(r => {
    if (r.status === 'NEEDS_INPUT') item('Précision nécessaire', r.title, '#/request/' + r.id);
    else if (['COMPLETE', 'PARTIAL', 'ERROR', 'INTERRUPTED'].includes(r.status) && !examined.includes(`${r.id}:${r.updatedAt}`)) item('Résultat à examiner', r.title, '#/request/' + r.id);
  });
  attentionTasks.forEach(t => { if (t.status === 'DRAFT') item('Plan à approuver', t.plan.objective, '#/approvals'); else if (['PAUSED', 'NEEDS_RECONCILIATION', 'FAILED'].includes(t.status)) item('Plan à examiner', t.plan.objective, '#/approvals'); });
  $('#attention-count').textContent = count ? `(${count})` : '';
  if (!count) host.append(el('p', 'muted', identity ? 'Aucune intervention attendue dans les éléments disponibles.' : 'Connectez-vous pour consulter les interventions attendues.'));
}
const notifications = userNotifications();
const approvals = userApprovals({ onTasks: tasks => { attentionTasks = tasks; notifications.tasks(tasks); drawAttention(); if (current) drawDetail(); }, onPlan(requestId, taskId) {
  const request = history.find(r => r.id === requestId);
  if (request) { request.taskId = taskId; save();
    if (request.serverId) { const own = epoch; api(`/api/agent/workspace/requests/${encodeURIComponent(request.serverId)}/plan`, { method: 'POST', body: { taskId } }).catch(() => { if (own === epoch) $('#notice').textContent = 'Le plan est créé, mais son lien avec cette demande n’a pas pu être enregistré. Retrouvez-le dans Plans à valider.'; }); } if (current === request) drawDetail(); }
} });
let epoch = 0;
let skillEpoch = 0;
const storageKey = () => `kex.agent.user.requests.${encodeURIComponent(identity)}`;
const favoriteKey = () => `kex.agent.user.favorites.${encodeURIComponent(identity)}`;
function saveFavorites() {
  try { localStorage.setItem(favoriteKey(), JSON.stringify(favorites)); }
  catch { $('#notice').textContent = 'Les favoris restent en mémoire : leur enregistrement sur ce navigateur a échoué.'; }
  drawFavorites();
}
function loadFavorites() {
  try { const rows = JSON.parse(localStorage.getItem(favoriteKey()) || '[]'); favorites = Array.isArray(rows) ? rows.filter(f => f && typeof f.key === 'string' && typeof f.title === 'string' && ['action', 'request'].includes(f.type)).slice(0, 20) : []; }
  catch { favorites = []; }
}
function toggleFavorite(favorite) {
  if (!identity) { $('#notice').textContent = 'Connectez-vous pour conserver vos favoris.'; return; }
  const index = favorites.findIndex(f => f.key === favorite.key);
  if (index >= 0) favorites.splice(index, 1);
  else { favorites.unshift(favorite); favorites = favorites.slice(0, 20); }
  saveFavorites(); if (current) drawDetail();
}
function drawFavorites() {
  const host = $('#favorites'); host.replaceChildren();
  favorites.forEach(f => {
    const item = el('article', 'favorite-card');
    item.append(el('strong', null, f.title), button('Préparer', () => prepareFavorite(f)), button('Retirer des favoris', () => toggleFavorite(f)));
    host.append(item);
  });
  if (!favorites.length) host.append(el('p', 'muted', identity ? 'Ajoutez une action ou une demande à vos favoris pour la retrouver ici.' : 'Connectez-vous pour retrouver vos favoris.'));
}
async function prepareFavorite(f) {
  if (f.type === 'request') return reprepare(f.request);
  if (f.skillId) {
    const own = epoch;
    try { const rows = await api('/api/agent/skills/available'); if (own !== epoch) return;
      const action = rows.find(r => r.id === f.skillId);
      if (!action) throw new Error('Cette compétence n’est plus disponible. Actualisez le catalogue.');
      openAction(action);
    } catch (error) { if (own === epoch) $('#notice').textContent = error.message; }
  } else { const action = STARTERS.find(s => s.title === f.title); if (action) openAction(action); }
}
async function reprepare(request) {
  if (!request || !identity || active) return;
  const own = epoch; $('#notice').textContent = '';
  try {
    restoreContext(request.context);
    if (request.draft?.skillId) {
      const rows = await api('/api/agent/skills/available'); if (own !== epoch) return;
      const skill = rows.find(s => s.id === request.draft.skillId);
      if (!skill || skillSignature(skill) !== request.draft.signature) throw new Error('La compétence de cette demande a changé ou n’est plus disponible. Choisissez-la dans le catalogue pour examiner sa nouvelle version.');
      if (!request.draft.edited) { openAction(skill, request.draft); return; }
      preparedSkill = skill;
    }
    const action = STARTERS.find(a => a.title === request.draft?.actionTitle);
    if (action && !request.draft.edited) { openAction(action, request.draft); return; }
    const message = request.turns?.find(t => t.role === 'user')?.text;
    if (typeof message !== 'string') throw new Error('La demande d’origine n’est pas disponible.');
    preparedDraft = request.draft ? structuredClone(request.draft) : null; if (!request.draft?.skillId) preparedSkill = null; $('#prompt').value = message;
    $('#selected-action').textContent = 'Nouvelle demande préparée. Vérifiez les paramètres et la période avant de la lancer.'; $('#selected-action').hidden = false;
    location.hash = '#/new'; route(); $('#prompt').focus();
  } catch (error) { if (own === epoch) $('#notice').textContent = error.message; }
}
function save() {
  if (!identity) return;
  try { sessionStorage.setItem(storageKey(), JSON.stringify(history.slice(0, 100))); sessionStorage.setItem(storageKey() + '.roles', roleScope); }
  catch { $('#notice').textContent = 'L’historique reste en mémoire : le stockage de cet onglet est indisponible.'; }
}
function load() {
  try {
    const recordedRoles = sessionStorage.getItem(storageKey() + '.roles');
    if (recordedRoles && recordedRoles !== roleScope) { history = []; return; }
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
  const page = request ? 'detail' : ['new', 'actions', 'requests', 'approvals', 'notifications', 'attention'].includes(name) ? name : 'new';
  ['new', 'actions', 'requests', 'approvals', 'notifications', 'attention', 'detail'].forEach(id => { $('#' + id).hidden = id !== page; });
  document.querySelectorAll('[data-page]').forEach(a => {
    if (a.dataset.page === (page === 'detail' ? 'requests' : page)) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
  current = request;
  if (request) {
    drawDetail();
    if (request.serverId && request.summaryOnly && !request.loadingDetail) {
      const own = epoch; request.loadingDetail = true; drawDetail();
      api(`/api/agent/workspace/requests/${encodeURIComponent(request.serverId)}`).then(row => {
        if (own !== epoch) return;
        const localId = request.id; Object.assign(request, row, { id: localId, summaryOnly: false });
        save(); if (current === request) { drawDetail(); approvals.request(request); }
      }).catch(() => { if (own === epoch) $('#notice').textContent = 'Le détail complet n’a pas pu être chargé. Actualisez avant de poursuivre.'; }).finally(() => { request.loadingDetail = false; if (own === epoch && current === request) drawDetail(); });
    }
  }
  approvals.request(request);
  if (page === 'approvals') approvals.refresh();
  if (page === 'actions') drawFavorites();
  if (page === 'requests') drawHistory();
  if (page === 'attention') drawAttention();
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
  $('#favorite-request').textContent = favorites.some(f => f.key === 'request:' + current.id) ? 'Retirer cette demande des favoris' : 'Ajouter cette demande aux favoris';
  $('#favorite-request').disabled = !!active || !identity;
  $('#reprepare-request').disabled = !!active || !identity;
  $('#prepare-plan').disabled = !!active || !!current.taskId;
  $('#run-status').textContent = LABELS[current.status] || 'État non disponible';
  const running = active?.request === current;
  $('#stop').hidden = !running;
  $('#followup-send').disabled = !!active || current.status === 'RUNNING' || !!current.loadingDetail || !identity || !current.conversationId;
  $('#followup-form').hidden = !current.conversationId;
  $('#open-expert').disabled = !current.conversationId || !!active;
  const steps = [current.conversationId ? 'Demande reçue par Kex' : 'Envoi de la demande'];
  current.tools.slice(-8).forEach(t => steps.push(activity(t)));
  if (current.status === 'RUNNING') steps.push(current.answerStarted ? 'Rédaction de la réponse en cours' : 'Traitement en cours, en attente des premiers résultats');
  else if (current.status === 'NEEDS_INPUT') steps.push('Choisissez une réponse ou écrivez votre précision, puis envoyez-la.');
  else if (current.status === 'COMPLETE') steps.push('Réponse reçue. La réussite de l’objectif reste à vérifier.');
  else steps.push('Réception incomplète. Vérifiez ce qui a été accompli avant de poursuivre.');
  $('#progress').replaceChildren(...steps.map(s => el('li', null, s)));
  const recap = $('#completion-summary'); recap.replaceChildren(); const bilan = summary(current, attentionTasks.find(t => t.id === current.taskId)); recap.hidden = !bilan;
  if (bilan) {
    recap.append(el('h2', null, bilan.title), el('p', null, bilan.evidence), el('p', null, bilan.limits));
    const answer = current.turns.filter(t => t.role === 'agent').at(-1); const parsed = answer?.completed ? parseResponse(answer.text) : null;
    if (parsed?.kind === 'result') recap.append(el('h3', null, 'Ce qui a été réalisé selon la réponse'), el('p', null, parsed.observations), el('h3', null, 'Limites signalées'), el('p', null, parsed.uncertainties), el('h3', null, 'Prochaine action proposée'), el('p', null, parsed.nextAction));
    recap.append(button('Marquer ce résultat comme examiné', () => { examined.push(`${current.id}:${current.updatedAt}`); examined = examined.slice(-200); try { localStorage.setItem(examinedKey(), JSON.stringify(examined)); } catch { $('#notice').textContent = 'La marque de lecture reste en mémoire.'; } drawAttention(); }));
  }
  $('#saved-context').hidden = !current.context; $('#saved-context-content').replaceChildren();
  if (current.context) $('#saved-context-content').append(el('pre', null, contextText(current.context)));
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
  const b = button('', () => openAction(action), 'action-card'); b.append(el('strong', null, action.title), el('span', null, action.description || 'Utiliser cette procédure approuvée avec votre contexte.')); const item = el('article', 'action-item');
  if (action.id) { const checks = action.verification?.checks || []; b.append(el('small', 'muted', `À préparer : contexte, cible et paramètres de la procédure. Exemple de résultat attendu : ${checks[0] || 'réponse documentée, critères à vérifier'}`)); }
  item.append(b, button('Ajouter aux favoris', () => toggleFavorite({ key: action.id ? 'skill:' + action.id : 'starter:' + action.title, type: 'action', title: action.title, skillId: action.id || null }))); return item;
}
function openAction(action, draft = null) {
  selected = action;
  $('#action-title').textContent = action.title;
  $('#action-description').textContent = action.description || 'Précisez votre contexte. Kex vérifiera les préconditions avant de poursuivre.';
  $('#action-subject').value = draft?.subject || ''; $('#action-period').value = draft?.period || ''; $('#action-error').textContent = '';
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
  fields.forEach((field, i) => { const value = draft?.parameters?.find(p => JSON.stringify(p.path) === JSON.stringify(field.path))?.value; if (typeof value === 'string') $('#skill-parameter-' + i).value = value; });
  if (draft) $('#action-error').textContent = 'Valeurs précédentes préremplies. Vérifiez la cible, la période et les paramètres avant de préparer la nouvelle demande.';
  $('#action-dialog').showModal();
}
async function skills() {
  const version = ++skillEpoch; const ownEpoch = epoch;
  availableSkills = [];
  const host = $('#skills'); host.replaceChildren(el('p', 'muted', identity ? 'Chargement des compétences…' : 'Connectez-vous pour consulter les compétences de votre équipe.'));
  if (!identity) return;
  try {
    const rows = await api('/api/agent/skills/available');
    if (version !== skillEpoch || ownEpoch !== epoch) return;
    availableSkills = rows; drawSkillCatalogue();
  } catch (e) {
    if (version !== skillEpoch || ownEpoch !== epoch) return;
    host.replaceChildren(el('p', 'muted', e.status === 404 ? 'Les compétences ne sont pas activées sur ce serveur. Les demandes guidées restent disponibles.' : 'Les compétences ne sont pas disponibles. Réessayez ou contactez votre administrateur.'));
  }
}
function clearIdentity() {
  // Invalide toutes les réponses tardives avant de changer l'espace affiché.
  clearTimeout(historyTimer); epoch++; skillEpoch++; historyVersion++; contextVersion++; contextLoading = false; serverHistory = false; attentionTasks = []; examined = []; availableSkills = []; restoreContext(null); $('#context-error').textContent = ''; $('#skill-search').value = ''; $('#skill-category').value = 'all'; drawAttention(); active?.controller.abort(); active = null;
  identity = null; history = []; current = null; selected = null; preparedSkill = null; preparedDraft = null; favorites = []; $('#process-options').replaceChildren(); notifications.reset(); approvals.reset(); drawFavorites(); drawAttention(); $('#completion-summary').replaceChildren(); $('#saved-context-content').replaceChildren(); $('#saved-context').hidden = true;
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
    identity = JSON.stringify([me.tenant, me.name]); roleScope = JSON.stringify((me.roles || []).slice().sort()); load(); loadFavorites(); notifications.reset(identity + '|' + roleScope, me.roles || []); approvals.reset(me.roles || []);
    try { examined = JSON.parse(localStorage.getItem(examinedKey()) || '[]'); if (!Array.isArray(examined)) examined = []; } catch { examined = []; }
    await refreshHistory(); if (ownEpoch !== epoch) return false; drawFavorites();
    $('#prompt').value = draft;
    $('#identity').textContent = `Connecté : ${me.name}`; $('#account').textContent = 'Mon accès';
    $('#send').disabled = false; $('#notice').textContent = ''; route(); skills();
    if ((me.roles || []).some(r => ['OPERATOR', 'ADMIN'].includes(r))) api('/api/agent/supervision/processes').then(rows => { if (ownEpoch === epoch) { const options = rows.slice(0, 100).map(p => { const option = el('option'); option.value = p.name || p.id; return option; }); $('#process-options').replaceChildren(...options); } }).catch(() => {});
    return true;
  } catch (e) {
    if (ownEpoch === epoch) $('#connection-error').textContent = e.status === 401 ? 'Ce code d’accès n’est pas reconnu.' : 'Connexion impossible. Vérifiez votre accès ou contactez votre administrateur.';
    return false;
  }
}
async function send(message, request = null) {
  if (active || !identity || contextLoading) return;
  if (message.length + contextText(request?.context || contextValue()).length > 30000) throw new Error('Réduisez la demande ou les pièces jointes : 30000 caractères maximum avec le contexte.');
  if (!request) {
    request = { id: crypto.randomUUID(), title: message.slice(0, 100), turns: [], tools: [], conversationId: null, context: contextValue(), draft: preparedDraft ? structuredClone(preparedDraft) : null };
    history.unshift(request); history = history.slice(0, 20);
  }
  const ownEpoch = epoch; const controller = new AbortController();
  active = { request, controller };
  request.turns = request.turns.slice(-38); request.turns.push({ role: 'user', text: message });
  const answer = { role: 'agent', text: '', sources: [] }; request.turns.push(answer);
  request.status = 'RUNNING'; request.answerStarted = false; request.updatedAt = new Date().toISOString();
  save(); current = request; location.hash = '#/request/' + request.id; route(); $('#send').disabled = true;
  let complete = false; let canonical = null;
  const firstCall = request.tools.length;
  try {
    const durable = !!request.serverId || (serverHistory && !request.conversationId);
    const response = await fetch(durable ? '/api/agent/workspace/requests/stream' : '/api/agent/chat/stream', { method: 'POST', headers: headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }), body: JSON.stringify(durable ? { id: request.serverId || null, message, context: request.context } : { message: responsePrompt(message + contextText(request.context)), conversationId: request.conversationId }), signal: controller.signal });
    if (!response.ok) throw await failure(response);
    for await (const event of events(response)) {
      if (ownEpoch !== epoch) return;
      if (event.name === 'request') { const saved = JSON.parse(event.data); request.serverId = saved.id; }
      else if (event.name === 'snapshot') canonical = JSON.parse(event.data);
      else if (event.name === 'conversation') request.conversationId = event.data;
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
    if (complete && canonical) { request.updatedAt = canonical.updatedAt; request.revision = canonical.revision; }
    if (!complete && !answer.error) answer.error = 'La fin de la réponse n’a pas été confirmée. Aucun nouvel envoi automatique n’a été effectué.';
  } catch (e) {
    if (ownEpoch !== epoch) return;
    request.status = e.name === 'AbortError' ? 'INTERRUPTED' : 'ERROR';
    answer.error = e.name === 'AbortError' ? 'Réception arrêtée. Cela ne garantit pas l’annulation des actions déjà engagées.' : e.message;
    if (e.status === 401) $('#notice').textContent = 'Votre accès a été refusé. Reconnectez-vous avant de poursuivre.';
  } finally {
    if (ownEpoch === epoch) {
      notifications.request(request); active = null; save(); $('#send').disabled = !identity; if (current === request) { drawDetail(); approvals.request(request); } drawHistory(); drawAttention();
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
    preparedDraft = { actionTitle: action.title, skillId: action.id || null, signature: action.id ? skillSignature(action) : null, subject: $('#action-subject').value.trim(), period: $('#action-period').value.trim(), parameters: fields.map((f, i) => ({ path: f.path, value: $('#skill-parameter-' + i).value.trim() })) };
    $('#selected-action').textContent = `Demande préparée : ${action.title}. Vous pouvez la modifier avant de la lancer.`; $('#selected-action').hidden = false;
    $('#action-dialog').close(); location.hash = '#/new'; route(); $('#prompt').focus();
  } catch (err) { $('#action-error').textContent = err.message; }
  finally { $('#prepare').disabled = false; }
});
$('#request-form').addEventListener('submit', async e => {
  e.preventDefault();
  const text = $('#prompt').value.trim(); const ownEpoch = epoch;
  if (!text || preparing || active || !identity || contextLoading) return;
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
$('#followup-form').addEventListener('submit', async e => { e.preventDefault(); const text = $('#followup').value.trim(); if (text && current && !active) { try { await send(text, current); $('#followup').value = ''; } catch (error) { $('#notice').textContent = error.message; } } });
$('#favorite-request').addEventListener('click', () => { if (current && !active) toggleFavorite({ key: 'request:' + current.id, type: 'request', title: current.title, request: { draft: current.draft || null, turns: [current.turns.find(t => t.role === 'user')] } }); });
$('#reprepare-request').addEventListener('click', () => reprepare(current));
$('#prompt').addEventListener('input', () => { if (preparedDraft) preparedDraft.edited = true; });
$('#stop').addEventListener('click', () => active?.controller.abort());
$('#open-expert').addEventListener('click', expert);
$('#refresh-skills').addEventListener('click', skills);
$('#skill-search').addEventListener('input', drawSkillCatalogue); $('#skill-category').addEventListener('change', drawSkillCatalogue);
for (const name of ['process', 'period', 'environment']) $('#context-' + name).addEventListener('input', drawContext);
$('#context-files').addEventListener('change', async () => {
  const own = epoch; const version = ++contextVersion; contextLoading = true; $('#send').disabled = true; $('#context-error').textContent = '';
  try { const rows = await readAttachments([...$('#context-files').files]); if (own === epoch && version === contextVersion) { attachments = rows; drawContext(); } }
  catch (error) { if (own === epoch && version === contextVersion) { attachments = []; $('#context-files').value = ''; drawContext(); $('#context-error').textContent = error.message; } }
  finally { if (own === epoch && version === contextVersion) { contextLoading = false; $('#send').disabled = !!active || !identity; } }
});
$('#refresh-requests').addEventListener('click', async () => { await refreshHistory(); route(); });
$('#refresh-attention').addEventListener('click', async () => { await refreshHistory(); await approvals.refresh(); drawAttention(); });
addEventListener('hashchange', route);
addEventListener('pagehide', () => { clearTimeout(historyTimer); if (active) { active.request.status = 'INTERRUPTED'; save(); active.controller.abort(); } });
route(); authenticate();
