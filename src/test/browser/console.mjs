// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// La console au navigateur, en CI. La suite Java sert des fichiers et vérifie que les chemins
// d'API qu'ils citent existent ; elle n'exécute pas une ligne de JavaScript. Chaque cas ci-dessous
// verrouille un défaut réellement rencontré, trouvé à la main faute de ce script.
//
//   PLAYWRIGHT_MODULE=… node src/test/browser/console.mjs
//
// Playwright n'est pas une dépendance du projet : il est installé par le job de CI, hors de
// l'arborescence, et son chemin arrive par PLAYWRIGHT_MODULE. Ajouter un package.json ferait
// vivre une seconde chaîne de construction pour un seul fichier.

import assert from 'node:assert/strict';
import { createServer } from 'node:http';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');

const BASE = process.env.KEX_AGENT_URL ?? 'http://localhost:8081';
const TOKEN = process.env.KEX_AGENT_API_KEY ?? 'ci-secret';
const GATEWAY_PORT = Number(process.env.KEX_GATEWAY_PORT ?? 8098);

// Une passerelle réduite à /models : le tri numérique et l'état « non annoncé » ont besoin d'un
// catalogue, et la CI n'appelle aucun tiers.
const gateway = createServer((request, response) => {
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify({ data: [
    { id: 'a/petit', context_length: 8192, supported_parameters: ['tools'] },
    { id: 'b/grand', context_length: 400000, supported_parameters: ['temperature'] },
    { id: 'c/moyen', context_length: 200000, supported_parameters: ['tools'] },
    { id: 'd/muet' },
  ] }));
}).listen(GATEWAY_PORT, '127.0.0.1');

const problems = [];
const checks = [];

async function check(name, body) {
  try {
    await body();
    checks.push(`  ✓ ${name}`);
  } catch (error) {
    problems.push(`  ✗ ${name}\n      ${error.message.split('\n')[0]}`);
  }
}

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
const page = await context.newPage();

// Une exception non rattrapée ne casse rien de visible : l'écran reste affiché, figé sur des
// données périmées. C'est exactement le mensonge que le reste de la console s'attache à éviter.
const scriptErrors = [];
page.on('pageerror', (error) => scriptErrors.push(String(error)));
page.on('console', (message) => {
  if (message.type() !== 'error' || expected(message)) return;
  scriptErrors.push(`${message.text()} — ${message.location().url}`);
});

/**
 * Deux échecs de requête sont attendus et déjà traités par la console, donc pas des défauts :
 * le `401` avant la saisie du jeton, et le `404` d'une métrique qu'aucun appel n'a encore créée —
 * la vue Technique l'affiche « — », comme le veut la règle « une mesure absente n'est pas zéro ».
 * Le navigateur les journalise quand même : les filtrer ici garde le reste du garde-fou utile.
 */
function expected(message) {
  return message.text().includes('401')
    || (message.text().includes('404') && message.location().url.includes('/actuator/metrics/'));
}

await page.goto(`${BASE}/#/settings`, { waitUntil: 'networkidle' });
await page.waitForSelector('dialog[open]');
await page.fill('#api-key', TOKEN);
await page.click('#credentials-form button[type=submit]');

await check('l’écran courant se recharge après la saisie du jeton', async () => {
  // Défaut : route() ne rechargeait pas la vue inchangée, et Configuration — exclue du sondage de
  // fond parce qu'elle porte un formulaire — restait sur « Jeton refusé » jusqu'à ce qu'on navigue.
  await page.waitForSelector('#llm-config dl.definition', { timeout: 10000 });
});

await check('un agent qui n’a rien analysé n’est pas OPÉRATIONNEL', async () => {
  // Défaut : l'état ne regardait que les cycles, donc une instance qui n'avait jamais rien mesuré
  // affichait un vert au-dessus d'un bandeau disant « Aucune analyse exécutée ».
  assert.equal(await page.$eval('#agent-status', (node) => node.dataset.state), 'UNKNOWN');
  assert.match(await page.$eval('#freshness', (node) => node.innerText), /Aucune analyse/);
});

await check('le titre de la page suit la vue', async () => {
  assert.match(await page.title(), /^Configuration — /);
  assert.equal(await page.$eval('#announcer', (node) => node.textContent), 'Configuration');
});

await check('le catalogue de la passerelle se trie, les valeurs absentes restant en bas',
  async () => {
    await page.click('#open-llm-models');
    await page.waitForSelector('#drawer table.grid tbody tr');
    const contexts = () => page.$$eval('#drawer table.grid tbody tr',
      (rows) => rows.map((row) => row.cells[1].dataset.sort ?? ''));

    await page.click('#drawer table.grid thead th:nth-child(2) button.sort');
    assert.deepEqual(await contexts(), ['8192', '200000', '400000', ''], 'ordre croissant');
    assert.equal(
      await page.$eval('#drawer table.grid thead th:nth-child(2)', (n) => n.getAttribute('aria-sort')),
      'ascending');

    await page.click('#drawer table.grid thead th:nth-child(2) button.sort');
    // La case vide reste en bas : la remonter la présenterait comme la plus grande valeur.
    assert.deepEqual(await contexts(), ['400000', '200000', '8192', ''], 'ordre décroissant');
  });

