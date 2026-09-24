// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

import { $, credentials, el, empty, freshnessStamp, openDrawer, report } from './core.js';

const ENDPOINT = '/api/agent/mcp-server';
const PROTOCOL = '2025-06-18';
let id = 0;
let session = null;
let catalog = { tools: [], resources: [], templates: [], prompts: [] };

async function rpc(method, params = {}) {
  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
    'MCP-Protocol-Version': PROTOCOL,
  };
  const token = credentials.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (session) headers['Mcp-Session-Id'] = session;
  const response = await fetch(ENDPOINT, {
    method: 'POST', headers,
    body: JSON.stringify({ jsonrpc: '2.0', id: ++id, method, params }),
  });
  session = response.headers.get('Mcp-Session-Id') || session;
  if (!response.ok) throw new Error(`MCP HTTP ${response.status}`);
  const payload = await response.json();
  if (payload.error) {
    const error = new Error(payload.error.message || 'Erreur MCP');
    error.rpc = payload;
    error.status = response.status;
    throw error;
  }
  return { result: payload.result, envelope: payload, status: response.status };
}

async function initialize() {
  return (await rpc('initialize', {
    protocolVersion: PROTOCOL, capabilities: {},
    clientInfo: { name: 'kex-control-center', version: '1' },
  })).result;
}

function setStatus(ok, label) {
  const node = $('#mcp-server-status');
  node.dataset.state = ok ? 'ok' : 'error';
  node.querySelector('.status-mark').textContent = ok ? '●' : '×';
  $('#mcp-server-status-label').textContent = label;
}

function openCatalogDetails(item, kind) {
  const body = el('div', 'stack');
  body.append(el('span', 'panel-kicker', kind === 'tools' ? 'Outil MCP' : kind.slice(0, -1)));
  body.append(el('h3', null, item.name || item.uri || item.uriTemplate || 'Sans nom'));
  if (item.description) body.append(el('p', null, item.description));
  const identity = item.uri || item.uriTemplate;
  if (identity) body.append(el('code', 'technical-id', identity));
  const schema = item.inputSchema || item.outputSchema;
  if (schema) {
    body.append(el('h4', null, 'Contrat JSON'));
    const pre = el('pre', 'dump');
    pre.textContent = JSON.stringify(schema, null, 2);
    body.append(pre);
  }
  openDrawer(item.name || item.uri || item.uriTemplate || 'Détail MCP', body);
}

function card(item, kind) {
  const node = el('article', 'mcp-catalog-card');
  const title = el('strong', 'mcp-catalog-name', item.name || item.uri || item.uriTemplate || 'Sans nom');
  const labels = { tools: 'LECTURE SEULE', resources: 'RESSOURCE', templates: 'MODÈLE', prompts: 'PROMPT' };
  const badge = el('span', 'chip', labels[kind] || kind.toUpperCase());
  const head = el('div', 'mcp-catalog-head'); head.append(title, badge);
  node.append(head);
  if (item.description) node.append(el('p', 'hint', item.description));
  const detail = el('button', 'ghost compact', 'Détails');
  detail.type = 'button';
  detail.addEventListener('click', () => openCatalogDetails(item, kind));
  node.append(detail);
  const schema = item.inputSchema || item.outputSchema;
  if (schema) {
    const details = document.createElement('details');
    details.innerHTML = '<summary>Contrat JSON</summary>';
    const pre = document.createElement('pre'); pre.textContent = JSON.stringify(schema, null, 2);
    details.append(pre); node.append(details);
  }
  return node;
}

function renderCatalog(kind = $('#mcp-catalog-kind').value) {
  const host = $('#mcp-catalog-list');
  host.replaceChildren(...(catalog[kind] || []).map((item) => card(item, kind)));
}

function templateArguments(item) {
  return [...(item?.uriTemplate?.matchAll(/\{([^}]+)\}/g) || [])].map((match) => match[1]);
}

