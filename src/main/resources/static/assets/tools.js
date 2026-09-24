// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Espaces plateforme : connexions MCP/Kafka, connaissance et santé système. Elle existe pour que le tableau de
// bord métier n'en soit pas saturé — les signaux bruts sont au second niveau, jamais au premier.

import {
  $, api, busy, circuitStateTag, confirmAction, definition, el, empty, exampleFromSchema, openDrawer,
  params, registerDrawer, render, report, schemaErrors, setDrawerParam, setParams, stateTag, toast,
} from './core.js';
import * as kafka from './kafka.js';
import * as knowledge from './knowledge.js';
import * as memory from './memory.js';
import * as summaries from './summaries.js';

// Cache du dernier relevé : la recherche filtre dessus plutôt que de refaire un appel réseau par
// caractère saisi — l'endpoint n'a pas de paramètre de recherche et n'a pas à en gagner un pour ça.
let lastServers = [];
let lastMetrics = [];
let runtimeServers = new Map();
let editingConnection = null;
let rotatingConnection = null;
let installTarget = null;
let catalogInstallTarget = null;

// Miroir du libellé français porté par McpTrustCriterion côté serveur (voir sa javadoc) : le JSON
// ne rend que le nom de la constante, comme les autres énumérations d'état de cette console —
// c'est stateTag/LABELS, dans core.js, qui pose déjà cette même règle pour AgentState et consorts.
const CRITERIA_LABELS = {
  OFFICIAL_PUBLISHER: 'Éditeur officiel / identité vérifiée',
  VERIFIABLE_PROVENANCE: 'Provenance source → build vérifiable',
  ARTIFACT_SIGNATURE: "Signature / attestation de l'artefact",
  SBOM_AVAILABLE: 'SBOM disponible',
  DEPENDENCY_CVE_SCAN: 'Analyse CVE / dépendances',
  ACTIVELY_MAINTAINED: 'Projet activement maintenu',
  MINIMAL_PERMISSIONS: 'Permissions minimales',
  DOCUMENTED_TOOLS: 'Outils et effets de bord documentés',
  CONTAINER_ISOLATION: 'Isolation / conteneur disponible',
  ADOPTION_REPUTATION: 'Réputation / adoption',
};

const CRITERION_STATE_LABELS = { MET: 'Validé', NOT_MET: 'Non validé', UNKNOWN: 'Non mesuré' };

export async function servers() {
  // Un panneau d'invocation ouvert porte un résultat en train d'être lu — celui du sondage de fond
  // comme celui d'un clic sur Rafraîchir : le reconstruire depuis zéro le perdrait sans qu'aucune
  // donnée nouvelle ne le justifie. Il reprend au prochain appel une fois le panneau refermé.
  if ($('#servers').querySelector('.invoke')) return;
  await render($('#servers'), async () => {
    const [connected, metrics, runtime, storage] = await Promise.all([
      api('/api/agent/mcp/servers'),
      // Un serveur MCP jamais appelé n'a simplement pas encore de métrique : ce n'est pas une panne.
      api('/api/agent/mcp/metrics').catch(() => []),
      api('/api/agent/mcp/runtime-servers').catch(() => []),
      api('/api/agent/mcp/storage').catch(() => null),
    ]);
    runtimeServers = new Map(runtime.map((server) => [server.connection, server]));
    const seen = new Set(connected.map((server) => server.connection));
    lastServers = connected.concat(runtime.filter((server) => !seen.has(server.connection)).map((server) => ({
      connection: server.connection, initialized: false, tools: [], disabled: !server.enabled,
    })));
    lastMetrics = metrics;
    renderStorageStatus(storage);
    return lastServers;
  }, renderServers);
}

function renderServers(list) {
  const query = (params().get('q') || '').trim().toLowerCase();
  const filtered = query ? list.filter((server) => matchesQuery(server, query)) : list;
  if (!filtered.length) {
    return query
      ? empty('Aucun serveur ni outil ne correspond à la recherche.')
      : empty('Aucune connexion MCP configurée.',
        'Sans outil, l’agent ne peut qu’observer ce qu’on lui raconte.');
  }
  const grid = el('div', 'servers-grid');
  filtered.forEach((server) => grid.append(card(server)));
  return grid;
}

function matchesQuery(server, query) {
  if (server.connection.toLowerCase().includes(query)) return true;
  if (server.serverName?.toLowerCase().includes(query)) return true;
  return (server.tools || []).some((tool) => tool.name.toLowerCase().includes(query));
}

function metricFor(connection, tool) {
  return lastMetrics.find((candidate) => candidate.connection === connection && candidate.tool === tool);
}

