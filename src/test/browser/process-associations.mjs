// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = resolve('src/main/resources/static');
let associations = [];
let role = 'ADMIN';
let denied = false;
let writes = 0;
const now = Date.now();
const envelope = (data) => ({ data, coverage: { complete: true }, truncated: false, unavailable: null });
const measured = (value) => envelope({ measured: true, value });
const series = { seriesId: 'orders-series', environment: 'lab', metricId: 'Commandes' };
const server = createServer(async (req, res) => {
  if (req.url.startsWith('/api/agent/')) {
    res.setHeader('Content-Type', 'application/json');
    let body;
    if (req.url.endsWith('/whoami')) body = { roles: [role] };
    else if (req.url.endsWith('/readiness')) body = { connection: 'kafka-explorer', connected: true,
      missingTools: [], catalogComplete: true, seriesCount: 1, ready: true };
    else if (req.url.endsWith('/metrics')) body = envelope([series]);
    else if (req.url.endsWith('/associations')) {
      if (req.method === 'PUT') {
        writes++;
        let input = ''; for await (const chunk of req) input += chunk;
        if (denied) { res.writeHead(503); res.end('{"detail":"Catalogue incomplet"}'); return; }
        associations = JSON.parse(input).associations;
      }
      body = { processId: 'orders', associations, overridden: true, unavailable: null };
    } else body = { processId: 'orders', readAt: now, truncated: false, unavailable: null,
      forecasts: associations.map((association) => ({ association, detail: {
        forecast: measured({ state: 'READY', visibility: 'SHADOW', strategy: 'TIMESFM', generatedAt: now,
          context: { inputFingerprint: 'i1', profileFingerprint: 'p1' },
          forecast: { points: [{ at: now + 600000 }] } }), quality: envelope({ measured: false }),
      } })), breaches: envelope([{ threshold: { seriesId: 'orders-series', threshold: 20, direction: 'ABOVE', visibility: 'SHADOW' },
        generatedAt: now, windowEndAt: now + 600000, inputFingerprint: 'i1', profileFingerprint: 'p1' }]) };
    res.end(JSON.stringify(body)); return;
  }
  if (req.url === '/') {
    res.setHeader('Content-Type', 'text/html');
    res.end('<meta charset="utf-8"><section id="host"></section><script type="module">import {processForecasts} from "/assets/process-forecasts.js"; window.load=()=>processForecasts("orders",document.querySelector("#host"));window.load();</script>'); return;
  }
  try {
    if (!/^\/assets\/[a-z.-]+$/.test(req.url)) throw new Error('path');
    res.setHeader('Content-Type', 'text/javascript');
    res.end(await readFile(resolve(root, '.' + req.url)));
  } catch { res.writeHead(404); res.end(); }
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const url = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch();
const page = await browser.newPage();
try {
  await page.goto(url);
  await page.getByRole('button', { name: 'Associer une prévision', exact: true }).click();
  await page.waitForSelector('select[aria-label="Série TimesFM à associer"]');
  assert.match(await page.locator('#host').innerText(), /Cinq outils disponibles/);
  assert.equal(writes, 0);
  await page.selectOption('select', '0');
  await page.getByRole('button', { name: 'Enregistrer l’association' }).click();
  await page.waitForFunction(() => document.querySelector('#host').textContent.includes('Dépassement prédit'));
  assert.deepEqual(associations, [{ seriesId: 'orders-series', environment: 'lab' }]);
  assert.equal(writes, 1);
  assert.match(await page.locator('#host').innerText(), /SHADOW.*Qualité réalisée non mesurée/s);
  assert.match(await page.locator('#host').innerText(), /ne remplacent pas les mesures actuelles/);
  await page.getByRole('button', { name: 'Associer une prévision', exact: true }).click();
  await page.getByRole('button', { name: 'Retirer', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#host').textContent.includes('Aucune prévision associée'));
  assert.deepEqual(associations, []);
  denied = true;
  await page.getByRole('button', { name: 'Associer une prévision', exact: true }).click();
  await page.selectOption('select', '0');
  await page.getByRole('button', { name: 'Enregistrer l’association' }).click();
  await page.waitForFunction(() => document.querySelector('#host').textContent.includes('Association non enregistrée'));
  assert.deepEqual(associations, []);
  role = 'OPERATOR';
  await page.evaluate(() => window.load());
  assert.equal(await page.getByRole('button', { name: 'Associer une prévision', exact: true }).count(), 0);
  assert.match(await page.locator('#host').innerText(), /nécessite le rôle ADMIN/);
  await page.evaluate(async () => { const { credentials } = await import('/assets/core.js'); credentials.set('new-token'); });
  assert.equal(await page.locator('#host').textContent(), '');
  console.log('✓ Associations persistées depuis la fiche, retrait, erreurs, aperçu daté, rôle et changement de jeton');
} finally { await browser.close(); server.close(); }
