// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = resolve('src/main/resources/static');
let disabled = false;
let operations = [];
const task = { id: 'task-one', status: 'DRAFT', updatedAt: new Date().toISOString(), detail: 'Plan à examiner',
  plan: { objective: 'Vérifier commandes <img src=x onerror="window.hacked=true">', preconditions: ['Examiner prod'],
    steps: [{ id: 'read', description: 'Mesurer', binding: 'lag', arguments: { topic: 'orders' }, dependsOn: [], expectation: { pointer: '/healthy', expected: true } }] },
  results: [{ id: 'read', status: 'PENDING', output: null }] };
const html = `<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/assets/console.css"><main style="margin:0;padding:16px;min-width:0">
<h1>Tâches</h1><button id="refresh-tasks">Actualiser</button><p id="task-note" role="status"></p>
<form id="task-plan-form"><label for="task-objective">Objectif</label><textarea id="task-objective" required></textarea><button id="task-plan-submit">Préparer un plan</button></form>
<div id="task-list"></div><section id="task-detail"></section></main>
<dialog id="confirm"><form method="dialog"><h2 id="confirm-title"></h2><div id="confirm-body"></div><button value="cancel" formnovalidate>Annuler</button><button id="confirm-accept" value="confirm">Confirmer</button></form></dialog><div id="toasts"></div>
<script type="module">import * as tasks from '/assets/tasks.js';import {credentials} from '/assets/core.js';location.hash='#/tasks';window.tasks=tasks;window.credentials=credentials;tasks.wire();tasks.view();</script>`;
const server = createServer(async (req, res) => {
  if (req.url.startsWith('/api/agent/tasks')) {
    res.setHeader('Content-Type', 'application/json');
    if (disabled) { res.writeHead(404); res.end('{"detail":"disabled"}'); return; }
    if (req.method === 'GET') { res.end(JSON.stringify([task])); return; }
    operations.push(req.url.split('/').at(-1));
    if (req.url.endsWith('/plan')) { task.status = 'DRAFT'; task.results[0].status = 'PENDING'; }
    if (req.url.endsWith('/approve')) task.status = 'APPROVED';
    if (req.url.endsWith('/run')) { task.status = 'VERIFIED'; task.results[0] = { id: 'read', status: 'VERIFIED',
      output: '{"healthy":true}', evidenceHash: 'sha256-proof', observedAt: new Date().toISOString(), detail: 'Postcondition vérifiée' }; }
    res.end(JSON.stringify(task)); return;
  }
  if (req.url === '/') { res.setHeader('Content-Type', 'text/html'); res.end(html); return; }
  try {
    if (!/^\/assets\/[a-z.-]+$/.test(req.url)) throw new Error();
    res.setHeader('Content-Type', req.url.endsWith('.js') ? 'text/javascript' : 'text/css');
    res.end(await readFile(resolve(root, '.' + req.url)));
  } catch { res.writeHead(404); res.end(); }
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const errors = []; page.on('pageerror', e => errors.push(String(e)));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.locator('[data-task-id="task-one"]').click();
  assert.equal(await page.locator('#task-detail img').count(), 0);
  await page.getByRole('button', { name: 'Approuver ce plan' }).click();
  assert.equal(operations.length, 0, 'opening approval must not approve or execute');
  await page.locator('#confirm-accept').click();
  await page.getByRole('button', { name: 'Lancer ou reprendre' }).waitFor();
  assert.deepEqual(operations, ['approve'], 'approval must not start execution');
  await page.getByRole('button', { name: 'Lancer ou reprendre' }).click();
  await page.locator('#confirm-accept').click();
  await page.getByText('Critère final confirmé', { exact: false }).first().waitFor();
  assert.deepEqual(operations, ['approve', 'run']);
  await page.getByText('Preuve ·', { exact: false }).click();
  assert.match(await page.locator('#task-detail').innerText(), /sha256-proof/);
  assert.equal(await page.evaluate(() => window.hacked), undefined);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'mobile layout has no horizontal overflow');
  await page.evaluate(() => window.credentials.set('another-tenant'));
  assert.equal(await page.locator('#task-detail').innerText(), '', 'credential switch clears task proof');
  disabled = true; await page.getByRole('button', { name: 'Actualiser' }).click();
  await page.getByText('Les tâches planifiées sont désactivées', { exact: false }).waitFor();
  assert.equal(await page.locator('#task-plan-form').isVisible(), false);
  assert.deepEqual(errors, []);
  console.log('✓ plans, approval separated from execution, evidence, XSS, mobile and credential isolation');
} finally { await browser.close(); await new Promise(done => server.close(done)); }