await check('un support d’outils non annoncé n’est pas affiché comme absent', async () => {
  const tags = await page.$$eval('#drawer table.grid tbody tr',
    (rows) => rows.map((row) => row.cells[2].innerText.replace(/\s+/g, ' ').trim()));
  assert.ok(tags.some((tag) => tag.includes('Non annoncé')), `attendu « Non annoncé » parmi ${tags}`);
});

await check('le panneau retient le focus et la coque devient inerte', async () => {
  // Défaut : Tab sortait par-derrière, sur un panneau qui porte « Approuver » et « Refuser ».
  assert.ok(await page.$eval('main', (node) => node.hasAttribute('inert')), 'main inerte');
  assert.ok(await page.$eval('.rail', (node) => node.hasAttribute('inert')), 'rail inerte');
  for (let i = 0; i < 6; i += 1) await page.keyboard.press('Tab');
  assert.ok(await page.evaluate(() => document.querySelector('#drawer').contains(document.activeElement)),
    'le focus est resté dans le panneau');
});

await check('le bouton Retour referme le panneau', async () => {
  await page.goBack();
  await page.waitForTimeout(300);
  assert.ok(await page.$eval('#drawer', (node) => node.hidden), 'panneau fermé');
  assert.ok(!(await page.$eval('main', (node) => node.hasAttribute('inert'))), 'coque rendue au clavier');
});

await check('l’état d’écran voyage dans l’URL et survit au rechargement', async () => {
  await page.goto(`${BASE}/#/processes?etat=UNKNOWN&q=order`);
  await page.waitForSelector('#processes-table table.grid tbody tr');
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForSelector('#processes-table table.grid tbody tr');
  assert.match(page.url(), /#\/processes\?etat=UNKNOWN&q=order/);
  const rows = await page.$$eval('#processes-table table.grid tbody tr', (list) => list.length);
  assert.equal(rows, 1, 'le filtre est réappliqué après rechargement');
});

await check('un panneau de supervision se rouvre depuis son adresse', async () => {
  // Les panneaux sont enregistrés dans un registre partagé : sans ce cas, un module qui ajoute le
  // sien peut fermer ceux des autres par ignorance, ce qui est arrivé.
  await page.click('#processes-table table.grid tbody tr');
  await page.waitForSelector('#drawer:not([hidden])');
  assert.match(page.url(), /processus=order-integration/);

  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForSelector('#drawer:not([hidden])', { timeout: 10000 });
  assert.match(await page.$eval('#drawer-title', (node) => node.textContent), /Order Integration/);

  await page.keyboard.press('Escape');
  // `state: 'attached'` : un élément porteur de `hidden` n'est jamais « visible », et l'attente
  // par défaut de Playwright expirerait sur un panneau pourtant bien refermé.
  await page.waitForSelector('#drawer[hidden]', { state: 'attached' });
  // Fermer efface le paramètre, sinon le rechargement suivant rouvrirait le panneau.
  assert.doesNotMatch(page.url(), /processus=/);
});

await check('le bandeau hors ligne apparaît puis disparaît', async () => {
  assert.ok(await page.$eval('#offline', (node) => node.hidden), 'caché tant qu’on est en ligne');
  await context.setOffline(true);
  await page.evaluate(() => dispatchEvent(new Event('offline')));
  await page.waitForFunction(() => !document.querySelector('#offline').hidden, null, { timeout: 3000 });
  await context.setOffline(false);
  await page.evaluate(() => dispatchEvent(new Event('online')));
  await page.waitForFunction(() => document.querySelector('#offline').hidden, null, { timeout: 3000 });
});

await check('la vue technique n’a qu’un bouton de rafraîchissement', async () => {
  // Défaut : chaque panneau ajouté à cette vue arrivait avec le sien — trois pour un même geste,
  // au-dessus d'un sondage de fond qui les rafraîchit déjà tous. Les autres vues n'en ont qu'un.
  await page.goto(`${BASE}/#/tools`, { waitUntil: 'networkidle' });
  const refreshers = await page.$$eval('#view-tools button',
    (nodes) => nodes.filter((node) => node.textContent.trim() === 'Rafraîchir').length);

  assert.equal(refreshers, 1);
  // Et il rafraîchit la vue entière, pas le seul panneau dans lequel il est posé.
  assert.match(await page.$eval('#refresh-tools', (node) => node.getAttribute('aria-label')),
    /vue technique/);
});

await check('la carte d’un serveur MCP unique occupe toute la largeur du panneau', async () => {
  // Défaut : `.servers-grid` posait `repeat(auto-fill, minmax(320px, 1fr))`. Avec un seul serveur
  // connecté, auto-fill réserve quand même les colonnes vides à leur largeur minimale plutôt que
  // de les effacer — la carte restait étroite dans un coin, à côté d'un vide. auto-fit corrige :
  // sans MCP dans ce job (spring.ai.mcp.client.enabled=false), la réponse est simulée pour
  // exercer ce rendu précis, seul cas de cette suite à le faire.
  await page.route('**/api/agent/mcp/servers', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([{
      connection: 'kafka-explorer', serverName: 'kafka-explorer-mcp', version: '0.1.0',
      protocolVersion: '2025-11-25', initialized: true,
      tools: [{ name: 'kex_list_topics', description: 'Liste les topics.' }],
    }]),
  }));
  await page.goto(`${BASE}/#/tools`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#servers .server');

  const widths = await page.evaluate(() => ({
    grid: document.querySelector('#servers .servers-grid').getBoundingClientRect().width,
    card: document.querySelector('#servers .server').getBoundingClientRect().width,
  }));
  assert.ok(widths.card > widths.grid - 40,
    `la carte (${widths.card}px) doit remplir la grille (${widths.grid}px)`);
  await page.unroute('**/api/agent/mcp/servers');
});

