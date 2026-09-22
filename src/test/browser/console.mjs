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
 * Trois échecs de requête sont attendus et déjà traités par la console, donc pas des défauts :
 * le `401` avant la saisie du jeton, le `404` d'une métrique qu'aucun appel n'a encore créée — la
 * vue Technique l'affiche « — », comme le veut la règle « une mesure absente n'est pas zéro » —, et
 * le `404` de la base de connaissance quand `kex.agent.knowledge.enabled` vaut false, comme dans ce
 * job : `knowledge.js` l'affiche calmement comme désactivée, mais le navigateur journalise quand
 * même l'échec réseau sous-jacent. Le filtrer ici garde le reste du garde-fou utile.
 */
function expected(message) {
  return message.text().includes('401')
    || (message.text().includes('404') && message.location().url.includes('/actuator/metrics/'))
    || (message.text().includes('404') && message.location().url.includes('/api/agent/knowledge'))
    || (message.text().includes('404') && message.location().url.includes('/api/agent/automations'));
}

/** Le strict nécessaire pour que decisionRow() (supervision.js) rende une carte sélectionnable. */
function decisionStub(id) {
  return {
    id, cycleId: 'cycle-1', anomalyId: 'a1', processId: 'order-integration',
    processName: 'Order Integration', capability: 'RESTART_CONSUMER', objective: 'objectif',
    context: 'contexte', action: `Redémarrer ${id}`, observations: [], estimatedImpact: 'Faible',
    confidence: 0.9, status: 'PENDING_APPROVAL', result: null, policyVersion: 'policy-v1',
    correlationId: 'corr', decidedBy: null, decidedAt: new Date().toISOString(),
    resolvedAt: null, expiresAt: new Date(Date.now() + 1800000).toISOString(),
  };
}

/** Le strict nécessaire pour que trendGroup() (supervision.js) ait de quoi tracer une tendance. */
function cycleStub(id, anomaliesDetected) {
  const now = new Date().toISOString();
  return {
    id, startedAt: now, finishedAt: now, processesAnalysed: 2, anomaliesDetected,
    decisionsTaken: 0, actionsExecuted: 0, events: [], failure: null,
  };
}

