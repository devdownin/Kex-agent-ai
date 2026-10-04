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
Les anciennes demandes locales encore en cours sont classées comme réception interrompue après rechargement. Pour les nouvelles demandes serveur, l’état enregistré est consulté sans rejouer le prompt. Une réception serveur restée sans fin confirmée pendant dix minutes est classée comme interrompue après reprise.

## Historique et console experte

**Mes demandes** consulte les 100 demandes serveur les plus récentes. Elles sont privées au compte,
au locataire et à ses autorités authentifiées : une autre personne du même locataire ou un rôle
modifié ne reçoit pas les transcriptions précédentes. Un autre appareil retrouve la conversation,
son contexte et les résultats enregistrés après connexion. La liste contient un aperçu ; ouvrir
une demande charge les tours complets. La réception active sur un autre appareil est actualisée
sans relancer le modèle. Deux reprises simultanées sont protégées par révision.

Le stockage local utilise `kex.agent.workspace.storage-directory` (par défaut
`${user.home}/.kex/workspace`) et des fichiers atomiques privés, pour une instance. Le profil
`shared-memory` utilise PostgreSQL, la migration V7 et une mise à jour conditionnelle SQL.
Le flux serveur enregistre le début, les sources, les outils et son issue, y compris annulation
ou erreur. Les réponses sont bornées à 64000 caractères, les conversations à 40 tours et
120000 caractères de transcription ; les tours les plus anciens peuvent être retirés.

Les anciennes demandes restent locales à leur onglet ; elles ne sont pas importées comme des
preuves serveur. Un serveur ancien sans ces API conserve ce mode local, signalé explicitement.
Le code d’accès reste dans l’onglet. Changer de compte efface les données affichées et invalide
les réponses réseau tardives.

**Examiner dans la console experte** transfère la même conversation et sa transcription via
un passage local à l’onglet. La console vérifie à nouveau l’identité avant d’afficher le contenu.
Elle ne copie pas ces demandes dans son ancien historique global. Les échanges supplémentaires
sur cette conversation alimentent l’historique isolé ; leur résultat est conservativement marqué
partiel, la console historique ne vérifiant pas le signal de fin du flux. Au retour à `/app`, les échanges de la console restent dans la copie locale. Le serveur de l’espace utilisateur conserve les échanges effectués via son propre flux ; il ne transforme pas une copie locale en résultat serveur. Le transfert n’est proposé qu’une fois la
réception terminée. Un simple lien vers la console ouvre sa conversation habituelle.

## Accès et compatibilité

Le HTML et les ressources statiques sont publics et inertes. Les appels à l’API restent protégés.
Le rôle `CHAT` peut lire `GET /api/agent/skills/available`, son historique privé et les détails de ses demandes, et appeler le flux utilisateur, en plus de ses routes de chat et d’identité. Associer un plan exige OPERATOR/ADMIN et vérifie le locataire du plan. Les autres routes de compétences gardent leurs droits précédents.
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


## Notifications utiles

Le centre **Notifications** signale les résultats reçus (y compris partiels, sans affirmer la réussite), les précisions nécessaires, les plans serveur en attente d’approbation et leurs résultats disponibles. Les résultats durables précisent si le critère est confirmé, non satisfait, incertain ou non vérifié. Un lien ouvre la demande ou la liste des plans ; il n’approuve ni ne lance aucune action. Les événements sont dédupliqués dans cet onglet et les 50 derniers sont conservés dans son historique. Les préférences sont isolées par compte et espace sur ce navigateur.

Les trois catégories peuvent être désactivées indépendamment. Les alertes du navigateur sont facultatives, nécessitent un clic explicite pour demander l’autorisation puis l’activation de la préférence, et apparaissent uniquement lorsque l’onglet est masqué. Leur contenu reste générique. Un refus d’autorisation laisse le centre interne accessible. Changer de compte ferme les alertes ouvertes et efface les informations affichées.

La page doit rester ouverte et le navigateur doit autoriser son exécution en arrière-plan. Il ne s’agit pas de notifications push après fermeture. Les plans sont consultés environ toutes les 30 secondes avec les droits OPERATOR/ADMIN existants (5 secondes pour un plan actif dans un onglet visible) ; un serveur indisponible arrête ces consultations jusqu’à l’actualisation. Aucun courriel ni message externe n’est envoyé.

## Contexte et pièces jointes

Dans **Nouvelle demande**, ouvrir **Ajouter du contexte ou des fichiers**. Renseigner le processus,
la période et l’environnement. Les noms de processus accessibles sont suggérés aux opérateurs ;
la saisie libre reste possible. Le résumé du contexte reste visible avant **Lancer**.

Joindre jusqu’à trois fichiers texte UTF-8 (`.txt`, `.csv`, `.json`, `.md`, `.log`, `.yaml`, `.yml`),
50 Ko et 5000 caractères maximum chacun. Un aperçu et un bouton de retrait permettent de vérifier
les données envoyées. Les formats PDF, images et tableurs binaires ne sont pas extraits dans ce
parcours. Le formulaire refuse un ensemble demande/contexte supérieur à 30000 caractères ;
le serveur valide les champs, les pièces jointes et le prompt final de 32000 caractères maximum.
Les fichiers sont des données non fiables, sans pouvoir d’approbation ni droits supplémentaires.
Le contexte initial reste lié à la conversation ; préparer une nouvelle demande permet de le changer.

## Interventions et bilan

**À suivre** regroupe les précisions nécessaires, les plans à approuver ou examiner, et les résultats
non encore examinés. Les liens ouvrent les demandes ou les plans et ne déclenchent pas d’action.
**Marquer ce résultat comme examiné** enlève son intervention de cette vue sur ce navigateur ;
ce geste ne valide pas l’objectif et n’approuve aucun plan. Les plans restent soumis à leurs états
serveur et aux confirmations existantes.

Le bilan de fin réunit faits rapportés, limites, sources disponibles et prochaine action proposée.
Une réponse complète demeure distincte d’un objectif vérifié. Seul l’état `VERIFIED` d’un plan
serveur associé permet d’afficher la confirmation de son critère final, avec accès à ses preuves.

## Trouver une compétence par besoin

Le catalogue propose une recherche dans les titres, descriptions et procédures, et quatre
regroupements indicatifs : vérifier/surveiller, comprendre/rechercher, préparer un bilan et autres
besoins. Les cartes indiquent les informations à préparer et un exemple de résultat attendu issu
des vérifications déclarées. Ces regroupements ne remplacent pas la revue de la procédure ni sa
revalidation avant envoi.
