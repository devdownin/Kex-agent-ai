// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { $, el } from './core.js';
import { drawEvidence } from './user-evidence.js';
import { activity, parseResponse } from './user-experience.js';
const date = at => at && Number.isFinite(Date.parse(at)) ? new Date(at).toLocaleString('fr-FR') : 'Date non fournie';
export function drawWork({ identity, history, tasks, examined, historyState, taskState }) {
  $('#work-note').textContent = !identity ? 'Connectez-vous pour retrouver votre travail.' : 'Votre compte et votre espace uniquement. Ouvrir un élément ne lance aucune action.';
  $('#work-freshness').textContent = identity ? `Demandes : ${historyState}. Plans : ${taskState}.` : '';
  const groups = { running: [], decisions: [], results: [] };
  [...history].sort((a, b) => (Date.parse(b.updatedAt) || 0) - (Date.parse(a.updatedAt) || 0)).forEach(r => {
    const row = [r.title, '#/request/' + r.id, date(r.updatedAt)];
    if (r.status === 'RUNNING') groups.running.push([...row, 'Traitement en cours']);
    else if (r.status === 'NEEDS_INPUT') groups.decisions.push([...row, 'Votre réponse est nécessaire']);
    else groups.results.push([...row, examined.includes(`${r.id}:${r.updatedAt}`) ? 'Résultat examiné' : r.status === 'COMPLETE' ? 'Résultat à examiner' : r.status === 'PARTIAL' ? 'Résultat partiel à examiner' : 'Réception interrompue à examiner']);
  });
  tasks.forEach(t => {
    const row = [t.plan.objective, '#/approvals', date(t.updatedAt)];
    if (t.status === 'RUNNING') groups.running.push([...row, 'Plan en cours']);
    else if (['DRAFT', 'APPROVED', 'PAUSED', 'NEEDS_RECONCILIATION', 'FAILED'].includes(t.status)) groups.decisions.push([...row, t.status === 'DRAFT' ? 'Votre décision est attendue' : t.status === 'APPROVED' ? 'Plan approuvé, à lancer' : 'Plan à examiner']);
  });
  for (const [group, rows] of Object.entries(groups)) {
    const host = $('#work-' + group); host.replaceChildren();
    rows.slice(0, 6).forEach(([title, target, at, status]) => {
      const card = el('article', 'work-card'); const link = el('a', null, title); link.href = target;
      card.append(link, el('p', null, status), el('small', 'muted', at)); host.append(card);
    });
    if (!rows.length) host.append(el('p', 'muted', identity ? 'Aucun élément dans les données disponibles.' : 'Votre travail apparaîtra après connexion.'));
    $('#work-' + group + '-count').textContent = `(${rows.length})`;
    if (rows.length > 6) host.append(el('p', 'muted', 'Les six premiers éléments sont affichés. Retrouvez la liste complète dans Mes demandes ou Plans à valider.'));
  }
}
export function drawRunActivity(request) {
  let status = request.status === 'NEEDS_INPUT' ? 'Votre réponse est attendue pour poursuivre.' : request.status === 'RUNNING' ? (request.answerStarted ? 'Kex rédige votre réponse.' : 'Kex analyse votre demande ; les premiers résultats sont attendus.') : 'Consultez le résultat et ses limites avant de poursuivre.';
  const lastTool = request.tools?.at(-1); if (request.status === 'RUNNING' && lastTool) status += ' Dernière étape reçue : ' + activity(lastTool) + '.';
  $('#run-activity').textContent = status;
  $('#last-activity').textContent = 'Dernière activité reçue : ' + date(request.updatedAt);
  const silent = request.status === 'RUNNING' && Number.isFinite(Date.parse(request.updatedAt)) && Date.now() - Date.parse(request.updatedAt) >= 60000;
  $('#activity-delay').hidden = !silent;
  $('#activity-delay').textContent = silent ? 'Aucune nouvelle information reçue depuis au moins une minute. Le traitement peut continuer côté serveur. Actualisez le suivi ; ne relancez pas la même action sans vérifier son état.' : '';
}
export function resultSections(host, answer) {
  const parsed = answer?.completed ? parseResponse(answer.text) : null;
  if (parsed?.kind === 'clarification') return;
  const conclusion = (typeof parsed?.conclusion === 'string' && parsed.conclusion.trim()) || parsed?.observations || answer?.text || 'Aucune conclusion disponible.';
  host.append(el('h3', null, 'Conclusion'));
  drawEvidence(host, conclusion.length > 500 ? conclusion.slice(0, 500) + '…' : conclusion, answer?.sources);
  const sourceDetails = el('details'); sourceDetails.append(el('summary', null, 'Sources')); host.append(sourceDetails);
  if (answer?.sources?.length) answer.sources.forEach(s => {
    const source = el('details'); source.append(el('summary', null, `${s.source || s.id || 'Source sans nom'} · ${date(s.observedAt)}`), el('pre', null, s.excerpt || 'Extrait non fourni')); sourceDetails.append(source);
  });
  else sourceDetails.append(el('p', null, 'Aucune source consultable fournie pour cette réponse.'));
  host.append(el('h3', null, 'Limites'), el('div', 'answer', parsed?.uncertainties || 'Les limites ne sont pas structurées dans cette réponse. Examinez le texte complet et les preuves avant de conclure.'));
  host.append(el('h3', null, 'Prochaine action'), el('div', 'answer', parsed?.nextAction || 'Vérifier les éléments disponibles ; demandez une précision si nécessaire.'));
}
