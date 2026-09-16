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

export function toast(message, kind) {
  const node = el('div', kind === 'error' ? 'toast error' : 'toast', message);
  $('#toasts').append(node);
  setTimeout(() => node.remove(), 6000);
}

export const report = (error) => toast(error instanceof Error ? error.message : String(error), 'error');

/* ── États UX ──────────────────────────────────────────────────────────── */

export const loading = (message = 'Chargement…') => el('p', 'state loading', message);

export function empty(message, detail) {
  const node = el('div', 'state empty');
  node.append(el('p', null, message));
  if (detail) node.append(el('p', 'hint', detail));
  return node;
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
  if (!host.firstChild) host.replaceChildren(loading());
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

export const clockTime = (iso) => (iso ? CLOCK.format(new Date(iso)) : '—');
export const stamp = (iso) => (iso ? STAMP.format(new Date(iso)) : '—');

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
export function confirmAction({ title, lines, accept }) {
  const dialog = $('#confirm');
  $('#confirm-title').textContent = title;
  $('#confirm-accept').textContent = accept;
  $('#confirm-body').replaceChildren(...lines.filter(Boolean).map(([label, value]) => {
    const row = el('div', 'confirm-row');
    row.append(el('span', 'label', label), el('span', 'value', value));
    return row;
  }));
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true });
  });
}
