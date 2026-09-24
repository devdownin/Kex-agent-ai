// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Socle partagé : appels HTTP, jeton, fabriques DOM et les états UX que la spec impose de traiter
// explicitement — chargement, vide, erreur, données obsolètes.

const KEY_STORAGE = 'kex.agent.api-key';

export const $ = (selector, root = document) => root.querySelector(selector);

export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  // textContent et jamais innerHTML : les réponses du modèle, les contenus MCP et les résultats
  // d'outils sont des données non fiables ; les injecter en HTML serait un XSS sur l'origine de l'API.
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
}

export function frag(...nodes) {
  const fragment = document.createDocumentFragment();
  fragment.append(...nodes.filter(Boolean));
  return fragment;
}

/* ── Jeton ─────────────────────────────────────────────────────────────── */

let memoryKey = read();

function read() {
  try {
    // sessionStorage et non localStorage : le jeton ne survit pas à la fermeture de l'onglet.
    return sessionStorage.getItem(KEY_STORAGE) || '';
  } catch {
    return '';
  }
}

export const credentials = {
  get: () => memoryKey,
  set(value) {
    memoryKey = value;
    try {
      if (value) sessionStorage.setItem(KEY_STORAGE, value);
      else sessionStorage.removeItem(KEY_STORAGE);
    } catch {
      /* navigation privée : le jeton reste en mémoire pour la durée de la page */
    }
    listeners.forEach((listener) => listener(value));
  },
};

const listeners = new Set();
export const onCredentialChange = (listener) => listeners.add(listener);

/* ── Appels HTTP ───────────────────────────────────────────────────────── */

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function headers(extra) {
  const base = memoryKey ? { Authorization: `Bearer ${memoryKey}` } : {};
  return Object.assign(base, extra || {});
}

const FALLBACK = {
  400: 'Requête refusée : une valeur est hors bornes.',
  401: 'Jeton refusé.',
  403: 'Accès refusé.',
  404: 'Ressource inconnue.',
  409: "L'état actuel refuse cette demande.",
  429: 'Débit de chat dépassé, réessayer dans un instant.',
  501: 'Capacité non supportée par ce serveur MCP.',
  503: "Service indisponible — kex.agent.api-key n'est peut-être pas configuré côté serveur.",
  504: "L'agent n'a pas répondu dans le délai imparti.",
};

export async function failure(response) {
  let detail = '';
  try {
    const body = await response.json();
    detail = body.detail || body.message || '';
  } catch {
    /* ProblemDetail absent : le statut suffit */
  }
  return new ApiError(response.status, detail || FALLBACK[response.status] || `HTTP ${response.status}`);
}

let unauthorized = () => {};
export const onUnauthorized = (handler) => {
  unauthorized = handler;
};

export async function api(path, options = {}) {
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers: headers(options.body === undefined ? undefined : { 'Content-Type': 'application/json' }),
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });
  if (response.status === 401) unauthorized();
  if (!response.ok) throw await failure(response);
  if (response.status === 204) return null;
  const type = response.headers.get('content-type') || '';
  return type.includes('json') ? response.json() : response.text();
}

/* ── Notifications ─────────────────────────────────────────────────────── */

// Une rafale d'actions (approbation groupée, plusieurs échecs à la suite) empilait les toasts sans
// limite, jusqu'à couvrir une bonne partie de l'écran. Le plus ancien cède la place plutôt que de
// s'accumuler indéfiniment.
const MAX_TOASTS = 4;