function card(server) {
  const node = el('article', 'server');
  const managed = runtimeServers.get(server.connection);
  if (managed && !managed.enabled) node.classList.add('disabled');
  const head = el('header');
  // Le serveur s'adresse par clé de connexion : le nom qu'il annonce n'existe qu'après le handshake.
  head.append(el('h3', null, server.connection));
  head.append(stateTag(managed && !managed.enabled ? 'UNKNOWN' : (server.initialized ? 'OK' : 'UNKNOWN'),
    managed && !managed.enabled ? 'Désactivé' : (server.initialized ? 'Initialisé' : 'Pas de handshake')));
  // Propre à cette connexion pour l'appel direct ; le chemin piloté par le modèle reste sur un
  // disjoncteur partagé entre serveurs, voir McpServerInfo.circuitBreakerState.
  if (server.circuitBreakerState) {
    head.append(circuitStateTag(server.circuitBreakerState, `Disjoncteur : ${server.circuitBreakerState}`));
  }
  node.append(head);

  const meta = [server.serverName, server.version, server.protocolVersion].filter(Boolean).join(' · ');
  node.append(el('p', 'muted', meta || 'Aucun handshake abouti pour l’instant'));

  if (managed) {
    const flags = el('div', 'server-flags');
    flags.append(el('span', 'badge', managed.transport));
    if (managed.hasBearerToken) flags.append(el('span', 'badge', 'Bearer chiffré'));
    if (managed.allowedTools?.length) flags.append(el('span', 'badge', `${managed.allowedTools.length} permission(s)`));
    const mappings = Object.keys(managed.capabilityMappings || {}).length;
    if (mappings) flags.append(el('span', 'badge', `${mappings} capacité(s)`));
    node.append(flags);
  }

  const tools = server.tools || [];
  if (tools.length) {
    const list = el('ul', 'tool-list');
    tools.forEach((tool) => {
      const item = el('li');
      const button = el('button');
      button.type = 'button';
      button.append(el('span', 'name', tool.name));
      if (tool.description) button.append(el('span', 'desc', tool.description));
      const metric = metricFor(server.connection, tool.name);
      if (metric) {
        const label = metric.averageDurationMs == null
          ? `${metric.callCount} appel(s)`
          : `${metric.callCount} appel(s) · ${Math.round(metric.averageDurationMs)} ms en moyenne`;
        button.append(el('span', 'muted', label));
      }
      button.addEventListener('click', () => invoke(node, server.connection, tool));
      item.append(button);
      list.append(item);
    });
    node.append(list);
  } else {
    node.append(empty('Aucun outil exposé.'));
  }

  const actions = el('div', 'server-actions');
  const resources = el('button', 'ghost', 'Ressources');
  resources.type = 'button';
  resources.disabled = Boolean(managed && !managed.enabled);
  // Répété une fois par serveur : sans libellé, un lecteur d'écran n'entend qu'« Ressources ».
  resources.setAttribute('aria-label', `Ressources de ${server.connection}`);
  resources.addEventListener('click', () => listResources(node, server.connection));
  actions.append(resources);
  if (managed) {
    const toggle = el('button', 'ghost', managed.enabled ? 'Désactiver' : 'Activer');
    toggle.type = 'button';
    toggle.addEventListener('click', () => busy(toggle, async () => {
      try {
        await api(`/api/agent/mcp/servers/${encodeURIComponent(server.connection)}/enabled?enabled=${!managed.enabled}`,
          { method: 'POST' });
        toast(`Serveur MCP « ${server.connection} » ${managed.enabled ? 'désactivé' : 'activé'}.`);
        await servers();
      } catch (error) { report(error); }
    }));
    const edit = el('button', 'ghost', 'Modifier');
    edit.type = 'button';
    edit.addEventListener('click', () => openEditor(managed));
    const refresh = el('button', 'ghost', 'Rafraîchir les outils');
    refresh.type = 'button';
    refresh.addEventListener('click', () => busy(refresh, async () => {
      try {
        const diagnostics = await api(`/api/agent/mcp/servers/${encodeURIComponent(server.connection)}/refresh`,
          { method: 'POST' });
        showDiagnostics(node, diagnostics);
        toast(`Catalogue « ${server.connection} » rafraîchi.`);
        await servers();
      } catch (error) { report(error); }
    }));
    const diagnostic = el('button', 'ghost', 'Diagnostic');
    diagnostic.type = 'button';
    diagnostic.addEventListener('click', async () => {
      try {
        showDiagnostics(node, await api(`/api/agent/mcp/servers/${encodeURIComponent(server.connection)}/diagnostics`));
      } catch (error) { report(error); }
    });
    const rotate = el('button', 'ghost', 'Secret');
    rotate.type = 'button';
    rotate.addEventListener('click', () => openSecretRotation(server.connection));
    actions.append(toggle, edit, refresh, diagnostic, rotate);
    const remove = el('button', 'ghost danger', 'Retirer');
    remove.type = 'button';
    remove.setAttribute('aria-label', `Retirer le serveur ${server.connection}`);
    remove.addEventListener('click', async () => {
      const confirmed = await confirmAction({
        title: 'Retirer ce serveur MCP ?',
        accept: 'Retirer le serveur',
        lines: [['Connexion', server.connection], ['Effet', 'Ses outils ne seront plus disponibles pour l’agent.']],
      });
      if (!confirmed) return;
      try {
        await api(`/api/agent/mcp/servers/${encodeURIComponent(server.connection)}`, { method: 'DELETE' });
        toast(`Serveur MCP « ${server.connection} » retiré.`);
        await servers();
      } catch (error) { report(error); }
    });
    actions.append(remove);
  }
  node.append(actions);
  return node;
}