function selectedCatalogItem() {
  const op = $('#mcp-playground-operation').value;
  const target = $('#mcp-playground-target').value;
  const items = op === 'tool' ? catalog.tools : op === 'resource' ? catalog.resources
    : op === 'template' ? catalog.templates : catalog.prompts;
  return items.find((entry) => (entry.name || entry.uri || entry.uriTemplate) === target);
}

function renderTemplateFields() {
  const host = $('#mcp-template-fields');
  if (!host) return;
  const op = $('#mcp-playground-operation').value;
  const item = selectedCatalogItem();
  const names = op === 'template' ? templateArguments(item) : [];
  host.hidden = names.length === 0;
  host.replaceChildren(...names.map((name) => {
    const label = document.createElement('label');
    label.textContent = name;
    const input = document.createElement('input');
    input.name = name; input.required = true; input.placeholder = name;
    label.append(input); return label;
  }));
}

function schemaInput(name, schema, required) {
  const label = document.createElement('label');
  label.className = 'mcp-schema-field';
  const title = document.createElement('span');
  title.textContent = required ? name + ' *' : name;
  label.append(title);

  let input;
  if (Array.isArray(schema.enum)) {
    input = document.createElement('select');
    if (!required) {
      const empty = document.createElement('option');
      empty.value = ''; empty.textContent = 'Non renseigné';
      input.append(empty);
    }
    schema.enum.forEach((value) => {
      const option = document.createElement('option');
      option.value = String(value); option.textContent = String(value);
      input.append(option);
    });
  } else if (schema.type === 'boolean') {
    input = document.createElement('select');
    const empty = document.createElement('option');
    empty.value = ''; empty.textContent = required ? 'Choisir…' : 'Non renseigné';
    input.append(empty);
    [['true', 'Oui'], ['false', 'Non']].forEach(([value, text]) => {
      const option = document.createElement('option'); option.value = value; option.textContent = text; input.append(option);
    });
  } else if (['object', 'array'].includes(schema.type)) {
    input = document.createElement('textarea');
    input.rows = 4;
    input.spellcheck = false;
    input.placeholder = schema.type === 'array' ? '[]' : '{}';
  } else {
    input = document.createElement('input');
    input.type = ['integer', 'number'].includes(schema.type) ? 'number' : 'text';
    if (schema.type === 'integer') input.step = '1';
    if (schema.minimum != null) input.min = String(schema.minimum);
    if (schema.maximum != null) input.max = String(schema.maximum);
  }
  input.dataset.schemaName = name;
  input.dataset.schemaType = schema.type || 'string';
  input.required = required;
  if (schema.description) {
    input.setAttribute('aria-describedby', 'mcp-schema-help-' + name);
    const help = document.createElement('small');
    help.id = 'mcp-schema-help-' + name; help.className = 'hint'; help.textContent = schema.description;
    label.append(input, help);
  } else label.append(input);
  return label;
}

function renderSchemaFields() {
  const host = $('#mcp-schema-fields');
  if (!host) return;
  const op = $('#mcp-playground-operation').value;
  const item = selectedCatalogItem();
  const schema = op === 'tool' ? item?.inputSchema : null;
  const properties = schema?.properties || {};
  const required = new Set(schema?.required || []);
  const fields = Object.entries(properties).map(([name, definition]) =>
    schemaInput(name, definition || {}, required.has(name)));
  host.hidden = fields.length === 0;
  host.replaceChildren(...fields);
}

function renderPlaygroundFields() {
  renderTemplateFields();
  renderSchemaFields();
}

function schemaArguments() {
  const values = {};
  document.querySelectorAll('#mcp-schema-fields [data-schema-name]').forEach((input) => {
    if (input.value === '') return;
    const type = input.dataset.schemaType;
    let value = input.value;
    if (type === 'boolean') value = value === 'true';
    else if (type === 'integer') value = Number.parseInt(value, 10);
    else if (type === 'number') value = Number.parseFloat(value);
    else if (type === 'object' || type === 'array') value = JSON.parse(value);
    values[input.dataset.schemaName] = value;
  });
  return values;
}

