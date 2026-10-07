// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const experience = await readFile('src/main/resources/static/assets/user-experience.js', 'utf8');
const source = (await readFile('src/main/resources/static/assets/user-presentation.js', 'utf8')).replace(/^import .*;$/gm, '');
const { presentedResponse, findingQuality } = await import('data:text/javascript;base64,' + Buffer.from(experience + '\n' + source).toString('base64'));
const raw = JSON.stringify({kind:'result',observations:'Fait',uncertainties:'Limite',nextAction:'Vérifier'});
assert.equal(presentedResponse({completed:true,text:raw,presentation:{text:null}}),null,'Ne pas afficher des champs rejetés comme un résultat validé');
assert.equal(presentedResponse({completed:false,text:raw}),null);
assert.equal(presentedResponse({completed:true,text:raw,presentation:{text:raw}}).observations,'Fait');
const sources=[{id:'s1',observedAt:'2026-09-01T00:00:00Z',validUntil:'2026-10-01T00:00:00Z'},{id:'s2',observedAt:null}];
const quality=findingQuality({sourceIds:['s1','s2'],evidenceType:'INFERENCE',contradictionIds:['s1','s2','invented']},sources,Date.parse('2026-10-05'));
assert.equal(quality.label,'Conclusion déduite'); assert.equal(quality.notes.length,4); assert.deepEqual(quality.contradictions,['s1','s2']);
assert.equal(findingQuality({sourceIds:[],evidenceType:'HYPOTHESIS'},sources).label,'Hypothèse');
console.log('✓ résultats normalisés, réponse rejetée, preuves anciennes/expirées, dates absentes et contradictions déclarées');

assert.equal(findingQuality({contradictionIds:['s1','s1']},sources).notes.length,0,'Une seule source répétée ne constitue pas une contradiction');
