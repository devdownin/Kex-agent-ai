// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Base de connaissance : les passages qu'une recherche par similarité renvoie avant chaque échange,
// pas une liste exhaustive — VectorStore (Spring AI) n'expose qu'ajouter, retirer et chercher,
// jamais énumérer. La recherche ici est donc le seul moyen de voir ce qui existe, avec les mêmes
// seuils que ceux appliqués avant un échange réel (KnowledgeController.search).

import { $, api, busy, confirmAction, el, empty, report, toast } from './core.js';

const BASE = '/api/agent/knowledge';

/**
 * Construit le panneau une seule fois : contrairement à une liste qui se rafraîchit sans risque,
 * réécrire ce panneau à chaque sondage de fond (la vue technique s'auto-rafraîchit) effacerait une
 * recherche en cours de lecture ou un formulaire d'ajout entamé, pour un gain nul — rien ici ne
 * change tout seul côté serveur entre deux sondages.
 */
export function panel() {
  const host = $('#knowledge-panel');
  if (host.firstChild) return;
  host.replaceChildren(build());
}

function build() {
  const wrap = el('div', 'stack');

  const form = el('form', 'row-end');
  const input = el('input');
  input.type = 'search';
  input.required = true;
  input.placeholder = 'Rechercher (même recherche qu’avant un échange)';
  const search = el('button', 'primary', 'Rechercher');
  search.type = 'submit';
  const addToggle = el('button', 'ghost', 'Ajouter un document');
  addToggle.type = 'button';
  form.append(input, search, addToggle);
  wrap.append(form);

  const results = el('div');
  results.append(empty('Recherchez pour voir ce qu’un échange verrait.',
    'Aucune liste exhaustive n’existe côté serveur : chaque recherche applique les mêmes seuils que ceux appliqués avant un échange réel.'));
  wrap.append(results);

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const query = input.value.trim();
    if (query) runSearch(query, results, search);
  });
  addToggle.addEventListener('click', () => openAddPanel(wrap));

  return wrap;
}

async function runSearch(query, host, button) {
  await busy(button, async () => {
    host.replaceChildren(el('p', 'state loading', 'Recherche…'));
    try {
      const matches = await api(`${BASE}?query=${encodeURIComponent(query)}`);
      renderResults(matches, host);
    }
    catch (error) {
      if (error.status === 404) { host.replaceChildren(disabledMessage()); return; }
      host.replaceChildren(empty('La recherche a échoué.'));
      report(error);
    }
  });
}

function renderResults(matches, host) {
  if (!matches.length) {
    host.replaceChildren(empty('Aucun passage au-dessus du seuil de similarité.',
      'Un passage sans rapport vaut moins que pas de passage du tout : le seuil l’a écarté, comme il l’écarterait avant un échange réel.'));
    return;
  }
  const list = el('div', 'stack');
  matches.forEach((match) => list.append(matchCard(match, list)));
  host.replaceChildren(list);
}

function matchCard(match, list) {
  const card = el('div', 'card');
  const header = el('header');
  header.append(el('h3', null, excerpt(match.text, 80)));
  if (match.score != null) header.append(el('span', 'time', match.score.toFixed(2)));
  card.append(header);
  card.append(el('p', null, match.text));
  if (match.metadata && Object.keys(match.metadata).length) {
    const chips = el('div', 'chips');
    Object.entries(match.metadata).forEach(([key, value]) => chips.append(el('code', 'chip', `${key} : ${value}`)));
    card.append(chips);
  }
  const actions = el('div', 'card-actions');
  const remove = el('button', 'ghost danger', 'Retirer');
  remove.type = 'button';
  remove.setAttribute('aria-label', `Retirer : ${excerpt(match.text, 40)}`);
  remove.addEventListener('click', () => busy(remove, () => removeMatch(match, card, list)));
  actions.append(remove);
  card.append(actions);
  return card;
}

async function removeMatch(match, card, list) {
  const confirmed = await confirmAction({
    title: 'Confirmer le retrait',
    accept: 'Retirer',
    lines: [
      ['Document', excerpt(match.text, 160)],
      ['Effet', 'Ce passage ne sera plus injecté avant aucun échange futur.'],
    ],
  });
  if (!confirmed) return;
  try {
    await api(BASE, { method: 'DELETE', body: [match.id] });
    toast('Document retiré.');
    card.remove();
    if (!list.children.length) {
      list.replaceWith(empty('Aucun passage au-dessus du seuil de similarité.'));
    }
  }
  catch (error) { report(error); }
}

function openAddPanel(host) {
  if (host.querySelector('.knowledge-add')) return;
  // Réutilise le traitement visuel de .invoke (bordure, en-tête) et l'espacement de .stack :
  // aucune règle CSS dédiée à ajouter pour un second panneau qui a la même forme.
  const panelEl = el('section', 'knowledge-add invoke stack');
  const head = el('header');
  head.append(el('h4', null, 'Ajouter un document'), closeButton(panelEl));
  panelEl.append(head);

  const text = el('textarea');
  text.rows = 4;
  text.required = true;
  text.placeholder = 'Texte du document';
  const metadata = el('textarea');
  metadata.rows = 2;
  metadata.spellcheck = false;
  metadata.placeholder = 'Métadonnées JSON (facultatif), ex. {"source": "runbook"}';

  const submit = el('button', 'primary', 'Ajouter');
  submit.type = 'button';
  submit.addEventListener('click', () => {
    const content = text.value.trim();
    if (!content) return;
    let parsedMetadata;
    try {
      parsedMetadata = parseMetadata(metadata.value);
    }
    catch (error) {
      report(error);
      return;
    }
    busy(submit, async () => {
      try {
        const ids = await api(BASE, { method: 'POST', body: [{ text: content, metadata: parsedMetadata }] });
        toast(`Document ajouté (${ids[0]}).`);
        panelEl.remove();
      }
      catch (error) {
        if (error.status === 404) { panelEl.replaceWith(disabledMessage()); return; }
        report(error);
      }
    });
  });

  panelEl.append(text, metadata, submit);
  host.insertBefore(panelEl, host.children[1]);
  text.focus();
}

function parseMetadata(raw) {
  if (!raw.trim()) return null;
  try {
    const value = JSON.parse(raw);
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error();
    return value;
  }
  catch {
    throw new Error('Les métadonnées doivent être un objet JSON.');
  }
}

function disabledMessage() {
  return empty('Base de connaissance désactivée.',
    'kex.agent.knowledge.enabled vaut false : aucun passage n’est injecté avant un échange.');
}

/** Même geste que les panneaux d'outil MCP : refermable sans dépendre d'un nouveau rendu. */
function closeButton(panelEl) {
  const close = el('button', 'ghost', 'Fermer');
  close.type = 'button';
  close.addEventListener('click', () => panelEl.remove());
  return close;
}

const excerpt = (text, max) => (text.length > max ? `${text.slice(0, max)}…` : text);
