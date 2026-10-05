// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { el } from './core.js';

const secretKey = /password|passwd|secret|token|authorization|api.?key|credential|cookie|private.?key/i;
export function redact(value, depth = 0) {
  if (depth > 20) return '[Profondeur limitée]';
  if (Array.isArray(value)) return value.map(v => redact(v, depth + 1));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, v]) => [key, secretKey.test(key) ? '[Masqué]' : redact(v, depth + 1)]));
  return typeof value === 'string' ? value.replace(/Bearer\s+[A-Za-z0-9._~+\/-]+=*/gi, 'Bearer [Masqué]') : value;
}
export function jsonValue(raw) {
  if (typeof raw !== 'string' || raw.length > 200000) return undefined;
  try { return redact(JSON.parse(raw.trim().replace(/^```(?:json)?\s*\n?([\s\S]*?)\n?```$/, '$1'))); } catch { return undefined; }
}
export function resultStatus(request, task) {
  if (task?.status === 'VERIFIED') return { tone: 'verified', icon: '✓', label: 'Objectif vérifié selon le critère du plan' };
  if (request.status === 'ERROR' || task?.status === 'FAILED') return { tone: 'failed', icon: '×', label: 'Échec signalé — résultat à examiner' };
  if (['PARTIAL', 'INTERRUPTED', 'NEEDS_INPUT'].includes(request.status)) return { tone: 'attention', icon: '!', label: request.status === 'NEEDS_INPUT' ? 'Précision nécessaire' : 'Résultat partiel ou interrompu' };
  return { tone: 'unknown', icon: '○', label: request.status === 'RUNNING' ? 'Traitement en cours' : 'Réponse reçue — objectif à vérifier' };
}
export function validTables(tables) {
  return Array.isArray(tables) ? tables.slice(0, 3).filter(t => t && typeof t.title === 'string' && t.title.length <= 200 && Array.isArray(t.columns) && t.columns.length > 0 && t.columns.length <= 12 && t.columns.every(c => typeof c === 'string' && c.length <= 120) && Array.isArray(t.rows) && t.rows.length <= 200 && t.rows.every(r => Array.isArray(r) && r.length === t.columns.length && r.every(v => v === null || typeof v === 'boolean' || (typeof v === 'number' && Number.isFinite(v)) || (typeof v === 'string' && v.length <= 2000)))) : [];
}
export function tableRows(table, term = '', column = null, descending = false) {
  const rows = table.rows.filter(row => row.some(v => String(v ?? '').toLocaleLowerCase('fr').includes(term.toLocaleLowerCase('fr'))));
  if (column !== null) rows.sort((a, b) => (typeof a[column] === 'number' && typeof b[column] === 'number' ? a[column] - b[column] : String(a[column] ?? '').localeCompare(String(b[column] ?? ''), 'fr', { numeric: true })) * (descending ? -1 : 1));
  return rows;
}
export function drawTables(host, tables, provenance = {}) {
  validTables(tables).forEach(table => {
    const section = el('section', 'result-table'); section.append(el('h3', null, table.title));
    const csv = el('button', null, 'Exporter ce tableau en CSV'); csv.type = 'button'; csv.addEventListener('click', () => downloadResult('tableau-kex.csv', tableCsv(table, provenance), 'text/csv;charset=utf-8')); section.append(csv);
    const label = el('label', null, 'Filtrer les lignes'); const input = el('input'); input.type = 'search'; label.append(input); section.append(label);
    const count = el('p', 'muted'); count.setAttribute('role', 'status');
    const wrap = el('div', 'table-scroll'); wrap.tabIndex = 0; wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', table.title || 'Tableau de résultat');
    const grid = el('table'); const caption = el('caption', null, table.title); const head = el('thead'); const tr = el('tr'); const body = el('tbody');
    let column = null; let descending = false;
    const headers = table.columns.map((name, i) => { const th = el('th'); th.scope = 'col'; const b = el('button', null, name); b.type = 'button'; b.setAttribute('aria-label', 'Trier par ' + name); b.addEventListener('click', () => { descending = column === i ? !descending : false; column = i; draw(); }); th.append(b); tr.append(th); return th; });
    head.append(tr); grid.append(caption, head, body); wrap.append(grid); section.append(count, wrap); host.append(section);
    function draw() {
      headers.forEach((th, i) => th.setAttribute('aria-sort', column === i ? descending ? 'descending' : 'ascending' : 'none'));
      const rows = tableRows(table, input.value, column, descending); count.textContent = `${rows.length} / ${table.rows.length} ligne(s)`; body.replaceChildren();
      rows.forEach(row => { const line = el('tr'); row.forEach((v, i) => line.append(el('td', null, secretKey.test(table.columns[i]) ? '[Masqué]' : String(redact(v) ?? '—')))); body.append(line); });
      if (!rows.length) { const line = el('tr'); const cell = el('td', null, 'Aucune ligne correspondant au filtre.'); cell.colSpan = table.columns.length; line.append(cell); body.append(line); }
    }
    // Filtrer les valeurs masquées plutôt que permettre de sonder leurs secrets.
    table = { ...table, rows: table.rows.map(row => row.map((v, i) => secretKey.test(table.columns[i]) ? '[Masqué]' : redact(v))) };
    input.addEventListener('input', draw); draw();
  });
}
export function drawJson(host, raw, provenance = null) {
  const value = jsonValue(raw); if (value === undefined) return;
  const details = el('details', 'result-json'); details.append(el('summary', null, 'Données brutes — JSON formaté'));
  const formatted = JSON.stringify(value, null, 2); const pre = el('pre'); const code = el('code');
  const tokens = /"(?:\\.|[^"\\])*"(?=\s*:)|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;
  let offset = 0;
  for (const match of formatted.matchAll(tokens)) {
    code.append(document.createTextNode(formatted.slice(offset, match.index)));
    const token = match[0]; const kind = token.startsWith('"') ? /^\s*:/.test(formatted.slice(match.index + token.length)) ? 'key' : 'string' : /^(true|false|null)$/.test(token) ? 'literal' : 'number';
    code.append(el('span', 'json-' + kind, token)); offset = match.index + token.length;
  }
  code.append(document.createTextNode(formatted.slice(offset))); pre.append(code);
  const label = el('label', null, 'Rechercher dans le JSON'); const search = el('input'); search.type = 'search'; label.append(search);
  const feedback = el('p', 'muted'); feedback.setAttribute('role', 'status');
  search.addEventListener('input', () => { const term = search.value.toLocaleLowerCase('fr'); feedback.textContent = term ? formatted.toLocaleLowerCase('fr').includes(term) ? 'Texte trouvé dans le JSON.' : 'Aucune correspondance.' : ''; code.querySelectorAll('span').forEach(s => s.classList.toggle('json-match', !!term && s.textContent.toLocaleLowerCase('fr').includes(term))); });
  const exported = provenance ? JSON.stringify(redact({ response: value, ...provenance }), null, 2) : formatted;
  const controls = el('div', 'row'); const copy = el('button', null, 'Copier le JSON'); copy.type = 'button';
  copy.addEventListener('click', async () => { try { await navigator.clipboard.writeText(exported); feedback.textContent = 'JSON copié.'; } catch { feedback.textContent = 'Copie indisponible. Sélectionnez le texte ou téléchargez le JSON.'; } });
  const download = el('button', null, 'Télécharger le JSON'); download.type = 'button';
  download.addEventListener('click', () => { const url = URL.createObjectURL(new Blob([exported], { type: 'application/json' })); const link = el('a'); link.href = url; link.download = 'resultat-kex.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); });
  controls.append(copy, download); details.append(el('p', 'muted', 'Les champs sensibles reconnus sont masqués dans cette vue et ses exports. Vérifiez le contenu avant partage.'), label, controls, feedback, pre); host.append(details);
}

// Préférences séparées par identité ; aucun contenu de réponse n’y est conservé.
export function rememberDetails(host, scope) {
  host.querySelectorAll('details').forEach(details => {
    const label = details.querySelector('summary')?.textContent;
    if (!label || details.dataset.preferenceBound) return;
    details.dataset.preferenceBound = 'true';
    const key = `kex-result-display:${scope}:${label}`;
    try { const saved = localStorage.getItem(key); if (saved !== null) details.open = saved === 'true'; } catch { /* Affichage utilisable sans stockage. */ }
    details.addEventListener('toggle', () => { try { localStorage.setItem(key, String(details.open)); } catch { /* Préférence non persistée. */ } });
  });
}
export function exportBundle(turn, provenance = {}) {
  const parsed = jsonValue(turn.text);
  return redact({ response: parsed === undefined ? turn.text : parsed, sources: turn.sources || [], ...provenance });
}
export function markdownSummary(turn, provenance = {}) {
  const data = exportBundle(turn, provenance); const r = data.response;
  const content = r && typeof r === 'object' && r.kind === 'result'
    ? [r.conclusion, r.observations, r.uncertainties, r.nextAction].filter(Boolean).join('\n\n') : typeof r === 'string' ? r : JSON.stringify(r, null, 2);
  return `${content}\n\nRéponse reçue : ${data.receivedAt || 'Date non fournie'}\n\nSources :\n${data.sources.map(s => `- [source:${s.id}] ${s.source || s.id} — ${s.observedAt || 'Date non fournie'} — validité : ${s.validUntil || 'Non fournie'}\n  ${s.excerpt || 'Extrait non fourni'}`).join('\n') || 'Aucune source fournie'}\n`;
}
export function tableCsv(table, provenance = {}) {
  const safe = redact(provenance);
  // Les métadonnées suivent chaque ligne pour rester associées après filtrage dans un tableur.
  const columns = [...table.columns, 'Sources (JSON)', 'Résultats outils (JSON)', 'Date de réponse'];
  const sources = JSON.stringify(safe.sources || []);
  const cell = value => { let v = String(value ?? ''); if (/^[\s]*[=+@-]/.test(v)) v = "'" + v; return '"' + v.replaceAll('"', '""') + '"'; };
  const rows = table.rows.map(row => [...row.map((v, i) => secretKey.test(table.columns[i]) ? '[Masqué]' : redact(v)), sources, JSON.stringify(safe.tools || []), safe.receivedAt || 'Date non fournie']);
  return '\uFEFF' + [columns, ...rows].map(row => row.map(cell).join(',')).join('\r\n');
}
function downloadResult(name, content, type) {
  const url = URL.createObjectURL(new Blob([content], { type })); const a = el('a'); a.href = url; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function drawExports(host, turn, provenance = {}) {
  const controls = el('div', 'row'); const feedback = el('p', 'muted'); feedback.setAttribute('role', 'status');
  const copy = el('button', null, 'Copier la synthèse en Markdown'); copy.type = 'button';
  copy.addEventListener('click', async () => { try { await navigator.clipboard.writeText(markdownSummary(turn, provenance)); feedback.textContent = 'Synthèse et sources copiées.'; } catch { feedback.textContent = 'Copie indisponible ; utilisez le téléchargement Markdown.'; } });
  const md = el('button', null, 'Télécharger le Markdown'); md.type = 'button'; md.addEventListener('click', () => downloadResult('synthese-kex.md', markdownSummary(turn, provenance), 'text/markdown;charset=utf-8'));
  const json = el('button', null, 'Exporter les données et sources en JSON'); json.type = 'button'; json.addEventListener('click', () => downloadResult('resultat-kex-sources.json', JSON.stringify(exportBundle(turn, provenance), null, 2), 'application/json'));
  controls.append(copy, md, json); host.append(controls, feedback);
}
export function validMetrics(metrics) {
  return Array.isArray(metrics) ? metrics.slice(0, 12).filter(m => m && typeof m.label === 'string' && m.label.length <= 200 && typeof m.value === 'number' && Number.isFinite(m.value) && typeof m.unit === 'string' && m.unit.trim() && m.unit.length <= 80 && typeof m.period === 'string' && m.period.trim() && m.period.length <= 200) : [];
}
export function metricPoints(metric) {
  if (!Array.isArray(metric.points) || metric.points.length > 200) return [];
  const points = metric.points.filter(p => p && typeof p.value === 'number' && Number.isFinite(p.value) && typeof p.at === 'string' && Number.isFinite(Date.parse(p.at)));
  // Une série mal formée ne doit pas sembler continue après suppression des trous.
  return points.length === metric.points.length ? points.slice().sort((a, b) => Date.parse(a.at) - Date.parse(b.at)) : [];
}
export function drawMetrics(host, metrics) {
  validMetrics(metrics).forEach(m => {
    const section = el('section', 'result-metric'); section.append(el('h3', null, m.label), el('strong', null, `${m.value.toLocaleString('fr-FR')} ${m.unit}`), el('p', 'muted', `Période : ${m.period}`));
    const c = m.comparison; const comparable = c && typeof c.value === 'number' && Number.isFinite(c.value) && typeof c.period === 'string' && c.period.trim();
    section.append(el('p', null, comparable ? `Référence : ${c.value.toLocaleString('fr-FR')} ${m.unit} (${c.period}) · Écart : ${(m.value - c.value).toLocaleString('fr-FR')} ${m.unit}` : 'Référence de comparaison non fournie.'));
    const points = metricPoints(m);
    if (points.length >= 2 && Date.parse(points.at(-1).at) > Date.parse(points[0].at)) {
      const ns = 'http://www.w3.org/2000/svg'; const svg = document.createElementNS(ns, 'svg'); svg.setAttribute('viewBox', '0 0 320 100'); svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', `${m.label} : évolution en ${m.unit}, du ${points[0].at} au ${points.at(-1).at}. Valeurs exactes dans le tableau.`);
      const low = Math.min(...points.map(p => p.value)); const high = Math.max(...points.map(p => p.value)); const start = Date.parse(points[0].at); const span = Date.parse(points.at(-1).at) - start;
      const line = document.createElementNS(ns, 'polyline'); line.setAttribute('points', points.map(p => `${10 + (Date.parse(p.at) - start) / span * 300},${high === low ? 50 : 90 - (p.value - low) / (high - low) * 80}`).join(' ')); line.setAttribute('fill', 'none'); line.setAttribute('stroke', 'currentColor'); line.setAttribute('stroke-width', '2'); svg.append(line); section.append(svg, el('p', 'muted', `Min. : ${low} ${m.unit} · Max. : ${high} ${m.unit} · Du ${points[0].at} au ${points.at(-1).at}`));
      drawTables(section, [{ title: m.label + ' — valeurs exactes', columns: ['Date', `Valeur (${m.unit})`], rows: points.map(p => [p.at, p.value]) }], m.provenance || {});
    } else if (m.points?.length) section.append(el('p', 'muted', 'Courbe indisponible : au moins deux mesures datées distinctes et valides sont nécessaires.'));
    host.append(section);
  });
}