function renderStorageStatus(storage) {
  const host = $('#mcp-storage-status');
  if (!storage) {
    host.textContent = 'État du stockage indisponible.';
    return;
  }
  host.textContent = storage.encryptedPersistence
    ? `Persistance chiffrée active · ${storage.configuredServers} serveur(s) administré(s)`
    : 'Mode mémoire : définissez KEX_MCP_STORAGE_KEY pour conserver les serveurs et secrets après redémarrage.';
}

/**
 * Deux points d'accès distants choisis par l'équipe, pas un relevé tiers : contrairement à
 * discover() ci-dessous, cette liste est statique côté serveur (McpRecommendedCatalog.ENTRIES) et
 * se charge donc avec le reste de la vue, sans bouton dédié ni appel réseau externe à chaque passage.
 */
export async function catalog() {
  await render($('#mcp-catalog'), () => api('/api/agent/mcp/catalog'), renderCatalog);
}

function renderCatalog(entries) {
  if (!entries.length) return empty('Aucune entrée dans le catalogue recommandé.');
  const grid = el('div', 'cards');
  entries.forEach((entry) => grid.append(catalogEntryCard(entry)));
  return grid;
}

function catalogEntryCard(entry) {
  const card = el('div', 'card');
  const header = el('header');
  header.append(el('h3', null, entry.name));
  if (entry.requiresToken) header.append(el('span', 'badge', 'Jeton requis'));
  card.append(header);
  if (entry.description) card.append(el('p', null, entry.description));
  card.append(el('p', 'muted', `${entry.url}${entry.endpoint}`));
  card.append(el('p', 'hint', `${entry.allowedTools.length} outil(s) autorisé(s)`));
  if (entry.documentation) {
    const link = el('a', null, 'Documentation');
    link.href = entry.documentation;
    link.target = '_blank';
    link.rel = 'noopener';
    card.append(link);
  }
  const actions = el('div', 'card-actions');
  const install = el('button', 'ghost', 'Installer');
  install.type = 'button';
  install.setAttribute('aria-label', `Installer ${entry.name}`);
  install.addEventListener('click', () => openInstallCatalogDialog(entry));
  actions.append(install);
  card.append(actions);
  return card;
}

function openInstallCatalogDialog(entry) {
  catalogInstallTarget = entry.id;
  $('#install-catalog-label').textContent =
    `${entry.name} — la connexion est créée désactivée, à activer ensuite dans « Connexions MCP » `
      + 'une fois vérifiée.';
  $('#install-catalog-form').reset();
  $('#install-catalog-connection').value = entry.id;
  // Refusé côté serveur dans les deux sens : un jeton manquant sur une entrée qui l'exige, ou un
  // jeton fourni sur une entrée publique qui n'en accepte aucun (McpRecommendedCatalog.install).
  $('#install-catalog-token-field').hidden = !entry.requiresToken;
  $('#install-catalog-token').required = entry.requiresToken;
  $('#install-catalog').showModal();
  $('#install-catalog-connection').focus();
}

/**
 * Interrogée à la demande, jamais au sondage de fond : chaque appel contacte un service tiers
 * (Docker Hub ou le registre officiel MCP) — voir le hint du panneau dans index.html.
 */
export async function discover() {
  await render($('#mcp-discovery'), () => api('/api/agent/mcp/catalog/discover'), renderDiscovery);
}

function renderDiscovery(sources) {
  if (!sources.length) return empty('Aucune source de découverte configurée.');
  const container = el('div', 'stack');
  sources.forEach((source) => container.append(sourceSection(source)));
  return container;
}

