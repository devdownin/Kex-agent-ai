// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
let historyFailure = false;
const root = resolve('src/main/resources/static'); const records = new Map(); const posts = [];
const server = createServer(async (req, res) => {
  const user = req.headers.authorization?.slice(7); const rows = records.get(user) || [];
  const json = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
  if (req.url === '/api/agent/whoami') return json({ name: user, tenant: 'team', roles: ['CHAT'] });
  if (req.url === '/api/agent/skills/available') return json([{ id: 's1', title: 'Vérifier les commandes', description: 'Surveiller les commandes', markdown: 'Lire les commandes', verification: { checks: ['État documenté'], parameters: { topic: 'orders' } } }, { id: 's2', title: 'Préparer un bilan', markdown: 'Résumer les observations' }]);
  if (req.url === '/api/agent/workspace/requests' && req.method === 'GET') return historyFailure ? json({ detail: 'Indisponible' }, 503) : json(rows);
  if (req.url === '/api/agent/workspace/requests/stream') {
    let body = ''; for await (const chunk of req) body += chunk; const input = JSON.parse(body); posts.push(input);
    let request = rows.find(r => r.id === input.id);
    if (!request) { request = { id: crypto.randomUUID(), title: input.message, status: 'RUNNING', updatedAt: new Date().toISOString(), conversationId: 'conv-' + posts.length, turns: [], tools: [], context: input.context }; rows.unshift(request); records.set(user, rows); }
    const clarification = input.message === 'Clarifier'; const reply = clarification ? { kind: 'clarification', question: 'Quelle période ?', choices: [{ label: 'Hier', value: 'Hier' }, { label: 'Aujourd’hui', value: 'Depuis ce matin' }] } : { kind: 'result', observations: 'État consulté <img src=x onerror="window.hacked=true">', uncertainties: 'Objectif non vérifié', nextAction: 'Examiner le résultat' };
    request.turns.push({ role: 'user', text: input.message }, { role: 'agent', text: JSON.stringify(reply), completed: true, sources: [] }); request.status = clarification ? 'NEEDS_INPUT' : 'COMPLETE'; request.updatedAt = new Date().toISOString();
    res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.end(`event: request\ndata: ${JSON.stringify(request)}\n\nevent: conversation\ndata: ${request.conversationId}\n\nevent: token\ndata: ${JSON.stringify(reply)}\n\nevent: snapshot\ndata: ${JSON.stringify(request)}\n\nevent: done\ndata: response-complete\n\n`); return;
  }
  if (req.url.startsWith('/api/agent/workspace/requests/')) { const id = req.url.split('/').at(-1); const row = rows.find(r => r.id === id); return json(row || {}, row ? 200 : 404); }
  if (req.url.startsWith('/api/')) return json({}, 404);
  try { const path = req.url.split('?')[0] === '/app' ? 'app/index.html' : req.url.split('?')[0].slice(1); if (path.includes('..')) throw new Error(); res.setHeader('Content-Type', path.endsWith('.js') ? 'text/javascript' : path.endsWith('.css') ? 'text/css' : 'text/html'); res.end(await readFile(resolve(root, path))); } catch { res.writeHead(404); res.end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done)); const browser = await chromium.launch();
const url = `http://127.0.0.1:${server.address().port}/app`;
async function login(page, user) { await page.locator('#account').click(); await page.locator('#access-key').fill(user); await page.locator('#connect').click(); await page.getByText('Connecté : ' + user, { exact: true }).waitFor(); }
try {
  for (const width of [1440, 390]) {
    records.clear(); posts.length = 0; historyFailure = false;
    const context = await browser.newContext({ viewport: { width, height: 900 } }); const page = await context.newPage(); const errors = []; page.on('pageerror', error => errors.push(String(error)));
    await page.goto(url); await login(page, 'alice');
    await page.locator('#request-context summary').click(); await page.locator('#context-process').fill('orders'); await page.locator('#context-period').fill('hier'); await page.locator('#context-environment').fill('production');
    await page.locator('#context-files').setInputFiles({ name: 'orders.csv', mimeType: 'text/csv', buffer: Buffer.from('id,etat\n1,OK') }); await page.locator('#context-summary').getByText('1 fichier(s) joint(s)', { exact: false }).waitFor();
    assert.equal(posts.length, 0, 'préparer le contexte ne lance pas de traitement');
    await page.locator('#context-preview summary').click(); assert.match(await page.locator('#context-preview pre').innerText(), /1,OK/);
    await page.locator('#prompt').fill('Vérifier les commandes'); await page.locator('#send').click(); await page.locator('#run-status').getByText('Réponse reçue', { exact: true }).waitFor();
    assert.equal(posts[0].context.environment, 'production'); assert.equal(posts[0].context.files[0].name, 'orders.csv');
    assert.match(await page.locator('#completion-summary').innerText(), /ne prouve pas/); assert.equal(await page.locator('#completion-summary img').count(), 0);
    await page.getByRole('link', { name: /^À suivre/ }).click(); await page.locator('#attention-list').getByRole('link', { name: 'Résultat à examiner' }).click();
    await page.locator('#completion-summary').getByRole('button', { name: 'Marquer ce résultat comme examiné' }).click(); await page.getByRole('link', { name: /^À suivre/ }).click(); await page.locator('#refresh-attention').click(); await page.locator('#attention-list').getByText('Aucune intervention', { exact: false }).waitFor();
    await page.getByRole('link', { name: 'Actions prêtes à l’emploi', exact: true }).click(); await page.locator('#skills .action-card').first().waitFor();
    await page.locator('#skill-category').selectOption('report'); assert.match(await page.locator('#skills').innerText(), /bilan/); assert.ok(!(await page.locator('#skills').innerText()).includes('Vérifier les commandes'));
    await page.locator('#skill-search').fill('introuvable'); await page.locator('#skills').getByText('Aucune compétence ne correspond', { exact: false }).waitFor();
    const secondContext = await browser.newContext({ viewport: { width, height: 900 } }); const other = await secondContext.newPage(); await other.goto(url + '#/requests'); await login(other, 'alice');
    await other.locator('#request-list').getByRole('button', { name: 'Consulter' }).click(); await other.locator('#saved-context summary').click(); await other.locator('#saved-context-content').getByText('orders.csv', { exact: false }).waitFor();
    await other.locator('#followup').fill('Clarifier'); await other.locator('#followup-send').click(); await other.locator('#run-status').getByText('Votre réponse est nécessaire', { exact: true }).waitFor();
    await other.getByRole('link', { name: /^À suivre/ }).click(); await other.locator('#attention-list').getByRole('link', { name: 'Précision nécessaire' }).waitFor(); assert.equal(posts.length, 2);
    historyFailure = true; await other.reload(); await other.getByText('Connecté : alice', { exact: true }).waitFor();
    await other.getByRole('link', { name: 'Mes demandes', exact: true }).click(); await other.locator('#request-list').getByRole('button', { name: 'Consulter' }).click();
    await other.locator('#followup').fill('Poursuivre'); await other.locator('#followup-send').click(); await other.locator('#run-status').getByText('Réponse reçue', { exact: true }).waitFor();
    assert.equal(posts.length, 3, 'une demande serveur conserve son flux protégé lorsque la lecture de l’historique échoue'); historyFailure = false;
    await login(other, 'bob'); await other.getByRole('link', { name: 'Mes demandes', exact: true }).click(); await other.locator('#request-list').getByText('Aucune demande', { exact: false }).waitFor(); assert.equal(await other.locator('#saved-context-content').textContent(), '');
    assert.equal(await page.evaluate(() => window.hacked), undefined); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); assert.deepEqual(errors, []);
    await context.close(); await secondContext.close();
  }
  console.log('✓ contexte et historique : aperçu sans exécution, pièces jointes, bilan, interventions, recherche, reprise autre appareil et isolation du compte');
} finally { await browser.close(); await new Promise(done => server.close(done)); }
