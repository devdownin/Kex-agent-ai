// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = (await readFile('src/main/resources/static/assets/user-evidence.js', 'utf8')).replace("import { el } from './core.js';", '').replace("import { findingQuality } from './user-presentation.js';", '');
const { resultIssue, citations, sourceValidity, validFindings } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
assert.equal(resultIssue({ status: 'COMPLETE', turns: [{ role: 'agent', completed: true, text: '' }] }).kind, 'empty');
assert.equal(resultIssue({ status: 'ERROR', turns: [{ role: 'agent', httpStatus: 403 }] }).kind, 'denied');
assert.equal(resultIssue({ detailError: { status: 503 } }).kind, 'unavailable');
assert.equal(resultIssue({ detailError: { status: 404 } }).title, 'Demande introuvable ou inaccessible');
assert.equal(resultIssue({ status: 'PARTIAL', turns: [{ role: 'agent', completed: false }] }).kind, 'interrupted');
assert.equal(resultIssue({ status: 'PARTIAL', turns: [{ role: 'agent', completed: true, text: 'Quelques observations' }] }).kind, 'partial');
assert.equal(resultIssue({ detailError: { status: 409 } }).kind, 'conflict');
assert.equal(resultIssue({ status: 'RUNNING' }), null);
const evidence = { id: 'real', source: 'Mesure', observedAt: '2026-10-01T10:00:00Z', validUntil: '2026-10-02T10:00:00Z' };
const parts = citations('Fait [source:real] ; hypothèse [source:invented]', [evidence]);
assert.equal(parts[1].source, evidence); assert.equal(parts[3].source, null, 'Une référence inventée ne devient pas une preuve');
assert.equal(sourceValidity(evidence, Date.parse('2026-10-05')), 'Validité expirée');
assert.equal(sourceValidity({ validUntil: 'invalid' }), 'Validité non fournie');
assert.match(sourceValidity(evidence, Date.parse('2026-10-01')), /Valide jusqu/);
assert.deepEqual(citations('Sans citation', [evidence]), [{ text: 'Sans citation' }]);
assert.equal(validFindings([{ text: 'Constat', sourceIds: ['real'] }]).length, 1);
assert.equal(validFindings([{ text: 'Constat', sourceIds: ['fake]marker'] }]).length, 0);
console.log('✓ résultats incomplets, refus, conflits, sources connues/inventées, dates et validité sans preuve fabriquée');
