// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Automatisations planifiées : un prompt relancé seul à l'heure dite, sur un cron à six champs à
// seconde fixe (au plus une fois par minute) et un fuseau explicite. Existe seulement sous le
// profil shared-memory — même verrou d'exécution par lease que le reste de la supervision multi-
// instance, jamais un @Scheduled qui doublerait les tâches d'une réplique à l'autre.

import {
  $, ago, api, busy, confirmAction, el, empty, render, report, sortable, stamp, stateTag, toast,
} from './core.js';

const BASE = '/api/agent/automations';

let editingId = null;
let auditLoaded = false;

export async function panel() {
  await list();
}

/**
 * La route n'existe que sous le profil `shared-memory`, avec `kex.agent.automation.enabled` à
 * `true` : un 404 ici dit « éteinte », pas « cassée ». Même piège et même garde que memory.js.
 */
async function load() {
  try {
    return { rows: await api(BASE) };
  }
  catch (error) {
    if (error.status === 404) return { disabled: true };
    throw error;
  }
}

async function list() {
  await render($('#automations-list'), load, (data) => {
    if (data.disabled) {
      return empty('Automatisations désactivées.',
        'kex.agent.automation.enabled vaut false, ou le profil shared-memory n’est pas actif : '
        + 'aucune tâche ne s’exécute seule.');
    }
    if (!data.rows.length) {
      return empty('Aucune automatisation créée.',
        'Un prompt planifié se relance seul, sans qu’un opérateur n’ait à revenir le déclencher.');
    }

    const table = el('table', 'grid');
    const head = el('thead');
    const headRow = el('tr');
    ['Nom', 'Planification', 'Prochaine exécution', 'Dernier résultat', 'Activée', '']
      .forEach((label) => headRow.append(el('th', null, label)));
    head.append(headRow);
    table.append(head);

    const body = el('tbody');
    data.rows.forEach((row) => body.append(automationRow(row)));
    table.append(body);

    const scroll = el('div', 'scroll-x');
    scroll.append(sortable(table));
    return scroll;
  });
}

const RESULT_STATES = { IDLE: 'UNKNOWN', RUNNING: 'RUNNING', SUCCEEDED: 'OK', FAILED: 'ERROR' };
const RESULT_LABELS = { IDLE: 'Jamais exécutée', RUNNING: 'En cours', SUCCEEDED: 'Réussie', FAILED: 'Échouée' };

function automationRow(row) {
  const line = el('tr');
  line.append(el('td', 'strong', row.name));

  const schedule = el('td');
  schedule.append(el('span', 'mono', row.cron));
  schedule.append(el('span', 'muted', ` · ${row.zone}`));
  line.append(schedule);

  const next = el('td');
  if (row.enabled) next.append(ago(row.nextRun));
  else next.append(el('span', 'muted', 'Désactivée'));
  if (row.nextRun) next.dataset.sort = row.nextRun;
  line.append(next);

  const result = el('td');
  result.append(stateTag(RESULT_STATES[row.status] || 'UNKNOWN', RESULT_LABELS[row.status] || row.status));
  if (row.lastRun) result.append(el('span', 'muted', ` · ${ago(row.lastRun)}`));
  if (row.lastRun) result.dataset.sort = row.lastRun;
  line.append(result);

  line.append(el('td', null, row.enabled ? 'Oui' : 'Non'));

  const actions = el('td');
  const edit = el('button', 'ghost', 'Modifier');
  edit.type = 'button';
  edit.setAttribute('aria-label', `Modifier : ${row.name}`);
  edit.addEventListener('click', () => openForm(row));
  const remove = el('button', 'ghost danger', 'Supprimer');
  remove.type = 'button';
  remove.setAttribute('aria-label', `Supprimer : ${row.name}`);
  remove.addEventListener('click', () => busy(remove, () => deleteAutomation(row)));
  actions.append(edit, remove);
  line.append(actions);
  return line;
}

async function deleteAutomation(row) {
  const confirmed = await confirmAction({
    title: 'Confirmer la suppression de l’automatisation',
    accept: 'Supprimer',
    lines: [
      ['Automatisation', row.name],
      ['Planification', `${row.cron} · ${row.zone}`],
    ],
  });
  if (!confirmed) return;
  try {
    await api(`${BASE}/${encodeURIComponent(row.id)}`, { method: 'DELETE' });
    toast('Automatisation supprimée.');
    await list();
  }
  catch (error) {
    // Une automatisation en cours d'exécution refuse la suppression (409) : la tentative reste
    // sûre, il suffit de réessayer une fois le cycle en cours terminé.
    report(error);
  }
}

/* ── Formulaire de création / modification ───────────────────────────── */

function openForm(row) {
  editingId = row?.id || null;
  $('#automation-form-title').textContent = row ? 'Modifier l’automatisation' : 'Créer une automatisation';
  $('#automation-form').reset();
  $('#automation-name').value = row?.name || '';
  $('#automation-prompt').value = row?.prompt || '';
  $('#automation-cron').value = row?.cron || '';
  $('#automation-zone').value = row?.zone || Intl.DateTimeFormat().resolvedOptions().timeZone || '';
  $('#automation-enabled').checked = row ? row.enabled : true;
  $('#automation-form-dialog').showModal();
  $('#automation-name').focus();
}

async function submitForm(event) {
  event.preventDefault();
  const submit = $('#submit-automation-form');
  await busy(submit, async () => {
    const body = {
      name: $('#automation-name').value.trim(),
      prompt: $('#automation-prompt').value.trim(),
      cron: $('#automation-cron').value.trim(),
      zone: $('#automation-zone').value.trim(),
      enabled: $('#automation-enabled').checked,
    };
    try {
      if (editingId) await api(`${BASE}/${encodeURIComponent(editingId)}`, { method: 'PUT', body });
      else await api(BASE, { method: 'POST', body });
      $('#automation-form-dialog').close();
      toast(editingId ? 'Automatisation modifiée.' : 'Automatisation créée.');
      await list();
    }
    catch (error) {
      report(error);
    }
  });
}

/* ── Audit ─────────────────────────────────────────────────────────────── */

async function toggleAudit() {
  const host = $('#automations-audit');
  host.hidden = !host.hidden;
  if (!host.hidden && !auditLoaded) {
    auditLoaded = true;
    await audit();
  }
}

async function audit() {
  const host = $('#automations-audit');
  if (!host.firstChild) host.replaceChildren(el('p', 'state loading', 'Chargement…'));
  try {
    const rows = await api(`${BASE}/audit`);
    if (!rows.length) {
      host.replaceChildren(empty('Aucun événement.', 'Créer, modifier, supprimer et exécuter s’y inscrivent tous.'));
      return;
    }
    const list_ = el('ol', 'stack');
    rows.forEach((row) => {
      const item = el('li', 'confirm-row');
      item.append(el('span', 'label', `${row.actor} · ${stamp(row.at)}`));
      item.append(el('span', 'value muted', `${row.action}${row.result ? ` — ${row.result}` : ''}`));
      list_.append(item);
    });
    host.replaceChildren(list_);
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

export function wire() {
  $('#create-automation').addEventListener('click', () => openForm(null));
  $('#cancel-automation-form').addEventListener('click', () => $('#automation-form-dialog').close());
  $('#automation-form').addEventListener('submit', submitForm);
  $('#automations-audit-toggle').addEventListener('click', toggleAudit);
}
