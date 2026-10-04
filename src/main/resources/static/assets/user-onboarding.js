// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import { $, el } from './core.js';
import { skillCategory } from './user-context.js';

export function userOnboarding({ prepare }) {
  let identity = null;
  const key = () => `kex.agent.user.onboarding.v1.${encodeURIComponent(identity)}`;
  function reset(nextIdentity = null, roles = []) {
    identity = nextIdentity;
    $('#onboarding-examples').replaceChildren();
    $('#onboarding-note').textContent = identity ? 'Chargement des exemples disponibles…' : 'Connectez-vous pour découvrir des exemples adaptés à votre équipe.';
    $('#onboarding-approval').textContent = roles.some(r => ['OPERATOR', 'ADMIN'].includes(r))
      ? 'Si vous préparez un plan, examinez ses étapes, ses cibles et ses paramètres. Approuver autorise ce plan ; le lancer reste une décision séparée.'
      : 'Si une action nécessite un plan à approuver, une personne autorisée doit examiner ses étapes et ses paramètres. Une réponse de Kex ne vaut pas approbation.';
    let completed = false;
    if (identity) { try { completed = localStorage.getItem(key()) === 'done'; } catch { /* Le guide reste accessible sans stockage. */ } }
    $('#onboarding').hidden = completed;
  }
  function examples(skills, starters, unavailable = false) {
    const host = $('#onboarding-examples'); host.replaceChildren();
    if (!identity) return;
    const selected = []; const ids = new Set(); const categories = new Set();
    // Diversifier les besoins sans inventer de compétence ni modifier le classement serveur.
    for (const skill of skills) {
      const category = skillCategory(skill);
      if (selected.length < 3 && !ids.has(skill.id) && !categories.has(category)) { selected.push(skill); ids.add(skill.id); categories.add(category); }
    }
    for (const skill of skills) if (selected.length < 3 && !ids.has(skill.id)) { selected.push(skill); ids.add(skill.id); }
    for (const starter of starters) if (selected.length < 3) selected.push(starter);
    $('#onboarding-note').textContent = unavailable ? 'Le catalogue est indisponible. Ces exemples de demandes guidées restent utilisables.'
      : skills.length ? 'Exemples choisis parmi les compétences de votre équipe, complétés si nécessaire par des demandes guidées.'
        : 'Aucune compétence approuvée disponible. Ces exemples préparent des demandes guidées.';
    selected.forEach(action => {
      const card = el('article', 'onboarding-example');
      card.append(el('strong', null, action.title), el('p', 'muted', action.id ? 'Compétence de votre équipe : précisez votre contexte dans le formulaire.' : action.description));
      const link = el('a', null, 'Préparer cet exemple'); link.href = '#/new';
      link.addEventListener('click', event => { event.preventDefault(); prepare({ type: 'action', title: action.title, skillId: action.id || null }); });
      card.append(link); host.append(card);
    });
  }
  $('#open-onboarding').addEventListener('click', () => { $('#onboarding').hidden = false; $('#onboarding-title').focus(); });
  $('#dismiss-onboarding').addEventListener('click', () => {
    if (identity) { try { localStorage.setItem(key(), 'done'); } catch { $('#notice').textContent = 'Le guide est masqué pour cette visite ; votre préférence n’a pas pu être enregistrée.'; } }
    $('#onboarding').hidden = true; $('#open-onboarding').focus();
  });
  return { reset, examples };
}
