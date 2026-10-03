// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Le parcours collecte des faits déclarés par l'opérateur. Les suggestions MCP restent des choix,
// jamais une preuve que le relevé est exhaustif ni une raison d'inventer une étape manquante.
import { $, api, el, onCredentialChange, toast } from './core.js';

const BASE = '/api/agent/supervision/processes';
const STEPS = [
  { key: 'name', question: 'Quel est le nom du processus ?', placeholder: 'Intégration des commandes', required: true },
  { key: 'description', question: 'Quel est son objectif métier ?', placeholder: 'Acheminer les commandes vers l’ERP' },
  { key: 'source', question: 'Quel topic marque son entrée ?', placeholder: 'orders.received', required: true, options: 'topics' },
  { key: 'destination', question: 'Quel topic marque sa sortie ?', placeholder: 'orders.validated (facultatif)', options: 'topics' },
  { key: 'group', question: 'Quel consumer group le traite ?', placeholder: 'orders-worker (facultatif)', options: 'groups' },
  { key: 'stages', question: 'Y a-t-il d’autres topics à suivre ?', placeholder: 'orders.checked, orders.enriched (facultatif)' },
  { key: 'forecast', question: 'Associer une prévision TimesFM à ce processus ?', options: 'forecasts' },
];

let step = 0;
let draft = {};
let topics = [];
let topicsNote = '';
let groups = [];
let groupsNote = '';
let admin = false;
let existingIds = new Set();
let generation = 0;
let saving = false;
let forecasts = [];
let forecastsNote = '';

onCredentialChange(() => {
  ++generation; draft = {}; topics = []; groups = []; forecasts = []; admin = false;
  $('#process-wizard').hidden = true;
  $('#process-wizard-question').replaceChildren();
  $('#process-wizard-draft').replaceChildren();
  $('#process-wizard-note').textContent = '';
  $('#process-wizard-config').hidden = true;
  $('#process-wizard-config-yaml').textContent = '';
});

const chosenForecast = () => forecasts.find((metric) => metric.seriesId === draft.forecast);

const slug = (name) => name.normalize('NFD').replace(/[\u0300-\u036f]/g, '')
  .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 64);

function topicNames() {
  return [...new Set([draft.source, ...((draft.stages || '').split(',').map((part) => part.trim())),
    draft.destination].filter(Boolean))];
}

function hint() {
  const topicsList = topicNames();
  const metric = chosenForecast();
  return `topics [${topicsList.join(', ')}]${draft.group ? `, consumer group ${draft.group}` : ''}`
    + (metric ? `, prévision TimesFM série ${JSON.stringify(metric.seriesId)}, environnement ${JSON.stringify(metric.environment)}` : '');
}

function value() {
  return {
    id: draft.id || slug(draft.name || ''), name: draft.name || '',
    description: draft.description || '', hint: draft.hint || hint(),
  };
}

function yaml(process) {
  const metric = chosenForecast();
  return `kex:\n  agent:\n    supervision:\n      processes:\n        - id: ${JSON.stringify(process.id)}\n`
    + `          name: ${JSON.stringify(process.name)}\n`
    + `          description: ${JSON.stringify(process.description)}\n`
    + `          hint: ${JSON.stringify(process.hint)}\n`
    + (metric ? `    forecasts:\n      process-links:\n        - series-id: ${JSON.stringify(metric.seriesId)}\n`
      + `          environment: ${JSON.stringify(metric.environment)}\n          process-ids: [${JSON.stringify(process.id)}]\n` : '');
}

async function loadForecasts(token) {
  forecastsNote = 'Lecture du catalogue TimesFM autorisé…';
  try {
    const read = await api('/api/agent/forecasts/metrics');
    if (token !== generation) return;
    if (read.unavailable || read.truncated || read.coverage?.complete !== true || !Array.isArray(read.data)) {
      forecasts = []; forecastsNote = 'Catalogue absent ou incomplet : continuez sans TimesFM, puis vérifiez la connexion MCP et ses permissions.';
    } else {
      forecasts = read.data.filter((m) => typeof m?.seriesId === 'string' && m.seriesId.length > 0 && typeof m.environment === 'string' && m.environment.length > 0 && typeof m.metricId === 'string');
      forecastsNote = forecasts.length ? 'Choix facultatif et explicite. Le hint guidera les lectures ; le lien visible exige d’appliquer la configuration proposée. Aucun calcul ni activation n’est lancé.'
        : 'Aucune série autorisée. Enrôlez les séries dans KafkaExplorer et autorisez les cinq outils MCP, puis revenez ici.';
    }
  } catch {
    if (token !== generation) return;
    forecasts = []; forecastsNote = 'Lecture TimesFM indisponible. La création du processus reste possible sans prévision.';
  }
  if (step === 6) render();
}

