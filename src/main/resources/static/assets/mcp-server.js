// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

import { $, credentials, el, report } from './core.js';

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
  if (payload.error) throw new Error(payload.error.message || 'Erreur MCP');
  return payload.result;
}

async function initialize() {
  return rpc('initialize', {
    protocolVersion: PROTOCOL, capabilities: {},
    clientInfo: { name: 'kex-control-center', version: '1' },
  });
}

function setStatus(ok, label) {
  const node = $('#mcp-server-status');
  node.dataset.state = ok ? 'ok' : 'error';
  node.querySelector('.status-mark').textContent = ok ? '●' : '×';
  $('#mcp-server-status-label').textContent = label;
}

function card(item, kind) {
  const node = el('article', 'mcp-catalog-card');
  const title = el('strong', 'mcp-catalog-name', item.name || item.uri || item.uriTemplate || 'Sans nom');
  const badge = el('span', 'chip', kind === 'tools' ? 'READ ONLY' : kind.slice(0, -1).toUpperCase());
  const head = el('div', 'mcp-catalog-head'); head.append(title, badge);
  node.append(head);
  if (item.description) node.append(el('p', 'hint', item.description));
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

function playgroundTargets() {
  const op = $('#mcp-playground-operation').value;
  const target = $('#mcp-playground-target');
  const items = op === 'tool' ? catalog.tools : op === 'resource' ? catalog.resources : catalog.prompts;
  target.replaceChildren(...items.map((item) => {
    const option = document.createElement('option');
    option.value = item.name || item.uri;
    option.textContent = item.name || item.uri;
    return option;
  }));
}

async function executePlayground(event) {
  event.preventDefault();
  const result = $('#mcp-playground-result');
  result.textContent = 'Exécution…';
  try {
    const args = JSON.parse($('#mcp-playground-arguments').value || '{}');
    const op = $('#mcp-playground-operation').value;
    const target = $('#mcp-playground-target').value;
    let payload;
    if (op === 'tool') payload = await rpc('tools/call', { name: target, arguments: args });
    else if (op === 'resource') payload = await rpc('resources/read', { uri: target });
    else payload = await rpc('prompts/get', { name: target, arguments: args });
    result.textContent = JSON.stringify(payload, null, 2);
  } catch (error) {
    result.textContent = `Erreur : ${error.message}`;
    report(error);
  }
}

export async function view() {
  try {
    const init = await initialize();
    const [tools, resources, templates, prompts] = await Promise.all([
      rpc('tools/list'), rpc('resources/list'), rpc('resources/templates/list'), rpc('prompts/list'),
    ]);
    catalog = {
      tools: tools.tools || [], resources: resources.resources || [],
      templates: templates.resourceTemplates || [], prompts: prompts.prompts || [],
    };
    $('#mcp-tools-count').textContent = catalog.tools.length;
    $('#mcp-resources-count').textContent = catalog.resources.length;
    $('#mcp-templates-count').textContent = catalog.templates.length;
    $('#mcp-prompts-count').textContent = catalog.prompts.length;
    $('#mcp-protocol').textContent = init.protocolVersion || PROTOCOL;
    $('#mcp-server-version').textContent = `Kex MCP ${init.serverInfo?.version || '—'}`;
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
  $('#mcp-playground-form')?.addEventListener('submit', executePlayground);
}