/** Le strict nécessaire pour que overview() (supervision.js) rende la vue d'ensemble. */
function overviewStub(overrides = {}) {
  const now = new Date().toISOString();
  return {
    agent: {
      state: 'OPERATIONAL', mode: 'SUPERVISED', paused: false, cycleInProgress: false,
      lastCycleAt: now, lastCycleId: 'c1', staleSince: null, policyVersion: 'policy-v1',
      confidenceThreshold: 0.85, stateReason: null, circuitBreakers: [],
    },
    processesMonitored: 1, processesOk: 0, processesWarning: 1, processesError: 0, processesUnknown: 0,
    anomaliesDetected: 0, pendingApprovals: 0,
    processes: [{
      processId: 'order-integration', name: 'Order Integration', state: 'WARNING', lastRun: now,
      durationMillis: 1200, delayMillis: 90000, note: 'Retard', coverage: { complete: true, stopReason: 'EXHAUSTED' },
    }],
    alerts: [], pending: [], lastCycle: null, maintenance: [], incidents: [],
    ...overrides,
  };
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

await check('le cockpit relie incident, preuves et chat contextuel sans changer de vue', async () => {
  const now = new Date().toISOString();
  const chatRequests = [];
  let overviewRequests = 0;
  let signalRefresh;
  const refreshed = new Promise((resolve) => { signalRefresh = resolve; });
  const alert = {
    id: 'order-lag', processId: 'order-integration', processName: 'Order Integration',
    title: 'Retard de consommation', severity: 'ERROR', occurrences: 3,
    firstSeenAt: new Date(Date.now() - 600000).toISOString(), lastSeenAt: now,
    observations: [{ label: 'consumerLag', value: '4200' }],
    analysis: 'Le retard progresse.', probableCause: 'Consumer ralenti', confidence: 0.91,
    recommendation: 'Inspecter le consumer', capability: 'RESTART_CONSUMER', pendingDecisionId: null,
    decisionIds: ['decision-related'],
  };
  await page.route('**/api/agent/supervision/overview', (route) => {
    overviewRequests += 1;
    if (overviewRequests > 1) signalRefresh();
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(overviewStub({
      anomaliesDetected: 1, alerts: [alert], incidents: [{
        cycleId: 'c1', detectedAt: now, processCount: 1, processNames: ['Order Integration'],
        severity: 'ERROR', titles: ['Retard de consommation'], alertIds: ['order-lag'],
      }],
      lastCycle: { ...cycleStub('c1', 1), events: [{ at: now, label: 'Analyse', detail: 'Retard confirmé' }] },
    })) });
  });
  await page.route('**/api/agent/supervision/decisions', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([
      { ...decisionStub('decision-related'), action: 'Décision réellement liée' },
      { ...decisionStub('decision-unrelated'), action: 'Décision étrangère au symptôme' },
    ]),
  }));
  await page.route('**/api/agent/chat', async (route) => {
    const body = route.request().postDataJSON();
    chatRequests.push(body);
    const other = body.message.includes('Contexte B');
    await route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({
        conversationId: other ? 'conv-context-b' : 'conv-incident',
        content: other ? 'Réponse B' : `Réponse incident ${chatRequests.length}`,
        tools: [], finishReason: 'end_turn',
      }),
    });
  });

  await page.goto(`${BASE}/#/incidents`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#incident-workspace .incident-detail');
  const sectionTitles = await page.$$eval('.incident-section > h3', (nodes) => nodes.map((node) => node.textContent));
  assert.deepEqual(sectionTitles, ['Symptômes actifs', 'Explorateur de preuves', 'Décisions liées', 'Chronologie']);
  const decisionsText = await page.$eval('.incident-section:nth-of-type(3)', (node) => node.innerText);
  assert.match(decisionsText, /Décision réellement liée/);
  assert.doesNotMatch(decisionsText, /Décision étrangère/);
  await page.click('.evidence-node > summary');
  assert.match(await page.$eval('.evidence-body', (node) => node.innerText), /consumerLag : 4200/);

  await page.click('.incident-detail-head button.primary');
  await page.waitForSelector('#context-chat:not([hidden])');
  assert.match(await page.$eval('#context-chat-payload', (node) => node.textContent), /Retard de consommation/);
  assert.match(page.url(), /#\/incidents/);
  await page.fill('#context-chat-prompt', 'Pourquoi cet incident ?');
  await page.click('#context-chat-send');
  await page.waitForFunction(() => document.querySelector('#context-chat-transcript')
    .innerText.includes('Réponse incident'));
  assert.equal(chatRequests[0].conversationId, null, 'le premier échange démarre une conversation dédiée');
  assert.match(chatRequests[0].message, /Retard de consommation/, 'le contexte est réellement transmis');
  await page.click('#context-chat-close');

  await page.evaluate(() => dispatchEvent(new CustomEvent('kex:context-chat', { detail: {
    contextId: 'alert:context-b', title: 'Alerte B', context: 'Contexte B',
  } })));
  await page.fill('#context-chat-prompt', 'Question B');
  await page.click('#context-chat-send');
  await page.waitForFunction(() => document.querySelector('#context-chat-transcript').innerText.includes('Réponse B'));
  assert.equal(chatRequests[1].conversationId, null,
    'un autre contexte ne reprend pas la conversation de l’incident');
  await page.click('#context-chat-close');

  await page.click('.incident-detail-head button.primary');
  await page.waitForFunction(() => document.querySelector('#context-chat-transcript')
    .innerText.includes('Pourquoi cet incident'));
  await page.fill('#context-chat-prompt', 'Et maintenant ?');
  await page.click('#context-chat-send');
  await page.waitForFunction(() => document.querySelector('#context-chat-transcript')
    .innerText.includes('Réponse incident 3'));
  assert.equal(chatRequests[2].conversationId, 'conv-incident', 'le même incident reprend sa conversation');
  await page.click('#context-chat-close');

  await page.evaluate(() => dispatchEvent(new Event('online')));
  await Promise.race([
    refreshed,
    new Promise((_, reject) => setTimeout(() => reject(new Error('le cockpit ne s’est pas actualisé')), 3000)),
  ]);

  await page.click('.incident-detail-head button.primary');
  await page.click('#context-chat-full');
  await page.waitForSelector('#transcript .sent-context');
  assert.match(await page.$eval('#transcript .sent-context', (node) => node.textContent),
    /Incident.*Retard de consommation/s);

  await page.unroute('**/api/agent/supervision/overview');
  await page.unroute('**/api/agent/supervision/decisions');
  await page.unroute('**/api/agent/chat');
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

await check('le catalogue recommandé liste les deux entrées et distingue celle qui exige un jeton', async () => {
  // Statique côté serveur (McpRecommendedCatalog.ENTRIES) : aucune route à simuler, contrairement
  // à la découverte qui appelle un service tiers.
  await page.waitForSelector('#mcp-catalog .card');
  const cards = await page.$$eval('#mcp-catalog .card', (nodes) => nodes.map((node) => ({
    title: node.querySelector('h3').textContent,
    requiresToken: Boolean(node.querySelector('.badge')),
  })));
  assert.deepEqual(cards, [
    { title: 'GitHub (lecture seule)', requiresToken: true },
    { title: 'Microsoft Learn', requiresToken: false },
  ]);
});

await check('le formulaire d’installation du catalogue n’affiche le champ jeton que si l’entrée l’exige, et installe sans lui sinon', async () => {
  await page.locator('#mcp-catalog .card', { hasText: 'GitHub (lecture seule)' })
    .getByRole('button', { name: 'Installer' }).click();
  await page.waitForSelector('dialog#install-catalog[open]');
  assert.equal(await page.$eval('#install-catalog-token-field', (node) => node.hidden), false);
  await page.click('#cancel-install-catalog');
  // Défaut possible : un <dialog> fermé devient display:none, donc invisible — waitForSelector sur
  // sa négation d'attribut ne se résoudrait jamais (voir CLAUDE.md). { state: 'hidden' } le fait.
  await page.waitForSelector('dialog#install-catalog', { state: 'hidden' });

  // register() teste toujours une vraie poignée de main, même pour une connexion créée désactivée
  // (voir sa javadoc) : simulée ici pour ne jamais sortir vers learn.microsoft.com en CI.
  let installed = null;
  await page.route('**/api/agent/mcp/catalog/microsoft-learn/install', (route) => {
    installed = route.request().postDataJSON();
    return route.fulfill({
      status: 201, contentType: 'application/json',
      body: JSON.stringify({ connection: installed.connection, serverName: null, version: null,
        protocolVersion: null, initialized: false, circuitBreakerState: null, tools: [] }),
    });
  });
  await page.locator('#mcp-catalog .card', { hasText: 'Microsoft Learn' })
    .getByRole('button', { name: 'Installer' }).click();
  await page.waitForSelector('dialog#install-catalog[open]');
  assert.equal(await page.$eval('#install-catalog-token-field', (node) => node.hidden), true);
  await page.click('#submit-install-catalog');
  await page.waitForSelector('dialog#install-catalog', { state: 'hidden' });
  assert.equal(installed.connection, 'microsoft-learn');
  await page.unroute('**/api/agent/mcp/catalog/microsoft-learn/install');
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

await check('le diagnostic MCP distingue ajout, suppression et changement de schéma', async () => {
  const serversUrl = /\/api\/agent\/mcp\/servers$/;
  const runtimeServersUrl = /\/api\/agent\/mcp\/runtime-servers$/;
  const diagnosticsUrl = /\/api\/agent\/mcp\/servers\/runtime-demo\/diagnostics$/;
  let diagnosticRequested = false;
  const server = {
    connection: 'runtime-demo', serverName: 'demo-mcp', version: '1.0.0',
    protocolVersion: '2025-11-25', initialized: true,
    tools: [{ name: 'search_v2', description: 'Recherche.' }],
  };
  const runtime = {
    connection: 'runtime-demo', transport: 'HTTP', url: 'https://mcp.example.net', endpoint: '/mcp',
    command: null, args: [], headerNames: [], environmentNames: [], enabled: true, allowedTools: [],
    capabilityMappings: {}, hasBearerToken: false, secretRotatedAt: null,
  };
  await page.route(serversUrl, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([server]),
  }));
  await page.route(runtimeServersUrl, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([runtime]),
  }));
  await page.route(diagnosticsUrl, (route) => {
    diagnosticRequested = true;
    return route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({
        connection: 'runtime-demo', transport: 'HTTP', enabled: true, connected: true, toolCount: 1,
        toolDiff: { comparedAt: new Date().toISOString(), added: ['search_v2'], removed: ['search'],
          schemaChanged: ['summarize'] },
        conflicts: [], capabilityMappings: {}, healthHistory: [],
      }),
    });
  });

  await page.goto(`${BASE}/#/tools`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#servers .server');
  await page.locator('#servers .server').getByRole('button', { name: 'Diagnostic', exact: true }).click();
  await page.waitForSelector('.mcp-diagnostics');
  assert.ok(diagnosticRequested, 'la requête de diagnostic doit viser le serveur sélectionné');
  const diff = await page.$eval('.mcp-tool-diff', (node) => node.innerText);
  assert.match(diff, /Ajoutés · 1[\s\S]*search_v2/);
  assert.match(diff, /Supprimés · 1[\s\S]*search/);
  assert.match(diff, /Schéma modifié · 1[\s\S]*summarize/);

  await page.unroute(serversUrl);
  await page.unroute(runtimeServersUrl);
  await page.unroute(diagnosticsUrl);
});

