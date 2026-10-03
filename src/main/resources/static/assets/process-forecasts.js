// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

import { api, el, onCredentialChange, stamp } from './core.js';

const BASE = '/api/agent/forecasts';
let generation = 0;
const hosts = new Set();
onCredentialChange(() => {
  generation++;
  for (const host of hosts) host.replaceChildren();
  hosts.clear();
});
const complete = (read) => read && !read.unavailable && !read.truncated && read.coverage?.complete === true;
const measured = (read) => complete(read) && read.data?.measured === true ? read.data.value : null;
const date = (value) => Number.isFinite(value) ? stamp(value) : 'Non communiquée';
const link = (id, text) => {
  const node = el('a', 'ghost', text); node.href = `#/forecasts?series=${encodeURIComponent(id)}`; return node;
};

export function readinessPanel(status) {
  const panel = el('section', 'forecast-readiness');
  panel.append(el('h3', null, 'Prérequis TimesFM'));
  if (!status) panel.append(el('p', 'hint', 'Diagnostic indisponible : vérifiez la connexion MCP.'));
  else {
    const checks = el('ul');
    checks.append(el('li', null, `Connexion ${status.connection || 'non configurée'} : ${status.connected ? 'connectée' : 'indisponible'}`),
      el('li', null, status.missingTools?.length ? `Outils manquants : ${status.missingTools.join(', ')}` : 'Cinq outils disponibles'),
      el('li', null, `Catalogue : ${status.catalogComplete ? 'complet' : 'incomplet ou indisponible'}`),
      el('li', null, `Séries autorisées : ${status.catalogComplete ? status.seriesCount : 'non vérifiées'}`));
    panel.append(checks);
    if (!status.seriesCount && status.catalogComplete) panel.append(el('p', 'hint', 'Enrôlez une série dans KafkaExplorer et vérifiez les permissions MCP.'));
  }
  const configure = el('a', 'ghost', 'Vérifier la connexion et les outils MCP'); configure.href = '#/integrations';
  const explorer = el('a', 'ghost', 'Consulter les prévisions'); explorer.href = '#/forecasts';
  panel.append(configure, explorer); return panel;
}

export async function processForecasts(processId, host) {
  for (const previous of hosts) if (!previous.isConnected) hosts.delete(previous);
  hosts.add(host);
  const ticket = generation;
  const current = () => ticket === generation && host.isConnected;
  host.replaceChildren(el('h3', 'drawer-sub', 'Prévisions associées'), el('p', 'hint', 'Lecture TimesFM…'));
  try {
    const [summary, user] = await Promise.all([
      api(`${BASE}/processes/${encodeURIComponent(processId)}`),
      api('/api/agent/whoami').catch(() => ({ roles: [] })),
    ]);
    if (!current()) return;
    host.replaceChildren(el('h3', 'drawer-sub', 'Prévisions associées'),
      el('p', 'hint', `Lecture du ${date(summary.readAt)}. Ces résultats futurs ne remplacent pas les mesures actuelles du processus.`));
    if (summary.unavailable) host.append(el('p', 'banner', summary.unavailable));
    else if (!summary.forecasts?.length) host.append(el('p', 'hint', 'Aucune prévision associée à ce processus.'));
    if (summary.truncated) host.append(el('p', 'banner', 'Aperçu limité à quatre séries. Consultez les associations pour voir la liste complète.'));
    for (const row of (summary.forecasts || []).slice(0, 4)) {
      const card = el('article', 'forecast-risk-card');
      const association = row.association;
      card.append(el('h4', null, `${association.environment} · ${association.seriesId}`));
      const record = measured(row.detail?.forecast);
      if (!record) card.append(el('p', 'hint', row.detail?.forecast?.unavailable || 'Prévision non mesurée ou incomplète.'));
      else {
        const points = record.forecast?.points || [];
        const end = points.at(-1)?.at;
        const expired = Number.isFinite(end) && end <= Date.now();
        card.append(el('p', null, `État : ${expired ? 'STALE — prévision expirée' : record.state} · Mode : ${record.visibility} · Stratégie : ${record.strategy}`),
          el('p', 'hint', `Calculée le ${date(record.generatedAt)} · Échéance : ${date(end)}`));
        if (record.visibility === 'SHADOW') card.append(el('p', 'hint', 'SHADOW : observation, sans alerte ni action depuis cette vue.'));
        const coherent = !expired && record.state === 'READY' && record.strategy === 'TIMESFM'
          && Number.isFinite(record.generatedAt) && typeof record.context?.inputFingerprint === 'string'
          && record.context.inputFingerprint.length > 0 && typeof record.context?.profileFingerprint === 'string'
          && record.context.profileFingerprint.length > 0;
        const risks = coherent && complete(summary.breaches) && Array.isArray(summary.breaches.data) ? summary.breaches.data.filter((risk) =>
          risk.threshold?.seriesId === association.seriesId && risk.windowEndAt > Date.now()
          && risk.generatedAt === record.generatedAt && risk.inputFingerprint === record.context?.inputFingerprint
          && risk.profileFingerprint === record.context?.profileFingerprint) : [];
        if (!complete(summary.breaches)) card.append(el('p', 'banner', 'Risques : lecture indisponible ou incomplète.'));
        else if (!risks.length) card.append(el('p', 'hint', 'Aucun dépassement futur correspondant à cette génération. Cela ne prouve pas l’absence de risque.'));
        for (const risk of risks.slice(0, 3)) card.append(el('p', null,
          `Dépassement prédit : seuil ${risk.threshold.threshold} (${risk.threshold.direction}) · fenêtre jusqu’au ${date(risk.windowEndAt)} · mode ${risk.threshold.visibility}`));
      }
      const quality = measured(row.detail?.quality);
      card.append(el('p', 'hint', quality
        ? `Qualité réalisée jusqu’au ${date(quality.evaluatedThrough)} : ${quality.evaluatedPoints} points · MAE ${Number.isFinite(quality.timesfmMetrics?.mae) ? quality.timesfmMetrics.mae : 'non mesurée'}. Elle ne garantit pas le résultat futur.`
        : 'Qualité réalisée non mesurée ou indisponible.'), link(association.seriesId, 'Examiner la prévision et sa qualité'));
      host.append(card);
    }
    if (user.roles?.includes('ADMIN')) {
      const edit = el('button', 'ghost', 'Associer une prévision'); edit.type = 'button';
      const editor = el('div'); host.append(edit, editor);
      edit.addEventListener('click', () => { edit.disabled = true; associationEditor(processId, editor, host, ticket); });
    } else host.append(el('p', 'hint', 'La modification des associations nécessite le rôle ADMIN.'));
  } catch (error) {
    if (current()) host.replaceChildren(el('h3', 'drawer-sub', 'Prévisions associées'), el('p', 'banner', `Lecture impossible : ${error.message}`));
  }
}

