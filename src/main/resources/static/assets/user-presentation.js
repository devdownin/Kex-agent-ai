// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { el } from './core.js';
import { parseResponse } from './user-experience.js';

export function presentedResponse(turn) {
  if (!turn?.completed) return null;
  return turn.presentation ? turn.presentation.text ? parseResponse(turn.presentation.text) : null : parseResponse(turn.text);
}
export function findingQuality(finding, sources = [], now = Date.now()) {
  const type = { OBSERVATION: 'Observation directe déclarée', INFERENCE: 'Conclusion déduite', HYPOTHESIS: 'Hypothèse' };
  const evidence = sources.filter(s => finding.sourceIds?.includes(s.id));
  const notes = [];
  if (evidence.some(s => Number.isFinite(Date.parse(s.validUntil)) && Date.parse(s.validUntil) <= now)) notes.push('Source à validité expirée');
  if (evidence.some(s => Number.isFinite(Date.parse(s.observedAt)) && now - Date.parse(s.observedAt) > 7 * 86400000)) notes.push('Source observée il y a plus de 7 jours');
  if (evidence.some(s => !Number.isFinite(Date.parse(s.observedAt)))) notes.push('Horodatage de source absent ou invalide');
  const contradictions = (finding.contradictionIds || []).filter(id => sources.some(s => s.id === id));
  if (contradictions.length >= 2) notes.push('Contradiction signalée entre sources — à vérifier');
  return { label: type[finding.evidenceType] || 'Qualification non fournie', notes, contradictions };
}
export function drawValidation(host, turn) {
  const warnings = turn.presentation?.warnings || [];
  if (!turn.presentation) { host.append(el('p', 'muted', 'Validation côté serveur non disponible pour cette réponse.')); return; }
  host.append(el('p', 'muted', turn.presentation.text ? 'Structure contrôlée côté serveur ; conclusions et exactitude des mesures à vérifier.' : 'Réponse affichée sans validation structurelle.'));
  if (warnings.length) {
    const details = el('details', 'result-validation'); details.append(el('summary', null, `${warnings.length} limite(s) de restitution signalée(s)`));
    const list = el('ul'); warnings.forEach(w => list.append(el('li', null, w))); details.append(list); host.append(details);
  }
}
export function drawConsultations(host, turn, recover) {
  const rows = turn.presentation?.consultations || [];
  if (!rows.length) return;
  const section = el('section', 'result-consultations'); section.append(el('h3', null, 'État des consultations'));
  rows.forEach(row => {
    const item = el('article', row.status === 'ERROR' ? 'consultation-error' : 'consultation-complete');
    item.append(el('strong', null, row.tool), el('p', null, row.status === 'ERROR' ? 'Consultation en échec — ses informations peuvent manquer au résultat.' : 'Appel terminé — cela ne confirme pas la conclusion.'));
    if (row.status === 'ERROR') {
      if (row.recoverable && recover) {
        const b = el('button', null, 'Reprendre uniquement cette lecture'); b.type = 'button'; b.addEventListener('click', () => recover(row.tool)); item.append(b);
      } else item.append(el('p', 'muted', 'Reprise automatique indisponible. Vérifiez le service ou préparez une nouvelle demande ; aucune action n’est rejouée.'));
    }
    section.append(item);
  });
  host.append(section);
}