await check('la base de connaissance affiche un état désactivé sans le confondre avec une panne', async () => {
  // kex.agent.knowledge.enabled vaut false par défaut et la CI ne le change pas : la route répond
  // réellement 404 ici, sans simulation — le cas exact que le piège documenté (une route éteinte
  // n'est pas une panne) couvre.
  await page.waitForSelector('#knowledge-panel input[type=search]');
  await page.fill('#knowledge-panel input[type=search]', 'rétention');
  await page.click('#knowledge-panel button[type=submit]');
  await page.waitForFunction(() =>
    document.querySelector('#knowledge-panel').textContent.includes('désactivée'));
});

await check('la recherche affiche les passages renvoyés, et retirer un document le retire de l’écran', async () => {
  // Aucune liste exhaustive côté serveur : simulée pour exercer le rendu des résultats sans
  // dépendre d'un modèle d'embeddings réel, hors de portée de la CI.
  const matches = [{ id: 'doc-1', text: 'La rétention des topics demo est de 7 jours.',
    metadata: { source: 'runbook' }, score: 0.83 }];
  let deletedIds = null;
  await page.route('**/api/agent/knowledge**', (route) => {
    const request = route.request();
    if (request.method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(matches) });
    }
    if (request.method() === 'DELETE') {
      deletedIds = request.postDataJSON();
      return route.fulfill({ status: 204 });
    }
    return route.fallback();
  });

  await page.click('#knowledge-panel button[type=submit]');
  await page.waitForSelector('#knowledge-panel .card');
  assert.match(await page.$eval('#knowledge-panel .card', (node) => node.textContent),
    /rétention[\s\S]*source : runbook/);

  await page.click('#knowledge-panel .card button:has-text("Retirer")');
  await page.waitForSelector('dialog#confirm[open]');
  await page.click('#confirm-accept');
  await page.waitForSelector('#knowledge-panel .card', { state: 'detached' });
  assert.deepEqual(deletedIds, ['doc-1']);

  await page.unroute('**/api/agent/knowledge**');
});

await check('ajouter un document envoie le texte et les métadonnées, puis referme le panneau', async () => {
  let posted = null;
  await page.route('**/api/agent/knowledge**', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    posted = route.request().postDataJSON();
    return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(['doc-2']) });
  });

  await page.click('#knowledge-panel button:has-text("Ajouter un document")');
  await page.waitForSelector('.knowledge-add');
  await page.locator('.knowledge-add textarea').nth(0).fill('Le déploiement Flink se fait le mardi.');
  await page.locator('.knowledge-add textarea').nth(1).fill('{"source":"runbook"}');
  await page.click('.knowledge-add button:has-text("Ajouter")');
  await page.waitForSelector('.knowledge-add', { state: 'detached' });
  assert.deepEqual(posted, [{ text: 'Le déploiement Flink se fait le mardi.', metadata: { source: 'runbook' } }]);

  await page.unroute('**/api/agent/knowledge**');
});

