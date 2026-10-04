// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
class Element {
  constructor() { this.children = []; this.handlers = {}; this.textContent = ''; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  addEventListener(name, handler) { this.handlers[name] = handler; }
}
const nodes = new Map();
globalThis.testDollar = id => { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); };
globalThis.testEl = (tag, style, text = '') => Object.assign(new Element(), { tag, textContent: text });
const storage = () => { const data = new Map(); return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value) }; };
globalThis.localStorage = storage(); globalThis.sessionStorage = storage();
globalThis.document = { hidden: false }; globalThis.location = { hash: '' }; globalThis.window = { focus() {} };
let permissions = 0; let release; const alerts = [];
globalThis.Notification = class {
  static permission = 'default';
  static requestPermission() { permissions++; return new Promise(resolve => { release = () => { this.permission = 'granted'; resolve('granted'); }; }); }
  constructor(title, options) { this.options = options; alerts.push(this); }
  close() { this.closed = true; this.onclose?.(); }
};
const source = (await readFile('src/main/resources/static/assets/user-notifications.js', 'utf8')).replace("import { $, el } from './core.js';", 'const $ = globalThis.testDollar, el = globalThis.testEl;');
const { userNotifications } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const ui = userNotifications(); const $ = globalThis.testDollar;
const count = () => $('#notification-list').children.filter(c => c.children.some(n => n.tag === 'a')).length;
const request = (status, id = 'r1', updatedAt = 'one') => ({ id, status, updatedAt, title: '<img onerror=evil()>' });
ui.reset('alpha', ['OPERATOR']); assert.equal(permissions, 0, 'aucune autorisation spontanée');
ui.request(request('RUNNING')); ui.request(request('ERROR')); ui.request(request('INTERRUPTED')); assert.equal(count(), 0);
ui.request(request('COMPLETE')); ui.request(request('COMPLETE')); assert.equal(count(), 1, 'un seul événement par réponse');
assert.equal($('#notification-list').children[0].children[1].textContent, '<img onerror=evil()>', 'texte sans interprétation HTML');
ui.request(request('NEEDS_INPUT', 'r2')); assert.equal(count(), 2); assert.match($('#notification-live').textContent, /Précision/);
$('#notification-list').children[0].children[0].handlers.click(); assert.equal($('#notification-count').textContent, '(1)');
$('#notify-result').checked = false; $('#notification-preferences').handlers.change();
ui.request(request('COMPLETE', 'r3')); assert.equal(count(), 2);
ui.reset('alpha', ['OPERATOR']); assert.equal($('#notify-result').checked, false); ui.request(request('NEEDS_INPUT', 'r2')); assert.equal(count(), 2, 'déduplication après rechargement');
ui.tasks([{ id: 't1', revision: 0, status: 'APPROVED', plan: { objective: 'Plan' } }]); assert.equal(count(), 2);
ui.tasks([{ id: 't1', revision: 0, status: 'DRAFT', plan: { objective: 'Plan' } }]); ui.tasks([{ id: 't1', revision: 0, status: 'DRAFT', plan: { objective: 'Plan' } }]); assert.equal(count(), 3);
const pending = $('#enable-notifications').handlers.click(); assert.equal(permissions, 1);
ui.reset('beta', ['CHAT']); release(); await pending; assert.equal(count(), 0); assert.equal($('#notify-result').checked, true); assert.equal($('#notify-desktop').checked, false, 'une autorisation tardive n’active pas un autre compte');
assert.equal($('#notify-approval').disabled, true);
$('#notify-desktop').checked = true; $('#notification-preferences').handlers.change();
ui.request(request('COMPLETE')); assert.equal(alerts.length, 0, 'pas d’alerte système dans un onglet visible');
document.hidden = true; ui.request(request('PARTIAL', 'r4')); assert.equal(alerts.length, 1); assert.equal(alerts[0].options.body, 'Résultat disponible'); assert.ok(!JSON.stringify(alerts[0].options).includes('onerror'));
ui.reset('alpha', ['OPERATOR']); assert.equal(alerts[0].closed, true); alerts[0].onclick(); assert.equal(location.hash, '', 'une ancienne alerte ne navigue pas après changement de compte');
Notification.permission = 'denied'; ui.reset('beta', ['CHAT']); ui.request(request('NEEDS_INPUT', 'r5')); assert.equal(alerts.length, 1); assert.match($('#notification-permission').textContent, /bloquées/); assert.ok(count() > 0);
ui.reset('gamma', ['OPERATOR']);
ui.tasks([{ id: 't2', revision: 3, status: 'RUNNING', plan: { objective: 'Plan' } }]); assert.equal(count(), 0);
ui.tasks([{ id: 't2', revision: 4, status: 'FAILED', plan: { objective: 'Plan' } }]); assert.equal(count(), 1); assert.match($('#notification-list').children[0].children[1].textContent, /Critère non satisfait/);
ui.tasks([{ id: 't2', revision: 4, status: 'FAILED', plan: { objective: 'Plan' } }]); assert.equal(count(), 1, 'les résultats durables sont dédupliqués');
$('#read-notifications').handlers.click(); assert.equal($('#notification-count').textContent, '');
console.log('✓ notifications : événements réels, déduplication, préférences, compte isolé, autorisation explicite et tardive, alertes génériques et refus');
