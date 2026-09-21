// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Gouvernance de l'agent : la charte, la bibliothèque de compétences approuvées et ce qui attend
// une décision. Trois lectures distinctes d'une même question — que peut dire l'agent de plus que
// son prompt système, et qui l'a autorisé — réunies dans un seul onglet plutôt que dispersées.

import {
  $, ago, api, busy, confirmAction, definition, el, empty, report, stamp, stateTag, toast,
} from './core.js';

const SKILLS_BASE = '/api/agent/skills';
const CHARTER_BASE = '/api/agent/charter';

export async function governance() {
  await Promise.all([charter(), reviewQueue(), curation()]);
}

/* ── Qui je suis ───────────────────────────────────────────────────────── */

/**
 * Sans jeton reconnu, `/api/agent/whoami` répond 401 comme toute autre route : l'étiquette reste
 * simplement masquée plutôt que d'afficher une erreur qui couvrirait le vrai message, déjà porté
 * par `credential-label` juste au-dessus.
 */
export async function whoami() {
  const label = $('#whoami-label');
  try {
    const data = await api('/api/agent/whoami');
    label.textContent = data.tenant === data.name
      ? `Connecté comme ${data.name}`
      : `Connecté comme ${data.name} · locataire ${data.tenant}`;
    label.hidden = false;
  }
  catch {
    label.hidden = true;
  }
}

/* ── Charte ────────────────────────────────────────────────────────────── */

let historyLoaded = false;