await check('les résumés durables s’affichent et se retirent depuis leur carte', async () => {
  const summary = {
    id: 'summary-1', owner: 'kex-agent-api', kind: 'SUMMARY', title: 'Purger les topics de test',
    markdown: 'Demande : Purger les topics de test\nRésultat : trois topics purgés.',
    evidence: 'Échange terminé normalement', conversationId: 'conv-1',
    createdAt: '2026-09-22T08:00:00Z', status: 'READY', reviewedBy: null, reviewedAt: null, reviewReason: null,
  };
  let removed = false;
  await page.route('**/api/agent/memory/summaries**', (route) => {
    const request = route.request();
    if (request.method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(removed ? [] : [summary]) });
    }
    if (request.method() === 'DELETE') {
      removed = true;
      return route.fulfill({ status: 204 });
    }
    return route.fallback();
  });

  await page.goto(`${BASE}/#/tools`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#summaries-list .card');
  assert.match(await page.$eval('#summaries-list', (node) => node.textContent), /Purger les topics de test/);

  await page.click('#summaries-list .card button:has-text("Oublier")');
  await page.waitForSelector('dialog#confirm[open]');
  await page.click('#confirm-accept');
  await page.waitForSelector('#summaries-list .card', { state: 'detached' });

  await page.unroute('**/api/agent/memory/summaries**');
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

await check('un outil qui attend des paramètres propose un exemple pré-rempli, jamais un objet vide',
  async () => {
    await page.route('**/api/agent/mcp/servers', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([{
        connection: 'kafka-explorer', serverName: 'kafka-explorer-mcp', version: '0.1.0',
        protocolVersion: '2025-11-25', initialized: true, circuitBreakerState: 'CLOSED',
        tools: [{
          name: 'kex_topics_prefill', description: 'Liste les topics.',
          inputSchema: {
            type: 'object', required: ['topic'],
            properties: { topic: { type: 'string' }, limit: { type: 'integer', default: 50 } },
          },
        }, {
          name: 'kex_ping_prefill', description: 'Sans paramètre.', inputSchema: { type: 'object', properties: {} },
        }],
      }]),
    }));
    await page.goto(`${BASE}/#/tools`, { waitUntil: 'networkidle' });
    // Un nom d'outil propre à ce cas, jamais réutilisé ailleurs dans la suite : `page.goto` vers un
    // hash déjà courant ne redéclenche pas le routage (pas de `hashchange` sur un fragment inchangé),
    // et sans ce repère la grille encore affichée par le cas précédent — pas la donnée qu'on vient de
    // mocker ici — serait ce que le clic suivant atteint.
    await page.waitForSelector('.tool-list .name:has-text("kex_ping_prefill")');
    // Un sélecteur re-résolu à chaque clic, jamais un handle gardé d'un appel à l'autre : le sondage
    // de fond peut re-rendre la grille entre les deux et détacher un handle capturé trop tôt.
    await page.click('#servers .server ul.tool-list li:nth-child(1) button');
    const prefilled = await page.$eval('.invoke textarea', (node) => JSON.parse(node.value));
    assert.deepEqual(prefilled, { topic: 'exemple', limit: 50 }, 'l’exemple doit couvrir chaque paramètre déclaré');
    await page.click('#servers .server ul.tool-list li:nth-child(2) button');
    const empty = await page.$eval('.invoke textarea', (node) => node.value);
    assert.equal(empty, '{}', 'un outil sans paramètre garde un objet vide, rien à y deviner');
    // Un panneau laissé ouvert suspend désormais le sondage de fond (voir le cas suivant) : le
    // refermer ici évite qu'il ne fige aussi la grille pour la prochaine vérification.
    await page.click('.invoke header button');
    await page.unroute('**/api/agent/mcp/servers');
  });