function sourceSection(source) {
  const section = el('div');
  section.append(el('h3', null, source.label));
  if (!source.enabled) {
    section.append(empty(`${source.label} est désactivée.`,
      'kex.mcp.catalog.sources.* reste éteint par défaut, comme chaque extension réseau de l’agent.'));
    return section;
  }
  if (source.error) {
    section.append(empty(`${source.label} est injoignable.`, source.error));
    return section;
  }
  if (!source.candidates.length) {
    section.append(empty('Aucun candidat renvoyé par cette source.'));
    return section;
  }
  const grid = el('div', 'cards');
  source.candidates.forEach((entry) => grid.append(candidateCard(source.sourceId, entry)));
  section.append(grid);
  return section;
}

function candidateCard(sourceId, entry) {
  const { candidate, score } = entry;
  const card = el('div', 'card');
  if (!score.eligible) card.dataset.state = 'ERROR';
  const header = el('header');
  header.append(el('h3', null, candidate.title || candidate.name));
  header.append(el('span', 'time', `${score.total}/100`));
  card.append(header);
  if (candidate.description) card.append(el('p', null, candidate.description));
  if (!score.eligible) {
    card.append(el('p', 'hint danger', `Disqualifié : ${score.disqualifiers.join(' ; ')}`));
  }
  const details = document.createElement('details');
  const summary = document.createElement('summary');
  summary.textContent = 'Détail de la note';
  details.append(summary);
  const list = el('ul');
  score.criteria.forEach((result) => list.append(el('li', null,
    `${CRITERIA_LABELS[result.criterion] || result.criterion} — `
      + `${CRITERION_STATE_LABELS[result.state] || result.state} (${result.awardedPoints} pt) : ${result.reason}`)));
  details.append(list);
  card.append(details);
  const actions = el('div', 'card-actions');
  const install = el('button', 'ghost', 'Installer');
  install.type = 'button';
  install.setAttribute('aria-label', `Installer ${candidate.title || candidate.name}`);
  install.disabled = !score.eligible;
  if (!score.eligible) install.title = 'Candidat disqualifié : voir le détail de la note ci-dessus.';
  install.addEventListener('click', () => openInstallDialog(sourceId, candidate));
  actions.append(install);
  card.append(actions);
  return card;
}

function openInstallDialog(sourceId, candidate) {
  installTarget = { sourceId, candidateId: candidate.id };
  $('#install-discovered-label').textContent =
    `${candidate.title || candidate.name} (${sourceId}) — la connexion est créée désactivée, `
      + "à activer ensuite dans « Connexions MCP » une fois vérifiée.";
  $('#install-discovered-form').reset();
  $('#install-discovered-connection').value = candidate.name.replace(/[^a-zA-Z0-9._-]/g, '-').slice(0, 64);
  $('#install-discovered').showModal();
  $('#install-discovered-connection').focus();
}

function showDiagnostics(host, diagnostics) {
  host.querySelector('.mcp-diagnostics')?.remove();
  const panel = el('section', 'mcp-diagnostics');
  const head = el('header');
  head.append(el('h4', null, `Diagnostic · ${diagnostics.transport}`), closeButton(panel));
  panel.append(head);
  const summary = el('p', 'muted', `${diagnostics.connected ? 'Connecté' : 'Hors ligne'} · ${diagnostics.toolCount} outil(s)`);
  panel.append(summary);
  if (diagnostics.toolDiff) panel.append(renderToolDiff(diagnostics.toolDiff));
  if (diagnostics.conflicts?.length) {
    const title = el('strong', null, 'Conflits détectés');
    const conflicts = el('ul');
    diagnostics.conflicts.forEach((conflict) => conflicts.append(el('li', null, conflict)));
    panel.append(title, conflicts);
  }
  if (diagnostics.healthHistory?.length) {
    const history = el('div');
    diagnostics.healthHistory.slice(0, 8).forEach((sample) => {
      const line = el('div', 'mcp-health-line');
      line.append(stateTag(sample.healthy ? 'OK' : 'ERROR', sample.healthy ? 'OK' : 'Échec'),
        el('time', 'muted', new Date(sample.checkedAt).toLocaleString('fr-FR')),
        el('span', null, `${sample.latencyMillis ?? '—'} ms · ${sample.message}`));
      history.append(line);
    });
    panel.append(el('strong', null, 'Historique de santé'), history);
  }
  host.append(panel);
}

