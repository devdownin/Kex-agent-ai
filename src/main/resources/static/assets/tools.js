// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Vue technique : les serveurs MCP et la santé de l'instance. Elle existe pour que le tableau de
// bord métier n'en soit pas saturé — les signaux bruts sont au second niveau, jamais au premier.

import { $, api, busy, circuitStateTag, el, empty, params, render, report, setParams, stateTag } from './core.js';
import * as kafka from './kafka.js';
import * as memory from './memory.js';

// Cache du dernier relevé : la recherche filtre dessus plutôt que de refaire un appel réseau par
// caractère saisi — l'endpoint n'a pas de paramètre de recherche et n'a pas à en gagner un pour ça.
let lastServers = [];
let lastMetrics = [];

export async function servers() {
  await render($('#servers'), async () => {
    const [list, metrics] = await Promise.all([
      api('/api/agent/mcp/servers'),
      // Un serveur MCP jamais appelé n'a simplement pas encore de métrique : ce n'est pas une panne.
      api('/api/agent/mcp/metrics').catch(() => []),
    ]);
    lastServers = list;
    lastMetrics = metrics;
    return list;
  }, renderServers);
}

function renderServers(list) {
  const query = (params().get('q') || '').trim().toLowerCase();
  const filtered = query ? list.filter((server) => matchesQuery(server, query)) : list;
  if (!filtered.length) {
    return query
      ? empty('Aucun serveur ni outil ne correspond à la recherche.')
      : empty('Aucune connexion MCP configurée.',
        'Sans outil, l’agent ne peut qu’observer ce qu’on lui raconte.');
  }
  const grid = el('div', 'servers-grid');
  filtered.forEach((server) => grid.append(card(server)));
  return grid;
}

function matchesQuery(server, query) {
  if (server.connection.toLowerCase().includes(query)) return true;
  if (server.serverName?.toLowerCase().includes(query)) return true;
  return (server.tools || []).some((tool) => tool.name.toLowerCase().includes(query));
}

function metricFor(connection, tool) {
  return lastMetrics.find((candidate) => candidate.connection === connection && candidate.tool === tool);
}

function card(server) {
  const node = el('article', 'server');
  const head = el('header');
  // Le serveur s'adresse par clé de connexion : le nom qu'il annonce n'existe qu'après le handshake.
  head.append(el('h3', null, server.connection));
  head.append(stateTag(server.initialized ? 'OK' : 'UNKNOWN',
    server.initialized ? 'Initialisé' : 'Pas de handshake'));
  // Propre à cette connexion pour l'appel direct ; le chemin piloté par le modèle reste sur un
  // disjoncteur partagé entre serveurs, voir McpServerInfo.circuitBreakerState.
  if (server.circuitBreakerState) {
    head.append(circuitStateTag(server.circuitBreakerState, `Disjoncteur : ${server.circuitBreakerState}`));
  }
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
      const metric = metricFor(server.connection, tool.name);
      if (metric) {
        const label = metric.averageDurationMs == null
          ? `${metric.callCount} appel(s)`
          : `${metric.callCount} appel(s) · ${Math.round(metric.averageDurationMs)} ms en moyenne`;
        button.append(el('span', 'muted', label));
      }
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
  // Répété une fois par serveur : sans libellé, un lecteur d'écran n'entend qu'« Ressources ».
  resources.setAttribute('aria-label', `Ressources de ${server.connection}`);
  resources.addEventListener('click', () => listResources(node, server.connection));
  actions.append(resources);
  node.append(actions);
  return node;
}

function invoke(host, connection, tool) {
  host.querySelector('.invoke')?.remove();
  const panel = el('section', 'invoke');
  panel.append(el('h4', null, tool.name));
  // Le schéma vient du serveur, jamais réinterprété : il dit ce que l'outil attend, pas ce qu'on
  // devine en tapant "{}" et en lisant l'erreur qui revient.
  if (tool.inputSchema && Object.keys(tool.inputSchema).length) {
    panel.append(el('pre', 'dump muted', JSON.stringify(tool.inputSchema, null, 2)));
  }

  const args = el('textarea');
  args.rows = 4;
  args.spellcheck = false;
  args.value = '{}';
  const output = el('pre', 'dump', '—');

  const run = el('button', 'primary', 'Invoquer');
  run.type = 'button';
  run.addEventListener('click', () => {
    let parsed;
    try {
      parsed = JSON.parse(args.value || '{}');
    } catch {
      output.textContent = 'Arguments JSON invalides.';
      return;
    }
    output.textContent = '…';
    busy(run, async () => {
      try {
        const result = await api(
          `/api/agent/mcp/servers/${encodeURIComponent(connection)}/tools/${encodeURIComponent(tool.name)}`,
          { method: 'POST', body: { arguments: parsed } });
        output.textContent = JSON.stringify(result, null, 2);
      } catch (error) {
        output.textContent = error.message;
        report(error);
      }
    });
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
  await Promise.all([servers(), kafka.topics(), memory.list(), health()]);
}

/**
 * Un seul bouton pour la vue entière, comme partout ailleurs (Décisions, Audit, Configuration) :
 * cette vue en portait un par panneau, soit trois pour un même geste, au-dessus d'un sondage de
 * fond qui les rafraîchit déjà tous les uns après les autres.
 */
export function wire() {
  $('#refresh-tools').addEventListener('click', view);
  $('#tools-search').addEventListener('input', (event) => {
    setParams({ q: event.target.value.trim() });
    // Filtre sur le relevé déjà en cache : pas d'appel réseau par caractère saisi.
    $('#servers').replaceChildren(renderServers(lastServers));
  });
}

/** Remet le champ en accord avec l'adresse, comme les recherches de Processus et Audit. */
export function syncFilters() {
  const query = params().get('q') || '';
  if ($('#tools-search').value !== query) $('#tools-search').value = query;
}