await check('un résultat affiché survit au sondage de fond, jusqu’à ce qu’on referme le panneau',
  async () => {
    // Défaut : la grille des serveurs MCP se reconstruisait entièrement à chaque sondage de fond
    // (15 s) comme à chaque clic sur Rafraîchir, panneau d'invocation ouvert ou non — un résultat
    // qu'on venait d'obtenir disparaissait sous les yeux, entre 9 et 19 s après l'appel selon le
    // moment où il tombait dans le cycle.
    let serverFetches = 0;
    await page.route('**/api/agent/mcp/servers', (route) => {
      serverFetches += 1;
      route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify([{
          connection: 'kafka-explorer', serverName: 'kafka-explorer-mcp', version: '0.1.0',
          protocolVersion: '2025-11-25', initialized: true, circuitBreakerState: 'CLOSED',
          tools: [{
            name: 'kex_ping_bgrefresh', description: 'Sans paramètre.',
            inputSchema: { type: 'object', properties: {} },
          }],
        }]),
      });
    });
    await page.route('**/api/agent/mcp/servers/*/tools/*', (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true }),
    }));

    await page.goto(`${BASE}/#/tools`, { waitUntil: 'networkidle' });
    // Nom propre à ce cas — voir le commentaire du cas précédent sur `page.goto` vers un hash déjà
    // courant.
    await page.waitForSelector('.tool-list .name:has-text("kex_ping_bgrefresh")');
    await page.click('#servers .server ul.tool-list button');
    await page.click('.invoke button.primary');
    await page.waitForFunction(() => document.querySelector('.invoke .dump.result')?.textContent.includes('ok'));
    const fetchesWithPanelOpen = serverFetches;

    // Même déclencheur que le cas du témoin de chargement plus haut : `online` relance un sondage
    // de fond sans attendre les 15 s réelles de REFRESH_MS.
    await page.evaluate(() => dispatchEvent(new Event('online')));
    await page.waitForTimeout(200);
    assert.equal(serverFetches, fetchesWithPanelOpen,
      'un panneau ouvert doit suspendre le rafraîchissement de la grille, pas seulement son affichage');
    const output = await page.$eval('.invoke .dump.result', (node) => node.textContent);
    assert.match(output, /"ok": true/, 'le résultat doit rester affiché tel quel');

    const refreshed = page.waitForResponse((response) => response.url().endsWith('/api/agent/mcp/servers'));
    await page.click('.invoke header button');
    await page.evaluate(() => dispatchEvent(new Event('online')));
    await refreshed;
    assert.ok(serverFetches > fetchesWithPanelOpen, 'refermer le panneau doit laisser reprendre le sondage');

    await page.unroute('**/api/agent/mcp/servers');
    await page.unroute('**/api/agent/mcp/servers/*/tools/*');
  });

await check('les arguments qui ne respectent pas le schéma d’un outil sont refusés sans appel réseau',
  async () => {
    await page.route('**/api/agent/mcp/servers', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([{
        connection: 'kafka-explorer', serverName: 'kafka-explorer-mcp', version: '0.1.0',
        protocolVersion: '2025-11-25', initialized: true, circuitBreakerState: 'CLOSED',
        tools: [{
          name: 'kex_topics_reject', description: 'Liste les topics.',
          inputSchema: { type: 'object', required: ['topic'], properties: { topic: { type: 'string' } } },
        }],
      }]),
    }));
    let called = false;
    await page.route('**/api/agent/mcp/servers/*/tools/*', (route) => {
      called = true;
      route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });
    await page.goto(`${BASE}/#/tools`, { waitUntil: 'networkidle' });
    // Nom propre à ce cas — voir le commentaire plus haut sur `page.goto` vers un hash déjà courant.
    await page.waitForSelector('.tool-list .name:has-text("kex_topics_reject")');
    await page.click('#servers .server ul.tool-list button');
    // Le champ est désormais pré-rempli d'un exemple valide : le vider pour tester le rejet lui-même.
    await page.fill('.invoke textarea', '{}');
    await page.click('.invoke button.primary');
    const output = await page.$eval('.invoke .dump.result', (node) => node.textContent);
    assert.match(output, /Arguments invalides/);
    assert.equal(called, false, 'la validation locale doit empêcher tout appel réseau');
    await page.click('.invoke header button');
    await page.unroute('**/api/agent/mcp/servers');
    await page.unroute('**/api/agent/mcp/servers/*/tools/*');
  });

await check('l’export CSV de l’audit déclenche un téléchargement', async () => {
  await page.goto(`${BASE}/#/audit`, { waitUntil: 'networkidle' });
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.click('#export-audit'),
  ]);
  assert.equal(download.suggestedFilename(), 'audit-kex-agent.csv');
});

await check('la sélection groupée approuve chaque décision cochée, sans nouvel endpoint de lot',
  async () => {
    await page.route('**/api/agent/supervision/decisions', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([decisionStub('d1'), decisionStub('d2')]),
    }));
    const approved = [];
    await page.route('**/api/agent/supervision/decisions/*/approve', (route) => {
      approved.push(route.request().url());
      route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ ...decisionStub('d1'), status: 'EXECUTED' }),
      });
    });
    await page.goto(`${BASE}/#/decisions`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#decisions-list .bulk-select');
    for (const box of await page.$$('#decisions-list .bulk-select')) await box.check();
    await page.waitForSelector('#decisions-bulk-bar:not([hidden])');
    await page.click('#decisions-bulk-approve');
    await page.click('#confirm-accept');
    await page.waitForSelector('#toasts .toast');
    assert.equal(approved.length, 2, `attendu 2 approbations, vu ${approved.length}`);
    await page.unroute('**/api/agent/supervision/decisions');
    await page.unroute('**/api/agent/supervision/decisions/*/approve');
  });

await check('la tendance des cycles se trace dès que deux cycles sont connus', async () => {
  await page.route('**/api/agent/supervision/cycles', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([cycleStub('c2', 3), cycleStub('c1', 1)]),
  }));
  await page.goto(`${BASE}/#/agent`, { waitUntil: 'networkidle' });
  await page.click('[data-agent-tab="performance"]');
  await page.waitForSelector('#performance svg.sparkline');
  await page.unroute('**/api/agent/supervision/cycles');
});