function playgroundTargets() {
  const op = $('#mcp-playground-operation').value;
  const target = $('#mcp-playground-target');
  const items = op === 'tool' ? catalog.tools : op === 'resource' ? catalog.resources : op === 'template' ? catalog.templates : catalog.prompts;
  target.replaceChildren(...items.map((item) => {
    const option = document.createElement('option');
    option.value = item.name || item.uri || item.uriTemplate;
    option.textContent = item.name || item.uri || item.uriTemplate;
    return option;
  }));
  renderPlaygroundFields();
}

async function executePlayground(event) {
  event.preventDefault();
  const result = $('#mcp-playground-result');
  result.textContent = 'Exécution…';
  try {
    let args = JSON.parse($('#mcp-playground-arguments').value || '{}');
    if ($('#mcp-playground-operation').value === 'tool') {
      args = { ...args, ...schemaArguments() };
    }
    if ($('#mcp-playground-operation').value === 'template') {
      args = Object.fromEntries([...document.querySelectorAll('#mcp-template-fields input')].map((input) => [input.name, input.value]));
    }
    const op = $('#mcp-playground-operation').value;
    const target = $('#mcp-playground-target').value;
    let payload;
    if (op === 'tool') payload = await rpc('tools/call', { name: target, arguments: args });
    else if (op === 'resource') payload = await rpc('resources/read', { uri: target });
    else if (op === 'template') {
      const template = catalog.templates.find((item) => (item.name || item.uriTemplate) === target);
      let uri = template?.uriTemplate || target;
      for (const [key, value] of Object.entries(args)) uri = uri.replaceAll(`{${key}}`, encodeURIComponent(value));
      if (/\{[^}]+\}/.test(uri)) throw new Error('Renseignez tous les paramètres du template');
      payload = await rpc('resources/read', { uri });
    } else payload = await rpc('prompts/get', { name: target, arguments: args });
    const value = payload.result || {};
    $('#mcp-playground-structured').textContent = JSON.stringify(value.structuredContent ?? value, null, 2);
    $('#mcp-playground-text').textContent = (value.content || []).filter((item) => item.type === 'text').map((item) => item.text).join('\n') || '—';
    result.textContent = JSON.stringify(payload.envelope, null, 2);
  } catch (error) {
    $('#mcp-playground-structured').textContent = '—';
    $('#mcp-playground-text').textContent = error.message;
    result.textContent = JSON.stringify(error.rpc || { error: error.message, httpStatus: error.status }, null, 2);
    report(error);
  }
}

function openSessionDetails(item) {
  const body = el('div', 'stack');
  const list = el('dl', 'definition-list');
  const add = (label, value) => {
    const dt = document.createElement('dt'); dt.textContent = label;
    const dd = document.createElement('dd'); dd.textContent = value ?? '—';
    list.append(dt, dd);
  };
  add('Client', `${item.name}/${item.version}`);
  add('État', item.state === 'IDLE' ? 'Inactif' : 'Actif');
  add('Protocole', item.protocol);
  add('Créée le', item.createdAt ? new Date(item.createdAt).toLocaleString() : '—');
  add('Dernière activité', item.lastActivityAt ? new Date(item.lastActivityAt).toLocaleString() : '—');
  add('Appels', String(item.callCount ?? 0));
  add('Identifiant de session', item.id);
  body.append(list, freshnessStamp(item.lastActivityAt, 5 * 60 * 1000, 'Activité'));
  openDrawer('Session MCP', body);
}