await check('un tableau déjà rendu ne clignote pas au sondage de fond', async () => {
  // Défaut repéré sur la grille des serveurs MCP de la vue Technique, mais dans render() lui-même
  // (core.js), partagé par Processus, Décisions, Alertes, Audit et les topics Kafka : chaque
  // sondage de fond effaçait l'hôte avec le témoin « Chargement… », plus étroit que le tableau
  // qu'il remplace, avant de le reconstruire — un flash toutes les 15 secondes qui donnait
  // l'impression que le bloc n'occupait plus toute la largeur disponible. Vérifié ici sur
  // Processus plutôt que sur la vue Technique : ce job démarre l'agent avec
  // `spring.ai.mcp.client.enabled=false`, sans serveur MCP à lister.
  await page.goto(`${BASE}/#/processes`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#processes-table table.grid tbody tr');
  const widthBefore = await page.$eval('#processes-table', (node) => node.getBoundingClientRect().width);

  await page.evaluate(() => {
    window.__flashed = false;
    window.__observer = new MutationObserver(() => {
      if (document.querySelector('#processes-table > .state.loading')) window.__flashed = true;
    });
    window.__observer.observe(document.querySelector('#processes-table'), { childList: true });
  });

  // `online` déclenche un sondage de fond immédiat (voir plus haut) : plus fiable qu'une attente de
  // REFRESH_MS (15 s) réelles pour observer un cycle.
  const refreshed = page.waitForResponse((response) => response.url().includes('/api/agent/supervision/overview'));
  await context.setOffline(true);
  await page.evaluate(() => dispatchEvent(new Event('offline')));
  await context.setOffline(false);
  await page.evaluate(() => dispatchEvent(new Event('online')));
  await refreshed;
  await page.waitForTimeout(100);

  assert.equal(await page.evaluate(() => window.__flashed), false,
    'le témoin de chargement ne doit pas remplacer un tableau déjà rendu');
  const widthAfter = await page.$eval('#processes-table', (node) => node.getBoundingClientRect().width);
  assert.equal(widthAfter, widthBefore, 'le bloc garde toute sa largeur pendant le sondage de fond');
});

await check('le tableau compact de la vue d’ensemble signale qu’il défile', async () => {
  // Défaut : la barre de défilement en survol (macOS, la plupart des Chromium) ne laissait aucune
  // trace tant qu'on n'avait pas touché le pavé tactile — la dernière colonne semblait coupée au
  // bord du panneau plutôt que défilable. `scrollbar-width: thin` restitue l'indice là où le
  // navigateur l'honore ; l'ombre peinte avec le contenu (assertée ici par sa seule présence,
  // indépendante du rendu du chrome) tient partout ailleurs, Safari compris.
  await page.goto(`${BASE}/#/overview`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#overview-processes .scroll-x table.grid tbody tr');
  // Le bascule hors-ligne juste avant ce cas déclenche son propre rechargement de fond en
  // reprenant la connexion : attendre le débordement plutôt que le lire une fois évite une course
  // avec ce second rendu.
  await page.waitForFunction(() => {
    const node = document.querySelector('#overview-processes .scroll-x');
    return node && node.scrollWidth > node.clientWidth;
  }, null, { timeout: 5000 });
  const scroll = await page.$eval('#overview-processes .scroll-x', (node) => ({
    scrollbarWidth: getComputedStyle(node).scrollbarWidth,
    hasEdgeShadow: getComputedStyle(node).backgroundImage.includes('gradient'),
  }));
  assert.equal(scroll.scrollbarWidth, 'thin');
  assert.ok(scroll.hasEdgeShadow, 'un halo de bord signale le contenu caché');
});

await check('aucune erreur de script sur le parcours', () => {
  assert.deepEqual(scriptErrors, []);
});

await browser.close();
gateway.close();

console.log(checks.join('\n'));
if (problems.length) {
  console.error(`\n${problems.length} vérification(s) en échec :\n${problems.join('\n')}`);
  process.exit(1);
}
console.log(`\n${checks.length} vérifications passées.`);