await check('la mise à jour de politique montre un diff avant/après, pas seulement l’état visé', async () => {
  await page.goto(`${BASE}/#/agent`, { waitUntil: 'networkidle' });
  await page.click('[data-agent-tab="general"]');
  await page.waitForSelector('#agent-form');
  const before = await page.$eval('input[name="mode"]:checked', (node) => node.value);
  const other = before === 'SUPERVISED' ? 'MANUAL' : 'SUPERVISED';
  await page.check(`input[name="mode"][value="${other}"]`);
  await page.click('#agent-form button[type=submit]');
  await page.waitForSelector('#confirm[open]');
  const body = await page.$eval('#confirm-body', (node) => node.textContent);
  assert.match(body, /→/, 'le diff doit montrer un avant → après, pas seulement le nouvel état');
  assert.ok(body.includes(before) && body.includes(other),
    `attendu ${before} et ${other} dans le diff, vu : ${body}`);
  // Annulé : ce cas ne doit pas laisser la politique du reste de la suite dans un état différent.
  await page.click('#confirm button[value=cancel]');
});

await check('un échange retrouvé dans l’historique se réaffiche sans rejouer l’appel au modèle',
  async () => {
    await page.route('**/api/agent/chat', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        conversationId: 'conv-histoire', content: 'Réponse simulée', tools: [],
        finishReason: 'end_turn', usage: null,
      }),
    }));
    await page.goto(`${BASE}/#/chat`, { waitUntil: 'networkidle' });
    await page.uncheck('#stream-mode');
    await page.fill('#prompt', 'Question de test pour l’historique');
    await page.click('#send');
    await page.waitForFunction(() => document.querySelector('#conversation-id')?.textContent === 'conv-histoire');

    await page.click('#open-history');
    await page.waitForSelector('#drawer:not([hidden])');
    const label = await page.$eval('#drawer-body .card h3', (node) => node.textContent);
    assert.match(label, /Question de test/);

    await page.click('#drawer-body .card button.primary');
    await page.waitForSelector('#drawer[hidden]', { state: 'attached' });
    const turns = await page.$$eval('#transcript li', (nodes) => nodes.length);
    assert.equal(turns, 2, 'la reprise doit réafficher le tour utilisateur et la réponse');
    await page.unroute('**/api/agent/chat');
  });

await check('plusieurs processus en anomalie au même cycle affichent un incident corrélé', async () => {
  await page.route('**/api/agent/supervision/overview', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify(overviewStub({
      incidents: [{
        cycleId: 'c1', detectedAt: new Date().toISOString(), processCount: 3,
        processNames: ['Order Integration', 'Billing', 'Shipping'], severity: 'ERROR', titles: ['Retard'],
      }],
    })),
  }));
  await page.goto(`${BASE}/#/overview`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#incident-banner .banner');
  const text = await page.$eval('#incident-banner .banner', (node) => node.textContent);
  assert.match(text, /Incident probable/);
  assert.match(text, /3 processus/);
  await page.unroute('**/api/agent/supervision/overview');
});

await check('un processus se met en maintenance, et une fenêtre active propose de la lever',
  async () => {
    let maintenanceActive = false;
    await page.route('**/api/agent/supervision/overview', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(overviewStub({
        maintenance: maintenanceActive
          ? [{ processId: 'order-integration', processName: 'Order Integration',
            until: new Date(Date.now() + 3600000).toISOString(), reason: 'Déploiement', declaredBy: 'x' }]
          : [],
      })),
    }));
    await page.route('**/api/agent/supervision/processes/order-integration/history', (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: '[]',
    }));
    await page.route('**/api/agent/supervision/processes/order-integration/maintenance', (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      maintenanceActive = true;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ processId: 'order-integration', processName: 'Order Integration',
          until: new Date(Date.now() + 7200000).toISOString(), reason: 'Déclarée depuis la console',
          declaredBy: 'ci-secret' }),
      });
    });

    await page.goto(`${BASE}/#/overview`, { waitUntil: 'networkidle' });
    await page.click('#overview-processes table.grid tbody tr');
    await page.waitForSelector('#drawer:not([hidden])');
    const declaration = page.waitForResponse((response) => response.url()
      .endsWith('/api/agent/supervision/processes/order-integration/maintenance')
      && response.request().method() === 'POST');
    await page.click('#drawer-body button:has-text("Mettre en maintenance")');
    await declaration;
    await page.waitForSelector('#drawer', { state: 'hidden' });

    // Un second passage, avec la fenêtre désormais active, doit proposer de la lever.
    await page.reload({ waitUntil: 'networkidle' });
    await page.click('#overview-processes table.grid tbody tr');
    await page.waitForSelector('#drawer:not([hidden])');
    const buttons = await page.$$eval('#drawer-body button', (nodes) => nodes.map((n) => n.textContent));
    assert.ok(buttons.some((text) => text.includes('Lever la maintenance')));

    await page.unroute('**/api/agent/supervision/overview');
    await page.unroute('**/api/agent/supervision/processes/order-integration/history');
    await page.unroute('**/api/agent/supervision/processes/order-integration/maintenance');
  });

/* ── Gouvernance : charte, file de revue, curation ────────────────────── */

await check('l’onglet Gouvernance bascule ses trois sections ensemble', async () => {
  await page.goto(`${BASE}/#/agent`, { waitUntil: 'networkidle' });
  await page.click('[data-agent-tab="governance"]');
  await page.waitForSelector('[data-agent-section="governance"]:not([hidden])');
  const hiddenCount = await page.$$eval('[data-agent-section="governance"]',
    (nodes) => nodes.filter((node) => node.hidden).length);
  assert.equal(hiddenCount, 0);
  assert.equal(await page.$eval('[data-agent-section="general"]', (node) => node.hidden), true);
});