export function toast(message, kind, action) {
  const node = el('div', kind === 'error' ? 'toast error' : 'toast', message);
  let dismiss = () => node.remove();
  if (action?.label && action?.run) {
    const button = el('button', 'ghost', action.label);
    button.type = 'button';
    button.addEventListener('click', async () => {
      button.disabled = true;
      try { await action.run(); dismiss(); } catch (error) { report(error); }
    });
    node.append(button);
  }
  const close = el('button', 'toast-close', '✕');
  close.type = 'button';
  close.setAttribute('aria-label', 'Fermer cette notification');
  close.addEventListener('click', () => dismiss());
  node.append(close);
  const host = $('#toasts');
  while (host.children.length >= MAX_TOASTS) host.firstElementChild.remove();
  host.append(node);
  if (kind !== 'error') {
    try {
      localStorage.setItem('kex.agent.last-action', JSON.stringify({ message, at: new Date().toISOString() }));
    } catch { /* retour visible via le toast pour cette session */ }
    dispatchEvent(new CustomEvent('kex:action', { detail: { message, at: new Date().toISOString() } }));
  }
  // Une minuterie qu'on peut suspendre : sans ça, un toast se referme sous la souris pendant
  // qu'on le lit, l'action qu'il propose disparaissant avec lui.
  const life = action ? 10_000 : 6000;
  let remaining = life;
  let since = Date.now();
  let timer = setTimeout(() => dismiss(), remaining);
  dismiss = () => { clearTimeout(timer); node.remove(); };
  node.addEventListener('mouseenter', () => {
    clearTimeout(timer);
    remaining -= Date.now() - since;
  });
  node.addEventListener('mouseleave', () => {
    since = Date.now();
    timer = setTimeout(() => dismiss(), Math.max(remaining, 1000));
  });
}

export const report = (error) => toast(error instanceof Error ? error.message : String(error), 'error');

/* ── États UX ──────────────────────────────────────────────────────────── */

export const loading = (message = 'Chargement…') => el('p', 'state loading', message);

export function skeleton(kind = 'list', message = 'Chargement…') {
  const node = el('div', `state loading skeleton skeleton-${kind}`);
  node.setAttribute('role', 'status');
  node.setAttribute('aria-label', message);
  node.append(el('span', 'sr-only', message));
  const count = kind === 'kpis' ? 4 : kind === 'table' ? 5 : 3;
  for (let index = 0; index < count; index += 1) node.append(el('span', 'skeleton-item'));
  return node;
}

export function empty(message, detail, action) {
  const node = el('div', 'state empty');
  node.setAttribute('data-empty-state', 'true');
  node.append(el('p', null, message));
  if (detail) node.append(el('p', 'hint', detail));
  if (action?.label && (action.href || action.onClick)) {
    const control = el(action.href ? 'a' : 'button', 'ghost empty-action', action.label);
    if (action.href) control.href = action.href;
    else {
      control.type = 'button';
      control.addEventListener('click', action.onClick);
    }
    node.append(control);
  }
  return node;
}

/**
 * Désactive un bouton le temps de son action. Sans cela, un double-clic part deux fois : la seconde
 * requête se heurte au verrou d'idempotence (409) ou à une ressource déjà supprimée (404), et
 * l'écran rend un toast rouge pour une action qui a pourtant abouti.
 *
 * <p>Le bouton peut avoir disparu du DOM entre-temps — la liste se réaffiche après l'action : le
 * réactiver sur un nœud détaché ne coûte rien et évite un test de présence à chaque appel.
 */
export async function busy(button, action) {
  button.disabled = true;
  try {
    return await action();
  }
  finally {
    button.disabled = false;
  }
}

/** Un écran en erreur garde un moyen de repartir : sans bouton, il ne reste que le rechargement. */
export function errorState(error, retry) {
  const node = el('div', 'state error');
  node.append(el('p', null, error instanceof Error ? error.message : String(error)));
  if (retry) {
    const button = el('button', 'ghost', 'Réessayer');
    button.type = 'button';
    button.addEventListener('click', retry);
    node.append(button);
  }
  return node;
}

/**
 * Rend une section asynchrone en traitant les trois états au même endroit, jamais un seul.
 *
 * Le sondage de fond rappelle `render()` sur un hôte déjà rempli : l'effacer avant la réponse
 * remplacerait un tableau plein par « Chargement… », plus étroit que lui, toutes les 15 secondes —
 * un flash qui donnait l'impression que le bloc n'occupait plus toute la largeur disponible.
 * L'état de chargement ne s'affiche donc qu'au tout premier rendu, quand l'hôte est encore vide.
 */
export async function render(host, load, draw) {
  if (!host.firstChild) host.replaceChildren(skeleton(host.dataset.skeleton || 'list'));
  try {
    const data = await load();
    const drawn = draw(data);
    host.replaceChildren(drawn ?? empty('Rien à afficher.'));
  } catch (error) {
    host.replaceChildren(errorState(error, () => render(host, load, draw)));
  }
}