async function charter() {
  const host = $('#charter-current');
  if (!host.firstChild) host.replaceChildren(el('p', 'state loading', 'Chargement…'));
  try {
    const data = await api(CHARTER_BASE);
    $('#charter-markdown').value = data.markdown || '';
    host.replaceChildren(data.updatedBy ? charterSummary(data)
      : empty('Aucune charte écrite.',
        'Le prompt système reste seul en vigueur tant que personne ne l’a rédigée.'));
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

function charterSummary(data) {
  const wrap = el('div', 'summary');
  wrap.append(definition('Écrite par', el('span', null, data.updatedBy)));
  wrap.append(definition('Le', el('span', null, stamp(data.updatedAt))));
  wrap.append(definition('Motif', el('span', null, data.reason || '—')));
  return wrap;
}

async function saveCharter(event) {
  event.preventDefault();
  const markdown = $('#charter-markdown').value;
  const reason = $('#charter-reason').value.trim();
  try {
    await api(CHARTER_BASE, { method: 'PUT', body: { markdown, reason } });
    toast('Charte enregistrée.');
    $('#charter-reason').value = '';
    await charter();
    if (historyLoaded) await charterHistory();
  }
  catch (error) {
    report(error);
  }
}

async function toggleCharterHistory() {
  const host = $('#charter-history');
  host.hidden = !host.hidden;
  if (!host.hidden && !historyLoaded) {
    historyLoaded = true;
    await charterHistory();
  }
}

async function charterHistory() {
  const host = $('#charter-history');
  try {
    const rows = await api(`${CHARTER_BASE}/versions`);
    if (!rows.length) {
      host.replaceChildren(empty('Aucune version antérieure.'));
      return;
    }
    const list = el('ol', 'stack');
    // Le service rend déjà la plus récente d'abord : rien à trier de plus ici.
    rows.forEach((row) => {
      const item = el('li', 'confirm-row');
      item.append(el('span', 'label', `${row.reviewedBy || row.owner} · ${ago(row.reviewedAt || row.createdAt)}`));
      item.append(el('span', 'value muted', row.reviewReason || '—'));
      list.append(item);
    });
    host.replaceChildren(list);
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

/* ── File de revue ─────────────────────────────────────────────────────── */

async function reviewQueue() {
  const host = $('#skills-review-queue');
  if (!host.firstChild) host.replaceChildren(el('div', 'state loading skeleton skeleton-list'));
  try {
    const rows = await api(`${SKILLS_BASE}/review-queue`);
    host.replaceChildren(rows.length ? reviewQueueList(rows)
      : empty('Aucune compétence en attente.',
        'Chaque échange réussi peut en proposer une ; rien n’attend de décision pour l’instant.'));
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

function reviewQueueList(rows) {
  const list = el('div', 'stack');
  rows.forEach((row) => list.append(reviewQueueCard(row)));
  return list;
}

function reviewQueueCard(row) {
  const card = el('article', 'card');
  card.dataset.state = 'PENDING';
  const head = el('header');
  head.append(el('h3', null, row.title));
  head.append(stateTag('PENDING', 'En attente'));
  card.append(head);
  card.append(definition('Propriétaire', el('span', null, row.owner)));
  card.append(definition('Proposée', el('span', null, ago(row.createdAt))));
  card.append(definition('Preuve', el('span', 'muted', row.evidence)));
  card.append(el('pre', 'dump muted', row.markdown));

  const actions = el('div', 'card-actions');
  const reject = el('button', 'ghost danger', 'Rejeter');
  reject.type = 'button';
  reject.setAttribute('aria-label', `Rejeter : ${row.title}`);
  reject.addEventListener('click', () => busy(reject, () => decideSkill(row, false)));
  const approve = el('button', 'primary', 'Approuver');
  approve.type = 'button';
  approve.setAttribute('aria-label', `Approuver : ${row.title}`);
  approve.addEventListener('click', () => busy(approve, () => decideSkill(row, true)));
  actions.append(reject, approve);
  card.append(actions);
  return card;
}

async function decideSkill(row, approve) {
  const confirmed = await confirmAction({
    title: approve ? 'Confirmer cette approbation' : 'Confirmer ce rejet',
    accept: approve ? `Approuver : ${row.title}` : `Rejeter : ${row.title}`,
    lines: [
      ['Compétence', row.title],
      ['Propriétaire', row.owner],
      ['Conséquence', approve
        ? 'Entre dans le contexte durable de ce locataire dès la prochaine conversation.'
        : 'Reste hors du prompt ; l’opérateur qui l’a proposée devra en tirer les conséquences.'],
    ],
  });
  if (!confirmed) return;
  try {
    await api(`${SKILLS_BASE}/${encodeURIComponent(row.id)}/${approve ? 'approve' : 'reject'}`,
      { method: 'POST', body: { reason: approve ? 'Approuvée depuis la console' : 'Rejetée depuis la console' } });
    toast(approve ? 'Compétence approuvée.' : 'Compétence rejetée.');
    await Promise.all([reviewQueue(), curation()]);
  }
  catch (error) {
    report(error);
  }
}

/* ── Curation ──────────────────────────────────────────────────────────── */

async function curation() {
  const host = $('#skills-curation');
  if (!host.firstChild) host.replaceChildren(el('div', 'state loading skeleton skeleton-kpis'));
  try {
    const data = await api(`${SKILLS_BASE}/curation`);
    host.replaceChildren(curationReport(data));
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

function curationReport(data) {
  const wrap = el('div', 'stack');

  const tiles = el('div', 'tiles');
  tiles.append(tile('Approuvées', data.approved));
  tiles.append(tile('Injectées', data.injected.length));
  tiles.append(tile('En sommeil', data.dormant.length));
  tiles.append(tile('Obsolètes', data.stale.length));
  tiles.append(tile('Caractères injectés', data.characters.toLocaleString('fr-FR')));
  wrap.append(tiles);

  if (data.dormant.length) {
    wrap.append(digestGroup('En sommeil : approuvées, mais au-delà du plafond d’injection', data.dormant, true));
  }
  if (data.stale.length) {
    wrap.append(digestGroup('Obsolètes : non revues depuis longtemps', data.stale, true));
  }
  if (data.injected.length) {
    wrap.append(digestGroup('Injectées', data.injected, false));
  }
  if (data.duplicates.length) {
    wrap.append(duplicatesGroup(data.duplicates));
  }
  if (!data.dormant.length && !data.stale.length && !data.injected.length) {
    wrap.append(empty('Aucune compétence approuvée.', 'La bibliothèque reste vide tant que personne n’en approuve.'));
  }
  return wrap;
}

function tile(label, value) {
  const node = el('div', 'tile');
  node.append(el('div', 'label', label), el('div', 'value', String(value)));
  return node;
}

function digestGroup(title, entries, retirable) {
  const group = el('div', 'stack');
  group.append(el('h3', 'drawer-sub', title));
  entries.forEach((entry) => group.append(digestRow(entry, retirable)));
  return group;
}

function digestRow(entry, retirable) {
  const row = el('div', 'confirm-row');
  row.append(el('span', 'label', entry.title));
  row.append(el('span', 'value muted',
    `Approuvée par ${entry.reviewedBy} · ${ago(entry.reviewedAt)} · ${entry.characters.toLocaleString('fr-FR')} car.`));
  if (retirable) {
    const retire = el('button', 'ghost danger', 'Retirer');
    retire.type = 'button';
    retire.setAttribute('aria-label', `Retirer : ${entry.title}`);
    retire.addEventListener('click', () => busy(retire, () => retireSkill(entry)));
    row.append(retire);
  }
  return row;
}

function duplicatesGroup(duplicates) {
  const group = el('div', 'stack');
  group.append(el('h3', 'drawer-sub', 'Doublons de titre'));
  duplicates.forEach((duplicate) => {
    const row = el('div', 'confirm-row');
    row.append(el('span', 'label', duplicate.title));
    row.append(el('span', 'value muted', `${duplicate.entries.length} compétences au même titre`));
    group.append(row);
  });
  return group;
}

/** Le curateur signale, un humain nommé retire — jamais l'inverse, et jamais sans motif. */
async function retireSkill(entry) {
  const { confirmed, reason } = await confirmAction({
    title: 'Confirmer le retrait',
    accept: `Retirer : ${entry.title}`,
    lines: [
      ['Compétence', entry.title],
      ['Conséquence', 'Sort du contexte de toutes les conversations suivantes de ce locataire.'],
    ],
    reasonLabel: 'Motif du retrait (conservé dans l’audit)',
    reasonRequired: true,
  });
  if (!confirmed) return;
  try {
    await api(`${SKILLS_BASE}/${encodeURIComponent(entry.id)}/retire`, { method: 'POST', body: { reason } });
    toast('Compétence retirée.');
    await curation();
  }
  catch (error) {
    report(error);
  }
}

/* ── Câblage ───────────────────────────────────────────────────────────── */

export function wire() {
  $('#charter-form').addEventListener('submit', saveCharter);
  $('#charter-history-toggle').addEventListener('click', toggleCharterHistory);
}