await check('le menu du jeton affiche l’acteur et le locataire actifs', async () => {
  // Défaut possible : whoami() ne se déclenche qu'au changement de jeton (onCredentialChange), or
  // celui-ci vient de sessionStorage au premier chargement de la page et ne passe jamais par
  // credentials.set() — sans l'appel direct au démarrage, l'étiquette resterait vide indéfiniment.
  // L'étiquette vit dans le popover des actions secondaires (<details>), fermé par défaut : sans
  // l'ouvrir, elle reste hors écran même une fois remplie.
  await page.click('.action-menu summary');
  await page.waitForSelector('#whoami-label:not([hidden])', { timeout: 10000 });
  assert.match(await page.$eval('#whoami-label', (node) => node.textContent), /Connecté comme/);
  await page.click('.action-menu summary');
});

await check('la charte se rédige et affiche qui l’a écrite', async () => {
  await page.fill('#charter-markdown', 'Ne jamais redémarrer un consumer en heures ouvrées.');
  await page.fill('#charter-reason', 'Vérification navigateur');
  const written = page.waitForResponse((response) => response.url().endsWith('/api/agent/charter')
    && response.request().method() === 'PUT');
  await page.click('#charter-form button[type=submit]');
  await written;
  await page.waitForSelector('#charter-current .summary');
  assert.match(await page.$eval('#charter-current', (node) => node.textContent), /kex-agent-api/);
});

// Créée par appel direct, pas par un formulaire de proposition — il n'y en a pas dans la console,
// une compétence naît d'une conversation. Le point sous test ici est la revue, pas la proposition.
const proposal = await page.request.post(`${BASE}/api/agent/skills`, {
  headers: { Authorization: `Bearer ${TOKEN}` },
  data: {
    title: 'Vérifier le lag avant un redémarrage',
    markdown: '# Procédure\n1. Vérifier kex_consumer_lag avant tout redémarrage.',
    evidence: 'Trois incidents évités', conversationId: null,
  },
});
assert.equal(proposal.status(), 201, 'la proposition de compétence a échoué en amont du test');
const proposed = await proposal.json();

await check('la file de revue affiche la compétence proposée, tous propriétaires confondus', async () => {
  await page.reload({ waitUntil: 'networkidle' });
  await page.click('[data-agent-tab="governance"]');
  await page.waitForSelector('#skills-review-queue article.card');
  assert.match(await page.$eval('#skills-review-queue', (node) => node.textContent),
    /Vérifier le lag avant un redémarrage/);
});

await check('approuver la fait disparaître de la file et apparaître dans la bibliothèque', async () => {
  const decided = page.waitForResponse((response) =>
    response.url().endsWith(`/api/agent/skills/${proposed.id}/approve`));
  await page.click('#skills-review-queue article.card button:has-text("Approuver")');
  await page.waitForSelector('dialog#confirm[open]');
  await page.click('#confirm-accept');
  await decided;
  await page.waitForSelector('#skills-review-queue [data-empty-state]');
  assert.match(await page.$eval('#skills-curation', (node) => node.textContent),
    /Vérifier le lag avant un redémarrage/);
});

// Le bouton Retirer ne rend que sur ce qui dépasse le plafond d'injection (5 par défaut) ou qui
// n'a pas été revu depuis longtemps — une compétence tout juste approuvée reste "Injectée", sans
// bouton. Cinq approbations supplémentaires repoussent la première "En sommeil" et lui en donnent
// un ; SkillsService.ranked classe la plus récemment approuvée d'abord, donc la première approuvée
// finit dernière — exactement la position que skip(limit) découvre.
for (let i = 0; i < 5; i += 1) {
  const filler = await page.request.post(`${BASE}/api/agent/skills`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
    data: {
      title: `Compétence de remplissage ${i}`,
      markdown: `# Procédure ${i}\nÉtape unique.`,
      evidence: 'Remplissage pour dépasser le plafond d’injection', conversationId: null,
    },
  });
  assert.equal(filler.status(), 201, 'une proposition de remplissage a échoué en amont du test');
  const fillerEntry = await filler.json();
  const fillerApproved = await page.request.post(`${BASE}/api/agent/skills/${fillerEntry.id}/approve`, {
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
    data: { reason: 'Remplissage de test' },
  });
  assert.equal(fillerApproved.status(), 200, 'une approbation de remplissage a échoué en amont du test');
}

// Ciblé par aria-label, pas par texte générique : plusieurs compétences peuvent devenir dormantes
// à la fois, chacune avec son propre bouton « Retirer ».
const retireButton = `#skills-curation button[aria-label="Retirer : ${proposed.title}"]`;

await check('annuler un retrait fonctionne sans motif rempli (formnovalidate)', async () => {
  // Défaut possible : un textarea required dans le même <form method="dialog"> que le bouton
  // Confirmer bloquerait aussi Annuler côté navigateur, sans formnovalidate sur ce bouton-là.
  // Un aller-retour de vue plutôt qu'un rechargement complet : route() ne recharge un écran que
  // sur un changement de vue détecté, et c'est ce détour qui fait relire la curation à jour.
  await page.goto(`${BASE}/#/overview`, { waitUntil: 'domcontentloaded' });
  await page.goto(`${BASE}/#/agent`, { waitUntil: 'domcontentloaded' });
  await page.click('[data-agent-tab="governance"]');
  await page.waitForSelector(retireButton);
  await page.click(retireButton);
  await page.waitForSelector('dialog#confirm[open]');
  await page.click('#confirm button[value=cancel]');
  // Un <dialog> sans l'attribut open devient display:none par défaut : attendre qu'il « ne
  // matche plus [open] » avec l'état visible implicite de waitForSelector ne se résout jamais,
  // puisqu'un dialogue fermé n'est justement plus visible. C'est sa disparition qu'il faut
  // attendre, pas une variante du même sélecteur.
  await page.waitForSelector('dialog#confirm', { state: 'hidden' });
  assert.match(await page.$eval('#skills-curation', (node) => node.textContent),
    /Vérifier le lag avant un redémarrage/);
});