/* ── Formats ───────────────────────────────────────────────────────────── */

const RELATIVE = new Intl.RelativeTimeFormat('fr', { numeric: 'auto' });
const CLOCK = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const STAMP = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'medium' });

function relativeDates() {
  try {
    return localStorage.getItem('kex.agent.date-format') === 'relative';
  } catch {
    return false;
  }
}

export const clockTime = (iso) => (iso ? (relativeDates() ? ago(iso) : CLOCK.format(new Date(iso))) : '—');
export const stamp = (iso) => (iso ? (relativeDates() ? ago(iso) : STAMP.format(new Date(iso))) : '—');

export function ago(iso) {
  if (!iso) return null;
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const units = [
    [60, 'second'],
    [3600, 'minute'],
    [86400, 'hour'],
  ];
  for (const [limit, unit] of units) {
    if (Math.abs(seconds) < limit) {
      const divisor = limit === 60 ? 1 : limit / 60;
      return RELATIVE.format(Math.round(seconds / divisor), unit);
    }
  }
  return RELATIVE.format(Math.round(seconds / 86400), 'day');
}

export function freshnessTag(at = new Date().toISOString(), label = 'Actualisé', staleAfterMillis = null) {
  const node = el('span', 'data-freshness');
  node.dataset.at = at;
  node.dataset.label = label;
  if (staleAfterMillis != null) node.dataset.staleAfter = String(staleAfterMillis);
  refreshFreshnessTag(node);
  return node;
}

export function refreshFreshnessTag(node) {
  const at = node?.dataset?.at;
  if (!at) return;
  const label = node.dataset.label || 'Actualisé';
  node.textContent = `${label} ${ago(at) || 'à l’instant'}`;
  node.title = stamp(at);
  const staleAfter = Number(node.dataset.staleAfter);
  const stale = Number.isFinite(staleAfter) && staleAfter > 0
    && Date.now() - new Date(at).getTime() > staleAfter;
  node.dataset.state = stale ? 'stale' : 'fresh';
}