function renderToolDiff(diff) {
  const section = el('section', 'mcp-tool-diff');
  const title = el('div', 'mcp-diff-title');
  title.append(el('strong', null, 'Diff du catalogue'));
  if (diff.comparedAt) title.append(el('time', 'muted', new Date(diff.comparedAt).toLocaleString('fr-FR')));
  section.append(title);
  const hasChanges = (diff.added?.length || 0) + (diff.removed?.length || 0)
    + (diff.schemaChanged?.length || 0) > 0;
  if (!hasChanges) {
    section.append(el('p', 'hint', 'Aucun changement depuis le rafraîchissement précédent.'));
    return section;
  }
  const groups = [
    ['Ajoutés', diff.added || [], 'added'],
    ['Supprimés', diff.removed || [], 'removed'],
    ['Schéma modifié', diff.schemaChanged || [], 'changed'],
  ];
  groups.filter(([, names]) => names.length).forEach(([label, names, kind]) => {
    const row = el('div', `mcp-diff-group ${kind}`);
    row.append(el('span', 'mcp-diff-label', `${label} · ${names.length}`));
    const chips = el('div', 'chips');
    names.forEach((name) => chips.append(el('code', 'chip', name)));
    row.append(chips);
    section.append(row);
  });
  return section;
}

/**
 * Le sondage de fond laisse désormais un panneau ouvert tranquille (voir `servers()`) : sans ce
 * bouton, la seule façon de le refermer serait d'en rouvrir un autre, ce qui suspendrait le
 * rafraîchissement de la grille jusque-là sans le moindre signe visible de pourquoi.
 */
function closeButton(panel) {
  const close = el('button', 'ghost', 'Fermer');
  close.type = 'button';
  close.addEventListener('click', () => panel.remove());
  return close;
}

function invoke(host, connection, tool) {
  host.querySelector('.invoke')?.remove();
  const panel = el('section', 'invoke');
  const head = el('header');
  head.append(el('h4', null, tool.name), closeButton(panel));
  panel.append(head);
  // Le schéma vient du serveur, jamais réinterprété : il dit ce que l'outil attend, pas ce qu'on
  // devine en tapant "{}" et en lisant l'erreur qui revient.
  if (tool.inputSchema && Object.keys(tool.inputSchema).length) {
    panel.append(el('pre', 'dump muted schema-hint', JSON.stringify(tool.inputSchema, null, 2)));
  }

  const args = el('textarea');
  args.rows = 4;
  args.spellcheck = false;
  // Un "{}" nu ne dit rien de ce qu'un outil à paramètres attend : un exemple conforme au schéma
  // vaut mieux qu'un objet vide à déchiffrer depuis la seule lecture du schéma affiché au-dessus.
  const properties = tool.inputSchema?.properties || {};
  args.value = Object.keys(properties).length
    ? JSON.stringify(exampleFromSchema(tool.inputSchema), null, 2)
    : '{}';
  // Deux ".dump" dans le même panneau une fois le schéma affiché : "result" les distingue, sans
  // quoi un sélecteur qui cible l'un des deux tombe sur le premier trouvé, pas forcément le bon.
  const output = el('pre', 'dump result', '—');

  const run = el('button', 'primary', 'Invoquer');
  run.type = 'button';
  run.addEventListener('click', () => {
    let parsed;
    try {
      parsed = JSON.parse(args.value || '{}');
    } catch {
      output.textContent = 'Arguments JSON invalides.';
      return;
    }
    // Un sous-ensemble du schéma, pas une validation complète (voir schemaErrors dans core.js) :
    // attraper une erreur de frappe ici évite l'aller-retour serveur, sans prétendre remplacer le
    // serveur MCP comme seule autorité sur ce qu'il accepte réellement.
    if (tool.inputSchema) {
      const errors = schemaErrors(parsed, tool.inputSchema, 'arguments');
      if (errors.length) {
        output.textContent = `Arguments invalides :\n- ${errors.join('\n- ')}`;
        return;
      }
    }
    output.textContent = '…';
    busy(run, async () => {
      try {
        const result = await api(
          `/api/agent/mcp/servers/${encodeURIComponent(connection)}/tools/${encodeURIComponent(tool.name)}`,
          { method: 'POST', body: { arguments: parsed } });
        output.textContent = JSON.stringify(result, null, 2);
      } catch (error) {
        output.textContent = error.message;
        report(error);
      }
    });
  });

  const row = el('div', 'row-end');
  row.append(run);
  panel.append(args, row, output);
  host.append(panel);
  args.focus();
}

async function listResources(host, connection) {
  host.querySelector('.invoke')?.remove();
  const panel = el('section', 'invoke');
  const head = el('header');
  head.append(el('h4', null, 'Ressources'), closeButton(panel));
  panel.append(head);
  const output = el('pre', 'dump', '…');
  panel.append(output);
  host.append(panel);
  try {
    const resources = await api(`/api/agent/mcp/servers/${encodeURIComponent(connection)}/resources`);
    if (!resources.length) {
      output.textContent = 'Aucune ressource exposée.';
      return;
    }
    output.textContent = '—';
    const list = el('ul', 'tool-list');
    resources.forEach((resource) => {
      const button = el('button');
      button.type = 'button';
      button.append(el('span', 'name', resource.name || resource.uri));
      button.append(el('span', 'desc', resource.mimeType || resource.uri));
      button.addEventListener('click', async () => {
        output.textContent = '…';
        try {
          const content = await api(`/api/agent/mcp/servers/${encodeURIComponent(connection)}`
            + `/resource?uri=${encodeURIComponent(resource.uri)}`);
          output.textContent = JSON.stringify(content, null, 2);
        } catch (error) {
          output.textContent = error.message;
        }
      });
      const item = el('li');
      item.append(button);
      list.append(item);
    });
    panel.insertBefore(list, output);
  } catch (error) {
    output.textContent = error.message;
  }
}

