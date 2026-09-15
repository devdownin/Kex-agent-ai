// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Ce qui décide de chaque échange : le fournisseur, le modèle, le trajet des prompts et les
// plafonds. En lecture seule — le client du modèle est câblé au démarrage du contexte, un champ
// modifiable ici accepterait une valeur que l'échange suivant ignorerait.

import { $, api, definition, el, render, stateTag } from './core.js';

const KEY_STATES = {
  true: { state: 'OK', label: 'Configurée' },
  false: { state: 'ERROR', label: 'Absente' },
};

/**
 * Une valeur non configurée n'est pas une valeur nulle : c'est le défaut du fournisseur qui
 * s'applique, et afficher « 0 » ou « — » ferait croire à une limite qu'on n'a pas posée.
 */
function orDefault(value, format = String) {
  if (value === null || value === undefined) {
    const node = el('span', 'unmeasured', 'défaut du fournisseur');
    node.title = 'Aucune valeur configurée ici : c’est le fournisseur qui tranche.';
    return node;
  }
  return el('span', 'mono', format(value));
}

/** Ce que l'agent n'a pas su lire ne s'affiche pas vide : vide se lirait « rien ». */
function unread(detail) {
  const node = el('span', 'unmeasured', 'non lu');
  node.title = detail;
  return node;
}

function isoDuration(value) {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$/.exec(value || '');
  if (!match) return value || '—';
  const [, hours, minutes, seconds] = match;
  return [hours && `${hours} h`, minutes && `${minutes} min`, seconds && `${Number(seconds)} s`]
    .filter(Boolean).join(' ') || '0 s';
}

function apiKeyRow(data) {
  const wrap = el('span', 'inline');
  const mark = KEY_STATES[data.apiKeyPresent]
    // `null` n'est pas `false` : « pas su regarder » ne s'affiche pas « pas de clé ».
    ?? { state: 'UNKNOWN', label: 'Non vérifiable' };
  wrap.append(stateTag(mark.state, mark.label));
  if (data.apiKeyVariable) wrap.append(el('span', 'mono muted', data.apiKeyVariable));
  return wrap;
}

function provider(data) {
  const wrap = el('span', 'inline');
  wrap.append(el('strong', null, data.label));
  // La valeur brute en plus du nom lisible, quand elle apporte quelque chose : « openai » à côté
  // d'« OpenRouter » dit ce qu'il faut poser dans KEX_AGENT_LLM_PROVIDER, « anthropic » à côté
  // d'« Anthropic » n'est que du bruit.
  if (data.provider.toLowerCase() !== data.label.toLowerCase()) {
    wrap.append(el('span', 'mono muted', data.provider));
  }
  if (data.gateway) wrap.append(stateTag('WARNING', 'Intermédiaire'));
  return wrap;
}

function endpoint(data) {
  if (data.baseUrl) return el('span', 'mono', data.baseUrl);
  return el('span', null, 'Appel direct chez le fournisseur');
}

function toolLimit(data) {
  if (data.maxToolCalls === null || data.maxToolCalls === undefined) {
    return unread('Aucun plafond configuré : un modèle qui boucle tourne jusqu’au délai maximal.');
  }
  const wrap = el('span', 'inline');
  wrap.append(el('span', 'mono', `${data.maxToolCalls} par échange`));
  if (data.onToolLimitExceeded) {
    wrap.append(el('span', 'muted', `au-delà : ${data.onToolLimitExceeded}`));
  }
  return wrap;
}

export async function view() {
  await render($('#llm-config'), () => api('/api/agent/llm'), (data) => {
    const wrap = el('div');
    // Les avertissements en tête : ce qu'une configuration coûte se lit avant ce qu'elle contient.
    data.warnings?.forEach((warning) => wrap.append(el('p', 'banner', warning)));

    wrap.append(definition('Fournisseur', provider(data)));
    wrap.append(definition('Modèle', data.model
      ? el('span', 'mono', data.model)
      : unread('Le fournisseur retenu n’est pas lu par cet écran.')));
    wrap.append(definition('Point d’accès', endpoint(data)));
    wrap.append(definition('Clé d’API', apiKeyRow(data)));
    wrap.append(definition('Plafond de sortie', orDefault(data.maxTokens, (raw) => `${raw} jetons`)));
    wrap.append(definition('Température', orDefault(data.temperature)));
    wrap.append(definition('Appels d’outils', toolLimit(data)));
    wrap.append(definition('Délai maximal d’un échange',
      el('span', 'mono', isoDuration(data.requestTimeout))));
    wrap.append(definition('Mémoire de conversation',
      el('span', 'mono', `${data.maxHistoryMessages} messages`)));
    wrap.append(definition('Journalisation des échanges', data.logInteractions
      ? stateTag('WARNING', 'Active — prompts et réponses dans les journaux')
      : stateTag('OK', 'Inactive')));
    wrap.append(definition('Modèle d’embeddings', data.embeddingProvider === 'none'
      ? el('span', null, 'Éteint')
      : el('span', 'mono', data.embeddingProvider)));
    wrap.append(definition('Base de connaissance',
      el('span', null, data.knowledgeEnabled ? 'Activée' : 'Désactivée')));

    if (data.systemPrompt) {
      const details = el('details', 'advanced');
      details.append(el('summary', null, 'Prompt système'));
      details.append(el('pre', 'dump prose', data.systemPrompt));
      wrap.append(details);
    }
    return wrap;
  });
}

export const wire = () => $('#refresh-llm').addEventListener('click', view);