async function loadTopics(token) {
  try {
    const data = await api('/api/agent/kafka/topics');
    if (token !== generation) return;
    topics = (data.topics || []).map((topic) => topic.name).filter(Boolean);
    topicsNote = data.unavailable || (data.truncated || data.coverage?.complete === false
      ? 'Liste Kafka partielle : vous pouvez aussi saisir un topic manuellement.' : '');
  } catch (error) {
    if (token !== generation) return;
    topicsNote = `Topics indisponibles : ${error.message}. La saisie manuelle reste possible.`;
  }
  if (step === 2 || step === 3) {
    draft[STEPS[step].key] = $('#process-wizard-answer').value;
    render();
  }
}

async function loadGroups(token) {
  if (!draft.source) return;
  try {
    const data = await api(`/api/agent/kafka/topics/${encodeURIComponent(draft.source)}/lag`);
    if (token !== generation) return;
    groups = [...new Set((data.groups || []).map((group) => group.groupId).filter(Boolean))];
    groupsNote = data.unavailable || (data.truncated || data.coverage?.complete === false
      ? 'Liste de groupes partielle : la saisie manuelle reste possible.' : '');
  } catch (error) {
    if (token !== generation) return;
    groupsNote = `Groupes indisponibles : ${error.message}. La saisie manuelle reste possible.`;
  }
  if (step === 4) {
    draft.group = $('#process-wizard-answer').value;
    render();
  }
}

function renderQuestion() {
  const item = STEPS[step];
  if (item.options === 'forecasts') {
    const select = el('select'); select.id = 'process-wizard-answer';
    const none = el('option', null, 'Continuer sans prévision'); none.value = ''; select.append(none);
    for (const metric of forecasts) {
      const option = el('option', null, `${metric.environment} · ${metric.metricId} · ${metric.seriesId}`);
      option.value = metric.seriesId; select.append(option);
    }
    select.value = draft.forecast || '';
    select.addEventListener('change', () => { draft.forecast = select.value; draft.hint = ''; renderDraft(); });
    const label = el('label', null, item.question); label.htmlFor = select.id;
    const guide = el('a', 'ghost', 'Consulter les prévisions et leur qualité'); guide.href = '#/forecasts';
    $('#process-wizard-question').replaceChildren(label, select, guide);
    $('#process-wizard-note').textContent = forecastsNote;
    $('#process-wizard-next').textContent = 'Suivant'; select.focus(); return;
  }
  const field = el('input');
  field.type = 'text';
  field.id = 'process-wizard-answer';
  field.value = draft[item.key] || '';
  field.placeholder = item.placeholder;
  field.maxLength = item.key === 'name' ? 160 : item.key === 'description' ? 1000 : 400;
  field.required = Boolean(item.required);
  const label = el('label', null, item.question);
  label.htmlFor = field.id;
  const container = $('#process-wizard-question');
  container.replaceChildren(label, field);
  if (item.options) {
    const list = el('datalist');
    list.id = 'process-wizard-options';
    (item.options === 'topics' ? topics : groups).forEach((entry) => {
      const option = el('option');
      option.value = entry;
      list.append(option);
    });
    field.setAttribute('list', list.id);
    container.append(list);
  }
  $('#process-wizard-note').textContent = item.options === 'topics' ? topicsNote
    : item.options === 'groups' ? groupsNote : item.required ? 'Ce champ est nécessaire.' : 'Facultatif : passez à la suite si vous ne le connaissez pas.';
  $('#process-wizard-next').textContent = 'Suivant';
  field.focus();
}

function renderPreview() {
  const container = $('#process-wizard-question');
  const title = el('h3', null, 'Vérifiez la déclaration avant de créer le processus');
  const summary = el('p', 'hint', 'Topics retenus : ' + topicNames().join(' → ')
    + (draft.group ? ` · Groupe : ${draft.group}` : ' · Groupe non renseigné'));
  const idLabel = el('label', null, 'Identifiant unique');
  const id = el('input');
  id.id = 'process-wizard-id';
  id.required = true;
  id.maxLength = 64;
  id.pattern = '[a-z0-9](?:[a-z0-9]|-)*';
  id.value = value().id;
  idLabel.htmlFor = id.id;
  const hintLabel = el('label', null, 'Preuves à consulter (hint)');
  const hints = el('textarea');
  hints.id = 'process-wizard-hint';
  hints.required = true;
  hints.maxLength = 2000;
  hints.rows = 2;
  hints.value = value().hint;
  hintLabel.htmlFor = hints.id;
  const preview = el('pre', 'dump');
  const warnings = el('p', 'hint');
  const forecastNote = el('p', 'hint', chosenForecast()
    ? 'TimesFM : le hint est enregistré avec le processus. Appliquez séparément le bloc forecasts.process-links pour afficher l’association dans Prévisions. La série reste administrée dans KafkaExplorer.'
    : 'Aucune prévision associée. Vous pourrez ajouter une association par série et environnement ultérieurement.');
  const refresh = () => {
    draft.id = id.value.trim();
    draft.hint = hints.value.trim();
    preview.textContent = yaml(value());
    const missing = [];
    if (!draft.description) missing.push('objectif non renseigné');
    if (!draft.destination) missing.push('topic de sortie non renseigné');
    if (!draft.group) missing.push('consumer group non renseigné');
    if (existingIds.has(value().id)) missing.push('identifiant déjà utilisé : choisissez-en un autre');
    warnings.textContent = missing.length ? `À vérifier : ${missing.join(' · ')}.` : 'Informations complètes.';
    $('#process-wizard-next').disabled = !admin || saving || existingIds.has(value().id);
  };
  id.addEventListener('input', refresh);
  hints.addEventListener('input', refresh);
  container.replaceChildren(title, summary, idLabel, id, hintLabel, hints, preview, warnings, forecastNote);
  refresh();
  $('#process-wizard-note').textContent = admin
    ? 'Confirmez pour enregistrer. Le processus sera visible immédiatement, puis mesuré au prochain cycle.'
    : 'L’aperçu est disponible ; l’enregistrement exige le rôle ADMIN.';
  $('#process-wizard-next').textContent = 'Créer ce processus';
  id.focus();
}

