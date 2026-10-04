// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = resolve('src/main/resources/static'); let available = true; let posts = 0; let held = null;
const skills = [
  { id: 'read', title: 'Vérifier les commandes', markdown: 'Lire les commandes', verification: { parameters: { topic: 'orders' } } },
  { id: 'report', title: 'Préparer un bilan <img src=x onerror="window.hacked=true">', markdown: 'Résumer les observations' },
  { id: 'incident', title: 'Comprendre une anomalie', markdown: 'Examiner les faits' },
  { id: 'other', title: 'Autre compétence', markdown: 'Autre procédure' },
];
const server = createServer(async (req, res) => {
  const user = req.headers.authorization?.slice(7);
  const json = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
  if (req.method === 'POST') posts++;
  if (req.url === '/api/agent/whoami') return json({ name: user, tenant: 'team', roles: [user === 'alpha' ? 'OPERATOR' : 'CHAT'] });
  if (req.url === '/api/agent/skills/available') { if (user === 'alpha' && held) { const wait = held; held = null; await wait; } return json(user === 'alpha' && available ? skills : []); }
  if (req.url.startsWith('/api/')) return json({}, 404);
  try { const path = req.url === '/app' ? 'app/index.html' : req.url.slice(1).split('?')[0]; if (path.includes('..')) throw new Error(); res.setHeader('Content-Type', path.endsWith('.js') ? 'text/javascript' : path.endsWith('.css') ? 'text/css' : 'text/html'); res.end(await readFile(resolve(root, path))); } catch { res.writeHead(404); res.end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done)); const browser = await chromium.launch();
const url = `http://127.0.0.1:${server.address().port}/app`;
async function login(page, user) { await page.locator('#account').click(); await page.locator('#access-key').fill(user); await page.locator('#connect').click(); await page.getByText('Connecté : ' + user, { exact: true }).waitFor(); }
try {
  for (const width of [1440, 390]) {
    available = true; posts = 0; held = null;
    const context = await browser.newContext({ viewport: { width, height: 900 } }); const page = await context.newPage(); const errors = []; page.on('pageerror', e => errors.push(String(e)));
    await page.goto(url); await page.locator('#onboarding-note').getByText('Connectez-vous', { exact: false }).waitFor(); assert.equal(await page.locator('#onboarding-examples a').count(), 0);
    await login(page, 'alpha'); await page.locator('#onboarding-examples a').nth(2).waitFor();
    assert.equal(await page.locator('#onboarding-examples a').count(), 3); assert.match(await page.locator('#onboarding-examples').innerText(), /Vérifier les commandes/); assert.ok(!(await page.locator('#onboarding-examples').innerText()).includes('Autre compétence'));
    assert.match(await page.locator('#onboarding-approval').innerText(), /décision séparée/); assert.equal(await page.locator('#onboarding img').count(), 0);
    await page.locator('#onboarding-examples a').first().click(); await page.locator('#action-dialog').waitFor({ state: 'visible' }); assert.equal(await page.locator('#action-title').textContent(), 'Vérifier les commandes'); assert.equal(posts, 0, 'un exemple prépare sans exécuter'); await page.locator('#close-action').click();
    available = false; await page.locator('#onboarding-examples a').first().click(); await page.locator('#notice').getByText('Cette compétence n’est plus disponible', { exact: false }).waitFor(); assert.equal(await page.locator('#action-dialog').isVisible(), false); assert.equal(posts, 0);
    await page.locator('#dismiss-onboarding').click(); assert.equal(await page.locator('#onboarding').isVisible(), false); await page.reload(); await page.getByText('Connecté : alpha', { exact: true }).waitFor(); assert.equal(await page.locator('#onboarding').isVisible(), false);
    await page.locator('#open-onboarding').click(); assert.equal(await page.evaluate(() => document.activeElement.id), 'onboarding-title'); await page.locator('#onboarding-note').getByText('Aucune compétence', { exact: false }).waitFor(); assert.equal(await page.locator('#onboarding-examples a').count(), 3);
    available = true; let release; held = new Promise(resolve => { release = resolve; });
    await page.getByRole('link', { name: 'Actions prêtes à l’emploi', exact: true }).click(); await page.locator('#refresh-skills').click();
    await login(page, 'beta'); release(); await page.getByRole('link', { name: 'Nouvelle demande', exact: true }).click(); await page.waitForLoadState('networkidle');
    assert.equal(await page.locator('#onboarding').isVisible(), true, 'un autre compte retrouve son premier usage'); assert.match(await page.locator('#onboarding-approval').innerText(), /personne autorisée/); assert.ok(!(await page.locator('#onboarding-examples').innerText()).includes('bilan <img'));
    await page.locator('#onboarding-examples a').first().click(); await page.locator('#action-dialog').waitFor({ state: 'visible' }); assert.equal(await page.locator('#skill-details').isVisible(), false, 'repli vers une demande guidée'); await page.locator('#close-action').click();
    assert.equal(posts, 0); assert.equal(await page.evaluate(() => window.hacked), undefined); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); assert.deepEqual(errors, []);
    await context.close();
  }
  console.log('✓ premier usage : trois exemples actuels, préparation sans exécution, retrait de compétence, guide mémorisé et rouvert, compte isolé, réponse tardive ignorée et mobile');
} finally { await browser.close(); await new Promise(done => server.close(done)); }
