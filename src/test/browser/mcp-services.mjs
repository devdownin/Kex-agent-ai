// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const assets = new URL('../../main/resources/static/assets/', import.meta.url);
const html = '<div id="servers"></div><div id="mcp-storage-status"></div><section id="drawer" hidden><h2 id="drawer-title"></h2><div id="drawer-body"></div><button id="drawer-close">Fermer</button></section>';
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
  let fail = false;
  let delayed = null;
  const detail = { connection: 'new-server', serverName: 'Services métier', version: '1.2',
    protocolVersion: '2025-06-18', initialized: true,
    tools: [{ name: 'list_topics', description: 'Consulter les topics disponibles', inputSchema: { type: 'object' } }] };
  await page.route('**/api/agent/mcp/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path.endsWith('/runtime-servers')) body = [{ connection: 'new-server', enabled: false, transport: 'HTTP' }];
    if (path.endsWith('/storage')) body = null;
    if (path.endsWith('/preview')) {
      previews++;
      if (delayed) await delayed;
      if (fail) return route.fulfill({ status: 502, contentType: 'application/json', body: '{}' });
      body = detail;
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
  assert.match(await page.textContent('#drawer-body'), /Désactivé/);
  assert.equal(previews, 1);
  console.log('✓ la sélection charge les informations et descriptions sans activation');
  await page.evaluate(async () => (await import('/assets/core.js')).closeDrawer());
  fail = true;
  await select();
  await page.waitForFunction(() => document.querySelector('#drawer-body').textContent.includes('Impossible de récupérer'));
  assert.doesNotMatch(await page.textContent('#drawer-body'), /Chargement des services/);
  fail = false;
  await page.getByRole('button', { name: 'Réessayer' }).click();
  await page.waitForFunction(() => document.querySelector('#drawer-body').textContent.includes('Consulter les topics disponibles'));
  console.log('✓ l’échec est explicite et le bouton Réessayer recharge les services');
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
