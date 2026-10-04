// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { $, api, el } from './core.js';

const BASE = '/api/agent/tasks';
const STATES = { DRAFT: 'Votre décision est attendue', APPROVED: 'Plan approuvé — à lancer', RUNNING: 'Traitement en cours', VERIFIED: 'Critère final confirmé', COMPLETED: 'Étapes terminées — objectif non vérifié', FAILED: 'Critère non satisfait', PAUSED: 'Traitement en pause', NEEDS_RECONCILIATION: 'Résultat incertain — examen nécessaire', CANCELLED: 'Plan refusé ou annulé' };

// Only server-owned plans enter this flow; model answers never supply approval identifiers.
export function userApprovals({ onPlan, onTasks = () => {} }) {
  let generation = 0; let roles = []; let tasks = []; let bindings = {}; let linked = null;
  let loading = false; let refreshQueued = false; let timer = null; let confirmation = null; let planning = null;
  const pending = new Set();
  const canOperate = () => roles.some(r => ['OPERATOR', 'ADMIN'].includes(r));
  const button = (label, run) => {
    const b = el('button', null, label); b.type = 'button'; b.addEventListener('click', run); return b;
  };
  function message(text) { $('#approval-note').textContent = text; $('#linked-plan-note').textContent = text; }
  function reset(nextRoles = []) {
    generation++; roles = nextRoles; tasks = []; bindings = {}; linked = null; loading = false; refreshQueued = false;
    clearTimeout(timer); pending.clear(); confirmation = null; planning = null;
    $('#approval-list').replaceChildren(); $('#linked-plan').replaceChildren(); $('#review-content').replaceChildren(); message('');
    $('#plan-objective').value = ''; $('#plan-error').textContent = '';
    for (const id of ['review-title', 'review-consequence', 'review-error']) $('#' + id).textContent = '';
    for (const id of ['plan-dialog', 'review-dialog']) if ($('#' + id).open) $('#' + id).close();
    $('#approvals-link').hidden = !canOperate(); $('#prepare-plan').hidden = !canOperate();
    $('#linked-plan-section').hidden = !canOperate(); $('#plan-submit').disabled = false;
  }
  function renderPlan(task, compact = false) {
    const card = el('article', 'panel approval-card'); card.dataset.taskId = task.id;
    card.append(el('h3', null, task.plan.objective), el('p', null, STATES[task.status] || 'État non disponible'));
    card.append(el('p', 'muted', task.detail || ''), el('p', 'muted', `Mise à jour : ${new Date(task.updatedAt).toLocaleString('fr-FR')}`));
    card.append(el('h4', null, 'Conditions à examiner'), el('p', null, task.plan.preconditions.join(' ; ') || 'Aucune condition déclarée'));
    const steps = el('ol');
    task.plan.steps.forEach((step, index) => {
      const binding = bindings[step.binding]; const item = el('li');
      item.append(el('strong', null, step.description), el('p', null, binding?.readOnly === true ? 'Consultation de données' : 'Action pouvant modifier le système — approbation ADMIN nécessaire'));
      const target = el('details'); target.open = !compact;
      target.append(el('summary', null, 'Cible et paramètres exacts'), el('pre', null, JSON.stringify(step.arguments, null, 2)));
      item.append(target);
      if (step.expectation) item.append(el('p', null, `Résultat à vérifier : ${step.expectation.pointer} = ${JSON.stringify(step.expectation.expected)}`));
      else item.append(el('p', 'muted', 'Aucun critère mesurable déclaré pour cette étape.'));
      const result = task.results[index];
      if (result) item.append(el('p', null, result.detail || (result.status === 'PENDING' ? 'À traiter' : result.status === 'VERIFIED' ? 'Critère confirmé' : result.status === 'UNKNOWN' ? 'Effet incertain' : result.status === 'COMPLETED' ? 'Appel terminé' : result.status === 'RUNNING' ? 'En cours' : 'Vérification non satisfaite')));
      if (result?.output) {
        const proof = el('details'); proof.append(el('summary', null, 'Consulter la preuve'), el('pre', null, result.output), el('p', 'muted', `Observation : ${result.observedAt || 'Date non fournie'}`)); item.append(proof);
      }
      const technical = el('details'); technical.append(el('summary', null, 'Détails techniques'), el('p', null, `${binding?.connection || 'Connexion non disponible'} · ${binding?.tool || step.binding}`)); item.append(technical);
      steps.append(item);
    });
    card.append(steps);
    const actions = el('div', 'row');
    if (task.status === 'DRAFT') {
      const approve = button('Approuver ce plan', () => review(task, 'approve'));
      approve.disabled = pending.has(task.id) || (!roles.includes('ADMIN') && task.plan.steps.some(s => bindings[s.binding]?.readOnly !== true));
      const reject = button('Refuser ce plan', () => review(task, 'cancel')); reject.disabled = pending.has(task.id);
      actions.append(approve, reject, el('p', 'muted', 'Approuver autorise ce plan exact. Le lancement reste une étape séparée.'));
    } else if (task.status === 'APPROVED') {
      const run = button('Lancer le plan approuvé', () => review(task, 'run')); run.disabled = pending.has(task.id); actions.append(run);
    } else if (['PAUSED', 'NEEDS_RECONCILIATION'].includes(task.status)) {
      actions.append(el('a', null, 'Examiner la reprise dans la console experte')); actions.lastChild.href = '/#/tasks';
    }
    card.append(actions); return card;
  }
  function draw() {
    $('#approval-list').replaceChildren(...tasks.map(t => renderPlan(t, true)));
    if (!tasks.length) $('#approval-list').append(el('p', 'muted', 'Aucun plan enregistré dans votre espace. Depuis une demande, préparez un plan à examiner.'));
    const task = tasks.find(t => t.id === linked);
    $('#linked-plan').replaceChildren(...(task ? [renderPlan(task)] : []));
    if (linked && !task) $('#linked-plan').append(el('p', 'muted', 'Ce plan n’est pas disponible. Actualisez son état.'));
  }
  async function refresh() {
    if (!canOperate()) return;
    if (loading) { refreshQueued = true; return; }
    const own = generation; loading = true; clearTimeout(timer); message('Actualisation des plans…');
    try {
      const [rows, configured] = await Promise.all([api(BASE), api(`${BASE}/bindings`)]);
      if (own !== generation) return;
      tasks = rows; bindings = configured; message(''); draw(); onTasks(tasks);
      timer = setTimeout(refresh, tasks.some(t => t.status === 'RUNNING') && !document.hidden ? 5000 : 30000);
    } catch (error) {
      if (own !== generation) return;
      // Stale approval controls must disappear when the server or authorization is unavailable.
      tasks = []; bindings = {}; $('#approval-list').replaceChildren(); $('#linked-plan').replaceChildren();
      message(error.status === 404 ? 'Les plans à approuver ne sont pas activés sur ce serveur.' : 'Les plans ne sont pas disponibles. Actualisez avant de décider. ' + error.message);
    } finally { if (own === generation) { loading = false; if (refreshQueued) { refreshQueued = false; refresh(); } } }
  }
  function show(id) { linked = id || null; if (canOperate()) { draw(); refresh(); } }
  function review(task, verb) {
    if (pending.has(task.id) || !canOperate()) return;
    confirmation = { task, verb, generation };
    $('#review-content').replaceChildren(renderPlan(task));
    $('#review-content').querySelector('.row')?.remove();
    $('#review-title').textContent = verb === 'approve' ? 'Autoriser ce plan exact ?' : verb === 'run' ? 'Lancer ce plan approuvé ?' : 'Refuser ce plan ?';
    $('#review-consequence').textContent = verb === 'approve' ? 'Vous autorisez les étapes, cibles et paramètres affichés. Aucun appel ne part avant le lancement.' : verb === 'run' ? 'Les étapes vont être exécutées. Des actions peuvent modifier le système. La réussite sera confirmée uniquement par les preuves disponibles.' : 'Ce brouillon sera annulé sans exécuter ses étapes.';
    $('#review-error').textContent = ''; $('#review-accept').disabled = false;
    $('#review-accept').textContent = verb === 'approve' ? 'Confirmer l’approbation' : verb === 'run' ? 'Confirmer le lancement' : 'Confirmer le refus';
    $('#review-dialog').showModal();
  }
  $('#review-close').addEventListener('click', () => { confirmation = null; $('#review-dialog').close(); });
  $('#review-form').addEventListener('submit', async event => {
    event.preventDefault(); const choice = confirmation;
    if (!choice || choice.generation !== generation || pending.has(choice.task.id)) return;
    pending.add(choice.task.id); $('#review-accept').disabled = true;
    try {
      const fresh = await api(`${BASE}/${encodeURIComponent(choice.task.id)}`);
      if (choice.generation !== generation) return;
      if (fresh.revision !== choice.task.revision || fresh.bindingFingerprint !== choice.task.bindingFingerprint || JSON.stringify(fresh.plan) !== JSON.stringify(choice.task.plan)) throw new Error('Le plan ou son état a changé. Fermez cette fenêtre et actualisez avant de décider.');
      await api(`${BASE}/${encodeURIComponent(fresh.id)}/${choice.verb}`, { method: 'POST' });
      if (choice.generation !== generation) return;
      confirmation = null; $('#review-dialog').close(); await refresh();
    } catch (error) {
      if (choice.generation === generation) $('#review-error').textContent = `Décision non confirmée. Aucun nouvel envoi automatique. ${error.message}`;
    } finally { if (choice.generation === generation) { pending.delete(choice.task.id); $('#review-accept').disabled = false; draw(); } }
  });
  $('#refresh-approvals').addEventListener('click', refresh);
  $('#refresh-linked-plan').addEventListener('click', refresh);
  $('#prepare-plan').addEventListener('click', () => {
    const request = planning; if (!request || !canOperate()) return;
    $('#plan-objective').value = request.turns.find(t => t.role === 'user')?.text || '';
    $('#plan-error').textContent = $('#plan-objective').value.length > 2000 ? 'Résumez l’objectif en 2000 caractères maximum. La demande complète reste dans votre historique.' : '';
    $('#plan-dialog').showModal();
  });
  $('#plan-close').addEventListener('click', () => $('#plan-dialog').close());
  $('#plan-form').addEventListener('submit', async event => {
    event.preventDefault(); const request = planning; const own = generation;
    const objective = $('#plan-objective').value.trim(); if (!request || !canOperate() || !objective || objective.length > 2000 || $('#plan-submit').disabled) return;
    $('#plan-submit').disabled = true;
    try {
      const task = await api(`${BASE}/plan`, { method: 'POST', body: { objective } });
      if (own !== generation) return;
      onPlan(request.id, task.id); linked = task.id; $('#plan-dialog').close(); await refresh();
    } catch (error) { if (own === generation) $('#plan-error').textContent = 'Préparation non confirmée. Aucun nouvel envoi automatique. ' + error.message; }
    finally { if (own === generation) $('#plan-submit').disabled = false; }
  });
  document.addEventListener('visibilitychange', () => { clearTimeout(timer); if (canOperate()) refresh(); });
  addEventListener('pagehide', () => clearTimeout(timer));
  return { reset, refresh, show, request(request) { planning = request; $('#prepare-plan').disabled = !request || !!request.taskId || request.status === 'RUNNING'; show(request?.taskId); } };
}
