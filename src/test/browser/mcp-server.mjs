// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const BASE = process.env.KEX_AGENT_URL || 'http://localhost:8081';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.addInitScript((token) => sessionStorage.setItem('kex-agent-api-key', token), process.env.KEX_AGENT_API_KEY || 'ci-secret');
const problems = [];
async function check(name, fn) {
  try { await fn(); console.log('✓ ' + name); }
  catch (error) { problems.push('✗ ' + name + ': ' + error.message); }
}
await check('le serveur MCP expose ses onglets et exécute un tool dans le playground', async () => {
  let nextId = 0;
  await page.route('**/api/agent/mcp-server', async (route) => {
    const request = route.request();
    const rpc = JSON.parse(request.postData() || '{}');
    nextId = rpc.id || nextId + 1;
    const results = {
      initialize: { protocolVersion: '2025-06-18', serverInfo: { name: 'kex-agent-ai', version: 'test' }, capabilities: {} },
      'tools/list': { tools: [{ name: 'kex_status', description: 'Current status', inputSchema: { type: 'object' }, outputSchema: { type: 'object' } }] },
      'resources/list': { resources: [{ uri: 'kex://supervision/status', name: 'Status' }] },
      'resources/templates/list': { resourceTemplates: [{ uriTemplate: 'kex://supervision/processes/{processId}', name: 'Process' }] },
      'prompts/list': { prompts: [{ name: 'kex_supervision_triage', description: 'Triage' }] },
      'tools/call': { content: [{ type: 'text', text: 'ok' }], structuredContent: { state: 'OK' }, isError: false },
      'resources/read': { contents: [{ uri: rpc.params?.uri, text: '{"state":"OK"}' }] },
    };
    await route.fulfill({
      status: 200, contentType: 'application/json',
      headers: { 'Mcp-Session-Id': 'browser-test-session' },
      body: JSON.stringify({ jsonrpc: '2.0', id: nextId, result: results[rpc.method] || {} }),
    });
  });

  await page.goto(`${BASE}/#/settings`, { waitUntil: 'domcontentloaded' });
  const mcpSettings = page.locator('[data-settings-target="settings-mcp-server"]');
  await mcpSettings.evaluate((button) => button.click());
  await page.waitForTimeout(500);
  const diagnostics = await page.evaluate(() => ({ hash: location.hash, tools: document.querySelector('#mcp-tools-count')?.textContent, status: document.querySelector('#mcp-server-status-label')?.textContent, panelHidden: document.querySelector('#settings-mcp-server')?.hidden }));
  console.log('MCP UI diagnostics', JSON.stringify(diagnostics));
  await page.waitForFunction(() => document.querySelector('#mcp-tools-count')?.textContent === '1');
  assert.equal(await page.textContent('#mcp-server-status-label'), 'Opérationnel · lecture seule');
  assert.equal(await page.textContent('#mcp-resources-count'), '1');
  assert.equal(await page.textContent('#mcp-templates-count'), '1');
  assert.equal(await page.textContent('#mcp-prompts-count'), '1');

  await page.click('[data-mcp-tab="catalog"]');
  await page.waitForSelector('[data-mcp-panel="catalog"]:not([hidden]) .mcp-catalog-card');
  assert.match(await page.textContent('#mcp-catalog-list'), /kex_status/);

  await page.click('[data-mcp-tab="playground"]');
  await page.selectOption('#mcp-playground-operation', 'tool');
  await page.selectOption('#mcp-playground-target', 'kex_status');
  await page.fill('#mcp-playground-arguments', '{}');
  await page.click('#mcp-playground-form button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('#mcp-playground-structured')?.textContent.includes('"state": "OK"'));
  assert.match(await page.textContent('#mcp-playground-structured'), /"state": "OK"/);
  assert.match(await page.textContent('#mcp-playground-text'), /ok/);
  assert.match(await page.textContent('#mcp-playground-result'), /"structuredContent"/);
  assert.match(await page.textContent('#mcp-playground-result'), /"isError": false/);

  await page.selectOption('#mcp-playground-operation', 'template');
  await page.selectOption('#mcp-playground-target', 'Process');
  await page.fill('#mcp-playground-arguments', '{"processId":"order-integration"}');
  await page.click('#mcp-playground-form button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('#mcp-playground-result')?.textContent.includes('order-integration'));
  assert.match(await page.textContent('#mcp-playground-result'), /kex:\/\/supervision\/processes\/order-integration/);

  await page.unroute('**/api/agent/mcp-server');
});

await browser.close();
if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
