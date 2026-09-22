// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Garde-fou transversal : tout contrôle statique livré dans index.html doit être
// actionnable et correctement nommé, même s'il n'a pas encore de scénario métier dédié.
// PLAYWRIGHT_MODULE=… node src/test/browser/ui-controls.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = new URL('../../main/resources/static/', import.meta.url);
const html = await readFile(new URL('index.html', root), 'utf8');
const css = await readFile(new URL('assets/console.css', root), 'utf8');
const browser = await chromium.launch();

try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  await page.setContent(html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<link\b[^>]*>/gi, ''));
  await page.addStyleTag({ content: css });

  const controls = await page.locator('button, a[href], input, select, textarea, summary').all();
  assert.ok(controls.length > 0, 'aucun contrôle interactif trouvé');

  const ids = new Set();
  for (const control of controls) {
    const tag = await control.evaluate((node) => node.tagName.toLowerCase());
    const type = (await control.getAttribute('type') ?? '').toLowerCase();
    if (tag === 'input' && type === 'hidden') continue;

    const id = await control.getAttribute('id');
    if (id) {
      assert.ok(!ids.has(id), `identifiant interactif dupliqué : #${id}`);
      ids.add(id);
    }

    const name = (await control.getAttribute('aria-label')
      ?? await control.getAttribute('title')
      ?? (await control.innerText().catch(() => ''))
      ?? '').trim();
    const labelled = await control.getAttribute('aria-labelledby');
    const labelFor = id ? await page.locator(`label[for="${id}"]`).count() : 0;
    const wrappedLabel = await control.locator('xpath=ancestor::label[1]').count();
    const value = (await control.getAttribute('value') ?? '').trim();
    assert.ok(name || labelled || labelFor || wrappedLabel || (tag === 'input' && value),
      `contrôle sans nom accessible : ${tag}${id ? '#' + id : ''}`);

    if (tag === 'a') {
      const href = await control.getAttribute('href');
      assert.ok(href && href !== '#', `lien sans destination : ${name || id || '<anonyme>'}`);
    }
  }

  const tabs = page.locator('[data-agent-tab]');
  for (let i = 0; i < await tabs.count(); i += 1) {
    const tab = tabs.nth(i);
    const target = await tab.getAttribute('data-agent-tab');
    assert.equal(await page.locator(`[data-agent-section="${target}"]`).count() > 0, true,
      `onglet agent sans section : ${target}`);
  }

  const submitters = page.locator('button[type="submit"], input[type="submit"]');
  for (let i = 0; i < await submitters.count(); i += 1) {
    const submitter = submitters.nth(i);
    assert.equal(await submitter.locator('xpath=ancestor::form[1]').count(), 1,
      'bouton submit hors formulaire');
  }

  console.log(`✓ ${controls.length} contrôles statiques inventoriés : noms, liens, onglets et formulaires cohérents`);
} finally {
  await browser.close();
}