export function duration(millis) {
  if (millis == null) return '—';
  const seconds = Math.round(millis / 1000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  return seconds % 60 ? `${minutes} min ${seconds % 60} s` : `${minutes} min`;
}

export const percent = (ratio) => `${Math.round((ratio ?? 0) * 100)} %`;

/**
 * Pastille d'état. La couleur ne porte jamais l'information seule : un glyphe distinct et un
 * libellé l'accompagnent toujours — règle P0 de la spec, et le seul moyen d'être lisible en
 * daltonisme comme en impression noir et blanc.
 */
const MARKS = { OK: '●', WARNING: '▲', ERROR: '✕', UNKNOWN: '?', PENDING: '◷', RUNNING: '◌', PAUSED: '⏸' };
const LABELS = { OK: 'OK', WARNING: 'Warning', ERROR: 'Erreur', UNKNOWN: 'Inconnu' };

/** Le même glyphe que `stateTag`, pour les endroits qui composent leur propre pastille. */
export const stateMark = (state) => MARKS[state] || '●';

/* ── Tri des tableaux ──────────────────────────────────────────────────── */

/**
 * Rend triable un tableau déjà construit. Après coup plutôt qu'à la construction : chaque vue bâtit
 * le sien à sa façon, et un tri qui lit le DOM les couvre toutes sans les réécrire.
 *
 * <p>La clé est `data-sort` quand la cellule en porte une — un horodatage ISO, un nombre brut — et
 * son texte sinon. Sans cela, « il y a 4 min » se trierait par ordre alphabétique, ce qui est pire
 * que de ne pas trier : l'ordre aurait l'air juste.
 */
export function sortable(table) {
  const headers = [...table.querySelectorAll('thead th')];
  const body = table.querySelector('tbody');
  if (!body) return table;

  headers.forEach((header, column) => {
    if (!header.textContent.trim()) return;
    const button = el('button', 'sort', header.textContent);
    button.type = 'button';
    header.replaceChildren(button);
    button.addEventListener('click', () => {
      const ascending = header.getAttribute('aria-sort') !== 'ascending';
      headers.forEach((other) => other.removeAttribute('aria-sort'));
      header.setAttribute('aria-sort', ascending ? 'ascending' : 'descending');
      const rows = [...body.rows];
      const keys = rows.map((row) => key(row.cells[column]));
      // Une seule valeur non numérique suffit à retomber sur l'alphabétique : mélanger les deux
      // rendrait l'ordre dépendant des lignes présentes, donc imprévisible d'un écran à l'autre.
      const numeric = keys.every((value) => value === '' || !Number.isNaN(Number(value)));
      rows.sort((left, right) =>
        compare(key(left.cells[column]), key(right.cells[column]), numeric, ascending));
      body.append(...rows);
    });
  });
  return table;
}

/** Le tiret cadratin est le « rien à afficher » de toute la console : ce n'est pas une valeur. */
const ABSENT = '—';

function key(cell) {
  if (!cell) return '';
  // L'espace fine insérée par toLocaleString casserait Number() : la clé brute existe pour ça.
  const raw = (cell.dataset.sort ?? cell.textContent).trim();
  return raw === ABSENT ? '' : raw;
}

/**
 * Les cases sans valeur restent en bas dans les deux sens. Les faire remonter au tri décroissant
 * les présenterait comme les plus grandes, alors qu'elles n'affirment rien du tout.
 */
function compare(left, right, numeric, ascending) {
  if (left === '' || right === '') return left === right ? 0 : (left === '' ? 1 : -1);
  const order = numeric ? Number(left) - Number(right) : left.localeCompare(right, 'fr');
  return ascending ? order : -order;
}

/** Une ligne « intitulé / valeur ». Partagée : la supervision et la vue du modèle la rendent toutes deux. */
export function definition(label, value) {
  const row = el('dl', 'definition');
  row.append(el('dt', null, label));
  const dd = el('dd');
  dd.append(value);
  row.append(dd);
  return row;
}

export function stateTag(state, label) {
  const tag = el('span', 'state-tag');
  tag.dataset.state = state;
  tag.append(el('span', 'state-mark', MARKS[state] || '•'));
  tag.append(el('span', null, label ?? LABELS[state] ?? state));
  return tag;
}

/* ── Disjoncteurs ──────────────────────────────────────────────────────── */

// Partagé entre la supervision (disjoncteurs de l'agent) et les serveurs MCP (un par connexion) :
// un disjoncteur n'est pas un des états de ProcessState/DecisionStatus/AgentState, sa propre petite
// table plutôt que de forcer une correspondance qui n'a pas de sens ailleurs.
const CIRCUIT_STATES = {
  CLOSED: 'OK',
  HALF_OPEN: 'WARNING',
  OPEN: 'ERROR',
  FORCED_OPEN: 'ERROR',
  DISABLED: 'UNKNOWN',
  METRICS_ONLY: 'UNKNOWN',
};

/** Pastille pour un état de disjoncteur brut ({@code CLOSED}/{@code OPEN}/...), ou l'inconnu. */
export function circuitStateTag(state, label) {
  return stateTag(CIRCUIT_STATES[state] || 'UNKNOWN', label ?? state ?? 'Inconnu');
}

export function circuitBreakersValue(circuitBreakers) {
  if (!circuitBreakers || !circuitBreakers.length) {
    return el('span', 'muted', 'Aucun');
  }
  const wrap = el('span', 'tag-group');
  circuitBreakers.forEach((breaker) => {
    wrap.append(circuitStateTag(breaker.state, `${breaker.name} : ${breaker.state}`));
  });
  return wrap;
}

/* ── Paramètres d'écran dans l'URL ─────────────────────────────────────── */

// L'état d'un écran voyage dans son adresse : « regarde ce que l'agent a fait entre 14 h et 15 h »
// doit être un lien, et un rechargement ne doit pas reperdre un filtre. Le hash porte donc
// `#/vue?clef=valeur`, la vue avant le `?`, le reste en paramètres.

export function viewName() {
  return location.hash.replace(/^#\//, '').split('?')[0] || '';
}

export function params() {
  const query = location.hash.split('?')[1] || '';
  return new URLSearchParams(query);
}

/**
 * @param push `true` pour créer une entrée d'historique — l'ouverture d'un panneau, que le bouton
 *             Retour doit pouvoir refermer. `false` pour un filtre qu'on ajuste au clavier : une
 *             entrée par caractère saisi rendrait le bouton Retour inutilisable.
 */
export function setParams(changes, push = false) {
  const next = params();
  for (const [key, value] of Object.entries(changes)) {
    if (value === null || value === undefined || value === '') next.delete(key);
    else next.set(key, value);
  }
  const query = next.toString();
  const hash = `#/${viewName()}${query ? `?${query}` : ''}`;
  if (hash === location.hash) return;
  if (push) location.hash = hash;
  else history.replaceState(null, '', hash);
}

/* ── Panneau latéral ───────────────────────────────────────────────────── */

// Hors des vues : la coque a besoin de savoir qu'un panneau est ouvert pour suspendre le
// rafraîchissement, et la vue Kafka comme la supervision l'ouvrent toutes deux.

const SHELL = ['.rail', '.topbar', 'main'];

let lastFocused = null;

export const drawerOpen = () => !$('#drawer').hidden;

export function openDrawer(title, body) {
  if (!drawerOpen()) lastFocused = document.activeElement;
  $('#drawer-title').textContent = title;
  $('#drawer-body').replaceChildren(body);
  $('#drawer').hidden = false;
  // `inert` retire le reste de la page du parcours clavier et de l'arbre d'accessibilité. Sans
  // lui, Tab sort derrière un panneau qui porte « Approuver » et « Refuser » — on peut valider
  // une action en croyant agir sur ce qu'on lit.
  SHELL.forEach((selector) => $(selector)?.setAttribute('inert', ''));
  $('#drawer-close').focus();
}

export function closeDrawer() {
  $('#drawer').hidden = true;
  SHELL.forEach((selector) => $(selector)?.removeAttribute('inert'));
  // Le focus revient d'où il vient : sans ça, la navigation clavier repart du haut de la page.
  lastFocused?.focus();
  lastFocused = null;
}

/* ── Panneaux adressables ──────────────────────────────────────────────── */

// Un panneau s'ouvre par l'adresse et pas seulement par un clic : c'est ce qui rend le bouton
// Retour capable de le refermer et un lien capable de le rouvrir. Le registre est ici pour
// qu'aucun module n'ait à connaître les panneaux des autres — la supervision ne sait rien du
// catalogue de modèles, et n'a pas à le fermer par ignorance.

const DRAWERS = new Map();

/** @param open reçoit la valeur du paramètre ; un panneau sans identifiant l'ignore. */
export function registerDrawer(param, open) {
  DRAWERS.set(param, open);
}

/**
 * Ouvre un panneau par son paramètre d'adresse, en effaçant tous les autres panneaux enregistrés.
 * Sans ça, chaque module qui ouvre un panneau devrait connaître les paramètres de tous les autres
 * pour ne pas laisser une adresse porter deux panneaux à la fois — exactement ce que le registre
 * existe pour éviter.
 */
export function setDrawerParam(param, value) {
  const cleared = Object.fromEntries([...DRAWERS.keys()].map((key) => [key, null]));
  setParams({ ...cleared, [param]: value }, true);
}

/** L'adresse fait foi : aucun paramètre de panneau, aucun panneau. */
export async function restoreDrawerFromUrl() {
  const query = params();
  const entry = [...DRAWERS].find(([param]) => query.get(param));
  if (!entry) {
    if (drawerOpen()) closeDrawer();
    return;
  }
  // Déjà ouvert : le rouvrir rejouerait la requête et écraserait ce qu'on est en train de lire.
  if (drawerOpen()) return;
  await entry[1](query.get(entry[0]));
}

/** Fermer efface aussi le paramètre, sans quoi l'adresse rouvrirait le panneau au rechargement. */
export function dismissDrawer() {
  closeDrawer();
  setParams(Object.fromEntries([...DRAWERS.keys()].map((param) => [param, null])));
}

/* ── Confirmation ──────────────────────────────────────────────────────── */

/**
 * Confirmation d'une action à impact. Le libellé du bouton reprend l'action — « Confirmer le
 * redémarrage de Consumer-02 » — plutôt qu'un « Êtes-vous sûr ? » qu'on approuve sans lire.
 */
/**
 * `reasonLabel` change le contrat de retour : sans lui, la promesse rend le booléen qu'elle a
 * toujours rendu, et chaque appelant existant le vérifie tel quel. Avec lui, elle rend
 * `{ confirmed, reason }` — un opt-in, jamais un défaut qui romprait un appelant qui ne
 * s'attendrait à recevoir qu'un booléen. `reasonRequired` pose l'attribut natif du champ, pas une
 * validation à la main ; sans `formnovalidate` sur Annuler, cette validation bloquerait aussi
 * l'abandon, ce que la balise porte déjà.
 */
export function confirmAction({ title, lines, accept, reasonLabel, reasonRequired = false }) {
  const dialog = $('#confirm');
  $('#confirm-title').textContent = title;
  $('#confirm-accept').textContent = accept;
  const body = $('#confirm-body');
  body.replaceChildren(...lines.filter(Boolean).map(([label, value]) => {
    const row = el('div', 'confirm-row');
    row.append(el('span', 'label', label), el('span', 'value', value));
    return row;
  }));
  let reasonField = null;
  if (reasonLabel) {
    const wrap = el('div', 'stack');
    const label = el('label', null, reasonLabel);
    reasonField = el('textarea');
    reasonField.id = 'confirm-reason';
    reasonField.rows = 2;
    label.htmlFor = reasonField.id;
    if (reasonRequired) reasonField.required = true;
    wrap.append(label, reasonField);
    body.append(wrap);
  }
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => {
      const confirmed = dialog.returnValue === 'confirm';
      resolve(reasonLabel ? { confirmed, reason: reasonField.value.trim() } : confirmed);
    }, { once: true });
  });
}

