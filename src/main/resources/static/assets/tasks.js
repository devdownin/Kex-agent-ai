// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

import { $, api, busy, confirmAction, el, onCredentialChange, report, stamp, viewName } from './core.js';

const BASE = '/api/agent/tasks';
const LABELS = { DRAFT: 'Plan à examiner', APPROVED: 'Plan approuvé', RUNNING: 'En cours',
  COMPLETED: 'Plan terminé — objectif non vérifié', VERIFIED: 'Critère final confirmé',
  FAILED: 'Critère non satisfait', PAUSED: 'En pause — reprise possible',
  NEEDS_RECONCILIATION: 'Résultat incertain — réconciliation requise', CANCELLED: 'Annulée',
  PENDING: 'À exécuter', UNKNOWN: 'Effet incertain' };
let selected = null;
let generation = 0;
let timer = null;
let inFlight = false;

function clear() {
  generation += 1; selected = null;
  clearTimeout(timer); timer = null;
  $('#task-list').replaceChildren(); $('#task-detail').replaceChildren();
}
onCredentialChange(clear);
window.addEventListener('hashchange', () => { clearTimeout(timer); });
document.addEventListener('visibilitychange', () => {
  clearTimeout(timer);
  if (!document.hidden && viewName() === 'tasks') view();
});

export async function view() {
  if (inFlight) return;
  inFlight = true;
  const current = generation;
  try {
    const tasks = await api(BASE);
    if (current !== generation) return;
    $('#task-note').textContent = tasks.length ? 'Sélectionnez une tâche pour examiner son plan et ses preuves.'
      : 'Aucune tâche. Préparez un plan ou décrivez votre objectif.';
    $('#task-plan-form').hidden = false;
    $('#task-list').replaceChildren(...tasks.map((task) => {
      const button = el('button', 'ghost', `${task.plan.objective} · ${LABELS[task.status] || task.status}`);
      button.type = 'button'; button.dataset.taskId = task.id;
      button.addEventListener('click', () => { selected = task.id; render(task); });
      return button;
    }));
    const active = tasks.find((task) => task.id === selected);
    if (active) render(active);
    if (tasks.some((task) => task.status === 'RUNNING') && !document.hidden && viewName() === 'tasks') {
      clearTimeout(timer); timer = setTimeout(view, 3000);
    }
  } catch (error) {
    if (current !== generation) return;
    $('#task-note').textContent = error.status === 404
      ? 'Les tâches planifiées sont désactivées sur cette installation. Un administrateur peut activer ce parcours.'
      : error.message;
    $('#task-plan-form').hidden = true;
  } finally { inFlight = false; }
}

function render(task) {
  const host = $('#task-detail');
  const title = el('h2', null, task.plan.objective);
  const state = el('p', 'hint', `${LABELS[task.status] || task.status} · ${stamp(task.updatedAt)}`);
  const note = el('p', null, task.detail || '');
  const preconditions = el('ul');
  task.plan.preconditions.forEach((value) => preconditions.append(el('li', null, value)));
  const steps = el('ol');
  task.plan.steps.forEach((step, index) => {
    const result = task.results[index];
    const item = el('li');
    item.append(el('h3', null, `${step.description} · ${LABELS[result.status] || result.status}`));
    item.append(el('p', 'hint', `Liaison : ${step.binding} · Dépendances : ${step.dependsOn.join(', ') || 'aucune'}`));
    const args = el('details'); args.append(el('summary', null, 'Paramètres et critère proposés'),
      el('pre', 'dump', JSON.stringify({ arguments: step.arguments, expectation: step.expectation }, null, 2)));
    item.append(args, el('p', 'hint', result.detail || ''));
    if (result.output) {
      const proof = el('details'); proof.append(el('summary', null, `Preuve · ${stamp(result.observedAt)}`),
        el('pre', 'dump', result.output), el('p', 'hint', `Empreinte : ${result.evidenceHash || 'absente'}`));
      item.append(proof);
    }
    steps.append(item);
  });
  const actions = el('div', 'panel-actions');
  if (task.status === 'DRAFT') actions.append(action('Approuver ce plan', async () => {
    const accepted = await confirmAction({ title: 'Approuver le plan exact', accept: 'Approuver le plan',
      lines: [['Objectif', task.plan.objective], ['Étapes', String(task.plan.steps.length)],
        ['Préconditions à examiner', task.plan.preconditions.join(' · ') || 'Aucune déclarée'],
        ['Conséquence', 'Autoriser les étapes et paramètres affichés. Les actions sensibles exigent ADMIN. Aucun appel ne part avant le lancement.']] });
    if (accepted) await mutate(task, 'approve');
  }));
  const leaseExpired = !task.leaseUntil || Date.parse(task.leaseUntil) <= Date.now();
  if (leaseExpired && (['APPROVED', 'PAUSED', 'NEEDS_RECONCILIATION'].includes(task.status) || task.status === 'RUNNING')) actions.append(action(
    task.status === 'NEEDS_RECONCILIATION' ? 'Réconcilier et reprendre' : 'Lancer ou reprendre', async () => {
      const accepted = await confirmAction({ title: 'Lancer le plan approuvé', accept: 'Lancer cette tâche',
        lines: [['Objectif', task.plan.objective], ['Conséquence', 'Les lectures reprennent. Une action incertaine est vérifiée sans être rejouée.']] });
      if (accepted) await mutate(task, 'run');
    }));
  if (['DRAFT', 'APPROVED', 'RUNNING', 'PAUSED'].includes(task.status)) actions.append(action('Interrompre', async () => {
    const accepted = await confirmAction({ title: 'Interrompre cette tâche', accept: 'Interrompre',
      lines: [['Objectif', task.plan.objective], ['Conséquence', 'Les étapes suivantes sont arrêtées. Une action déjà partie peut encore produire un effet.']] });
    if (accepted) await mutate(task, 'cancel');
  }));
  if (['FAILED', 'PAUSED', 'COMPLETED', 'VERIFIED', 'CANCELLED'].includes(task.status)) actions.append(action('Replanifier', async () => {
    const objective = $('#task-objective').value.trim() || task.plan.objective;
    const current = generation;
    const next = await api(`${BASE}/plan`, { method: 'POST', body: { objective, previousTaskId: task.id } });
    if (current !== generation) return;
    selected = next.id; render(next); await view();
  }));
  host.replaceChildren(title, state, note, el('h3', null, 'Préconditions à examiner'), preconditions, steps, actions);
}
function action(label, run) {
  const button = el('button', 'ghost', label); button.type = 'button';
  button.addEventListener('click', () => busy(button, run)); return button;
}
async function mutate(task, verb) {
  const current = generation;
  const next = await api(`${BASE}/${encodeURIComponent(task.id)}/${verb}`, { method: 'POST' });
  if (current !== generation) return;
  render(next); await view();
}
export function wire() {
  $('#refresh-tasks').addEventListener('click', view);
  $('#task-plan-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const button = $('#task-plan-submit');
    busy(button, async () => {
      const current = generation;
      $('#task-note').textContent = 'Préparation du plan…';
      try {
        const task = await api(`${BASE}/plan`, { method: 'POST', body: { objective: $('#task-objective').value.trim() } });
        if (current !== generation) return;
        selected = task.id; render(task); await view();
      } catch (error) { if (current === generation) report(error); }
    });
  });
}