function renderDraft() {
  const host = $('#process-wizard-draft');
  const known = [
    ['Nom', draft.name], ['Objectif', draft.description], ['Entrée', draft.source],
    ['Sortie', draft.destination], ['Consumer group', draft.group], ['Topics supplémentaires', draft.stages],
    ['Prévision TimesFM', chosenForecast() ? `${chosenForecast().environment} · ${chosenForecast().metricId}` : ''],
  ].filter(([, text]) => Boolean(text));
  host.replaceChildren(el('strong', null, 'Déclaration en préparation'));
  if (!known.length) {
    host.append(el('p', 'hint', 'Les informations renseignées apparaîtront ici au fil des étapes.'));
    return;
  }
  const list = el('dl');
  known.forEach(([label, text]) => list.append(el('dt', null, label), el('dd', null, text)));
  host.append(list);
}

function render() {
  if ($('#process-wizard').hidden) return;
  $('#process-wizard-progress').textContent = step < STEPS.length
    ? `Question ${step + 1} sur ${STEPS.length}` : 'Vérification finale';
  $('#process-wizard-bar').value = step + 1;
  renderDraft();
  $('#process-wizard-back').disabled = step === 0 || saving;
  $('#process-wizard-next').disabled = saving;
  if (step === STEPS.length) renderPreview();
  else renderQuestion();
}

async function open() {
  if (saving) return;
  $('#process-wizard-config').hidden = true;
  $('#process-wizard-config-yaml').textContent = '';
  draft = {};
  step = 0;
  topics = [];
  groups = [];
  topicsNote = '';
  groupsNote = '';
  forecasts = []; forecastsNote = '';
  admin = false;
  existingIds = new Set();
  const token = ++generation;
  $('#process-wizard').hidden = false;
  render();
  // Les suggestions ne doivent jamais empêcher la saisie manuelle si le MCP est absent.
  loadTopics(token);
  api(`${BASE}/definitions`).then((definitions) => {
    if (token !== generation) return;
    existingIds = new Set(definitions.map((process) => process.id));
    if (step === STEPS.length) render();
  }).catch(() => { /* le serveur valide aussi l'unicité à l'enregistrement */ });
  try {
    const user = await api('/api/agent/whoami');
    if (token === generation) {
      admin = user.roles?.includes('ADMIN') || false;
      if (step === STEPS.length) render();
    }
  } catch {
    if (token === generation) admin = false;
  }
}

export function wireProcessWizard() {
  $('#start-process-wizard').addEventListener('click', open);
  $('#process-wizard-close').addEventListener('click', () => {
    ++generation;
    $('#process-wizard').hidden = true;
  });
  $('#process-wizard-back').addEventListener('click', () => {
    if (step < STEPS.length) draft[STEPS[step].key] = $('#process-wizard-answer').value.trim();
    step = Math.max(0, step - 1);
    render();
  });
  $('#process-wizard-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (step < STEPS.length) {
      const item = STEPS[step];
      draft[item.key] = $('#process-wizard-answer').value.trim();
      if (item.key === 'name') draft.id = '';
      if (['source', 'destination', 'group', 'stages'].includes(item.key)) draft.hint = '';
      if (item.key === 'forecast') draft.hint = '';
      step++;
      if (step === 4) loadGroups(generation);
      if (step === 6) loadForecasts(generation);
      render();
      return;
    }
    if (!admin || saving) return;
    saving = true;
    const token = generation;
    const proposedYaml = yaml(value());
    $('#process-wizard-next').disabled = true;
    try {
      const process = await api(BASE, { method: 'POST', body: value() });
      if (token !== generation) return;
      ++generation;
      $('#process-wizard').hidden = true;
      $('#process-wizard-config-yaml').textContent = proposedYaml;
      $('#process-wizard-config').hidden = false;
      toast(`Processus « ${process.name} » créé. Il sera mesuré au prochain cycle.`, 'success', {
        label: 'Voir le processus',
        run: () => { location.hash = `#/processes?processus=${encodeURIComponent(process.id)}`; },
      });
    } catch (error) {
      if (token !== generation) return;
      $('#process-wizard-note').textContent = `Création impossible : ${error.message}. Corrigez puis réessayez.`;
    } finally {
      saving = false;
      if (!$('#process-wizard').hidden) $('#process-wizard-next').disabled = false;
    }
  });
}