export async function view() {
  try {
    const init = await initialize();
    const [tools, resources, templates, prompts] = await Promise.all([
      rpc('tools/list'), rpc('resources/list'), rpc('resources/templates/list'), rpc('prompts/list'),
    ]);
    catalog = {
      tools: tools.result.tools || [], resources: resources.result.resources || [],
      templates: templates.result.resourceTemplates || [], prompts: prompts.result.prompts || [],
    };
    $('#mcp-tools-count').textContent = catalog.tools.length;
    $('#mcp-resources-count').textContent = catalog.resources.length;
    $('#mcp-templates-count').textContent = catalog.templates.length;
    $('#mcp-prompts-count').textContent = catalog.prompts.length;
    $('#mcp-protocol').textContent = init.protocolVersion || PROTOCOL;
    $('#mcp-server-version').textContent = `Kex MCP ${init.serverInfo?.version || '—'}`;
    try {
      const headers = { 'X-Kex-Mcp-View': 'summary' };
      const token = credentials.get(); if (token) headers.Authorization = `Bearer ${token}`;
      const summary = await fetch(ENDPOINT, { headers }).then((response) => response.ok ? response.json() : null);
      if (summary) {
        $('#mcp-active-sessions').textContent = summary.activeSessions ?? '0';
        const healthLabels = { UP: 'Opérationnel', DEGRADED: 'Dégradé', DOWN: 'Indisponible' };
        $('#mcp-health-state').textContent = healthLabels[summary.state] || summary.state || '—';
        const healthMetric = $('#mcp-health-state').parentElement;
        healthMetric.querySelector('.freshness-tag')?.remove();
        healthMetric.append(freshnessStamp(new Date().toISOString(), 60_000, 'Vérifié'));
        const host = $('#mcp-session-list');
        const sessions = summary.sessions || [];
        if (!sessions.length) {
          host.replaceChildren(empty('Aucun client MCP connecté.',
            'Le serveur Kex est disponible mais aucune session cliente n’est actuellement observée.',
            { href: '#/settings', label: 'Voir la configuration MCP' }));
        } else {
          const table = el('table', 'mcp-session-table');
          const head = document.createElement('thead');
          const header = document.createElement('tr');
          ['Client', 'État', 'Activité', 'Appels', 'Protocole', ''].forEach((label) => header.append(el('th', null, label)));
          head.append(header);
          const body = document.createElement('tbody');
          sessions.forEach((item) => {
            const row = document.createElement('tr');
            row.className = 'clickable-row';
            row.tabIndex = 0;
            row.setAttribute('aria-label', `Détails de la session ${item.name}/${item.version}`);
            row.addEventListener('click', () => openSessionDetails(item));
            row.addEventListener('keydown', (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openSessionDetails(item);
              }
            });
            const client = el('td', null, `${item.name}/${item.version}`);
            const state = el('td', null, item.state === 'IDLE' ? '○ Inactif' : '● Actif');
            state.dataset.state = item.state === 'IDLE' ? 'idle' : 'active';
            const activity = document.createElement('td');
            activity.append(freshnessStamp(item.lastActivityAt, 5 * 60 * 1000, 'Activité'));
            const calls = el('td', 'technical-id', String(item.callCount ?? 0));
            const protocol = el('td', 'technical-id', item.protocol || '—');
            const actionCell = document.createElement('td');
            const details = el('button', 'ghost compact', 'Détails');
            details.type = 'button';
            details.addEventListener('click', (event) => {
              event.stopPropagation();
              openSessionDetails(item);
            });
            actionCell.append(details);
            row.append(client, state, activity, calls, protocol, actionCell);
            body.append(row);
          });
          table.append(head, body);
          host.replaceChildren(table);
        }
      }
    } catch (error) { report(error); }
    setStatus(true, 'Opérationnel · lecture seule');
    renderCatalog();
    playgroundTargets();
  } catch (error) {
    setStatus(false, 'Indisponible');
    report(error);
  }
}

export function bind() {
  document.querySelectorAll('[data-mcp-tab]').forEach((button) => button.addEventListener('click', () => {
    document.querySelectorAll('[data-mcp-tab]').forEach((item) => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-mcp-panel]').forEach((panel) => {
      panel.hidden = panel.dataset.mcpPanel !== button.dataset.mcpTab;
    });
  }));
  $('#mcp-catalog-kind')?.addEventListener('change', () => renderCatalog());
  $('#mcp-playground-operation')?.addEventListener('change', playgroundTargets);
  $('#mcp-playground-target')?.addEventListener('change', renderPlaygroundFields);
  $('#mcp-playground-form')?.addEventListener('submit', executePlayground);
  $('#mcp-playground-copy')?.addEventListener('click', async () => {
    await navigator.clipboard.writeText($('#mcp-playground-result').textContent);
  });
}
