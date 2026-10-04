// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
// Le dépôt n'a pas de package.json : l'URL data conserve le format module sous Node 18 aussi.
const source = await readFile(new URL('../../main/resources/static/assets/user-stream.js', import.meta.url), 'utf8');
const { events } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const wire = ': heartbeat\r\nevent: conversation\r\ndata: conv-1\r\n\r\nevent: token\r\ndata: vérifié\r\ndata: ligne suivante\r\n\r\nevent: done\r\ndata: response-complete\r\n\r\n';
const bytes = new TextEncoder().encode(wire);
for (const size of [1, 2, 7, 17, 500]) {
  let offset = 0;
  const stream = new ReadableStream({ pull(controller) {
    if (offset === bytes.length) { controller.close(); return; }
    controller.enqueue(bytes.slice(offset, offset + size)); offset = Math.min(bytes.length, offset + size);
  } });
  const got = []; for await (const event of events(new Response(stream))) got.push(event);
  assert.deepEqual(got, [{ name: 'conversation', data: 'conv-1' }, { name: 'token', data: 'vérifié\nligne suivante' }, { name: 'done', data: 'response-complete' }]);
  assert.equal(stream.locked, false);
}
const incomplete = new Response('event: token\ndata: fragment');
const incompleteEvents = [];
for await (const event of events(incomplete)) incompleteEvents.push(event);
assert.deepEqual(incompleteEvents, [], 'a truncated event is not a complete response');
let cancelled = false;
const early = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('event: token\ndata: fragment\n\n')); }, cancel() { cancelled = true; } });
for await (const event of events(new Response(early))) { assert.equal(event.name, 'token'); break; }
assert.equal(cancelled, true);
assert.equal(early.locked, false);
console.log('✓ SSE UTF-8, CRLF, multiline data, incomplete events and reader cleanup');
