// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = resolve('src/main/resources/static');
const unsafe = '<img src=x onerror="window.hacked=true">';
const request = (id, status, text) => ({ id, title: ({ input: 'précision', result: 'résultat', free: 'réponse libre' })[id] || id, status, conversationId: 'c-' + id, updatedAt: '2026-10-04T10:00:00Z', turns: [{ role: 'user', text: id }, { role: 'agent', text, completed: status !== 'RUNNING', sources: id === 'result' ? [{ source: 'Mesure commandes ' + unsafe, observedAt: '2026-10-04T09:00:00Z', excerpt: 'Lag = 0' }] : [] }], tools: [] });
const initial = [request('traitement', 'RUNNING', ''), request('input', 'NEEDS_INPUT', JSON.stringify({ kind: 'clarification', question: 'Quelle période ?', choices: [{ label: 'Hier', value: 'Hier' }, { label: 'Ce matin', value: 'Ce matin' }] })), request('result', 'COMPLETE', JSON.stringify({ kind: 'result', observations: 'Aucun retard observé ' + unsafe, uncertainties: 'Mesure limitée à une période', nextAction: 'Vérifier la prochaine période' })), request('free', 'COMPLETE', 'Texte libre lisible')];
const plan = { id: 'p1', revision: 1, bindingFingerprint: 'exact', status: 'DRAFT', updatedAt: '2026-10-04T10:00:00Z', plan: { objective: 'Vérifier orders', preconditions: ['Accès aux mesures'], steps: [{ description: 'Lire orders', binding: 'read', arguments: { topic: 'orders', period: 'ce matin' } }] }, results: [{ status: 'PENDING' }] };
let historyFailure = false; let taskFailure = false; let posts = 0; let stream; let held; let heldStarted;
const server = createServer(async (req, res) => {
  const user = req.headers.authorization?.slice(7); const json = (v, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(v)); };
  if (req.method === 'POST') posts++;
  if (req.url === '/api/agent/whoami') return json({ name: user, tenant: 'team', roles: [user === 'alice' ? 'OPERATOR' : 'CHAT'] });
  if (req.url === '/api/agent/skills/available') return json([]);
  if (req.url === '/api/agent/workspace/requests') { if (held && user === 'alice') { heldStarted(); await held; } return json(user === 'alice' ? initial : [], historyFailure ? 503 : 200); }
  if (req.url.startsWith('/api/agent/workspace/requests/') && req.url !== '/api/agent/workspace/requests/stream') return json(initial.find(r => r.id === decodeURIComponent(req.url.split('/').at(-1))));
  if (req.url === '/api/agent/tasks') return json([plan], taskFailure ? 503 : 200);
  if (req.url === '/api/agent/tasks/bindings') return json({ read: { readOnly: true, connection: 'kafka', tool: 'query' } });
  if (req.url === '/api/agent/workspace/requests/stream') { for await (const chunk of req) { /* Drain the request. */ } stream = res; res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.write('event: request\ndata: {"id":"live-request"}\n\nevent: conversation\ndata: live-conversation\n\nevent: tool\ndata: {"tool":"get_health","failed":false}\n\n'); return; }
  if (req.url.startsWith('/api/')) return json({}, 404);
  try { const path = req.url.split('?')[0] === '/app' ? 'app/index.html' : req.url.split('?')[0].slice(1); if (path.includes('..')) throw new Error(); res.setHeader('Content-Type', path.endsWith('.js') ? 'text/javascript' : path.endsWith('.css') ? 'text/css' : 'text/html'); res.end(await readFile(resolve(root, path))); } catch { res.writeHead(404); res.end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done)); const browser = await chromium.launch();
