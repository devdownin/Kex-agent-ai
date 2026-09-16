// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Mémoire long-terme du modèle : ce qu'il retient au-delà d'une conversation, via l'outil
// remember_fact. Lecture et suppression seulement ici — écrire n'est jamais un geste d'opérateur,
// c'est au modèle de choisir quoi retenir. Un souvenir écrit sans écran pour le voir ni le
// corriger contredirait tout le reste du Control Center : observer, comprendre, vérifier.

import { $, ago, api, busy, confirmAction, el, empty, render, report, toast } from './core.js';

const BASE = '/api/agent/memory';

export async function list() {
  await render($('#memory-list'), load, (data) => {
    if (data.disabled) {
      return empty('Mémoire long-terme désactivée.',
        'kex.agent.memory.enabled vaut false : le modèle ne retient rien au-delà d’une conversation.');
    }
    if (!data.rows.length) {
      return empty('Aucun souvenir retenu.',
        'Le modèle n’a rien jugé utile de retenir au-delà des conversations.');
    }

    const table = el('table', 'grid');
    const head = el('thead');
    const headRow = el('tr');
    ['Souvenir', 'Conversation', 'Retenu', ''].forEach((label) => headRow.append(el('th', null, label)));
    head.append(headRow);
    table.append(head);

    const body = el('tbody');
    data.rows.forEach((row) => body.append(memoryRow(row)));
    table.append(body);

    const scroll = el('div', 'scroll-x');
    scroll.append(table);
    return scroll;
  });
}

/**
 * La route n'existe que sous `kex.agent.memory.enabled` : un 404 ici dit « éteinte », pas
 * « cassée ». Les confondre afficherait « Ressource inconnue » et un bouton « Réessayer » qui ne
 * réussira jamais, là où le motif réel tient en une propriété à changer.
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

function memoryRow(row) {
  const line = el('tr');
  line.append(el('td', null, row.content));
  line.append(el('td', 'mono', row.conversationId || '—'));
  const when = el('td');
  when.append(ago(row.createdAt));
  line.append(when);

  const actions = el('td');
  const remove = el('button', 'ghost danger', 'Oublier');
  remove.type = 'button';
  // Sans libellé, un lecteur d'écran n'entend qu'« Oublier » répété autant de fois qu'il y a de
  // lignes, sans jamais dire lequel des souvenirs part.
  remove.setAttribute('aria-label', `Oublier : ${excerpt(row.content)}`);
  remove.addEventListener('click', () => busy(remove, () => forget(row)));
  actions.append(remove);
  line.append(actions);
  return line;
}

const excerpt = (content) => (content.length > 80 ? `${content.slice(0, 80)}…` : content);

async function forget(row) {
  const confirmed = await confirmAction({
    title: 'Confirmer la suppression du souvenir',
    accept: 'Supprimer',
    lines: [
      ['Souvenir', row.content],
      ['Conversation', row.conversationId || '—'],
    ],
  });
  if (!confirmed) return;
  try {
    await api(`${BASE}/${encodeURIComponent(row.id)}`, { method: 'DELETE' });
    toast('Souvenir supprimé.');
    await list();
  }
  catch (error) {
    report(error);
  }
}
