// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
// Contrat UI sur les vrais modules : aucune dépendance Kafka, LLM ou modèle TimesFM.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = resolve('src/main/resources/static');
let scenario = 'ready';
const now = Date.now();
const envelope = (data) => ({ data, coverage: { complete: true }, warnings: [], truncated: false, unavailable: null });
const measured = (value) => envelope({ measured: true, value });
const unknown = () => envelope({ measured: false, reason: 'Qualité non mesurée' });
const metric = { seriesId: 's1', metricId: 'Lag commandes', environment: 'prod', unit: 'messages', horizon: 60 };
const history = { seriesId: 's1', inputFingerprint: 'i1', profileFingerprint: 'p1', points: [
  { endAt: now - 120000, value: 0, imputed: false }, { endAt: now - 60000, value: null, imputed: false },
  { endAt: now, value: 4, imputed: true },
] };
function detail(id) {
  const at = scenario === 'stale' ? now - 1000 : now + 3600000;
  return { seriesId: id, forecast: measured({ context: history, state: 'READY', strategy: scenario === 'fallback' ? 'LAST_VALUE' : 'TIMESFM',
    visibility: 'SHADOW', generatedAt: now, forecast: { seriesId: id, outputUnit: 'messages', modelId: 'timesfm', modelRevision: 'pinned', points: [
      { at: now + 600000, central: 6, q10: 4, q50: 6, q90: 8 }, { at, central: 10, q10: 8, q50: 10, q90: 12 },
    ] } }), history: measured({ ...history, inputFingerprint: scenario === 'mismatch' ? 'other' : 'i1' }),
  quality: ['unknown', 'dashboard-unknown'].includes(scenario) ? unknown() : measured({ evaluatedPoints: 60, evaluatedThrough: now - 60000,
    timesfmMetrics: { mae: 1.25, mase: null, meanPinballLoss: 0.3, q10Q90Coverage: 0.8, meanIntervalWidth: 4 },
    baselineMae: { LAST_VALUE: 2, MOVING_AVERAGE: 3, SEASONAL_NAIVE: 1.5, LINEAR_TREND: 2.5 } }) };
}
const server = createServer(async (req, res) => {
  if (req.url.startsWith('/api/agent/forecasts')) {
    res.setHeader('Content-Type', 'application/json');
    if (scenario === 'http-error') { res.writeHead(503); res.end('{"detail":"Serveur temporairement indisponible"}'); return; }
    let body;
    if (req.url.endsWith('/metrics')) body = scenario === 'unavailable' ? { unavailable: 'Outil absent' } : envelope(scenario === 'empty' ? [] : [
      { ...metric, metricId: scenario === 'xss' ? '<img src=x onerror="window.hacked=true">' : metric.metricId },
      { ...metric, seriesId: 's2', environment: 'dev', metricId: 'Lag dev' },
    ]);
    else if (req.url.endsWith('/breaches')) body = scenario === 'breach-error' ? { unavailable: 'Lecture refusée' } : envelope(['breach', 'below', 'breach-mismatch', 'dashboard-unknown'].includes(scenario) ? [
      { windowEndAt: now + 3600000, generatedAt: now, inputFingerprint: scenario === 'breach-mismatch' ? 'other' : 'i1', profileFingerprint: 'p1',
        threshold: { seriesId: 's1', threshold: scenario === 'below' ? 9 : 5, direction: scenario === 'below' ? 'BELOW' : 'ABOVE', visibility: 'SHADOW' } },
      { windowEndAt: now - 1000, generatedAt: now, threshold: { seriesId: 's1', threshold: 999, direction: 'ABOVE', visibility: 'SHADOW' } },
      { windowEndAt: now + 3600000, generatedAt: now, threshold: { seriesId: 's2', threshold: 20, direction: 'ABOVE', visibility: 'ACTIVE' } },
    ] : []);
    else body = detail(req.url.split('/').at(-1));
    res.end(JSON.stringify(body)); return;
  }
  if (req.url === '/') {
    res.setHeader('Content-Type', 'text/html');
    res.end('<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/assets/console.css"><main style="margin:0;padding:16px;min-width:0"><h1>Prévisions</h1><div id="forecast-content"></div><section><h2>Risques à venir</h2><div id="forecast-dashboard-content"></div></section></main><script type="module">import * as f from "/assets/forecasts.js"; window.forecasts=f; f.view();</script>'); return;
  }
  try {
    if (!/^\/assets\/[a-z.-]+$/.test(req.url)) throw new Error('path');
    res.setHeader('Content-Type', req.url.endsWith('.js') ? 'text/javascript' : 'text/css');
    res.end(await readFile(resolve(root, '.' + req.url)));
  } catch { res.writeHead(404); res.end(); }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {});
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errors = []; page.on('pageerror', (error) => errors.push(String(error)));
  const base = `http://127.0.0.1:${server.address().port}`;
  async function load(mode) { scenario = mode; await page.goto(base); await page.waitForFunction(() => Boolean(window.forecasts)); await page.waitForFunction(() => !document.body.textContent.includes('Lecture des prévisions existantes…') && !document.body.textContent.includes('Lecture de l’historique et de la qualité…')); }
  await load('ready');
  assert.match(await page.locator('body').innerText(), /Mode observation/);
  assert.equal(await page.locator('.forecast-band').count(), 1);
  assert.match(await page.locator('body').innerText(), /MAE dernière valeur/);
  assert.equal(await page.locator('#forecast-series option').count(), 1);
  await page.getByText('Voir les valeurs et les imputations', { exact: true }).click();
  assert.match(await page.locator('tbody').innerText(), /Historique imputé/);
  const rows = await page.locator('tbody tr').allTextContents();
  assert.ok(rows[0].includes('0')); assert.ok(rows[1].includes('Non mesuré'));
  const href = await page.getByRole('link', { name: 'Analyser avec l’agent' }).getAttribute('href');
  assert.match(decodeURIComponent(href), /série s1, environnement prod/);
  assert.match(decodeURIComponent(href), /Constat actuel.*Prévision.*Qualité.*Limites.*Vérifications proposées/);
  await page.getByText('Comprendre la qualité', { exact: true }).click();
  assert.match(await page.locator('body').innerText(), /Écart absolu moyen/);
  await page.locator('#forecast-environment').selectOption('dev');
  await page.waitForFunction(() => document.querySelector('#forecast-series')?.value === 's2');
  assert.match(await page.locator('#forecast-series').innerText(), /Lag dev/);
  await load('unknown'); assert.match(await page.locator('body').innerText(), /Qualité non mesurée/);
  await load('fallback'); assert.equal(await page.locator('.forecast-band').count(), 0);
  assert.match(await page.locator('body').innerText(), /baseline sans intervalle/);
  await load('stale'); assert.match(await page.locator('body').innerText(), /Prévision expirée/);
  await load('mismatch'); assert.match(await page.locator('body').innerText(), /Historique renouvelé/);
  await load('xss'); assert.equal(await page.locator('#forecast-content img').count(), 0);
  assert.equal(await page.evaluate(() => window.hacked), undefined);
  await load('breach-error'); assert.match(await page.locator('body').innerText(), /Lecture refusée/);
  assert.doesNotMatch(await page.locator('body').innerText(), /Aucun dépassement/);
  await load('breach');
  assert.equal(await page.locator('.forecast-threshold-line').count(), 1);
  assert.equal(await page.locator('.forecast-breach-point').count(), 1);
  assert.equal(await page.getByRole('button', { name: 'Voir cette prévision' }).count(), 1);
  assert.match(await page.locator('body').innerText(), /au-dessus de 5 messages/);
  assert.doesNotMatch(await page.locator('body').innerText(), /au-dessus de (999|20)/);
  await page.getByRole('button', { name: 'Voir cette prévision' }).click();
  await page.waitForFunction(() => document.querySelector('#forecast-series')?.value === 's1');
  await page.evaluate(() => window.forecasts.dashboard());
  assert.equal(await page.locator('.forecast-risk-card').count(), 2, 'Les risques des deux environnements sont visibles, les risques expirés exclus');
  assert.match(await page.locator('#forecast-dashboard-content').innerText(), /2 dépassement.*prochaine échéance/s);
  assert.match(await page.locator('#forecast-dashboard-content').innerText(), /Qualité réalisée.*60 points/s);
  const riskHref = await page.getByRole('link', { name: 'Examiner la prévision' }).last().getAttribute('href');
  assert.match(riskHref, /series=s2/);
  await page.evaluate((href) => { location.hash = href; return window.forecasts.view(); }, riskHref);
  assert.equal(await page.locator('#forecast-environment').inputValue(), 'dev');
  assert.equal(await page.locator('#forecast-series').inputValue(), 's2');
  await load('below');
  assert.equal(await page.locator('.forecast-threshold-line').count(), 1);
  assert.equal(await page.locator('.forecast-breach-point').count(), 1);
  await load('breach-mismatch');
  assert.equal(await page.locator('.forecast-threshold-line').count(), 0);
  assert.match(await page.locator('body').innerText(), /Seuil non superposé/);
  await load('dashboard-unknown'); await page.evaluate(() => window.forecasts.dashboard());
  assert.match(await page.locator('#forecast-dashboard-content').innerText(), /Qualité : non mesurée/);
  await load('breach-error'); await page.evaluate(() => window.forecasts.dashboard());
  assert.match(await page.locator('#forecast-dashboard-content').innerText(), /Lecture refusée/);
  assert.doesNotMatch(await page.locator('#forecast-dashboard-content').innerText(), /Aucun dépassement/);
  await load('unavailable'); assert.match(await page.locator('body').innerText(), /Outil absent/);
  await load('empty'); assert.match(await page.locator('body').innerText(), /Aucune métrique autorisée/);
  await load('http-error'); assert.equal(await page.getByRole('button', { name: 'Réessayer' }).count(), 1);
  await load('breach'); await page.evaluate(() => window.forecasts.dashboard());
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'La vue mobile déborde');
  if (process.env.FORECAST_SCREENSHOT) await page.screenshot({ path: process.env.FORECAST_SCREENSHOT, fullPage: true });
  await page.evaluate(async () => { const core = await import('/assets/core.js'); core.credentials.set('new-key'); });
  assert.equal(await page.locator('#forecast-content').innerText(), '');
  assert.equal(await page.locator('#forecast-dashboard-content').innerText(), '');
  assert.deepEqual(errors, []);
  console.log('✓ Prévisions : contrat, environnements, valeurs manquantes, fallback, péremption, provenance, XSS, erreurs, mobile et changement de jeton');
} finally { await browser.close(); await new Promise((resolve) => server.close(resolve)); }