const url = `http://127.0.0.1:${server.address().port}/app`;
async function login(page, user) { await page.locator('#account').click(); await page.locator('#access-key').fill(user); await page.locator('#connect').click(); await page.getByText('Connecté : ' + user, { exact: true }).waitFor(); }
try {
  for (const width of [1440, 390]) {
    historyFailure = taskFailure = false; posts = 0; stream = held = null;
    const context = await browser.newContext({ viewport: { width, height: 900 } }); const page = await context.newPage(); const errors = []; page.on('pageerror', e => errors.push(String(e)));
    await page.goto(url); await page.locator('#work').waitFor({ state: 'visible' }); await login(page, 'alice');
    await page.locator('#work-decisions').getByRole('link', { name: 'Vérifier orders', exact: true }).waitFor();
    assert.equal(await page.locator('#work-running a').count(), 1); assert.equal(await page.locator('#work-decisions a').count(), 2); assert.equal(await page.locator('#work-results a').count(), 2); assert.equal(posts, 0);
    await page.locator('#work-running a').click(); await page.locator('#activity-delay').waitFor({ state: 'visible' }); assert.match(await page.locator('#activity-delay').innerText(), /peut continuer/); assert.match(await page.locator('#last-activity').innerText(), /04\/10\/2026/);
    await page.getByRole('link', { name: 'Mon travail', exact: true }).click(); await page.locator('#work-results').getByRole('link', { name: 'résultat', exact: true }).click();
    const recap = page.locator('#completion-summary'); await recap.getByRole('heading', { name: 'Conclusion', exact: true }).waitFor();
    assert.match(await recap.innerText(), /Aucun retard observé/); assert.match(await recap.innerText(), /Mesure limitée/); assert.match(await recap.innerText(), /Vérifier la prochaine période/); await recap.locator('summary').click(); assert.match(await recap.innerText(), /Lag = 0/); assert.equal(await recap.locator('img').count(), 0);
    await recap.getByRole('button', { name: 'Marquer ce résultat comme examiné' }).click(); await page.getByRole('link', { name: 'Mon travail', exact: true }).click(); assert.match(await page.locator('#work-results').innerText(), /Résultat examiné/);
    await page.locator('#work-results').getByRole('link', { name: 'réponse libre', exact: true }).click(); await recap.getByText('Texte libre lisible', { exact: true }).waitFor(); assert.match(await recap.innerText(), /Texte libre lisible/); assert.match(await recap.innerText(), /Aucune source consultable/);
    let releaseRefresh; let refreshStarted; held = new Promise(r => { releaseRefresh = r; }); const refreshWaiting = new Promise(r => { refreshStarted = r; }); heldStarted = refreshStarted;
    await page.locator('#refresh-current').click(); await refreshWaiting;
    await page.getByRole('link', { name: 'Nouvelle demande', exact: true }).click(); await page.locator('#prompt').fill('Nouvelle consultation'); await page.locator('#send').click(); await page.locator('#run-activity').getByText('État du processus consulté', { exact: false }).waitFor(); assert.equal(await page.locator('#refresh-current').isDisabled(), true, 'la réception active ne doit pas être remplacée par une ancienne copie serveur');
    releaseRefresh(); held = null;
    await page.getByRole('link', { name: 'Mon travail', exact: true }).click(); await page.locator('#work-freshness').getByText('réception active, actualisation différée', { exact: false }).waitFor(); await page.locator('#work-running').getByRole('link', { name: 'Nouvelle consultation', exact: true }).waitFor(); assert.equal(posts, 1);
    stream.end('event: token\ndata: Réponse de la consultation\n\nevent: done\ndata: response-complete\n\n'); await page.locator('#work-results').getByRole('link', { name: 'Nouvelle consultation', exact: true }).waitFor();
    historyFailure = taskFailure = true; await page.locator('#refresh-work').click(); await page.locator('#work-freshness').getByText('indisponibles', { exact: false }).waitFor(); await page.waitForFunction(() => document.querySelector('#work-decisions').textContent.indexOf('Vérifier orders') < 0); assert.equal(posts, 1);
    historyFailure = taskFailure = false; let release; let started; held = new Promise(r => { release = r; }); const startedPromise = new Promise(r => { started = r; }); heldStarted = started;
    await page.locator('#refresh-work').click(); await startedPromise; await login(page, 'bob'); release(); held = null; await page.waitForLoadState('networkidle');
    assert.equal(await page.locator('#work a[href^="#/request/"]').count(), 0); assert.equal(await page.locator('#work a[href="#/approvals"]').count(), 0); assert.equal(posts, 1);
    assert.equal(await page.evaluate(() => window.hacked), undefined); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); assert.deepEqual(errors, []); await context.close();
  }
  console.log('✓ mon travail : états, sources, réponse libre, activité silencieuse, flux en cours, données indisponibles et isolation après réponse tardive sur ordinateur et mobile');
} finally { stream?.end(); await browser.close(); await new Promise(done => server.close(done)); }
