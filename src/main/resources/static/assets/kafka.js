// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Vue technique du cluster : topics, puis les groupes qui les lisent et leur retard. Second
// niveau délibérément — le tableau de bord métier n'a pas à en être saturé.

import { $, ago, api, el, empty, errorState, openDrawer, render, stateTag } from './core.js';

const BASE = '/api/agent/kafka';

// Les verdicts viennent de l'outil. On les traduit pour l'écran, on ne les recalcule pas : c'est
// lui qui sait qu'un retard sans membre assigné ne se résorbera pas de lui-même.
const VERDICTS = {
  CAUGHT_UP: { state: 'OK', label: 'À jour' },
  BEHIND: { state: 'WARNING', label: 'En retard' },
  STALLED: { state: 'ERROR', label: 'À l’arrêt' },
  NOT_MEASURED: { state: 'UNKNOWN', label: 'Non mesuré' },
};

const verdict = (value) => VERDICTS[value] || { state: 'UNKNOWN', label: value || 'Inconnu' };

/**
 * Une mesure absente n'est pas zéro. Zéro affirme « rattrapé » ou « aucun échec » ; une mesure
 * absente n'affirme rien, et les confondre fait lire un consumer à l'arrêt comme un consumer à jour.
 */
function measured(value, format = (raw) => String(raw)) {
  if (!value || !value.measured) {
    const node = el('span', 'unmeasured', 'non mesuré');
    node.title = value?.reason || 'Aucune mesure rendue';
    return node;
  }
  return el('span', 'mono', format(value.value));
}

const counted = (raw) => Number(raw).toLocaleString('fr-FR');

function lagDuration(millis) {
  const seconds = Math.round(Number(millis) / 1000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  return minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

/** Une vue vide dit toujours pourquoi : sans motif, elle se lirait « aucun topic ». */
function unavailable(reason) {
  return empty('Vue technique indisponible.', reason);
}

function coverageNote(coverage) {
  if (!coverage || coverage.complete) return null;
  if (coverage.stopReason === 'NOT_REPORTED') {
    return el('p', 'hint', 'Cet outil ne rend pas d’enveloppe de couverture : on ne sait pas si le '
      + 'relevé est allé au bout.');
  }
  const missed = coverage.notReached?.length ? ` — non lu : ${coverage.notReached.join(', ')}` : '';
  return el('p', 'banner', `Relevé partiel (${coverage.stopReason})${missed}. Ce qui manque n’est `
    + 'pas « rien » : il n’a simplement pas été regardé.');
}

export async function topics() {
  await render($('#kafka-topics'), () => api(`${BASE}/topics`), (data) => {
    if (data.unavailable) return unavailable(data.unavailable);
    if (!data.topics.length) {
      return empty('Aucun topic rendu par l’outil.', 'Le relevé a abouti, la liste est vide.');
    }

    const wrap = el('div');
    const note = coverageNote(data.coverage);
    if (note) wrap.append(note);
    data.warnings?.forEach((warning) => wrap.append(el('p', 'hint', warning)));
    if (data.truncated) {
      wrap.append(el('p', 'hint', 'Réponse tronquée par le serveur : la liste n’est pas complète.'));
    }

    const table = el('table', 'grid');
    const head = el('thead');
    const headRow = el('tr');
    ['Topic', 'Partitions', 'Enregistrements', 'Dernière activité', ''].forEach((label) =>
      headRow.append(el('th', null, label)));
    head.append(headRow);
    table.append(head);

    const body = el('tbody');
    for (const topic of data.topics) {
      const line = el('tr');
      const name = el('td', 'strong');
      name.append(el('span', 'mono', topic.name));
      if (topic.deadLetter) name.append(stateTag('WARNING', 'Rebut'));
      line.append(name);
      line.append(el('td', 'mono', topic.partitions));

      const records = el('td');
      records.append(measured(topic.records, counted));
      line.append(records);

      const activity = el('td');
      activity.append(measured(topic.lastActivityMs, (raw) => ago(new Date(Number(raw)).toISOString())));
      line.append(activity);

      const actions = el('td');
      const inspect = el('button', 'ghost', 'Groupes');
      inspect.type = 'button';
      inspect.addEventListener('click', () => openLag(topic.name));
      actions.append(inspect);
      line.append(actions);
      body.append(line);
    }
    table.append(body);

    const scroll = el('div', 'scroll-x');
    scroll.append(table);
    wrap.append(scroll);
    return wrap;
  });
}

async function openLag(topic) {
  openDrawer(topic, el('p', 'state loading', 'Relevé des groupes…'));
  try {
    const data = await api(`${BASE}/topics/${encodeURIComponent(topic)}/lag`);
    openDrawer(topic, lagPanel(data));
  } catch (error) {
    openDrawer(topic, errorState(error, () => openLag(topic)));
  }
}

function lagPanel(data) {
  if (data.unavailable) return unavailable(data.unavailable);

  const wrap = el('div');
  const note = coverageNote(data.coverage);
  if (note) wrap.append(note);

  if (data.worstVerdict) {
    const summary = el('div', 'definition-row');
    summary.append(el('span', 'label', 'Pire verdict'));
    summary.append(stateTag(verdict(data.worstVerdict).state, verdict(data.worstVerdict).label));
    wrap.append(summary);
  }
  // L'écart entre examinés et existants est la part non regardée : la taire ferait lire une liste
  // courte comme une liste complète.
  if (data.groupsInCluster > data.groupsExamined) {
    wrap.append(el('p', 'hint',
      `${data.groupsExamined} groupe(s) relevé(s) sur ${data.groupsInCluster} dans le cluster.`));
  }

  if (!data.groups.length) {
    wrap.append(empty('Aucun groupe ne lit ce topic.', 'Parmi ceux qui ont été relevés.'));
    return wrap;
  }

  for (const group of data.groups) {
    const card = el('article', 'card');
    const mark = verdict(group.verdict);
    card.dataset.state = mark.state;
    const head = el('header');
    head.append(el('h3', 'mono', group.groupId));
    head.append(stateTag(mark.state, mark.label));
    card.append(head);

    if (group.error) {
      card.append(el('p', 'banner', `Relevé impossible : ${group.error}`));
    }
    if (group.explanation) {
      card.append(el('p', 'muted', group.explanation));
    }

    card.append(lagRow('Retard (messages)', measured(group.recordLag, counted)));
    card.append(lagRow('Retard (temps)', measured(group.lagMillis, lagDuration)));
    card.append(lagRow('État', el('span', null, [group.state, group.type].filter(Boolean).join(' · ') || '—')));
    if (group.partitionsWithoutCommit) {
      card.append(lagRow('Partitions sans commit', el('span', 'mono warn', group.partitionsWithoutCommit)));
    }
    wrap.append(card);
  }
  return wrap;
}

function lagRow(label, value) {
  const row = el('div', 'definition-row');
  row.append(el('span', 'label', label));
  row.append(value);
  return row;
}

export const wire = () => $('#refresh-kafka').addEventListener('click', topics);