/* ── Validation de schéma (sous-ensemble) ─────────────────────────────── */

// Une antisèche côté client, pas un validateur JSON Schema complet : type, required, enum,
// bornes numériques et longueurs suffisent à attraper une erreur de frappe avant l'aller-retour
// serveur. $ref, allOf/anyOf/oneOf et les schémas composés restent du ressort du serveur, seule
// autorité — les rejeter en silence ici serait pire que ne rien vérifier.
function typeOf(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
}

function matchesType(value, type) {
  if (type === 'integer') return typeOf(value) === 'number' && Number.isInteger(value);
  return typeOf(value) === type;
}

export function schemaErrors(value, schema, label = 'valeur') {
  if (!schema || typeof schema !== 'object') return [];
  const errors = [];
  if (schema.type && !matchesType(value, schema.type)) {
    return [`${label} : attendu ${schema.type}, reçu ${typeOf(value)}`];
  }
  if (schema.enum && !schema.enum.includes(value)) {
    errors.push(`${label} : doit être l’une de [${schema.enum.join(', ')}]`);
  }
  if (typeof value === 'string') {
    if (schema.minLength != null && value.length < schema.minLength) {
      errors.push(`${label} : au moins ${schema.minLength} caractère(s)`);
    }
    if (schema.maxLength != null && value.length > schema.maxLength) {
      errors.push(`${label} : au plus ${schema.maxLength} caractère(s)`);
    }
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) {
      errors.push(`${label} : ne correspond pas au motif attendu`);
    }
  }
  if (typeof value === 'number') {
    if (schema.minimum != null && value < schema.minimum) errors.push(`${label} : au moins ${schema.minimum}`);
    if (schema.maximum != null && value > schema.maximum) errors.push(`${label} : au plus ${schema.maximum}`);
  }
  if (schema.type === 'object' && value && typeof value === 'object') {
    (schema.required || []).forEach((key) => {
      if (!(key in value)) errors.push(`${label}.${key} : champ requis manquant`);
    });
    Object.entries(schema.properties || {}).forEach(([key, sub]) => {
      if (key in value) errors.push(...schemaErrors(value[key], sub, `${label}.${key}`));
    });
  }
  if (schema.type === 'array' && Array.isArray(value) && schema.items) {
    value.forEach((item, index) => errors.push(...schemaErrors(item, schema.items, `${label}[${index}]`)));
  }
  return errors;
}

