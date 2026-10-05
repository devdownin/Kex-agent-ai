// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile('src/main/resources/static/assets/mcp-services.js', 'utf8');
const { parameterRows, exampleArguments, toolEffects, discoveryState, servicePrompt } =
  await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const schema = { type: 'object', required: ['topic'], properties: {
  topic: { type: 'string', description: 'Topic à examiner', examples: ['orders'] },
  limit: { type: 'integer', minimum: 1, description: 'Nombre de résultats' },
} };
assert.deepEqual(parameterRows(schema).map(row => [row.name, row.required]), [['topic', true], ['limit', false]]);
assert.deepEqual(exampleArguments(schema), { topic: 'orders' });
assert.equal(exampleArguments({ oneOf: [{ type: 'string' }] }), '<à renseigner selon le contrat>');
assert.equal(exampleArguments({ type: 'boolean', default: true }), true);
assert.deepEqual(parameterRows(null), []);
assert.deepEqual(toolEffects({ name: 'delete_everything' }), ['Effets non précisés']);
assert.deepEqual(toolEffects({ annotations: { readOnlyHint: true } }), ['Lecture seule déclarée par le serveur']);
assert.ok(toolEffects({ annotations: { destructiveHint: true } }).some(label => label.includes('suppression')));
assert.ok(toolEffects({ readOnlyByPolicy: true }).some(label => label.includes('politique Kex')));
assert.equal(discoveryState({ tools: [] }, 'loading').label, 'Récupération des informations…');
assert.equal(discoveryState({ tools: [] }, 'error').label, 'Échec de connexion');
assert.equal(discoveryState({ tools: [], reportedToolCount: 2 }, 'ready').label, 'Aucun service autorisé');
assert.equal(discoveryState({ tools: [], reportedToolCount: 0 }, 'ready').label, 'Aucun service exposé');
assert.equal(discoveryState({ tools: [{ name: 'search' }] }, 'ready').label, 'Informations récupérées');
assert.equal(discoveryState({ tools: [], resources: [{ name: 'orders' }] }, 'ready').label, 'Informations récupérées');
assert.equal(discoveryState({ tools: [], prompts: [{ name: 'triage' }], cached: true }, 'ready').label, 'Dernier catalogue conservé');
assert.match(discoveryState({ stale: true }, 'ready').label, /actualisation en échec/);
assert.match(discoveryState({ retrievedAt: '2026-10-04T19:00:00Z' }, 'error').label, /catalogue conservé/);
const prompt = servicePrompt({ connection: 'my-mcp' }, { name: 'search', description: 'Rechercher les topics', inputSchema: schema });
assert.match(prompt, /my-mcp/); assert.match(prompt, /search/); assert.match(prompt, /"topic": "orders"/);
assert.match(prompt, /\[à compléter\]/); assert.match(prompt, /validations Kex/);
console.log('✓ services MCP : paramètres, exemples, états et effets déclarés sans déduction du nom');

const { serviceAvailability, expectedResult, discoveryFailure } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
assert.match(serviceAvailability({ initialized: true }, { enabled: false }).label, /désactivée/);
assert.match(serviceAvailability({ stale: true }, { enabled: true }).label, /revalider/);
assert.match(serviceAvailability({ initialized: false }).label, /non confirmée/);
assert.equal(expectedResult({ name: 'known' }).declared, false);
const output = expectedResult({ outputSchema: { type: 'object', required: ['count'], properties: { count: { type: 'integer', description: 'Nombre de topics' } } } });
assert.equal(output.declared, true); assert.equal(output.fields[0].required, true); assert.deepEqual(output.example, { count: 0 });
assert.equal(discoveryFailure(403).retry, false); assert.match(discoveryFailure(503).detail, /catalogue est vide/);
