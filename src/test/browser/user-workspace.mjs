// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = resolve('src/main/resources/static');
let mode = 'success'; let approved = true; let posts = [];
const unsafe = '<img src=x onerror="window.hacked=true">';
const server = createServer(async (req, res) => {
  const token = req.headers.authorization;
  if (req.url === '/api/agent/whoami') {
    res.setHeader('Content-Type', 'application/json');
    if (!['Bearer alpha', 'Bearer beta'].includes(token)) { res.writeHead(401); res.end('{}'); return; }
    res.end(JSON.stringify({ name: token.slice(7), tenant: token.slice(7), roles: ['CHAT'] })); return;
  }
  if (req.url === '/api/agent/skills/available') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(token === 'Bearer alpha' && approved ? [{ id: 'skill-1', title: 'Procédure commandes', markdown: '# Lire les faits ' + unsafe }] : [])); return;
  }
  if (req.url === '/api/agent/chat/stream') {
    let body = ''; for await (const chunk of req) body += chunk; posts.push(JSON.parse(body));
    res.setHeader('Content-Type', 'text/event-stream');
    res.write('event: conversation\ndata: conv-1\n\n');
    if (mode === 'hold') return;
    res.write('event: tool\ndata: {"tool":"kex_process_health","durationMillis":9,"failed":false}\n\n');
    res.write('event: sources\ndata: [{"source":"runbook","observedAt":"2026-10-04T08:00:00Z","excerpt":"preuve"}]\n\n');
    res.write('event: token\ndata: Observation ' + unsafe + '\n\n');
    if (mode === 'error') res.write('event: error\ndata: Service indisponible\n\n');
    if (mode === 'success') res.write('event: done\ndata: response-complete\n\n');
    res.end(); return;
  }
  const path = req.url.split('?')[0];
  if (path === '/') {
    res.setHeader('Content-Type', 'text/html');
    res.end(`<meta charset="utf-8"><span id="conversation-id"></span><button id="clear-conversation">Effacer</button><ul id="transcript"></ul><ul id="tool-log"></ul><p id="tool-log-empty"></p><div id="toasts"></div><script type="module">import {prefill} from '/assets/chat.js';await prefill();</script>`);
    return;
  }
  try {
    const filename = path === '/app' ? 'app/index.html' : path.slice(1);
    if (filename.includes('..')) throw new Error();
    res.setHeader('Content-Type', filename.endsWith('.js') ? 'text/javascript' : filename.endsWith('.css') ? 'text/css' : filename.endsWith('.png') ? 'image/png' : 'text/html');
    res.end(await readFile(resolve(root, filename)));
  } catch { res.writeHead(404); res.end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const browser = await chromium.launch();
try {
  for (const width of [1440, 768, 390, 320]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } });
    const page = await context.newPage(); const errors = []; page.on('pageerror', e => errors.push(String(e)));
    await page.goto(`http://127.0.0.1:${server.address().port}/app`);
    assert.equal(await page.locator('#send').isDisabled(), true);
    await page.locator('#account').click(); await page.locator('#access-key').fill('alpha'); await page.locator('#connect').click();
    await page.getByText('Connecté : alpha').waitFor();
    await page.getByRole('link', { name: 'Actions prêtes à l’emploi', exact: true }).click();
    await page.getByRole('button', { name: /Procédure commandes/ }).click();
    await page.locator('#action-subject').fill('commandes'); approved = false;
    await page.locator('#prepare').click(); await page.getByText('Cette compétence a changé', { exact: false }).waitFor();
    assert.equal(posts.length, 0, 'preparing must not execute a skill');
    await page.locator('#close-action').click(); approved = true;
    await page.getByRole('button', { name: /Vérifier un processus/ }).click();
    await page.locator('#action-subject').fill('commandes'); await page.locator('#prepare').click();
    await page.locator('#send').click(); await page.getByText('Réponse reçue', { exact: true }).waitFor();
    assert.match(await page.locator('#progress').innerText(), /reste à vérifier/);
    assert.equal(await page.locator('#turns img').count(), 0);
    await page.getByText('Sources consultées').click(); assert.match(await page.locator('#turns').innerText(), /runbook/);
    assert.equal(await page.evaluate(() => window.hacked), undefined);
    await page.locator('#open-expert').click();
    await page.waitForFunction(() => document.querySelector('#conversation-id')?.textContent === 'conv-1');
    assert.match(await page.locator('#transcript').innerText(), /Observation/);
    assert.equal(await page.evaluate(() => localStorage.getItem('kex.agent.conversation.conv-1')), null, 'handoff must not copy private turns to the global expert history');
    await page.goto(`http://127.0.0.1:${server.address().port}/app#/requests`);
    await page.getByRole('button', { name: 'Consulter' }).click();
    await page.getByText('Réponse reçue', { exact: true }).waitFor();
    mode = 'partial'; await page.locator('#followup').fill('Précise les limites'); await page.locator('#followup-send').click();
    await page.getByText('Résultat partiel', { exact: true }).waitFor();
    mode = 'error'; await page.locator('#followup').fill('Nouvelle vérification'); await page.locator('#followup-send').click();
    await page.getByText('Traitement interrompu', { exact: true }).waitFor();
    mode = 'hold'; await page.locator('#followup').fill('Vérification lente'); await page.locator('#followup-send').click();
    await page.getByText('En cours', { exact: true }).waitFor();
    await page.locator('#account').click(); await page.locator('#access-key').fill('beta'); await page.locator('#connect').click();
    await page.getByText('Connecté : beta').waitFor();
    await page.getByRole('link', { name: 'Mes demandes', exact: true }).click();
    assert.match(await page.locator('#request-list').innerText(), /Aucune demande/);
    assert.equal(await page.locator('#turns').innerText(), '', 'account changes clear transcript and ignore old stream');
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `no horizontal overflow at ${width}px`);
    assert.deepEqual(errors, []);
    posts = []; mode = 'success'; await context.close();
  }
  console.log('✓ user workspace: guided and approved actions, streaming, sources, XSS, history, handoff, failures and account isolation at 1440/768/390/320px');
} finally { await browser.close(); await new Promise(done => server.close(done)); }
