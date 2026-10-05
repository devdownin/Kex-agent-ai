// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = (await readFile('src/main/resources/static/assets/user-results.js', 'utf8')).replace("import { el } from './core.js';", '');
const { jsonValue, redact, validTables, tableRows, resultStatus, tableCsv, exportBundle, markdownSummary, validMetrics, metricPoints } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
assert.equal(resultStatus({ status: 'COMPLETE' }).tone, 'unknown', 'Une réponse complète ne confirme pas l’objectif');
assert.equal(resultStatus({ status: 'COMPLETE' }, { status: 'VERIFIED' }).tone, 'verified');
assert.equal(resultStatus({ status: 'ERROR' }).tone, 'failed');
assert.equal(resultStatus({ status: 'PARTIAL' }).tone, 'attention');
assert.deepEqual(jsonValue('```json\n{"authorization":"Bearer secret","nested":{"api_key":"secret"}}\n```'), { authorization: '[Masqué]', nested: { api_key: '[Masqué]' } });
assert.deepEqual(redact(['Bearer abc.def', { password: 'hide', value: 12 }]), ['Bearer [Masqué]', { password: '[Masqué]', value: 12 }]);
for (const raw of ['{', 'Texte libre', 'x'.repeat(200001)]) assert.equal(jsonValue(raw), undefined);
assert.equal(jsonValue('null'), null);
const table = { title: 'Retards', columns: ['Processus', 'Lag (messages)'], rows: [['orders', 12], ['customers', 2], ['billing', 0]] };
assert.deepEqual(tableRows(table, '', 1).map(r => r[1]), [0, 2, 12]);
assert.deepEqual(tableRows(table, 'ORDER'), [['orders', 12]]);
assert.deepEqual(tableRows(table, '', 1, true).map(r => r[1]), [12, 2, 0]);
assert.equal(table.rows[0][1], 12, 'Le tri ne modifie pas la réponse d’origine');
for (const bad of [{ ...table, rows: [[{}]] }, { ...table, rows: [[1]] }, { ...table, rows: Array(201).fill(['x', 1]) }, { ...table, columns: Array(13).fill('c') }]) assert.equal(validTables([bad]).length, 0);
assert.equal(validTables([table, table, table, table]).length, 3);
console.log('✓ résultats : statut fondé sur le serveur, JSON invalide, masquage imbriqué, tri numérique, filtres et limites des tableaux');

const provenance = { receivedAt: '2026-10-05T09:00:00Z', sources: [{ id: 'measure', source: 'Orders', observedAt: '2026-10-05T08:00:00Z', excerpt: 'Lag 12', token: 'secret' }] };
const turn = { text: JSON.stringify({ kind: 'result', observations: 'Lag 12 [source:measure]', uncertainties: 'Période limitée', nextAction: 'Vérifier', apiKey: 'secret' }), sources: provenance.sources };
assert.equal(exportBundle(turn, provenance).response.apiKey, '[Masqué]');
assert.equal(exportBundle(turn, provenance).sources[0].token, '[Masqué]');
assert.match(markdownSummary(turn, provenance), /\[source:measure\].*Orders/);
assert.match(markdownSummary(turn, provenance), /2026-10-05T08:00:00Z/);
const csv = tableCsv({ title: 'Export', columns: ['Nom', 'token'], rows: [['=HYPERLINK("bad")', 'secret'], ['a,b', 'hidden']] }, provenance);
assert.match(csv, /"'=HYPERLINK\(""bad""\)"/); assert.match(csv, /"a,b"/); assert.match(csv, /2026-10-05T08:00:00Z/); assert.doesNotMatch(csv, /secret|hidden/);
const metric = { label: 'Lag', value: 12, unit: 'messages', period: 'Ce matin', points: [{ at: '2026-10-05T08:00:00Z', value: 12 }, { at: '2026-10-05T07:00:00Z', value: 18 }] };
assert.equal(validMetrics([metric, { ...metric, value: NaN }, { ...metric, unit: '' }, { ...metric, period: '' }]).length, 1);
assert.deepEqual(metricPoints(metric).map(p => p.value), [18, 12]);
assert.deepEqual(metricPoints({ ...metric, points: [...metric.points, { at: 'invalid', value: 0 }] }), []);
assert.equal(metric.points[0].value, 12, 'La série reçue reste intacte');
console.log('✓ exports sourcés et datés, secrets masqués, CSV protégé et mesures incomplètes exclues');