async function metric(name) {
  try {
    const body = await api(`/actuator/metrics/${encodeURIComponent(name)}`);
    const measurement = body.measurements?.find((m) => m.statistic === 'VALUE' || m.statistic === 'COUNT');
    return measurement ? measurement.value : null;
  } catch {
    // Une métrique absente (modèle jamais appelé, endpoint filtré) n'est pas une panne.
    return null;
  }
}

function tile(label, value) {
  const node = el('div', 'tile');
  node.append(el('div', 'label', label), el('div', 'value', value));
  return node;
}

export async function health() {
  const tiles = $('#metric-tiles');
  const dump = $('#health-dump');
  try {
    const status = await fetch('/actuator/health').then((response) => response.json());
    dump.textContent = JSON.stringify(status, null, 2);
  } catch (error) {
    dump.textContent = error.message;
  }

  const [tokens, requests, memory] = await Promise.all([
    metric('gen_ai.client.token.usage'),
    metric('http.server.requests'),
    metric('jvm.memory.used'),
  ]);
  const format = (value) => (value == null ? '—' : Math.round(value).toLocaleString('fr-FR'));
  tiles.replaceChildren(
    tile('Jetons consommés', format(tokens)),
    tile('Requêtes HTTP', format(requests)),
    tile('Mémoire JVM', memory == null ? '—' : `${Math.round(memory / 1048576)} Mio`),
  );

  try {
    const info = await api('/actuator/info');
    if (info?.build) tiles.append(tile('Version', info.build.version));
  } catch {
    /* /actuator/info exige le jeton : son absence ne casse pas la vue */
  }
}

export async function integrationsView() {
  // Une source de découverte n'est appelée qu'au clic sur son propre bouton : l'ouverture de
  // l'espace Intégrations reste locale et déterministe.
  if (!$('#mcp-discovery').firstChild) {
    $('#mcp-discovery').append(empty('Aucune source interrogée pour l’instant.',
      'Interroger les sources contacte un service tiers : ce n’est jamais automatique.'));
  }
  await Promise.all([servers(), catalog(), kafka.topics()]);
}

export async function knowledgeView() {
  await Promise.all([knowledge.panel(), memory.list(), summaries.list()]);
}

export async function systemView() {
  await health();
}

// Conservé pour les appels internes ou signets anciens qui rafraîchissaient l'ancien écran Technique.
export async function view() {
  await Promise.all([integrationsView(), knowledgeView(), systemView()]);
}

/**
 * Un seul bouton pour la vue entière, comme partout ailleurs (Décisions, Audit, Configuration) :
 * cette vue en portait un par panneau, soit trois pour un même geste, au-dessus d'un sondage de
 * fond qui les rafraîchit déjà tous les uns après les autres.
 */
