# Audit et application de Redesign.md

La console conserve HTML, CSS et JavaScript natifs. Cette passe concerne les primitives visuelles partagées ; les routes, identifiants DOM, API et actions métier restent inchangés.

## Diagnostic

| Point observé | Traitement |
| --- | --- |
| Police système sans identité ; hiérarchie limitée | Outfit variable locale, graisses 100–900, police de secours et `font-display: swap` |
| Accent indigo décoratif concurrent du teal | Les tokens agent réutilisent l’accent teal ; les couleurs sémantiques des alertes et décisions sont conservées |
| Bandeau bleu sombre et navigation sombre dans le thème clair | Surfaces et textes suivent les tokens du thème, y compris l’indicateur de santé |
| Libellés KPI et groupes de navigation en capitales | Casse naturelle, graisse moyenne, contraste renforcé des libellés KPI |
| Contenu étiré sur les écrans larges | Vues plafonnées à 1440 px ; espacement vertical de 20 px |
| Commandes et panneaux avec des formes peu différenciées | Rayon de 10 px pour les commandes, panneaux de 16 px |
| Boutons sans retour d’appui | Translation de 1 px, transitions de 200 ms, désactivation avec mouvement réduit |
| Texte introductif très large | Largeur maximale de 65 caractères et retours équilibrés du titre |

## Éléments déjà présents

La console dispose de skeletons, de messages d’erreur, d’états vides, d’un lien d’évitement, de focus visibles, d’une navigation repliable, d’un menu de commandes et de pictogrammes SVG homogènes. Ils sont conservés. Le shell garde sa hauteur bornée à `100dvh` afin que les longues listes défilent sans emporter la navigation.

Les couleurs d’erreur, d’avertissement et d’approbation ont un sens opérationnel : elles ne sont pas supprimées au nom d’un accent décoratif unique. Aucune donnée, identité, métrique ou promesse n’est inventée. Aucun nouvel outil JavaScript, service externe de polices, image décorative ou effet de défilement n’est ajouté.

## Police embarquée

Source : [Google Fonts / Outfit](https://github.com/google/fonts/tree/main/ofl/outfit).
Le fichier variable original est distribué avec sa licence SIL OFL dans `src/main/resources/static/assets/fonts/OFL.txt`. Son chargement reste local, sans requête vers un fournisseur tiers.

## Validation

Contrôles ciblés : `src/test/browser/actions-menu.mjs`, `src/test/browser/ui-controls.mjs`, inspection visuelle des thèmes clair et sombre sur écran large et mobile, et `git diff --check`.

Résultats locaux : `git diff --check` réussi ; syntaxe des règles et déclarations CSS analysée sans erreur ; police variable et caractères français vérifiés. Le test `actions-menu.mjs` ne démarre pas car Chromium manque. Son installation a échoué (archives de téléchargement invalides). Les vérifications navigateur et l’inspection visuelle restent à effectuer ; aucun résultat responsive n’est revendiqué.
