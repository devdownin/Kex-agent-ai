// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile('src/main/resources/static/assets/user-context.js', 'utf8');
const { readAttachments, contextText, skillCategory, summary } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const file = (name, text, size = text.length) => ({ name, size, text: async () => text });
assert.deepEqual(await readAttachments([file('orders.csv', 'id,etat\n1,OK')]), [{ name: 'orders.csv', text: 'id,etat\n1,OK' }]);
for (const rows of [[file('binary.pdf', 'data')], [file('big.txt', 'x'.repeat(5001))], [file('empty.txt', '')], [file('broken.txt', '\u0000')], [file('unknown.txt', '\ufffd')], [file('big.txt', 'x', 50001)], Array.from({ length: 4 }, () => file('x.txt', 'x'))]) await assert.rejects(readAttachments(rows));
assert.match(contextText({ process: 'orders', period: 'hier', environment: 'test', files: [{ name: '<img>', text: '<script>' }] }), /<script>/);
assert.equal(skillCategory({ title: 'Préparer un bilan' }), 'report'); assert.equal(skillCategory({ title: 'Comprendre une anomalie' }), 'investigate'); assert.equal(skillCategory({ title: 'Vérifier les commandes' }), 'monitor'); assert.equal(skillCategory({ title: 'Autre' }), 'other');
assert.equal(summary({ status: 'RUNNING' }), null);
assert.match(summary({ status: 'COMPLETE', turns: [{ role: 'agent', completed: true, sources: [{}] }] }).limits, /ne prouve pas/);
assert.match(summary({ status: 'INTERRUPTED', turns: [{ role: 'agent', completed: false }] }).limits, /pas confirmée/);
assert.match(summary({ status: 'NEEDS_INPUT', turns: [{ role: 'agent', completed: true }] }).title, /précision/);
assert.match(summary({ status: 'COMPLETE', turns: [{ role: 'agent', completed: true }] }, { status: 'VERIFIED' }).title, /Objectif vérifié/);
console.log('✓ contexte : fichiers lisibles et bornés, catégories indicatives et bilan sans réussite inventée');