export function wire() {
  $('#refresh-tools').addEventListener('click', view);
  $('#tools-search').addEventListener('input', (event) => {
    setParams({ q: event.target.value.trim() });
    // Filtre sur le relevé déjà en cache : pas d'appel réseau par caractère saisi.
    $('#servers').replaceChildren(renderServers(lastServers));
  });

  const dialog = $('#add-mcp-server');
  $('#open-add-mcp').addEventListener('click', () => openEditor());
  $('#cancel-add-mcp').addEventListener('click', () => dialog.close());
  $('#mcp-transport').addEventListener('change', syncTransportFields);
  $('#mcp-template').addEventListener('change', applyTemplate);
  $('#test-mcp').addEventListener('click', () => testForm());
  $('#add-mcp-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const submit = $('#submit-add-mcp');
    busy(submit, async () => {
      try {
        const body = formRegistration();
        const test = await api('/api/agent/mcp/servers/test', { method: 'POST', body });
        renderTest(test);
        if (!test.success) throw new Error(test.message || 'Le test de connexion a échoué.');
        const endpoint = editingConnection
          ? `/api/agent/mcp/servers/${encodeURIComponent(editingConnection)}`
          : '/api/agent/mcp/servers';
        await api(endpoint, { method: editingConnection ? 'PUT' : 'POST', body });
        const name = $('#mcp-connection').value.trim();
        dialog.close();
        toast(`Serveur MCP « ${name} » ${editingConnection ? 'modifié' : 'ajouté'}.`);
        await servers();
      } catch (error) { report(error); }
    });
  });

  $('#export-mcp').addEventListener('click', async () => {
    try {
      const bundle = await api('/api/agent/mcp/configuration');
      const link = document.createElement('a');
      link.href = URL.createObjectURL(new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' }));
      link.download = `kex-mcp-${new Date().toISOString().slice(0, 10)}.json`;
      link.click();
      URL.revokeObjectURL(link.href);
      toast('Configuration MCP exportée sans secrets.');
    } catch (error) { report(error); }
  });
  $('#import-mcp').addEventListener('click', () => $('#mcp-import-file').click());
  $('#mcp-import-file').addEventListener('change', async (event) => {
    const [file] = event.target.files;
    if (!file) return;
    try {
      const bundle = JSON.parse(await file.text());
      await api('/api/agent/mcp/configuration', { method: 'POST', body: bundle });
      toast('Configurations importées désactivées. Renseignez leurs secrets avant activation.');
      await servers();
    } catch (error) { report(error); }
    event.target.value = '';
  });

  const rotate = $('#rotate-mcp-secret');
  $('#cancel-rotate-mcp').addEventListener('click', () => rotate.close());
  $('#rotate-mcp-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const submit = $('#submit-rotate-mcp');
    busy(submit, async () => {
      try {
        await api(`/api/agent/mcp/servers/${encodeURIComponent(rotatingConnection)}/secret`, {
          method: 'PATCH', body: { bearerToken: $('#rotate-mcp-token').value },
        });
        rotate.close();
        toast(`Secret de « ${rotatingConnection} » remplacé après test.`);
        await servers();
      } catch (error) { report(error); }
    });
  });

  const installCatalog = $('#install-catalog');
  $('#cancel-install-catalog').addEventListener('click', () => installCatalog.close());
  $('#install-catalog-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const submit = $('#submit-install-catalog');
    busy(submit, async () => {
      try {
        const connection = $('#install-catalog-connection').value.trim();
        const bearerToken = $('#install-catalog-token-field').hidden
          ? undefined : ($('#install-catalog-token').value || undefined);
        await api(`/api/agent/mcp/catalog/${encodeURIComponent(catalogInstallTarget)}/install`,
          { method: 'POST', body: { connection, bearerToken } });
        installCatalog.close();
        toast(`Serveur MCP « ${connection} » installé depuis le catalogue, désactivé.`);
        await servers();
      } catch (error) { report(error); }
    });
  });

  $('#discover-mcp').addEventListener('click', () => busy($('#discover-mcp'), discover));

  const install = $('#install-discovered');
  $('#cancel-install-discovered').addEventListener('click', () => install.close());
  $('#install-discovered-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const submit = $('#submit-install-discovered');
    busy(submit, async () => {
      try {
        const { sourceId, candidateId } = installTarget;
        const connection = $('#install-discovered-connection').value.trim();
        const bearerToken = $('#install-discovered-token').value || undefined;
        await api(`/api/agent/mcp/catalog/discover/${encodeURIComponent(sourceId)}/`
          + `${encodeURIComponent(candidateId)}/install`, { method: 'POST', body: { connection, bearerToken } });
        install.close();
        toast(`Serveur MCP « ${connection} » installé depuis ${sourceId}, désactivé.`);
        await servers();
      } catch (error) { report(error); }
    });
  });
}

function parseObject(id, label) {
  try {
    const value = JSON.parse($(id).value || '{}');
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error();
    return value;
  } catch {
    throw new Error(`${label} doit être un objet JSON.`);
  }
}

function formRegistration() {
  const transport = $('#mcp-transport').value;
  return {
    connection: $('#mcp-connection').value.trim(),
    transport,
    url: transport === 'HTTP' ? $('#mcp-url').value.trim() : null,
    endpoint: transport === 'HTTP' ? ($('#mcp-endpoint').value.trim() || '/mcp') : null,
    bearerToken: $('#mcp-token').value || null,
    headers: transport === 'HTTP' ? parseObject('#mcp-headers', 'Les en-têtes') : {},
    command: transport === 'STDIO' ? $('#mcp-command').value.trim() : null,
    args: transport === 'STDIO' ? $('#mcp-args').value.split('\n').map((value) => value.trim()).filter(Boolean) : [],
    environment: transport === 'STDIO' ? parseObject('#mcp-environment', 'L’environnement') : {},
    enabled: $('#mcp-enabled').checked,
    allowedTools: $('#mcp-allowed-tools').value.split(',').map((value) => value.trim()).filter(Boolean),
    capabilityMappings: parseObject('#mcp-capabilities', 'Les capacités'),
  };
}

