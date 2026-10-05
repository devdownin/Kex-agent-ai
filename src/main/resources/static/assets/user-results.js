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
export function drawTables(host, tables) {
  validTables(tables).forEach(table => {
    const section = el('section', 'result-table'); section.append(el('h3', null, table.title));
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
export function drawJson(host, raw) {
  const value = jsonValue(raw); if (value === undefined) return;
  const details = el('details', 'result-json'); details.append(el('summary', null, 'Voir le JSON formaté'));
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
  const controls = el('div', 'row'); const copy = el('button', null, 'Copier le JSON'); copy.type = 'button';
  copy.addEventListener('click', async () => { try { await navigator.clipboard.writeText(formatted); feedback.textContent = 'JSON copié.'; } catch { feedback.textContent = 'Copie indisponible. Sélectionnez le texte ou téléchargez le JSON.'; } });
  const download = el('button', null, 'Télécharger le JSON'); download.type = 'button';
  download.addEventListener('click', () => { const url = URL.createObjectURL(new Blob([formatted], { type: 'application/json' })); const link = el('a'); link.href = url; link.download = 'resultat-kex.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); });
  controls.append(copy, download); details.append(el('p', 'muted', 'Les champs sensibles reconnus sont masqués dans cette vue et ses exports. Vérifiez le contenu avant partage.'), label, controls, feedback, pre); host.append(details);
}
