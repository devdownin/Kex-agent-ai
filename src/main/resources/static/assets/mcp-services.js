// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

export function parameterRows(schema = {}) {
  const required = new Set(schema?.required || []);
  return Object.entries(schema?.properties || {}).map(([name, field]) => ({
    name, required: required.has(name),
    type: Array.isArray(field?.type) ? field.type.join(' / ') : field?.type || 'Non précisé',
    description: field?.description || 'Description non fournie',
    choices: Array.isArray(field?.enum) ? JSON.stringify(field.enum) : null,
  }));
}

// Un exemple à adapter, jamais une promesse de validité pour les schémas complexes ($ref, oneOf…).
export function exampleArguments(schema = {}, depth = 0) {
  if (!schema || typeof schema !== 'object' || depth > 6) return '<à renseigner>';
  for (const key of ['example', 'default', 'const']) if (key in schema) return schema[key];
  if (schema.examples?.length) return schema.examples[0];
  if (schema.enum?.length) return schema.enum[0];
  if (schema.$ref || schema.oneOf || schema.anyOf || schema.allOf) return '<à renseigner selon le contrat>';
  const type = schema.type || (schema.properties ? 'object' : null);
  if (type === 'object') return Object.fromEntries(Object.entries(schema.properties || {})
    .filter(([key]) => !schema.required || schema.required.includes(key))
    .map(([key, value]) => [key, exampleArguments(value, depth + 1)]));
  if (type === 'array') return [exampleArguments(schema.items, depth + 1)];
  if (type === 'boolean') return false;
  if (type === 'number' || type === 'integer') return schema.minimum ?? 0;
  return '<à renseigner>';
}

export function toolEffects(tool) {
  const hints = tool.annotations || {};
  const effects = [];
  if (tool.readOnlyByPolicy) effects.push('Lecture seule selon la politique Kex');
  if (hints.readOnlyHint === true) effects.push('Lecture seule déclarée par le serveur');
  if (hints.readOnlyHint === false) effects.push('Écriture possible déclarée par le serveur');
  if (hints.destructiveHint === true) effects.push('Effets destructifs possibles déclarés (modification ou suppression)');
  if (hints.destructiveHint === false) effects.push('Absence d’effet destructif déclarée par le serveur');
  if (!effects.length) effects.push('Effets non précisés');
  return effects;
}

export function discoveryState(server, phase) {
  if (phase === 'loading') return { state: 'PENDING', label: 'Récupération des informations…' };
  if (phase === 'error') return { state: 'ERROR', label: 'Échec de connexion' };
  if (phase === 'ready' || server.initialized) {
    if (!server.tools?.length) return { state: 'WARNING', label: server.reportedToolCount > 0
      ? 'Aucun service autorisé' : 'Aucun service exposé' };
    return { state: 'OK', label: 'Informations récupérées' };
  }
  return { state: 'UNKNOWN', label: 'Informations non récupérées' };
}

export function servicePrompt(server, tool) {
  return [
    `Je souhaite utiliser le service « ${tool.name} » de la connexion MCP « ${server.connection} ».`,
    `Objectif déclaré : ${tool.description || 'À préciser avec moi.'}`,
    'Mon besoin : [à compléter].',
    `Paramètres proposés, à adapter avant envoi :\n${JSON.stringify(exampleArguments(tool.inputSchema), null, 2)}`,
    'Demande-moi les précisions nécessaires. Respecte les permissions et les validations Kex avant toute action.',
  ].join('\n\n');
}
