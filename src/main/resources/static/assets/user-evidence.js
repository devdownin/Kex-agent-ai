// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { el } from './core.js';
import { findingQuality } from './user-presentation.js';

const date = value => Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('fr-FR') : 'Date non fournie';
export function resultIssue(request) {
  if (request.loadingDetail) return null;
  const answer = request.turns?.filter(t => t.role === 'agent').at(-1);
  const status = request.detailError?.status || answer?.httpStatus;
  if ([401, 403].includes(status)) return { kind: 'denied', title: 'Accès refusé', text: 'Votre accès ne permet pas de récupérer ce résultat. Vérifiez votre connexion et vos droits.', action: 'account' };
  if (status === 409) return { kind: 'conflict', title: 'État modifié ailleurs', text: 'Une autre session a modifié cette demande ou un traitement est déjà actif. Actualisez son état avant toute nouvelle tentative.', action: 'refresh' };
  if (status === 429) return { kind: 'limited', title: 'Limite temporaire atteinte', text: 'L’envoi a été refusé par la limite de débit. Patientez puis vérifiez l’état de la demande. Aucun nouvel envoi automatique.', action: 'refresh' };
  if (request.detailError) return { kind: 'unavailable', title: status === 404 ? 'Demande introuvable ou inaccessible' : status >= 500 || status === 0 ? 'Erreur de récupération' : 'Information indisponible', text: 'Le détail n’a pas pu être récupéré. Les données déjà affichées peuvent être anciennes ; leur absence ne signifie pas qu’aucun résultat existe.', action: 'refresh' };
  if (request.status === 'RUNNING' || request.status === 'NEEDS_INPUT') return null;
  if (request.status === 'INTERRUPTED' || (request.status === 'PARTIAL' && !answer?.completed)) return { kind: 'interrupted', title: 'Réception interrompue', text: 'La fin du traitement n’est pas confirmée. Des actions peuvent avoir été engagées côté serveur. Vérifiez le suivi et le plan avant de poursuivre.', action: 'refresh' };
  if (request.status === 'ERROR') return { kind: 'error', title: 'Erreur de récupération ou de traitement', text: 'Le résultat complet n’est pas disponible. Consultez les détails reçus et vérifiez l’état serveur avant de préparer une nouvelle demande.', action: 'refresh' };
  if (answer?.completed && !answer.text?.trim()) return { kind: 'empty', title: 'Aucun résultat reçu', text: 'La réception est terminée sans contenu. Cela ne prouve ni l’absence de données ni la réussite de l’objectif. Précisez le périmètre après vérification.', action: 'prepare' };
  if (request.status === 'PARTIAL') return { kind: 'partial', title: 'Résultat partiel', text: 'Une partie de la réponse est disponible, mais une consultation ou une action a échoué. Les conclusions peuvent être incomplètes.', action: 'refresh' };
  return null;
}
export function citations(text, sources = []) {
  sources = Array.isArray(sources) ? sources : [];
  const parts = []; const pattern = /\[source:([^\]\r\n]{1,200})\]/g; let offset = 0;
  for (const match of String(text).matchAll(pattern)) {
    if (match.index > offset) parts.push({ text: text.slice(offset, match.index) });
    parts.push({ id: match[1], source: sources.find(s => s.id === match[1]) || null }); offset = match.index + match[0].length;
  }
  if (offset < text.length) parts.push({ text: text.slice(offset) });
  return parts;
}
export function sourceValidity(source, now = Date.now()) {
  const until = Date.parse(source.validUntil);
  return Number.isFinite(until) ? until <= now ? 'Validité expirée' : 'Valide jusqu’au ' + date(source.validUntil) : 'Validité non fournie';
}
export function drawEvidence(host, text, sources = []) {
  const content = el('div', 'answer evidence-text'); let linked = false;
  citations(text, sources).forEach(part => {
    if (part.text !== undefined) { content.append(document.createTextNode(part.text)); return; }
    if (!part.source) { content.append(el('span', 'source-missing', `[source:${part.id}] — Source non fournie`)); return; }
    linked = true; const source = part.source; const group = el('span', 'evidence-reference');
    const button = el('button', 'evidence-link', `${source.source || source.id} · ${date(source.observedAt)}`); button.type = 'button'; button.setAttribute('aria-expanded', 'false');
    const detail = el('span', 'evidence-excerpt'); detail.hidden = true;
    detail.append(el('strong', null, 'Source ' + source.id), el('span', null, ' · Observé le ' + date(source.observedAt) + ' · ' + sourceValidity(source)), el('span', 'muted', 'Association proposée dans la réponse ; vérifiez l’extrait et sa validité.'), el('span', null, source.excerpt || 'Extrait non fourni.'));
    button.addEventListener('click', () => { detail.hidden = !detail.hidden; button.setAttribute('aria-expanded', String(!detail.hidden)); }); group.append(button, detail); content.append(group);
  });
  host.append(content);
  if (!linked) host.append(el('p', 'muted evidence-note', 'Aucune source reliée à ce constat dans la réponse. Les sources consultées ne prouvent pas à elles seules cette conclusion.'));
}
export function validFindings(findings) {
  return Array.isArray(findings) ? findings.slice(0, 20).filter(f => f && typeof f.text === 'string' && f.text.trim() && f.text.length <= 4000 && Array.isArray(f.sourceIds) && f.sourceIds.length <= 10 && f.sourceIds.every(id => typeof id === 'string' && /^[^\]\r\n]{1,200}$/.test(id)) && (f.toolNames === undefined || (Array.isArray(f.toolNames) && f.toolNames.length <= 10 && f.toolNames.every(name => typeof name === 'string' && name.length <= 200)))) : [];
}
export function drawFindings(host, findings, sources, tools = []) {
  const rows = validFindings(findings); if (!rows.length) return;
  const section = el('section', 'result-findings'); section.append(el('h3', null, 'Constats et preuves associées'));
  rows.forEach(f => { const item = el('article'); const quality = findingQuality(f, sources); item.append(el('p', 'finding-quality', quality.label)); quality.notes.forEach(note => item.append(el('p', 'source-missing', note))); if (quality.contradictions.length >= 2) drawEvidence(item, 'Sources en contradiction signalée : ' + quality.contradictions.map(id => `[source:${id}]`).join(' '), sources); drawEvidence(item, f.text + ' ' + f.sourceIds.map(id => `[source:${id}]`).join(' '), sources);
    (f.toolNames || []).forEach(name => { const calls = (tools || []).filter(t => t.tool === name); if (calls.length) drawToolEvidence(item, calls); else item.append(el('p', 'source-missing', `Outil ${name} : aucun résultat fourni pour cette réponse.`)); }); section.append(item); }); host.append(section);
}

export function drawToolEvidence(host, tools = []) {
  (tools || []).forEach(t => {
    const details = el('details', 'tool-evidence'); details.append(el('summary', null, `${t.tool} · ${date(t.observedAt)} · ${t.failed ? 'Échec' : 'Appel terminé'}`));
    details.append(el('p', 'muted', 'Association proposée dans la réponse ; cet appel ne confirme pas à lui seul la conclusion.'), el('pre', null, t.result || 'Résultat non disponible : appel ancien, contenu non structuré ou trop volumineux. Consultez le service et vérifiez vos droits.'));
    host.append(details);
  });
}