await check('retirer avec un motif la fait disparaître de la bibliothèque', async () => {
  // Les cinq compétences de remplissage restent approuvées et injectées : la bibliothèque n'est pas
  // vide après ce retrait, seule la compétence dormante retirée en a disparu.
  await page.click(retireButton);
  await page.waitForSelector('dialog#confirm[open]');
  await page.fill('#confirm-reason', 'Vérification navigateur : nettoyage');
  const removed = page.waitForResponse((response) =>
    response.url().endsWith(`/api/agent/skills/${proposed.id}/retire`));
  const refreshed = page.waitForResponse((response) =>
    response.url().endsWith('/api/agent/skills/curation'));
  await page.click('#confirm-accept');
  await removed;
  await refreshed;
  const text = await page.$eval('#skills-curation', (node) => node.textContent);
  assert.doesNotMatch(text, /Vérifier le lag avant un redémarrage/);
});

await check('l’onglet Automatisations affiche un état désactivé sans le confondre avec une panne', async () => {
  // kex.agent.automation.enabled et le profil shared-memory ne sont pas actifs en CI : la route
  // répond réellement 404 ici, sans simulation — même piège documenté que pour la mémoire durable
  // et la base de connaissance.
  await page.goto(`${BASE}/#/agent`, { waitUntil: 'networkidle' });
  await page.click('[data-agent-tab="automations"]');
  await page.waitForSelector('[data-agent-section="automations"]:not([hidden])');
  await page.waitForFunction(() =>
    document.querySelector('#automations-list').textContent.includes('désactivées'));
});

await check('l’automatisation planifiée se supprime depuis la liste', async () => {
  const row = {
    id: 'auto-1', owner: 'kex-agent-api', name: 'Purge des topics de test',
    prompt: 'Lister les topics de test et signaler ceux à purger.',
    cron: '0 0 8 * * MON-FRI', zone: 'Europe/Paris', enabled: true,
    nextRun: '2026-09-23T08:00:00Z', lastRun: null, status: 'IDLE', result: '',
    createdAt: '2026-09-22T08:00:00Z', updatedAt: '2026-09-22T08:00:00Z',
  };
  let deletedId = null;
  let removed = false;
  await page.route('**/api/agent/automations**', (route) => {
    const request = route.request();
    if (request.method() === 'GET' && request.url().endsWith('/api/agent/automations')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(removed ? [] : [row]) });
    }
    if (request.method() === 'DELETE') {
      deletedId = request.url().split('/').pop();
      removed = true;
      return route.fulfill({ status: 204 });
    }
    return route.fallback();
  });

  // Un aller-retour de vue plutôt qu'un rechargement complet : la même adresse ne redéclenche
  // pas route() (voir CLAUDE.md), donc pas automations.panel() — le détour par une autre vue
  // fait relire la liste sous la simulation qui vient d'être posée.
  await page.goto(`${BASE}/#/overview`, { waitUntil: 'domcontentloaded' });
  await page.goto(`${BASE}/#/agent`, { waitUntil: 'domcontentloaded' });
  await page.click('[data-agent-tab="automations"]');
  await page.waitForSelector('#automations-list table.grid');
  assert.match(await page.$eval('#automations-list', (node) => node.textContent), /Purge des topics de test/);

  await page.click('#automations-list button:has-text("Supprimer")');
  await page.waitForSelector('dialog#confirm[open]');
  await page.click('#confirm-accept');
  await page.waitForSelector('#automations-list tbody tr', { state: 'detached' });
  assert.equal(deletedId, 'auto-1');

  await page.unroute('**/api/agent/automations**');
});

await check('créer une automatisation envoie le cron et le prompt, puis referme le panneau', async () => {
  let posted = null;
  await page.route('**/api/agent/automations', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    posted = route.request().postDataJSON();
    return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({
      id: 'auto-2', owner: 'kex-agent-api', ...posted, nextRun: '2026-09-23T08:00:00Z', lastRun: null,
      status: 'IDLE', result: '', createdAt: '2026-09-22T08:00:00Z', updatedAt: '2026-09-22T08:00:00Z',
    }) });
  });

  await page.click('#create-automation');
  await page.waitForSelector('#automation-form-dialog[open]');
  await page.fill('#automation-name', 'Rapport hebdomadaire des topics inactifs');
  await page.fill('#automation-prompt', 'Lister les topics sans production depuis 7 jours.');
  await page.fill('#automation-cron', '0 0 8 * * MON');
  await page.fill('#automation-zone', 'Europe/Paris');
  await page.click('#submit-automation-form');
  await page.waitForSelector('#automation-form-dialog', { state: 'hidden' });
  assert.deepEqual(posted, {
    name: 'Rapport hebdomadaire des topics inactifs',
    prompt: 'Lister les topics sans production depuis 7 jours.',
    cron: '0 0 8 * * MON', zone: 'Europe/Paris', enabled: true,
  });

  await page.unroute('**/api/agent/automations');
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