/* ── Exemple pré-rempli ────────────────────────────────────────────────── */

function exampleString(schema) {
  switch (schema.format) {
    case 'date-time': return new Date().toISOString();
    case 'date': return new Date().toISOString().slice(0, 10);
    case 'uuid': return '00000000-0000-0000-0000-000000000000';
    case 'email': return 'nom@exemple.com';
    case 'uri':
    case 'url': return 'https://exemple.com';
    default: return 'exemple';
  }
}

/**
 * Une valeur plausible pour un nœud de schéma JSON — jamais une validation. `example`, `examples`,
 * `default` et le premier `enum` déclarés par le serveur priment toujours sur ce qui serait sinon
 * deviné ici : c'est lui qui sait ce qu'une valeur signifie, pas cette heuristique.
 */
export function exampleFromSchema(schema) {
  if (!schema || typeof schema !== 'object') return null;
  if ('example' in schema) return schema.example;
  if (Array.isArray(schema.examples) && schema.examples.length) return schema.examples[0];
  if ('default' in schema) return schema.default;
  if (Array.isArray(schema.enum) && schema.enum.length) return schema.enum[0];
  const type = schema.type || (schema.properties ? 'object' : schema.items ? 'array' : null);
  switch (type) {
    case 'object': {
      const value = {};
      Object.entries(schema.properties || {}).forEach(([key, sub]) => { value[key] = exampleFromSchema(sub); });
      return value;
    }
    case 'array':
      return schema.items ? [exampleFromSchema(schema.items)] : [];
    case 'boolean':
      return false;
    case 'integer':
    case 'number':
      return schema.minimum ?? 0;
    case 'string':
      return exampleString(schema);
    default:
      return null;
  }
}

