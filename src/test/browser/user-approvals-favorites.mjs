// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = resolve('src/main/resources/static');
const unsafe = '<img src=x onerror="window.hacked=true">';
let tasks, operations, chats, available, hold;
const skill = { id: 's1', title: 'Vérifier les commandes', markdown: 'Lire les commandes', verification: { parameters: { check: { topic: 'orders' } }, preconditions: ['Accès aux commandes'], checks: ['État documenté'] } };
const bindings = { read: { readOnly: true, connection: 'kafka', tool: 'query' }, change: { readOnly: false, connection: 'kafka', tool: 'restart' } };
function task(id, binding = 'read') {
  return { id, revision: 0, bindingFingerprint: 'exact-contract', status: 'DRAFT', plan: { objective: id === 't1' ? 'Examiner les commandes' : 'Redémarrer le processus', preconditions: ['Examiner la cible'], steps: [{ id: 'step1', description: 'Consulter la cible', binding, arguments: { topic: 'orders', context: unsafe }, expectation: null }] }, results: [{ status: 'PENDING' }], updatedAt: '2026-10-04T10:00:00Z' };
}
const server = createServer(async (req, res) => {
  const token = req.headers.authorization?.slice(7);
  const json = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
  if (req.url === '/api/agent/whoami') return json({ name: token, tenant: token, roles: [token === 'alpha' ? 'OPERATOR' : token === 'admin' ? 'ADMIN' : 'CHAT'] });
  if (req.url === '/api/agent/skills/available') return json(token === 'alpha' && available ? [skill] : []);
  if (req.url === '/api/agent/chat/stream') {
    let body = ''; for await (const chunk of req) body += chunk; chats.push(JSON.parse(body));
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.end('event: conversation\ndata: conv-' + chats.length + '\n\nevent: token\ndata: Réponse reçue pour cette demande\n\nevent: done\ndata: response-complete\n\n'); return;
  }
  if (req.url.startsWith('/api/agent/tasks')) {
    if (!['alpha', 'admin'].includes(token)) return json({ detail: 'Accès refusé' }, 403);
    if (req.url === '/api/agent/tasks/bindings') return json(bindings);
    if (req.url === '/api/agent/tasks' && req.method === 'GET') {
      if (hold) { const deferred = hold; hold = null; await deferred; }
      return json(tasks);
    }
    let body = ''; for await (const chunk of req) body += chunk;
    if (req.url === '/api/agent/tasks/plan') {
      operations.push({ verb: 'plan', body: JSON.parse(body) }); const next = task('t3'); next.plan.objective = JSON.parse(body).objective; tasks.push(next); return json(next, 201);
    }
    const [, id, verb] = req.url.match(/tasks\/([^/]+)(?:\/(\w+))?$/) || [];
    const current = tasks.find(t => t.id === id); if (!current) return json({}, 404);
    if (!verb) return json(current);
    operations.push({ id, verb });
    if (verb === 'approve') { if (current.plan.steps[0].binding === 'change' && token !== 'admin') return json({}, 403); current.status = 'APPROVED'; }
    if (verb === 'run') current.status = 'RUNNING';
    if (verb === 'cancel') current.status = 'CANCELLED';
    current.revision++; return json(current);
  }
  try {
    const name = req.url.split('?')[0] === '/app' ? 'app/index.html' : req.url.split('?')[0].slice(1);
    if (name.includes('..')) throw new Error();
    res.setHeader('Content-Type', name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html');
    res.end(await readFile(resolve(root, name)));
  } catch { res.writeHead(404); res.end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const browser = await chromium.launch();
try {
  for (const width of [1440, 390]) {
    tasks = [task('t1'), task('t2', 'change')]; operations = []; chats = []; available = true; hold = null;
    const context = await browser.newContext({ viewport: { width, height: 900 } }); const page = await context.newPage(); const errors = []; page.on('pageerror', e => errors.push(String(e)));
    const login = async token => { await page.locator('#account').click(); await page.locator('#access-key').fill(token); await page.locator('#connect').click(); await page.getByText('Connecté : ' + token, { exact: true }).waitFor(); };
    await page.goto(`http://127.0.0.1:${server.address().port}/app`); await login('alpha');
    await page.locator('#approvals-link').click(); const read = page.locator('#approval-list [data-task-id="t1"]'); const change = page.locator('#approval-list [data-task-id="t2"]');
    await read.getByRole('button', { name: 'Approuver ce plan', exact: true }).waitFor();
    assert.equal(await change.getByRole('button', { name: 'Approuver ce plan', exact: true }).isDisabled(), true, 'mutation approval requires ADMIN');
    await read.getByRole('button', { name: 'Approuver ce plan', exact: true }).click(); assert.equal(operations.length, 0);
    assert.match(await page.locator('#review-content').innerText(), /orders/); assert.equal(await page.locator('#review-content img').count(), 0);
    tasks[0].revision++; tasks[0].plan.objective = 'Examiner les commandes actualisées'; await page.locator('#review-accept').click(); await page.getByText('Le plan ou son état a changé', { exact: false }).waitFor(); assert.equal(operations.length, 0, 'stale plans must not be approved');
    await page.locator('#review-close').click(); await page.locator('#refresh-approvals').click(); await read.getByRole('heading', { name: 'Examiner les commandes actualisées', exact: true }).waitFor();
    await read.getByRole('button', { name: 'Approuver ce plan', exact: true }).click(); await page.locator('#review-accept').click();
    await read.getByRole('button', { name: 'Lancer le plan approuvé', exact: true }).waitFor();
    assert.deepEqual(operations, [{ id: 't1', verb: 'approve' }], 'approval must not run the task');
    await read.getByRole('button', { name: 'Lancer le plan approuvé', exact: true }).click(); await page.locator('#review-accept').click(); await read.getByText('Traitement en cours', { exact: true }).waitFor();
    assert.equal(operations.at(-1).verb, 'run');
    tasks[0].status = 'VERIFIED'; await page.locator('#refresh-approvals').click(); await read.getByText('Critère final confirmé', { exact: true }).waitFor();
    await change.getByRole('button', { name: 'Refuser ce plan', exact: true }).click(); await page.locator('#review-accept').click(); await change.getByText('Plan refusé ou annulé', { exact: true }).waitFor();
    await page.getByRole('link', { name: 'Actions prêtes à l’emploi', exact: true }).click();
    await page.locator('#skills').getByRole('button', { name: 'Vérifier les commandes', exact: false }).click();
    await page.locator('#action-subject').fill('Commandes'); await page.locator('#action-period').fill('Ce matin'); await page.locator('#skill-parameter-0').fill('orders-live'); await page.locator('#prepare').click();
    await page.locator('#action-dialog').waitFor({ state: 'hidden' }); await page.locator('#send').click(); await page.locator('#run-status').getByText('Réponse reçue', { exact: true }).waitFor();
    await page.locator('#favorite-request').click(); await page.locator('#reprepare-request').click(); await page.locator('#action-dialog').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#skill-parameter-0').inputValue(), 'orders-live'); assert.equal(await page.locator('#action-period').inputValue(), 'Ce matin'); assert.equal(chats.length, 1);
    await page.locator('#close-action').click(); await page.locator('#prepare-plan').click(); await page.locator('#plan-objective').fill('Vérifier les commandes depuis ce matin'); await page.locator('#plan-submit').click(); await page.locator('#plan-dialog').waitFor({ state: 'hidden' });
    await page.locator('#linked-plan [data-task-id="t3"]').waitFor(); assert.equal(operations.at(-1).verb, 'plan'); assert.equal(tasks.at(-1).status, 'DRAFT');
    await page.reload(); await page.getByText('Connecté : alpha', { exact: true }).waitFor(); await page.locator('#linked-plan [data-task-id="t3"]').waitFor();
    await page.getByRole('link', { name: 'Actions prêtes à l’emploi', exact: true }).click(); await page.locator('#favorites').getByRole('button', { name: 'Préparer', exact: true }).waitFor();
    available = false; await page.locator('#favorites').getByRole('button', { name: 'Préparer', exact: true }).click(); await page.locator('#notice').getByText('La compétence de cette demande a changé', { exact: false }).waitFor(); assert.equal(chats.length, 1);
    available = true; await page.locator('#favorites').getByRole('button', { name: 'Préparer', exact: true }).click(); await page.locator('#action-dialog').waitFor({ state: 'visible' }); await page.locator('#prepare').click(); await page.locator('#action-dialog').waitFor({ state: 'hidden' }); await page.locator('#send').click(); await page.locator('#run-status').getByText('Réponse reçue', { exact: true }).waitFor();
    assert.equal(chats.length, 2); assert.equal(chats[1].conversationId, null, 'relaunch creates a new conversation');
    await page.locator('#approvals-link').click(); await page.locator('#approvals').waitFor({ state: 'visible' }); await page.waitForFunction(() => document.querySelector('#approval-note').textContent === '');
    let release; hold = new Promise(resolve => { release = resolve; }); await page.locator('#refresh-approvals').click(); await page.locator('#approval-note').getByText('Actualisation des plans…', { exact: true }).waitFor();
    await login('beta'); release(); await page.getByRole('link', { name: 'Actions prêtes à l’emploi', exact: true }).click(); await page.locator('#favorites').getByText('Ajoutez une action', { exact: false }).waitFor();
    assert.equal(await page.locator('#approvals-link').isVisible(), false); assert.equal(await page.locator('#approval-list').innerText(), ''); assert.equal(await page.locator('#linked-plan').innerText(), '');
    assert.equal(await page.evaluate(() => window.hacked), undefined); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); assert.deepEqual(errors, []);
    await context.close();
  }
  console.log('✓ user approvals and favorites: exact confirmation, stale plan, role gates, separate launch, rejection, linked plan, persistent isolated favorites, checked parameters and new conversation');
} finally { await browser.close(); await new Promise(done => server.close(done)); }
