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

function templateArguments(item) {
  return [...(item?.uriTemplate?.matchAll(/\{([^}]+)\}/g) || [])].map((match) => match[1]);
}

function renderTemplateFields() {
  const host = $('#mcp-template-fields');
  if (!host) return;
  const op = $('#mcp-playground-operation').value;
  const target = $('#mcp-playground-target').value;
  const item = catalog.templates.find((entry) => (entry.name || entry.uriTemplate) === target);
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
}

async function executePlayground(event) {
  event.preventDefault();
  const result = $('#mcp-playground-result');
  result.textContent = 'Exécution…';
  try {
    let args = JSON.parse($('#mcp-playground-arguments').value || '{}');
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
        $('#mcp-health-state').textContent = summary.state || '—';
        const host = $('#mcp-session-list');
        host.replaceChildren(...(summary.sessions || []).map((item) => {
          const row = el('div', 'mcp-session-row');
          row.append(el('strong', '', `${item.name}/${item.version}`),
            el('span', 'muted', `${item.callCount} appels · ${new Date(item.lastActivityAt).toLocaleString()}`));
          return row;
        }));
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
  $('#mcp-playground-target')?.addEventListener('change', renderTemplateFields);
  $('#mcp-playground-form')?.addEventListener('submit', executePlayground);
  $('#mcp-playground-copy')?.addEventListener('click', async () => {
    await navigator.clipboard.writeText($('#mcp-playground-result').textContent);
  });
}
