// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { $, el } from './core.js';

const LABELS = { result: 'Résultat disponible', input: 'Précision nécessaire', approval: 'Approbation attendue' };
const DEFAULTS = { result: true, input: true, approval: true, desktop: false };

export function userNotifications() {
  let identity = null; let preferences = { ...DEFAULTS }; let rows = []; let seen = []; let generation = 0;
  const desktop = new Set();
  const key = suffix => `kex.agent.user.notifications.${encodeURIComponent(identity)}.${suffix}`;
  function persist() {
    if (!identity) return;
    try {
      localStorage.setItem(key('preferences'), JSON.stringify(preferences));
      sessionStorage.setItem(key('events'), JSON.stringify({ rows, seen }));
    } catch { $('#notification-permission').textContent = 'Les notifications restent en mémoire : leur enregistrement a échoué.'; }
  }
  function draw() {
    $('#notification-count').textContent = rows.some(r => !r.read) ? `(${rows.filter(r => !r.read).length})` : '';
    const host = $('#notification-list'); host.replaceChildren();
    rows.forEach(row => {
      const card = el('article', 'panel');
      const link = el('a', null, `${LABELS[row.type]}${row.read ? '' : ' · Non lu'}`); link.href = row.target;
      link.addEventListener('click', () => { row.read = true; persist(); draw(); });
      card.append(link, el('p', null, row.title), el('small', 'muted', new Date(row.at).toLocaleString('fr-FR'))); host.append(card);
    });
    if (!rows.length) host.append(el('p', 'muted', identity ? 'Aucune notification pour le moment.' : 'Connectez-vous pour retrouver vos notifications.'));
  }
  function permission() {
    const supported = 'Notification' in globalThis;
    $('#enable-notifications').disabled = !identity || !supported || Notification.permission !== 'default';
    $('#notify-desktop').disabled = !identity || !supported || Notification.permission !== 'granted';
    $('#notification-permission').textContent = !supported ? 'Les alertes du navigateur ne sont pas disponibles. Les notifications restent accessibles ici.' : Notification.permission === 'denied' ? 'Les alertes sont bloquées par le navigateur. Les notifications restent accessibles ici ; vous pouvez modifier l’autorisation dans les réglages du site.' : Notification.permission === 'granted' ? 'Alertes autorisées. Choisissez si vous souhaitez les recevoir pour cet espace.' : 'Les alertes du navigateur nécessitent votre autorisation explicite.';
  }
  function notify(id, type, title, target) {
    if (!identity || seen.includes(id)) return;
    // Mémoriser aussi les événements désactivés évite une rafale lors d’un changement de préférence.
    seen.push(id); seen = seen.slice(-500);
    if (!preferences[type]) { persist(); return; }
    rows.unshift({ id, type, title, target, at: new Date().toISOString(), read: false }); rows = rows.slice(0, 50);
    persist(); draw(); $('#notification-live').textContent = LABELS[type];
    if (preferences.desktop && document.hidden && 'Notification' in globalThis && Notification.permission === 'granted') {
      try {
        const own = generation;
        const alert = new Notification('Kex-anHarness', { body: LABELS[type], tag: `kex-${id}` }); desktop.add(alert);
        alert.onclose = () => desktop.delete(alert);
        alert.onclick = () => { if (own === generation) { window.focus(); location.hash = target; } alert.close(); };
      } catch { /* Le centre de notifications reste disponible si le navigateur refuse l’alerte. */ }
    }
  }
  function reset(nextIdentity = null, roles = []) {
    generation++; desktop.forEach(alert => alert.close()); desktop.clear();
    identity = nextIdentity; preferences = { ...DEFAULTS }; rows = []; seen = [];
    if (identity) {
      try {
        const saved = JSON.parse(localStorage.getItem(key('preferences')) || '{}');
        for (const type of Object.keys(DEFAULTS)) if (typeof saved[type] === 'boolean') preferences[type] = saved[type];
        const events = JSON.parse(sessionStorage.getItem(key('events')) || '{}');
        rows = Array.isArray(events.rows) ? events.rows.filter(r => r && typeof r.id === 'string' && Object.hasOwn(LABELS, r.type) && typeof r.title === 'string' && typeof r.target === 'string' && (r.target === '#/approvals' || /^#\/request\/[a-zA-Z0-9-]+$/.test(r.target))).slice(0, 50) : [];
        seen = Array.isArray(events.seen) ? events.seen.filter(id => typeof id === 'string').slice(-500) : [];
      } catch { rows = []; seen = []; }
    }
    for (const type of Object.keys(DEFAULTS)) { $('#notify-' + type).checked = preferences[type]; $('#notify-' + type).disabled = !identity; }
    $('#notify-approval').disabled = !identity || !roles.some(r => ['OPERATOR', 'ADMIN'].includes(r));
    $('#notification-live').textContent = ''; permission(); draw();
  }
  $('#notification-preferences').addEventListener('change', () => {
    if (!identity) return;
    for (const type of Object.keys(DEFAULTS)) preferences[type] = $('#notify-' + type).checked;
    persist();
  });
  $('#notification-preferences').addEventListener('submit', event => event.preventDefault());
  $('#enable-notifications').addEventListener('click', async () => {
    if (!identity || !('Notification' in globalThis) || Notification.permission !== 'default') return;
    const own = generation; $('#enable-notifications').disabled = true;
    try { await Notification.requestPermission(); }
    catch { /* Les notifications internes ne dépendent pas de cette autorisation. */ }
    if (own === generation) permission();
  });
  $('#read-notifications').addEventListener('click', () => { rows.forEach(r => { r.read = true; }); persist(); draw(); });
  return {
    reset,
    request(request) {
      if (!['COMPLETE', 'PARTIAL', 'NEEDS_INPUT'].includes(request.status)) return;
      const type = request.status === 'NEEDS_INPUT' ? 'input' : 'result';
      notify(`request:${request.id}:${request.updatedAt}`, type, request.status === 'PARTIAL' ? 'Réception partielle : vérifiez les limites dans la demande.' : request.title, '#/request/' + request.id);
    },
    tasks(tasks) {
      const outcomes = { VERIFIED: 'Critère final confirmé', COMPLETED: 'Objectif non vérifié', FAILED: 'Critère non satisfait', PAUSED: 'Traitement en pause', NEEDS_RECONCILIATION: 'Résultat incertain — examen nécessaire' };
      tasks.forEach(task => {
        if (task.status === 'DRAFT') notify(`approval:${task.id}:${task.revision}`, 'approval', task.plan.objective, '#/approvals');
        else if (Object.hasOwn(outcomes, task.status)) notify(`result:${task.id}:${task.revision}`, 'result', `${outcomes[task.status]} : ${task.plan.objective}`, '#/approvals');
      });
    },
  };
}
