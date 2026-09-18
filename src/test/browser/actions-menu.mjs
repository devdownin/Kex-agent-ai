// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Vérification de superposition sur les vrais HTML/CSS, sans dépendance au serveur ni aux API.
// PLAYWRIGHT_MODULE=… node src/test/browser/actions-menu.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = new URL('../../main/resources/static/', import.meta.url);
const html = await readFile(new URL('index.html', root), 'utf8');
const css = await readFile(new URL('assets/console.css', root), 'utf8');
const browser = await chromium.launch();

try {
  for (const width of [1440, 1024, 768, 390]) {
    const page = await browser.newPage({ viewport: { width, height: 1000 } });
    try {
      // Seuls les scripts et ressources externes sont retirés ; la structure reste celle livrée.
      await page.setContent(html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
        .replace(/<link\b[^>]*>/gi, ''));
      await page.addStyleTag({ content: css });
      await page.locator('#view-overview').evaluate((node) => { node.hidden = false; });
      await page.locator('.action-menu > summary').click();
      await page.evaluate(() => Promise.all(document.getAnimations().map((a) => a.finished)));

      const actions = page.locator('.action-menu-popover .menu-action');
      assert.equal(await actions.count(), 3);
      for (const action of await actions.all()) {
        // isVisible() seul ne détecte pas un élément recouvert par un autre bloc.
        const unobscured = await action.evaluate((node) => {
          const box = node.getBoundingClientRect();
          return [0.1, 0.5, 0.9].every((x) => [0.1, 0.5, 0.9].every((y) =>
            node.contains(document.elementFromPoint(box.x + box.width * x, box.y + box.height * y))));
        });
        assert.ok(unobscured, `${width}px : ${await action.textContent()} est masqué`);
        await action.click({ trial: true, timeout: 2000 });
      }
      await page.locator('.action-menu > summary').click();
      assert.equal(await page.locator('.action-menu').getAttribute('open'), null);
      console.log(`✓ ${width}px : les trois actions sont accessibles et le menu se referme`);
    } finally {
      await page.close();
    }
  }
} finally {
  await browser.close();
}
