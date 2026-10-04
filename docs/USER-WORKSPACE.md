# Espace utilisateur

L’espace utilisateur est accessible à `/app` (également `/app/`). La console experte conserve
son adresse `/` et tous ses liens existants. Chaque interface propose un accès à l’autre.

## Première demande

1. Ouvrir **Espace utilisateur** et se connecter avec le code d’accès fourni par l’administrateur.
2. Décrire le besoin dans **Nouvelle demande**, ou choisir une **Action prête à l’emploi**.
3. Vérifier la demande préparée, la modifier si nécessaire, puis sélectionner **Lancer**.
4. Lire le suivi, la réponse et ses sources. Poursuivre avec le champ de précision si nécessaire.

Les demandes guidées proposées sont **Vérifier un processus**, **Retrouver une commande**,
**Comprendre une anomalie** et **Préparer un bilan**. Elles demandent l’élément concerné et un
contexte facultatif. Ce sont des modèles de prompts, pas des procédures exécutées directement.

Le catalogue de compétences est distinct : il présente les procédures approuvées de l’équipe,
avec leur contenu consultable. Il utilise le filtre serveur existant (approbation humaine,
validité de la preuve, contradictions) et le locataire de l’identité authentifiée. Le catalogue
est revérifié à la préparation et au lancement pour détecter un retrait ou une modification.
Les préconditions sont transmises à l’agent avec la demande ; cette interface n’est pas un moteur
de workflows et ne garantit pas qu’une dépendance externe soit disponible avant l’appel.

Les droits de la procédure ne s’ajoutent jamais à ceux de l’agent. Le formulaire ne propose ni
approbation de compétences, ni invocation directe d’outils, ni modification de politique.
Les approbations opérationnelles et les plans durables restent dans la console experte.

## Suivi et preuves

Le suivi repose sur les événements du serveur : identification de la conversation, outils
terminés, texte de réponse, sources documentaires, erreur et confirmation de fin du flux.
Aucun pourcentage, plan d’exécution ou succès n’est inventé. Le statut **Réponse reçue** indique
uniquement que la réponse est arrivée, pas que l’objectif a été vérifié. Une fermeture du flux
sans événement `done` est présentée comme **Résultat partiel**, y compris avec un ancien serveur.
Un échec d’outil rend également le résultat partiel. Les sources affichent leur provenance,
leur date d’observation et leur extrait, lorsqu’ils sont fournis par l’API.

**Arrêter la réception** ferme le flux côté navigateur. Des actions déjà engagées peuvent avoir
abouti : l’écran le rappelle. Aucun échec ou interruption ne déclenche de nouvel envoi automatique.
Un rechargement classe une demande encore en cours comme réception interrompue.

## Historique et console experte

**Mes demandes** conserve jusqu’à 20 demandes et 40 tours par demande dans `sessionStorage`,
séparément pour chaque couple locataire/utilisateur. L’historique appartient à cet onglet,
ne contient pas le code d’accès et n’est pas un stockage partagé entre appareils ou répliques.
Un changement de compte efface les données affichées et invalide les réponses réseau tardives.

**Examiner dans la console experte** transfère la même conversation et sa transcription via
un passage local à l’onglet. La console vérifie à nouveau l’identité avant d’afficher le contenu.
Elle ne copie pas ces demandes dans son ancien historique global. Les échanges supplémentaires
sur cette conversation alimentent l’historique isolé ; leur résultat est conservativement marqué
partiel, la console historique ne vérifiant pas le signal de fin du flux. Au retour à `/app`,
les échanges sont disponibles dans **Mes demandes**. Le transfert n’est proposé qu’une fois la
réception terminée. Un simple lien vers la console ouvre sa conversation habituelle.

## Accès et compatibilité

Le HTML et les ressources statiques sont publics et inertes. Les appels à l’API restent protégés.
Le rôle `CHAT` peut lire uniquement `GET /api/agent/skills/available`, en plus de ses routes de
chat et d’identité. Les autres routes de compétences gardent leurs droits précédents.
Si les compétences sont désactivées, les demandes guidées et le prompt libre restent disponibles.
Le code d’accès utilise le stockage de session déjà partagé avec la console. Les modes clair et
sombre suivent le système ; les parcours sont conçus pour clavier et écran mobile.

## Vérifications

- `node src/test/frontend/user-stream.mjs` : découpage UTF-8/CRLF, données multilignes, événements
  tronqués et libération du lecteur.
- `node src/test/browser/user-workspace.mjs` avec Playwright : préparation sans exécution,
  compétence retirée, réponse/source, historique, transfert, XSS, erreurs, fin non confirmée,
  isolation de compte et absence de débordement à 1440, 768, 390 et 320 px.
- `./mvnw verify` : routes statiques, sécurité du catalogue et contrat de fin de flux, plus la
  suite existante. Les deux scripts frontend font partie du job navigateur de la CI.

## Guided answers and skill forms

The user workspace requests a structured response using the existing chat stream. A completed, valid result displays observations, uncertainties and the next proposed action. A clarification offers two to four choices; selecting one only fills the follow-up draft. The user must send it to continue and can write another answer. Free text, invalid JSON and interrupted responses remain visible without inferred conclusions. These presentation instructions do not change tool permissions.

The progress list describes observed tool completions in ordinary language and keeps names and durations in expandable details. A completed call does not establish that the user's objective succeeded.

Skill forms show declared preconditions and expected checks, plus up to twenty scalar parameters from verification evidence, with example values. These values are examples, not a validation schema. Complex parameters stay in the procedure and require clarification. Skills without metadata retain the general subject/context form. Changes to procedure or verification metadata invalidate a prepared skill before sending. Preconditions are displayed for examination, never automatically asserted as satisfied.
