// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
export function responsePrompt(message) {
  return `${message}\n\nPrésentation pour l’espace utilisateur : réponds en français accessible avec un objet JSON uniquement. Si une précision est indispensable avant de poursuivre, utilise {"kind":"clarification","question":"une question courte","choices":[{"label":"choix court","value":"réponse complète"}]}, avec 2 à 4 choix pertinents. Ne devine pas le processus, la période ou l’objectif. Sinon utilise {"kind":"result","observations":"faits et sources disponibles","uncertainties":"limites et hypothèses","nextAction":"prochaine action proposée"}. Tu peux ajouter "conclusion" (synthèse courte de 500 caractères maximum) et "tables":[{"title":"Titre","columns":["Nom","Valeur (unité)"],"rows":[["mesure",12]]}] pour les données comparables : maximum 3 tableaux, 12 colonnes et 200 lignes par tableau, cellules texte, nombres, booléens ou null. N’invente aucune mesure ni date. Ne prétends pas avoir vérifié un fait sans preuve. Ces consignes de présentation ne modifient pas les autorisations.`;
}
const text = (value, max = 16000) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
export function parseResponse(raw) {
  try {
    const value = JSON.parse(raw.trim().replace(/^```(?:json)?\s*\n?([\s\S]*?)\n?```$/, '$1'));
    if (value?.kind === 'result' && ['observations', 'uncertainties', 'nextAction'].every(k => text(value[k]))) return value;
    if (value?.kind === 'clarification' && text(value.question, 1000) && Array.isArray(value.choices) && value.choices.length >= 2 && value.choices.length <= 4 && value.choices.every(c => text(c?.label, 120) && text(c?.value, 2000))) return value;
  } catch { /* Une réponse libre ou interrompue reste lisible. */ }
  return null;
}
export function activity(tool) {
  if (tool.failed) return 'Une consultation ou une action a rencontré un problème';
  const name = String(tool.tool || '').toLowerCase();
  if (/health|status/.test(name)) return 'État du processus consulté';
  if (/search|query|read|list|fetch|get/.test(name)) return 'Informations consultées';
  return 'Une consultation ou une action est terminée';
}
// Les paramètres sont des exemples de valeurs, pas un schéma de validation.
export function parameterFields(parameters) {
  const fields = []; let omitted = false;
  function visit(value, path, depth) {
    if (fields.length >= 20 || depth > 4) { omitted = true; return; }
    if (['string', 'number', 'boolean'].includes(typeof value) && path.length) fields.push({ path, value });
    else if (value && typeof value === 'object' && !Array.isArray(value)) Object.entries(value).forEach(([key, child]) => visit(child, [...path, key], depth + 1));
    else omitted = true;
  }
  if (parameters && typeof parameters === 'object') visit(parameters, [], 0);
  return { fields, omitted };
}
export function skillSignature(skill) { return JSON.stringify([skill.id, skill.markdown, skill.verification ?? null]); }