/* ── Export CSV ────────────────────────────────────────────────────────── */

// RFC 4180 : une valeur qui contient une virgule, un guillemet ou un saut de ligne doit être
// entre guillemets, doublés à l'intérieur — sans ça, un motif ou un acteur avec une virgule décale
// toutes les colonnes qui suivent.
function csvCell(value) {
  const text = value == null ? '' : String(value);
  return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function toCsv(columns, rows) {
  const lines = [columns.map(([label]) => csvCell(label)).join(',')];
  rows.forEach((row) => lines.push(columns.map(([, pick]) => csvCell(pick(row))).join(',')));
  return lines.join('\r\n');
}

/**
 * Un lien éphémère : le navigateur télécharge, rien ne reste dans le DOM après. `columns` est une
 * liste de `[intitulé, (ligne) => valeur]`, pour ne pas dupliquer la mise en forme déjà écrite pour
 * l'écran.
 */
export function downloadCsv(filename, columns, rows) {
  const blob = new Blob([toCsv(columns, rows)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = el('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/* ── Mini-tendance ─────────────────────────────────────────────────────── */

/**
 * Une poignée de points sans axes ni légende — pas un graphique — pour repérer une dérive avant
 * qu'elle ne soit un chiffre inquiétant dans un compteur agrégé. En dessous de deux points connus,
 * `null` : un tracé sur un seul point ne dirait rien, et une droite plate inventerait une tendance
 * qui n'existe pas.
 */
export function sparkline(values, { width = 160, height = 32 } = {}) {
  const known = (values || []).filter((value) => value != null);
  if (known.length < 2) return null;
  const clean = values.map((value) => value ?? 0);
  const max = Math.max(...clean, 1);
  const min = Math.min(...clean, 0);
  const span = max - min || 1;
  const step = width / (clean.length - 1);
  const points = clean.map((value, index) =>
    `${(index * step).toFixed(1)},${(height - ((value - min) / span) * height).toFixed(1)}`).join(' ');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'sparkline');
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label',
    `Tendance sur les ${clean.length} derniers cycles, de ${clean[0]} à ${clean[clean.length - 1]}`);
  const polyline = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
  polyline.setAttribute('points', points);
  svg.append(polyline);
  return svg;
}
