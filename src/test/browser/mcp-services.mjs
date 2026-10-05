// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const assets = new URL('../../main/resources/static/assets/', import.meta.url);
const html = '<div id="servers"></div><div id="mcp-storage-status"></div><textarea id="prompt"></textarea><section id="drawer" hidden><h2 id="drawer-title"></h2><div id="drawer-body"></div><button id="drawer-close">Fermer</button></section>';
const server = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://localhost').pathname;
    response.setHeader('Content-Type', path.endsWith('.js') ? 'text/javascript' : 'text/html');
    response.end(path.startsWith('/assets/') ? await readFile(new URL(path.slice(8), assets)) : html);
  } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  let previews = 0;
  let fail = false; let denied = false; let noOutput = false;
  let delayed = null;
  let refreshFails = false;
  const detail = { connection: 'new-server', serverName: 'Services métier', version: '1.2',
    protocolVersion: '2025-06-18', initialized: true,
    reportedToolCount: 1,
    retrievedAt: '2026-10-04T19:00:00Z', cached: false, stale: false,
    supportedCapabilities: ['tools', 'resources', 'prompts'],
    resources: [{ uri: 'test://orders', name: 'Commandes', description: 'Données de commandes', mimeType: 'application/json' }],
    resourceTemplates: [{ uriTemplate: 'test://orders/{id}', name: 'Commande par identifiant', description: 'Une commande' }],
    prompts: [{ name: 'triage', description: 'Analyser une anomalie', arguments: [{ name: 'topic', description: 'Topic concerné', required: true }] }],
    tools: [{ name: 'list_topics', description: 'Consulter les topics disponibles',
      annotations: { readOnlyHint: true, destructiveHint: false },
      outputSchema: { type: 'object', required: ['count'], properties: { count: { type: 'integer', description: 'Nombre de topics' } } },
      inputSchema: { type: 'object', required: ['topic'], properties: {
        topic: { type: 'string', description: 'Topic à examiner', examples: ['orders'] },
        limit: { type: 'integer', description: 'Nombre de résultats' },
      } } }] };
  await page.route('**/api/agent/mcp/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path.endsWith('/runtime-servers')) body = [{ connection: 'new-server', enabled: false, transport: 'HTTP' }];
    if (path.endsWith('/storage')) body = null;
    if (path.endsWith('/preview')) {
      previews++;
      if (delayed) await delayed;
      if (denied) return route.fulfill({ status: 403, contentType: 'application/json', body: '{}' });
      if (fail) return route.fulfill({ status: 502, contentType: 'application/json', body: '{}' });
      body = refreshFails && route.request().url().includes('refresh=true')
        ? { ...detail, cached: true, stale: true, refreshError: 'Actualisation impossible. Le dernier catalogue est conservé.' }
        : noOutput ? { ...detail, tools: detail.tools.map(t => ({ ...t, description: null, outputSchema: null })) } : detail;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.evaluate(async () => { const tools = await import('/assets/tools.js'); await tools.servers(); });
  assert.equal(previews, 0, 'le sondage ne contacte pas les serveurs désactivés');
  const select = () => page.locator('#servers button', { hasText: 'Détails' }).click();
  await select();
  await page.waitForFunction(() => document.querySelector('#drawer-body').textContent.includes('Consulter les topics disponibles'));
  assert.match(await page.textContent('#drawer-body'), /Services métier/);
  assert.match(await page.textContent('#drawer-body'), /Connexion désactivée/);
  assert.match(await page.textContent('.mcp-discovery-state'), /Informations récupérées/);
  assert.match(await page.textContent('.mcp-service-card'), /Lecture seule déclarée/);
  const rows = await page.locator('.mcp-service-card tbody tr').allTextContents();
  assert.match(rows[0], /topicRequisstringTopic à examiner/);
  assert.match(rows[1], /limitFacultatifintegerNombre de résultats/);
  assert.match(await page.locator('.mcp-service-card pre').first().textContent(), /"topic": "orders"/);
  assert.match(await page.textContent('.mcp-service-card'), /Indisponible : connexion désactivée/);
  assert.match(await page.textContent('.mcp-expected-result'), /count.*integer.*Nombre de topics/s);
  assert.match(await page.textContent('.mcp-expected-result'), /Ce n’est pas un résultat observé/);
  assert.equal(previews, 1);
  assert.match(await page.textContent('[data-catalog-kind="resources"]'), /test:\/\/orders/);
  assert.match(await page.textContent('[data-catalog-kind="templates"]'), /test:\/\/orders\/\{id\}/);
  assert.match(await page.textContent('[data-catalog-kind="prompts"]'), /topic · Requis/);
  console.log('✓ la sélection charge les informations et descriptions sans activation');
  const retrieved = await page.textContent('#drawer-body time');
  refreshFails = true;
  await page.getByRole('button', { name: 'Actualiser le catalogue' }).click();
  await page.waitForFunction(() => document.querySelector('.mcp-discovery-state').textContent.includes('actualisation en échec'));
  assert.equal(await page.textContent('#drawer-body time'), retrieved);
  assert.match(await page.textContent('[data-catalog-kind="resources"]'), /Données de commandes/);
  assert.match(await page.textContent('[data-catalog-kind="prompts"]'), /Analyser une anomalie/);
  refreshFails = false;
  await page.getByRole('button', { name: 'Actualiser le catalogue' }).click();
  await page.waitForFunction(() => document.querySelector('.mcp-discovery-state').textContent.includes('Informations récupérées'));
  console.log('✓ le catalogue daté, les ressources et prompts restent visibles après une actualisation en échec');
  const previewsBeforeUse = previews;
  await page.fill('#prompt', 'Mon brouillon existant');
  await page.getByRole('button', { name: 'Utiliser ce service' }).click();
  assert.ok(page.url().includes('#/chat?serviceDraft=1'));
  assert.equal(await page.locator('#drawer').evaluate(node => node.hidden), true);
  await page.evaluate(async () => (await import('/assets/chat.js')).prefill());
  const draft = await page.inputValue('#prompt');
  assert.ok(draft.startsWith('Mon brouillon existant\n\n'));
  assert.match(draft, /list_topics/); assert.match(draft, /new-server/);
  assert.match(draft, /"topic": "orders"/); assert.match(draft, /\[à compléter\]/);
  assert.equal(previews, previewsBeforeUse, 'la préparation ne rappelle pas le serveur');
  assert.ok(!page.url().includes('serviceDraft='));
  await page.evaluate(async () => (await import('/assets/chat.js')).prefill());
  assert.equal(await page.inputValue('#prompt'), draft, 'le brouillon est consommé une seule fois');
  console.log('✓ les paramètres et effets sont lisibles ; Utiliser prépare un prompt sans envoi ni perte du brouillon');
  await page.evaluate(async () => (await import('/assets/core.js')).closeDrawer());
  fail = true;
  await select();
  await page.waitForFunction(() => document.querySelector('#drawer-body').textContent.includes('Impossible de récupérer'));
  assert.match(await page.textContent('.mcp-discovery-state'), /Échec de connexion/);
  assert.doesNotMatch(await page.textContent('#drawer-body'), /Chargement des services/);
  fail = false;
  await page.getByRole('button', { name: 'Réessayer' }).click();
  await page.waitForFunction(() => document.querySelector('#drawer-body').textContent.includes('Consulter les topics disponibles'));
  console.log('✓ l’échec est explicite et le bouton Réessayer recharge les services');
  await page.evaluate(async () => (await import('/assets/core.js')).closeDrawer());
  denied = true; await select(); await page.getByText('Accès refusé', { exact: true }).waitFor(); assert.equal(await page.getByRole('button', { name: 'Réessayer' }).count(), 0);
  denied = false; noOutput = true; await page.evaluate(async () => (await import('/assets/core.js')).closeDrawer()); await select();
  await page.getByText('Objectif non fourni par le serveur.', { exact: true }).waitFor(); assert.match(await page.textContent('.mcp-expected-result'), /non décrit par le serveur/);
  noOutput = false;
  await page.evaluate(async () => (await import('/assets/core.js')).closeDrawer());
  let release;
  delayed = new Promise(resolve => { release = resolve; });
  const before = previews;
  await select();
  await page.waitForFunction(() => document.querySelector('#drawer-body').textContent.includes('Chargement des services'));
  while (previews === before) await new Promise(resolve => setTimeout(resolve, 10));
  await page.evaluate(async () => {
    const core = await import('/assets/core.js');
    core.openDrawer('Autre sélection', core.el('p', null, 'Autre contenu'));
  });
  release();
  await page.waitForResponse(response => response.url().endsWith('/preview'));
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await page.textContent('#drawer-title'), 'Autre sélection');
  assert.equal(await page.textContent('#drawer-body'), 'Autre contenu');
  console.log('✓ une réponse tardive ne remplace pas la nouvelle sélection');
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
