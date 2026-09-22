// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Canaux de notification : où part une demande de validation quand elle cible une personne, pas
// un outil. Lecture seule — écrire ici voudrait dire manipuler un webhook ou un secret depuis la
// console, ce qui reste un geste d'exploitant sur application.yml.

import { $, api, definition, el, stateTag } from './core.js';

const BASE = '/api/agent/channels/status';

export async function status() {
  const host = $('#channels-status');
  if (!host.firstChild) host.replaceChildren(el('p', 'state loading', 'Chargement…'));
  try {
    host.replaceChildren(channelsList(await api(BASE)));
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

function channelsList(data) {
  const list = el('div', 'stack');
  list.append(definition('Slack', tag(data.slack, !data.slack ? 'Inactif'
    : data.slackInteractiveButtons ? 'Actif, boutons intégrés' : 'Actif, lien vers la console')));
  list.append(definition('Teams', tag(data.teams, data.teams ? 'Actif, lien vers la console' : 'Inactif')));
  list.append(definition('E-mail', tag(data.email, data.email
    ? `Actif, ${data.emailRecipients} destinataire${data.emailRecipients > 1 ? 's' : ''}` : 'Inactif')));
  list.append(definition('Approbation entrante', tag(data.inboundApproval, data.inboundApproval ? 'Active' : 'Inactive')));
  list.append(definition('URL de console', tag(data.consoleUrlConfigured,
    data.consoleUrlConfigured ? 'Configurée' : 'Absente')));
  return list;
}

const tag = (active, label) => stateTag(active ? 'OK' : 'UNKNOWN', label);