async function associationEditor(id, editor, host, ticket) {
  const current = () => ticket === generation && host.isConnected;
  editor.replaceChildren(el('p', 'hint', 'Chargement des associations et du catalogue autorisé…'));
  try {
    const [saved, catalog, status] = await Promise.all([
      api(`${BASE}/processes/${encodeURIComponent(id)}/associations`), api(`${BASE}/metrics`),
      api(`${BASE}/readiness`).catch(() => null),
    ]);
    if (!current()) return;
    editor.replaceChildren(readinessPanel(status), el('p', 'hint', 'Les changements sont enregistrés immédiatement pour ce processus, sans redémarrage ni activation de série.'));
    const note = el('p', 'hint'); note.setAttribute('role', 'status');
    const controls = [];
    const save = async (associations) => {
      controls.forEach((control) => { control.disabled = true; });
      try {
        await api(`${BASE}/processes/${encodeURIComponent(id)}/associations`, { method: 'PUT', body: { associations } });
        if (current()) await processForecasts(id, host);
      } catch (error) {
        if (current()) { note.textContent = `Association non enregistrée : ${error.message}`; controls.forEach((control) => { control.disabled = false; }); }
      }
    };
    for (const association of saved.associations || []) {
      const row = el('p', null, `${association.environment} · ${association.seriesId} `);
      const remove = el('button', 'ghost', 'Retirer'); remove.type = 'button'; controls.push(remove);
      remove.addEventListener('click', () => save(saved.associations.filter((entry) => entry !== association)));
      remove.disabled = Boolean(saved.unavailable) || saved.associations.length > 5;
      row.append(remove); editor.append(row);
    }
    if (saved.unavailable || saved.associations.length > 4) {
      editor.append(el('p', 'banner', saved.unavailable
        ? `${saved.unavailable}. Réparez le périmètre avant de modifier la liste, ou retirez explicitement toutes les associations.`
        : 'Plus de quatre liens hérités du YAML : retirez toutes les associations pour choisir une nouvelle liste de quatre séries au maximum.'));
      const clear = el('button', 'ghost', 'Retirer toutes les associations'); clear.type = 'button'; controls.push(clear);
      clear.addEventListener('click', () => save([])); editor.append(clear);
    } else if (complete(catalog) && Array.isArray(catalog.data) && saved.associations.length < 4) {
      const select = el('select'); select.setAttribute('aria-label', 'Série TimesFM à associer');
      const placeholder = el('option', null, 'Choisir une série autorisée'); placeholder.value = ''; select.append(placeholder);
      const candidates = catalog.data.filter((metric) => !saved.associations.some((entry) => entry.seriesId === metric.seriesId && entry.environment === metric.environment));
      candidates.forEach((metric, index) => { const option = el('option', null, `${metric.environment} · ${metric.metricId} · ${metric.seriesId}`); option.value = String(index); select.append(option); });
      const add = el('button', 'primary', 'Enregistrer l’association'); add.type = 'button'; add.disabled = true;
      select.addEventListener('change', () => { add.disabled = select.value === ''; });
      add.addEventListener('click', () => {
        const metric = candidates[Number(select.value)];
        if (metric && select.value !== '') save([...saved.associations, { seriesId: metric.seriesId, environment: metric.environment }]);
      });
      controls.push(select, add); editor.append(select, add);
    } else editor.append(el('p', 'hint', saved.associations.length >= 4 ? 'Maximum quatre associations depuis la console.' : 'Catalogue indisponible ou incomplet : aucun choix de série proposé.'));
    editor.append(note);
  } catch (error) {
    if (current()) editor.replaceChildren(el('p', 'banner', `Chargement impossible : ${error.message}`));
  }
}
