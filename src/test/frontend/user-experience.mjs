// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile('src/main/resources/static/assets/user-experience.js', 'utf8');
const { parseResponse, parameterFields, responsePrompt, skillSignature, activity } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const result = { kind: 'result', observations: '<img onerror=evil()>', uncertainties: 'Pas de preuve', nextAction: 'Vérifier' };
assert.deepEqual(parseResponse(JSON.stringify(result)), result);
assert.deepEqual(parseResponse('```json\n' + JSON.stringify(result) + '\n```'), result);
for (const raw of ['Texte libre', '{"kind":"result"', JSON.stringify({ ...result, uncertainties: '' }), 'null', JSON.stringify({ kind: 'clarification', question: 'Quand ?', choices: [] })]) assert.equal(parseResponse(raw), null);
const clarification = { kind: 'clarification', question: 'Quand ?', choices: [{ label: 'Aujourd’hui', value: 'Depuis ce matin' }, { label: 'Hier', value: 'Hier' }] };
assert.deepEqual(parseResponse(JSON.stringify(clarification)), clarification);
assert.equal(parseResponse(JSON.stringify({ ...clarification, choices: [{ label: 'x', value: {} }, clarification.choices[1]] })), null);
const fields = parameterFields({ check: { topic: 'orders', limit: 10, enabled: true, complex: ['a'] } });
assert.equal(fields.fields.length, 3); assert.equal(fields.omitted, true);
assert.deepEqual(fields.fields[0].path, ['check', 'topic']);
assert.equal(parameterFields(Object.fromEntries(Array.from({ length: 30 }, (_, i) => [i, i]))).fields.length, 20);
assert.notEqual(skillSignature({ id: 's', markdown: 'm' }), skillSignature({ id: 's', markdown: 'm', verification: { preconditions: ['changed'] } }));
assert.match(responsePrompt('Vérifier commandes'), /^Vérifier commandes/);
assert.match(activity({ tool: 'unknown', failed: true }), /problème/);
console.log('✓ user experience: structured and malformed responses, clarification validation, bounded skill parameters, metadata changes and failed activities');
