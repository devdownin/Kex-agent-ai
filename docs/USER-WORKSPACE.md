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
Les utilisateurs OPERATOR et ADMIN peuvent désormais examiner les plans durables dans cet espace. La supervision cyclique et la réconciliation des actions incertaines restent dans la console experte.

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
- `node src/test/frontend/user-experience.mjs` : réponses structurées ou invalides, choix de clarification,
  paramètres bornés et modifications des métadonnées.
- `node src/test/browser/user-workspace.mjs` avec Playwright : préparation sans exécution,
  compétence retirée, réponse/source, historique, transfert, XSS, erreurs, fin non confirmée,
  isolation de compte et absence de débordement à 1440, 768, 390 et 320 px.
- `./mvnw verify` : routes statiques, sécurité du catalogue et contrat de fin de flux, plus la
  suite existante. Les scripts frontend font partie du job navigateur de la CI.

## Clarifications et formulaires de compétences

L’espace utilisateur demande une réponse structurée dans le flux de chat existant. Une réponse
complète et valide affiche les constats, les incertitudes et la prochaine action proposée. Une
clarification offre deux à quatre choix : sélectionner un choix remplit uniquement le brouillon.
L’utilisateur doit l’envoyer pour poursuivre et peut écrire une autre réponse. Le texte libre,
le JSON invalide et les réponses interrompues restent visibles sans conclusion déduite.
Ces consignes de présentation ne changent pas les autorisations.

Le suivi décrit les appels terminés en langage courant. Les noms d’outils et leurs durées restent
consultables dans les détails. Un appel terminé ne prouve pas la réussite de l’objectif.

Les formulaires affichent les préconditions et vérifications déclarées, ainsi que jusqu’à vingt
paramètres simples issus des preuves de vérification, avec des exemples. Ces valeurs sont des
exemples, pas un schéma de validation. Les paramètres complexes restent dans la procédure et
nécessitent une clarification. Les compétences sans métadonnées gardent le formulaire général.
Un changement de procédure ou de métadonnées invalide la compétence préparée avant l’envoi.
Les préconditions sont présentées pour examen ; elles ne sont jamais déclarées satisfaites automatiquement.

## Plans à valider dans le suivi

Le suivi d’une demande propose **Préparer un plan à valider** aux rôles OPERATOR et ADMIN.
L’objectif est relu dans un formulaire limité à 2000 caractères. La route existante `/api/agent/tasks/plan`
propose des étapes sans exécuter d’outil. Seul l’identifiant de tâche renvoyé par le serveur est
associé à la demande ; un identifiant suggéré dans une réponse du modèle n’est jamais utilisé.
La page **Plans à valider** présente aussi les plans du locataire, sans les attribuer arbitrairement
à la conversation affichée. Les tâches doivent être activées et leurs liaisons configurées.

Les cartes affichent objectif, étapes, cible/paramètres exacts, préconditions et preuves.
**Approuver ce plan** et **Refuser ce plan** ouvrent une confirmation précisant la conséquence.
Le plan, sa révision et l’empreinte des liaisons sont relus avant l’envoi ; un changement bloque
la décision et exige une nouvelle lecture. Le serveur conserve ses contrôles d’état, de politique
et de droits. Les actions non classées en lecture seule nécessitent ADMIN. Le rôle CHAT ne
reçoit aucun droit supplémentaire et ne charge pas ce catalogue.

L’approbation n’exécute rien. **Lancer le plan approuvé** exige une confirmation séparée.
L’état RUNNING est actualisé toutes les cinq secondes quand la page est visible. Le statut
VERIFIED indique le critère final confirmé ; COMPLETED ne prouve pas la réussite de l’objectif.
Les états incertains renvoient à la console experte pour leur réconciliation. Le changement
de compte efface les cartes et dialogues, et invalide toute réponse tardive.

## Favoris et nouvelle demande préparée

Une action guidée, une compétence ou une demande peut être ajoutée aux favoris. Jusqu’à vingt
favoris sont conservés dans `localStorage`, sous une clé isolée par locataire et utilisateur.
Ils persistent sur ce navigateur après fermeture de l’onglet et peuvent contenir le texte et
les paramètres saisis ; ils ne contiennent pas le code d’accès. **Retirer des favoris** supprime
l’entrée. Un stockage indisponible laisse les favoris en mémoire avec une explication.

**Préparer une nouvelle demande** réutilise la cible, la période et les paramètres d’origine
dans un formulaire à relire. Une compétence doit encore être disponible et avoir les mêmes
contenu et métadonnées ; sinon l’utilisateur doit examiner sa nouvelle version dans le catalogue.
La vérification est répétée avant l’envoi. Une demande modifiée conserve son texte modifié,
avec la même vérification de compétence. Les demandes anciennes sans métadonnées sont
reprises comme brouillons de texte libre. Aucun résultat, plan approuvé ni autorisation n’est
rejoué. Seul **Lancer** crée une nouvelle demande et une nouvelle conversation.

Le parcours `src/test/browser/user-approvals-favorites.mjs` vérifie confirmations, conflit de
révision, séparation approbation/lancement, droits, refus, association serveur, favoris persistants,
compétence retirée, reprise des paramètres, nouvelle conversation et isolation du compte.
