// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Résumés durables : ce que la mémoire long-terme retient d'un échange terminé normalement
// (demande + résultat), au-delà des faits ponctuels que memory.js couvre déjà. Lecture et
// suppression seulement — un résumé s'écrit à la fin d'un échange réussi, jamais depuis la
// console.

import { $, ago, api, busy, confirmAction, definition, el, empty, render, report, toast } from './core.js';

const BASE = '/api/agent/memory/summaries';

export async function list() {
  await render($('#summaries-list'), load, (data) => {
    if (data.disabled) {
      return empty('Résumés durables désactivés.',
        'kex.agent.memory.enabled vaut false : aucun échange ne survit à sa conversation.');
    }
    if (!data.rows.length) {
      return empty('Aucun résumé conservé.',
        'Aucun échange terminé normalement n’a encore été résumé pour ce locataire.');
    }

    const stack = el('div', 'stack');
    data.rows.forEach((row) => stack.append(summaryCard(row)));
    return stack;
  });
}

/**
 * La route n'existe que sous `kex.agent.memory.enabled` : un 404 ici dit « éteinte », pas
 * « cassée ». Même piège et même garde que memory.js.
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

function summaryCard(row) {
  const card = el('article', 'card');
  const head = el('header');
  head.append(el('h3', null, row.title));
  head.append(el('span', 'muted', ago(row.createdAt)));
  card.append(head);
  card.append(definition('Conversation', el('span', 'mono', row.conversationId || '—')));
  card.append(el('pre', 'dump muted', row.markdown));

  const actions = el('div', 'card-actions');
  const remove = el('button', 'ghost danger', 'Oublier');
  remove.type = 'button';
  remove.setAttribute('aria-label', `Oublier : ${row.title}`);
  remove.addEventListener('click', () => busy(remove, () => forget(row)));
  actions.append(remove);
  card.append(actions);
  return card;
}

async function forget(row) {
  const confirmed = await confirmAction({
    title: 'Confirmer la suppression du résumé',
    accept: 'Supprimer',
    lines: [
      ['Résumé', row.title],
      ['Conversation', row.conversationId || '—'],
    ],
  });
  if (!confirmed) return;
  try {
    await api(`${BASE}/${encodeURIComponent(row.id)}`, { method: 'DELETE' });
    toast('Résumé supprimé.');
    await list();
  }
  catch (error) {
    report(error);
  }
}