function syncTransportFields() {
  const http = $('#mcp-transport').value === 'HTTP';
  $('#mcp-http-fields').hidden = !http;
  $('#mcp-stdio-fields').hidden = http;
  $('#mcp-url').required = http;
  $('#mcp-command').required = !http;
}

function applyTemplate() {
  const template = $('#mcp-template').value;
  const presets = {
    github: { transport: 'HTTP', connection: 'github', url: 'https://api.githubcopilot.com', endpoint: '/mcp' },
    filesystem: { transport: 'STDIO', connection: 'filesystem', command: 'npx', args: '-y\n@modelcontextprotocol/server-filesystem\n/data' },
    postgres: { transport: 'STDIO', connection: 'postgres', command: 'npx', args: '-y\n@modelcontextprotocol/server-postgres', environment: '{\n  "DATABASE_URL": "postgresql://…"\n}' },
  };
  const preset = presets[template];
  if (!preset) return;
  $('#mcp-transport').value = preset.transport;
  $('#mcp-connection').value = preset.connection;
  $('#mcp-url').value = preset.url || '';
  $('#mcp-endpoint').value = preset.endpoint || '/mcp';
  $('#mcp-command').value = preset.command || '';
  $('#mcp-args').value = preset.args || '';
  $('#mcp-environment').value = preset.environment || '{}';
  syncTransportFields();
}

function openEditor(server = null) {
  editingConnection = server?.connection || null;
  const form = $('#add-mcp-form');
  form.reset();
  $('#mcp-endpoint').value = '/mcp';
  $('#mcp-headers').value = '{}';
  $('#mcp-environment').value = '{}';
  $('#mcp-capabilities').value = '{}';
  $('#mcp-enabled').checked = true;
  $('#mcp-test-status').className = 'state unknown';
  $('#mcp-test-status').textContent = 'Non testé';
  $('#mcp-test-result').textContent = 'Renseignez la connexion puis lancez le test.';
  $('#mcp-dialog-title').textContent = server ? `Modifier ${server.connection}` : 'Ajouter un serveur MCP';
  $('#submit-add-mcp').textContent = server ? 'Enregistrer' : 'Ajouter';
  $('#mcp-connection').readOnly = Boolean(server);
  if (server) {
    $('#mcp-connection').value = server.connection;
    $('#mcp-transport').value = server.transport;
    $('#mcp-url').value = server.url || '';
    $('#mcp-endpoint').value = server.endpoint || '/mcp';
    $('#mcp-command').value = server.command || '';
    $('#mcp-args').value = (server.args || []).join('\n');
    $('#mcp-enabled').checked = server.enabled;
    $('#mcp-allowed-tools').value = (server.allowedTools || []).join(', ');
    $('#mcp-capabilities').value = JSON.stringify(server.capabilityMappings || {}, null, 2);
    $('#mcp-headers').value = JSON.stringify(Object.fromEntries((server.headerNames || []).map((key) => [key, ''])), null, 2);
    $('#mcp-environment').value = JSON.stringify(Object.fromEntries((server.environmentNames || []).map((key) => [key, ''])), null, 2);
  }
  syncTransportFields();
  $('#add-mcp-server').showModal();
  $('#mcp-connection').focus();
}

async function testForm() {
  const button = $('#test-mcp');
  await busy(button, async () => {
    try {
      renderTest(await api('/api/agent/mcp/servers/test', { method: 'POST', body: formRegistration() }));
    } catch (error) {
      renderTest({ success: false, message: error.message });
    }
  });
}

function renderTest(result) {
  const status = $('#mcp-test-status');
  status.className = `state ${result.success ? 'ok' : 'error'}`;
  status.textContent = result.success ? 'Test réussi' : 'Échec du test';
  $('#mcp-test-result').textContent = result.success
    ? `${result.serverName || 'Serveur MCP'} · ${result.protocolVersion || 'protocole négocié'} · ${result.latencyMillis} ms\n${(result.tools || []).join('\n') || 'Aucun outil exposé'}`
    : (result.message || 'Connexion impossible');
}

function openSecretRotation(connection) {
  rotatingConnection = connection;
  $('#rotate-mcp-label').textContent = `La nouvelle valeur sera testée sur « ${connection} » avant de remplacer l’ancienne.`;
  $('#rotate-mcp-form').reset();
  $('#rotate-mcp-secret').showModal();
  $('#rotate-mcp-token').focus();
}

/** Remet le champ en accord avec l'adresse, comme les recherches de Processus et Audit. */
export function syncFilters() {
  const query = params().get('q') || '';
  if ($('#tools-search').value !== query) $('#tools-search').value = query;
}
