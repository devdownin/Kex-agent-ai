// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Vue technique : les serveurs MCP et la santé de l'instance. Elle existe pour que le tableau de
// bord métier n'en soit pas saturé — les signaux bruts sont au second niveau, jamais au premier.

import { $, api, el, empty, render, report, stateTag } from './core.js';
import * as kafka from './kafka.js';

export async function servers() {
  await render($('#servers'), () => api('/api/agent/mcp/servers'), (list) => {
    if (!list.length) {
      return empty('Aucune connexion MCP configurée.',
        'Sans outil, l’agent ne peut qu’observer ce qu’on lui raconte.');
    }
    const grid = el('div', 'servers-grid');
    list.forEach((server) => grid.append(card(server)));
    return grid;
  });
}

function card(server) {
  const node = el('article', 'server');
  const head = el('header');
  // Le serveur s'adresse par clé de connexion : le nom qu'il annonce n'existe qu'après le handshake.
  head.append(el('h3', null, server.connection));
  head.append(stateTag(server.initialized ? 'OK' : 'UNKNOWN',
    server.initialized ? 'Initialisé' : 'Pas de handshake'));
  node.append(head);

  const meta = [server.serverName, server.version, server.protocolVersion].filter(Boolean).join(' · ');
  node.append(el('p', 'muted', meta || 'Aucun handshake abouti pour l’instant'));

  const tools = server.tools || [];
  if (tools.length) {
    const list = el('ul', 'tool-list');
    tools.forEach((tool) => {
      const item = el('li');
      const button = el('button');
      button.type = 'button';
      button.append(el('span', 'name', tool.name));
      if (tool.description) button.append(el('span', 'desc', tool.description));
      button.addEventListener('click', () => invoke(node, server.connection, tool));
      item.append(button);
      list.append(item);
    });
    node.append(list);
  } else {
    node.append(empty('Aucun outil exposé.'));
  }

  const actions = el('div', 'row-end');
  const resources = el('button', 'ghost', 'Ressources');
  resources.type = 'button';
  resources.addEventListener('click', () => listResources(node, server.connection));
  actions.append(resources);
  node.append(actions);
  return node;
}

function invoke(host, connection, tool) {
  host.querySelector('.invoke')?.remove();
  const panel = el('section', 'invoke');
  panel.append(el('h4', null, tool.name));

  const args = el('textarea');
  args.rows = 4;
  args.spellcheck = false;
  args.value = '{}';
  const output = el('pre', 'dump', '—');

  const run = el('button', 'primary', 'Invoquer');
  run.type = 'button';
  run.addEventListener('click', async () => {
    let parsed;
    try {
      parsed = JSON.parse(args.value || '{}');
    } catch {
      output.textContent = 'Arguments JSON invalides.';
      return;
    }
    run.disabled = true;
    output.textContent = '…';
    try {
      const result = await api(
        `/api/agent/mcp/servers/${encodeURIComponent(connection)}/tools/${encodeURIComponent(tool.name)}`,
        { method: 'POST', body: { arguments: parsed } });
      output.textContent = JSON.stringify(result, null, 2);
    } catch (error) {
      output.textContent = error.message;
      report(error);
    } finally {
      run.disabled = false;
    }
  });

  const row = el('div', 'row-end');
  row.append(run);
  panel.append(args, row, output);
  host.append(panel);
  args.focus();
}

async function listResources(host, connection) {
  host.querySelector('.invoke')?.remove();
  const panel = el('section', 'invoke');
  panel.append(el('h4', null, 'Ressources'));
  const output = el('pre', 'dump', '…');
  panel.append(output);
  host.append(panel);
  try {
    const resources = await api(`/api/agent/mcp/servers/${encodeURIComponent(connection)}/resources`);
    if (!resources.length) {
      output.textContent = 'Aucune ressource exposée.';
      return;
    }
    output.textContent = '—';
    const list = el('ul', 'tool-list');
    resources.forEach((resource) => {
      const button = el('button');
      button.type = 'button';
      button.append(el('span', 'name', resource.name || resource.uri));
      button.append(el('span', 'desc', resource.mimeType || resource.uri));
      button.addEventListener('click', async () => {
        output.textContent = '…';
        try {
          const content = await api(`/api/agent/mcp/servers/${encodeURIComponent(connection)}`
            + `/resource?uri=${encodeURIComponent(resource.uri)}`);
          output.textContent = JSON.stringify(content, null, 2);
        } catch (error) {
          output.textContent = error.message;
        }
      });
      const item = el('li');
      item.append(button);
      list.append(item);
    });
    panel.insertBefore(list, output);
  } catch (error) {
    output.textContent = error.message;
  }
}

async function metric(name) {
  try {
    const body = await api(`/actuator/metrics/${encodeURIComponent(name)}`);
    const measurement = body.measurements?.find((m) => m.statistic === 'VALUE' || m.statistic === 'COUNT');
    return measurement ? measurement.value : null;
  } catch {
    // Une métrique absente (modèle jamais appelé, endpoint filtré) n'est pas une panne.
    return null;
  }
}

function tile(label, value) {
  const node = el('div', 'tile');
  node.append(el('div', 'label', label), el('div', 'value', value));
  return node;
}

export async function health() {
  const tiles = $('#metric-tiles');
  const dump = $('#health-dump');
  try {
    const status = await fetch('/actuator/health').then((response) => response.json());
    dump.textContent = JSON.stringify(status, null, 2);
  } catch (error) {
    dump.textContent = error.message;
  }

  const [tokens, requests, memory] = await Promise.all([
    metric('gen_ai.client.token.usage'),
    metric('http.server.requests'),
    metric('jvm.memory.used'),
  ]);
  const format = (value) => (value == null ? '—' : Math.round(value).toLocaleString('fr-FR'));
  tiles.replaceChildren(
    tile('Jetons consommés', format(tokens)),
    tile('Requêtes HTTP', format(requests)),
    tile('Mémoire JVM', memory == null ? '—' : `${Math.round(memory / 1048576)} Mio`),
  );

  try {
    const info = await api('/actuator/info');
    if (info?.build) tiles.append(tile('Version', info.build.version));
  } catch {
    /* /actuator/info exige le jeton : son absence ne casse pas la vue */
  }
}

export async function view() {
  await Promise.all([servers(), kafka.topics(), health()]);
}

export function wire() {
  $('#refresh-servers').addEventListener('click', servers);
  kafka.wire();
}
