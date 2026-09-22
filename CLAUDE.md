# Notes pour Claude Code

## Le projet en une phrase

Agent IA Spring Boot 4 / Spring AI 2 sur Java 25, client de serveurs MCP, branché par défaut sur le
serveur MCP de [Kafka SQL Explorer](https://github.com/devdownin/Kafkaexplorer).

## Commandes

```bash
./mvnw verify                              # format, licences, tests, couverture, SBOM
./mvnw spotless:apply                      # corrige format et en-têtes avant de committer
./mvnw test -Dtest=NomDuTest               # un test
./mvnw spring-boot:run                     # agent sur 8081
docker compose -f compose/smoke.yml up -d --wait   # agent + serveur MCP factice

# La console au navigateur : l'agent doit tourner, Playwright est hors du projet
PLAYWRIGHT_MODULE=/chemin/playwright/index.mjs node src/test/browser/console.mjs
```

JDK 25 requis. La CI construit aussi l'image Docker et monte la stack de fumée.

## Structure

| Paquet | Contenu |
|---|---|
| `web/` | Contrôleur REST, sécurité, filtre de débit |
| `agent/` | Conversation : mémoire, appel bloquant, flux SSE |
| `mcp/` | Introspection et invocation des serveurs MCP |
| `config/` | `ChatClient`, propriétés, bearer MCP, seau à jetons, lecture de la configuration du modèle |
| `knowledge/` | Base de connaissance optionnelle : magasin vectoriel, advisor, ingestion |
| `supervision/` | Cycle d'analyse, politique d'autonomie, décisions, validation humaine, audit |
| `kafka/` | Vue technique du cluster : traduit les outils MCP d'Explorer, ne recalcule rien |
| `resources/static/` | La Control Center : console d'exploitation, sans étape de build |

## Conventions

- Français pour les commentaires, la documentation et les noms de tests ; anglais pour le
  `README.md` vitrine (`README.fr.md` en miroir).
- Commentaire = la raison d'un choix, pas la paraphrase du code. S'il n'y a pas de raison
  non évidente, pas de commentaire.
- Injection par constructeur, records pour les DTO et les `@ConfigurationProperties`.
- Chaque correction de défaut arrive avec le test qui l'aurait attrapée.
- En-tête SPDX en tête de chaque fichier Java — `spotless:apply` le pose.
- Plancher de couverture : 85 % instructions, 70 % branches. `verify` échoue en dessous.

## Pièges déjà rencontrés

- **Ne pas remettre `spring.ai.mcp.client.initialized` à `true`.** En initialisation hâtive, un
  serveur MCP injoignable fait échouer le démarrage de l'application entière.
- **Les serveurs MCP s'adressent par clé de connexion**, jamais par le nom qu'ils annoncent :
  celui-ci est inconnu avant le handshake.
- **Ne pas déclarer de bean `VectorStore` hors de `kex.agent.knowledge.enabled`** : sans modèle
  d'embeddings, le contexte ne démarre pas. Même piège que le starter JDBC ci-dessous.
- **Deux starters de modèle sur le classpath imposent `spring.ai.model.chat`.** Chaque
  autoconfiguration de modèle s'active en l'absence de propriété (`matchIfMissing`) : sans cette
  ligne, Anthropic et OpenAI déclarent chacun leur `ChatModel` et le contexte échoue au démarrage.
  Les modalités que le starter OpenAI apporte en prime — embeddings, images, modération, audio —
  sont à `none` pour la même raison, et parce qu'un `EmbeddingModel` non demandé satisferait en
  silence la base de connaissance.

- **Ne pas ajouter le starter JDBC hors du profil `shared-memory`** : sa seule présence sur le
  classpath fait échouer le démarrage quand aucune base n'est configurée.
- **Ne pas désactiver CSRF globalement** — CodeQL le signale, à juste titre. Il est levé sur
  `/api/**` seulement.
- **Le refus d'authentification appartient à l'`AuthenticationEntryPoint`**, pas au filtre :
  dans le filtre, il bloque aussi les routes en `permitAll` comme `/actuator/health`.

- **Une exception de fournisseur non attrapée ne doit jamais recopier notre propre 401.**
  `AnthropicException` et `OpenAIException` remontaient non gérées depuis `AgentController` ;
  Anthropic répond 401 à une mauvaise clé, et rien ne distinguait alors « votre bearer kex est
  refusé » de « la clé du fournisseur est fausse ». Les deux hiérarchies d'exceptions ont une
  racine commune par fournisseur : `@ExceptionHandler({AnthropicException.class,
  OpenAIException.class})` vers `BAD_GATEWAY` couvre toute la famille sans connaître chaque
  sous-classe, et ne recoupe aucun code que la sécurité ou le rate limit utilisent déjà.

- **L'état de l'agent regarde aussi ce qui le rend capable d'agir.** Sans clé de modèle il est
  `DEGRADED`, jamais analysé il est `UNKNOWN` : un vert en tête d'écran affirmerait que tout va
  bien au-dessus d'un bandeau qui dit « Aucune analyse exécutée ».

- **Un panneau s'enregistre dans le registre de `core.js`**, avec la clef d'URL qui le rouvre. Un
  panneau ouvert hors registre est fermé au premier routage par les autres, qui ne le connaissent
  pas — et le bouton Retour ne le referme pas.

- **Un tri lit `data-sort` quand l'affiché ne se trie pas.** « il y a 4 min » ou « 200 000 » avec
  son espace fine, rangés par ordre alphabétique, donnent un ordre qui a l'air juste.

- **Sur un tag, c'est lui qui fixe la version publiée, jamais une saisie séparée.** `publish.yml`
  réaligne le pom de son propre checkout sur le tag (`versions:set`, jamais commité) avant de
  construire, plutôt que de vérifier puis refuser un écart : un `v0.3.0` posé sur un pom resté en
  `0.2.1` a déjà cassé une publication, avec un tag qui n'était plus récupérable une fois poussé.
  Une version `SNAPSHOT` reste refusée pour la même raison qu'avant : son contenu changerait sous
  un nom censé être figé.

- **L'image se construit deux fois avant de partir sur un registre public.** La première, chargée
  en local (`load`), sert au scan de vulnérabilités ; la seconde, poussée (`push`), relit le même
  cache et ne reconstruit quasiment rien. Pousser d'abord et scanner après publierait une image
  vulnérable avant de savoir qu'elle l'est.

- **Un binaire de l'image de base qu'on n'appelle jamais se retire, il ne se tolère pas.** `pebble`
  vient d'`eclipse-temurin:25-jre`, pas de notre arbre de dépendances : aucune ligne de `pom.xml`
  ne le corrige, et attendre une image amont bloquerait chaque publication jusque-là. L'ENTRYPOINT
  lance `java` directement ; rien ne l'invoque. `tomcat.version` en revanche se corrige dans le
  pom — c'est une propriété que Spring Boot gère et documente pour avancer un composant sans
  attendre sa version mineure suivante.

- **Un état illisible vaut `UNKNOWN`, jamais `OK`.** Une donnée manquante et une donnée saine se
  ressemblent dans un tableau de bord, et les confondre fait rater une panne.

- **Un relevé partiel prouve une présence, jamais une absence.** Un `OK` rendu sur une passe
  incomplète redevient `UNKNOWN` ; un `ERROR` tient. Une couverture non remontée ne dégrade rien —
  la plupart des serveurs MCP n'en portent pas.

- **Une mesure absente n'est jamais zéro.** Un lag à `0` affirme « rattrapé » ; une mesure absente
  n'affirme rien. Les confondre fait lire un consumer à l'arrêt comme un consumer à jour.

- **Le serveur MCP de Kafka SQL Explorer est en lecture seule.** Quinze outils, aucun mutant :
  devant lui l'agent observe et recommande, il n'agit pas.

- **Le mode d'exécution ne peut que restreindre l'autonomie d'une capacité.** L'élargir depuis le
  mode ouvrirait d'un coup des actions délibérément mises sous supervision.

- **Un plancher de confiance par capacité ne peut que relever le plancher global.** L'abaisser
  rendrait le plancher global illisible : sa valeur ne dirait plus rien sans relire chaque ligne.

- **Un taux calculé sur zéro verdict est un chiffre inventé.** Le taux de pertinence reste `null`
  tant qu'aucun humain n'a tranché : un `0` se lirait « l'agent se trompe toujours ».

- **Pas de `@Scheduled` sur le cycle de supervision.** En multi-instance, chaque réplique lancerait
  le sien et les actions partiraient en double.

- **Le `permitAll` de la console reste borné au `GET` et aux chemins énumérés.** Un joker de
  racine ferait hériter l'ouverture à toute route future servie ici.

- **Le nom d'un `@PathVariable` se retrouve dans la spécification OpenAPI.** Il doit correspondre
  au vocabulaire de la documentation, pas à une variable interne — le test de la spécification a
  attrapé un `{server}` là où tout le reste disait `{connection}`.

- **Le bandeau d'état ne recopie jamais le corps brut d'une exception amont.** `stateReason`
  concaténait `last.failure()` — souvent le JSON renvoyé par le fournisseur du modèle — dans une
  phrase censée s'afficher en tête de chaque écran. Le détail technique reste dans le déroulé du
  cycle et l'audit, qui le portaient déjà ; le bandeau ne dit que où le trouver.

- **Une barre de défilement en survol (macOS, la plupart des Chromium) ne prouve rien à l'écran.**
  `.scroll-x` défilait bel et bien, mais sans indice tant qu'on n'avait pas touché le pavé
  tactile — la dernière colonne d'un tableau compact semblait simplement coupée au bord du
  panneau. `scrollbar-width: thin` aide là où le navigateur l'honore ; l'ombre peinte avec le
  contenu (`background-attachment: local` par-dessus `scroll`) ne dépend d'aucun chrome de
  navigateur et tient même là où `::-webkit-scrollbar` est ignoré.

- **Un bouton qui part en requête se désactive le temps de l'appel.** Sans cela, un double-clic
  envoie deux fois : la seconde requête se heurte au verrou d'idempotence (`409`) ou à une ressource
  déjà supprimée (`404`), et l'écran rend un toast rouge pour une action qui a pourtant abouti — le
  pire des retours, puisqu'il pousse à recommencer. `busy(...)` dans `core.js`.

- **Une fonctionnalité éteinte n'est pas une panne.** Une route conditionnée par une propriété rend
  `404` quand elle est désactivée : l'afficher en « Ressource inconnue » sous un bouton « Réessayer »
  fait chercher un incident là où il n'y a qu'une propriété à changer.

- **Une grille de cartes prend `auto-fit`, pas `auto-fill`.** `auto-fill` réserve toutes les
  colonnes qui tiennent dans la largeur, même vides : avec un seul serveur MCP connecté — le cas de
  l'installation par défaut — la carte restait étroite dans un coin à côté d'un vide. `auto-fit`
  réduit les colonnes vides à zéro et laisse le `1fr` de `minmax()` redistribuer l'espace aux cartes
  réelles ; les deux ne divergent que quand les cartes ne remplissent pas une rangée entière.

- **Le sondage de fond ne réinitialise pas un bloc déjà rendu.** `render()` effaçait l'hôte avec le
  témoin « Chargement… », plus étroit que le contenu qu'il remplace, avant chaque réponse — y
  compris au quinzième sondage sur un tableau déjà affiché. Le résultat : un flash toutes les 15
  secondes qui donnait l'impression que le bloc n'occupait plus toute la largeur disponible. Le
  témoin ne s'affiche plus qu'au tout premier rendu, quand l'hôte est encore vide.

- **`Flux.timeout(Duration)` borne le silence entre deux éléments, pas la durée d'un flux.** Le
  chemin en flux paraissait plafonné comme le chemin bloquant ; il ne l'était pas. Vingt tours
  d'outils entrecoupés de jetons ne dépassent jamais le délai entre deux éléments et tenaient la
  connexion des minutes durant — précisément ce que `kex.agent.request-timeout` existe pour
  empêcher, et ce que `spring.mvc.async.request-timeout` suppose déjà empêché. Un plafond de durée
  se pose avec `takeUntilOther(Mono.delay(...))`, et un flux de test qui débite sans se taire est
  le seul qui distingue les deux : `Flux.never()` passe avec l'une comme avec l'autre.

- **Un échange en échec laisse sa trace en mémoire, et l'identifiant généré ne sort jamais.**
  L'advisor de mémoire écrit le message de l'utilisateur *avant* l'appel au modèle. Sur échec, si
  l'appelant n'avait pas fourni d'identifiant, personne ne connaît celui qui a été tiré : l'entrée
  reste inatteignable et impurgeable — persistée en base sous `shared-memory`. Elle est donc purgée,
  et pour un timeout seulement à la fin de la tâche orpheline : l'appel bloquant n'est pas
  interruptible et écrit sa réponse après coup, une purge immédiate la ferait revenir juste après.

- **`.call().content()` jette le `ChatResponse`**, donc le motif d'arrêt et les jetons consommés.
  Une réponse coupée au plafond `max-tokens` se rendait alors exactement comme une réponse
  complète. `chatResponse()` — et `responseEntity(...)` pour la sortie structurée — gardent les
  deux. `EmptyUsage` compte `0` là où le fournisseur n'a rien dit : à traduire en absence, jamais
  en zéro.

- **Un `Supplier` décoré ne couvre pas un flux.** Le disjoncteur `agent-model` ne protégeait que
  `ask`/`askStructured` : ouvert, il rendait `503` sur `/chat` pendant que `/chat/stream` — ce que
  la console emprunte par défaut — continuait d'appeler le fournisseur en panne, sans même que ses
  échecs comptent. `CircuitBreakerOperator` (`resilience4j-reactor`) le pose sur le flux, après le
  plafond de durée pour que le timeout compte comme un échec, et par `transformDeferred` : l'état
  se lit à la souscription, pas à l'assemblage.

- **`SimpleLoggerAdvisor` écrit en `DEBUG`.** `kex.agent.log-interactions: true` enregistrait
  l'advisor sans qu'une ligne n'apparaisse au niveau par défaut, pendant que la console avertissait
  d'une fuite de prompts inexistante : une propriété qui ment deux fois. Le niveau est posé dans
  `application.yml` ; il ne produit rien tant que la propriété reste fausse, l'advisor n'étant alors
  pas enregistré.

- **`pg_advisory_lock` ne survit pas à un `JdbcTemplate` adossé à un pool.** Un verrou consultatif
  Postgres s'attache à la connexion qui l'a pris ; `JdbcTemplate` en emprunte une par appel, sans
  garantie que l'appel qui relâche tienne la même que celui qui a pris. `SupervisionScheduler` verrouille
  donc avec une ligne à jour d'expiration (`UPDATE ... WHERE locked_until < ?`), qui n'a pas ce
  problème et expire d'elle-même si la réplique qui la tenait tombe en plein cycle.

- **`EnumMap(Map)` refuse une source vide qui n'est pas déjà un `EnumMap`.** Il ne peut alors pas
  déduire le type d'énumération, et lève `IllegalArgumentException` — précisément le cas de la
  première installation, avant tout plancher de confiance par capacité. `new EnumMap<>(Capability.class)`
  puis `putAll(...)` évite la déduction.

- **Deux stubs Mockito qui matchent le même appel : le dernier enregistré gagne**, pas le premier.
  Un test qui stubbe une erreur avant d'appeler un `service(...)` fabriqué par un helper — lequel
  pose son propre stub de succès par défaut — voit ce second stub écraser silencieusement le
  premier. Le stub qui doit compter se pose donc après avoir construit ce qu'il teste.

- **Un disjoncteur par connexion MCP ne couvre que ce que le code peut nommer.** `McpToolCatalog`
  isole désormais un disjoncteur par serveur (`mcp-tool-<connexion>`) pour l'appel direct — un
  serveur en panne n'ouvre plus le disjoncteur des autres. Le chemin piloté par le modèle, lui,
  reste sur un disjoncteur partagé : `SyncMcpToolCallback`, que Spring AI construit à partir du
  `McpSyncClient`, n'expose pas publiquement la connexion dont il vient, donc rien ne permet d'y
  router vers le bon disjoncteur. Une limite documentée, pas contournée.

- **Une propriété gardée par un `@Profile` ne dit pas qu'elle ne sert à rien ailleurs.**
  `kex.agent.supervision.schedule.enabled=true` sans le profil `shared-memory` actif ne lève ni
  erreur ni avertissement : `SupervisionScheduleConfig` porte à la fois le `@Profile` et le
  `@ConditionalOnProperty`, donc hors du profil la condition sur la propriété n'est simplement
  jamais évaluée. Lu seul, l'`application.yml` a l'air correct. `SupervisionScheduleConsistencyCheck`
  compare les deux au démarrage et avertit si la propriété est vraie sans le profil qui la rend utile
  — même geste que `LlmProviderCheck`, pas un échec.

- **Un champ de configuration manquant n'est pas toujours une erreur — parfois c'est le défaut.**
  Un premier réflexe avertissait sur toute entrée `kex.mcp.bearer-tokens[]` incomplète, `url-prefix`
  ou `token` manquant confondus. Ça aurait fait crier au démarrage sur l'installation par défaut
  elle-même : `application.yml` déclare un `url-prefix` pour Kafka Explorer avec un `token` vide,
  exactement la forme d'un serveur qui ne demande aucune authentification. Seul un jeton *sans*
  préfixe est une erreur sans lecture alternative — il ne s'appliquera jamais à aucune requête —
  et c'est le seul cas que `McpBearerTokenCustomizer` signale désormais.

- **Deux `.dump` dans le même panneau : un sélecteur qui n'en cible qu'un tombe sur le premier
  trouvé.** Le panneau d'invocation d'un outil MCP affiche le schéma (`.dump.muted`) au-dessus du
  résultat une fois qu'un outil déclare son `inputSchema`. Une vérification Playwright qui ciblait
  `.invoke .dump` sans autre précision lisait donc le rappel du schéma, pas le résultat de l'appel
  — un texte plausible, jamais celui attendu, et le test échouait sur un message qui n'avait rien à
  voir avec le défaut qu'il cherchait à couvrir. Le résultat porte désormais sa propre classe
  (`.dump.result`), et c'est elle que le test cible.

- **`.shell` veut une hauteur définie, un `min-height` ne borne rien.** Sans hauteur définie, une
  grille CSS dimensionne ses lignes `fr` sur leur propre contenu, pas sur l'espace restant de
  l'écran : un tableau plus haut que l'écran (Audit, Processus, serveurs MCP) faisait alors défiler
  toute la page, rail et barre du haut compris — ni l'un ni l'autre n'ayant de `position: sticky`
  sur desktop. `overflow: auto` sur `main` ne s'active qu'une fois `.shell` en `height: 100dvh`, pas
  `min-height`.

- **Une troncature sans critère de tri choisit au hasard.** `limit(5)` sur les compétences
  approuvées suivait l'ordre du dépôt : la sixième n'agissait jamais et rien ne le disait. Un
  plafond se double toujours d'un ordre explicite (`SkillsService.ranked`, la plus récemment
  approuvée d'abord) et d'un endroit où lire ce qui tombe au-delà (`/api/agent/skills/curation`).

- **Noter une compétence sans télémétrie d'usage, c'est inventer un chiffre.** Le curateur ne dit que
  ce qui est mesurable — laquelle agit, laquelle dort, laquelle en double une autre, laquelle n'a pas
  été revue depuis longtemps. Il ne retire rien non plus : retirer change le prompt de toutes les
  conversations suivantes, donc même circuit humain que l'approbation.

- **Un plancher de confiance ne retient pas ce que l'humain reprochait.** Il dit « moins souvent » ;
  le motif de refus, lui, dit pourquoi. Au-delà de N refus concordants, `RepeatedRefusals` part vers
  `skills`, qui en propose une règle — par événement, pour que la supervision n'ait pas à connaître
  la bibliothèque de compétences, ni l'inverse.

- **Une proposition reformulée à chaque passage échappe à la déduplication.** Le texte tiré des refus
  est déterministe et sans appel au modèle : sinon la file de revue voit dix fois la même règle.

- **Un battement d'ordonnanceur se garde régulier ; c'est la décision d'agir qui s'adapte.**
  Reprogrammer `@Scheduled` à chaud demanderait de manipuler l'ordonnanceur ; un battement fixe qui
  s'abstient se lit dans les journaux et se teste sans horloge réelle. Et il ne prend pas le verrou
  quand il s'abstient : une réplique qui juge le cycle non dû n'a pas à bloquer les autres.

- **La charte ne passe pas par une revue, contrairement à une compétence.** Une compétence naît d'une
  proposition du modèle ; la charte n'a pas d'autre auteur qu'un `ADMIN` authentifié, et la faire
  approuver reviendrait à se faire approuver par soi-même. Elle reste de la donnée de référence : elle
  restreint ce que l'agent propose, elle n'élargit jamais une autonomie.

- **Le rappel de messagerie est la seule route qui décide sans bearer.** Slack et Teams n'en émettent
  pas. Trois verrous cumulés, aucun suffisant seul : HMAC sur le corps brut *avec* l'horodatage
  (signer l'un sans l'autre laisse rejouer ou réécrire), correspondance explicite expéditeur → acteur
  d'audit (un acteur générique ferait dire « approuvé par slack », qui n'est pas une personne), et
  extinction par défaut avec refus de démarrer sans secret. La comparaison de signature passe par
  `MessageDigest.isEqual` — un `equals` sur chaîne fuit par son temps de réponse.

- **Chercher une entrée sous l'identité de l'appelant, c'est supposer qu'il en est propriétaire.**
  Approuver une compétence exige `ADMIN` ; en proposer une ne demande que de converser, et celles que
  la boucle de refus produit appartiennent à l'opérateur qui a refusé. `review` cherchait l'entrée
  sous le principal appelant : il fallait donc être à la fois propriétaire *et* administrateur, ce
  qu'aucune clé `OPERATOR` n'est jamais — ces compétences restaient `PENDING` à vie. Le propriétaire
  se résout désormais par l'identifiant (`LearningRepository.ownerOf`), la transition restant atomique
  sur `(owner, id, PENDING)`. Corollaire : rejeter exige `ADMIN` comme approuver, sans quoi un
  opérateur viderait la file de revue d'une autre équipe.

- **Restreindre la revue d'une compétence au locataire de l'appelant referait le même blocage,
  par une autre porte.** Sans locataire déclaré, une clé est son propre locataire : `admin` et
  `ops-console` n'en partagent jamais un par défaut. Exiger que le locataire de l'approbateur
  coïncide avec celui du propriétaire rendrait donc à nouveau inapprouvable, par défaut, tout ce
  que `ownerOf` a été introduit pour rendre approuvable. La revue reste un geste de plateforme,
  transverse par construction — ce qui manquait n'était pas une restriction mais une découverte
  (`GET /api/agent/skills/review-queue`), pour ne plus dépendre de l'audit pour trouver un
  identifiant à trancher.

- **Un `CREATE TABLE IF NOT EXISTS` posé à la construction n'est pas une migration, même
  idempotent.** Il ne rejoue rien dans l'ordre et ne dit jamais ce qu'une base donnée porte
  réellement — `kex_agent_memory` avait déjà eu besoin d'un second `ALTER TABLE ADD COLUMN IF NOT
  EXISTS` pour `owner`, preuve que « ça ne change jamais » ne tenait pas. Flyway prend le relais
  sous `shared-memory`, avec la même exclusion par défaut que `DataSourceAutoConfiguration` :
  `FlywayAutoConfiguration` ne se déclenche que sur `flyway-core` au classpath
  (`@ConditionalOnClass`), pas sur un bean `DataSource` déjà là — sans l'exclusion, elle tenterait
  de migrer une base absente hors de ce profil.

- **Retrofiter Flyway sur une base qui tournait déjà sous l'ancien mécanisme veut dire que `V1` doit
  réellement s'exécuter, pas être sautée.** Le défaut de Flyway (`baseline-version: 1`) marquerait
  la première migration « déjà appliquée » sans l'exécuter dès qu'un schéma non vide et non géré
  est détecté — et une table absente de cette base précise (parce que son propre dépôt n'avait
  jamais eu l'occasion de tourner) resterait absente pour de bon. `baseline-version: "0"` place le
  curseur *avant* `V1`, qui garde alors exceptionnellement le style `IF NOT EXISTS` de l'ancien
  mécanisme : elle s'exécute pour de vrai sur toute base, neuve ou partielle, sans jamais échouer
  sur ce qui existe déjà. `FlywayBaselineRetrofitTest` le prouve dans les deux sens.

- **Un dépôt JDBC testé seul, contre son propre H2, ne passe plus par le contexte Spring qui
  applique une migration.** Une DDL réécrite à la main dans le test diverge tôt ou tard de celle
  que la production exécute réellement, en silence. `FlywayTestSchema.migrate(dataSource)` rejoue
  la vraie migration avant chaque test qui construit son propre `DataSource`.

- **Le bouton d'une notification n'est un raccourci que s'il déclenche vraiment quelque chose.**
  `InboundApprovalController` existait pour approuver depuis Slack sans ouvrir de navigateur, mais
  rien de ce que `SlackChannelAdapter` postait ne pouvait l'appeler : le seul bouton envoyé liait
  vers la console — exactement la friction que la route existe pour éviter. Construite, jamais
  branchée, une capacité reste une promesse non tenue à l'écran.

- **La signature d'interactivité de Slack n'est ni le même secret ni le même calcul que l'entrée
  générique.** Le secret vient de Slack, à la création de l'App ; celui de l'entrée générique est
  choisi par l'exploitant. La base signée diffère aussi (`v0:<horodatage>:<corps>`, résultat
  préfixé `v0=`, contre `<horodatage>.<corps>` en hexadécimal nu) : les confondre laisserait une
  signature calculée pour l'une validée avec le secret de l'autre. Deux classes séparées
  (`SlackRequestSignature`, `InboundSignature`), jamais une seule paramétrée par le format.

- **Un filtre qui lit `getParameter()` avant un contrôleur peut vider `@RequestBody`.** Pour un
  corps `application/x-www-form-urlencoded`, le conteneur consomme le flux dès qu'un `getParameter`
  déclenche l'analyse des paramètres — un appel direct à la méthode du contrôleur ne l'aurait
  jamais révélé, puisqu'il contourne toute la chaîne. `SlackInteractivityWiringTest` poste une vraie
  requête HTTP à travers le contexte Spring complet pour le prouver, plutôt que de le supposer parce
  que ça compile.

- **Une Adaptive Card Teams ne livre nulle part sans un Bot Framework enregistré.** `Action.Submit`
  suppose une identité Azure AD et un canal de messagerie inscrit, bien au-delà d'un webhook
  entrant. Ce n'est pas un manque à combler discrètement plus tard : c'est une limite de la
  plateforme, à dire dans le code et la doc plutôt qu'à contourner par un flux qui semblerait
  fonctionner sans avoir jamais été vérifié contre un vrai tenant Teams.

- **`onCredentialChange` ne se déclenche que sur `credentials.set(...)`, jamais sur un jeton relu
  de `sessionStorage` au chargement.** Un abonné qui ne s'exécute qu'à ce changement-là (`whoami()`)
  reste inerte pour quiconque revient sur un onglet déjà connecté — il faut aussi l'appeler une
  fois, directement, dans le bloc de démarrage qui lit `credentials.get()`.

- **Un `<textarea required>` ajouté à un dialogue partagé bloque aussi son bouton Annuler**, tant
  que les deux boutons `submit` restent dans le même `<form method="dialog">` — la validation
  native HTML porte sur le formulaire entier, pas sur le bouton cliqué. `formnovalidate` sur
  Annuler seul referme cette fenêtre : sans lui, un champ obligatoire empêcherait jusqu'à l'abandon
  de l'action qu'il est censé motiver.

- **Étendre une fonction partagée par une douzaine d'appelants change son contrat de retour, pas
  seulement son corps.** `confirmAction` rendait un booléen partout ; lui ajouter un champ de motif
  ne pouvait pas se faire en renvoyant toujours `{ confirmed, reason }`, qui aurait cassé chaque
  `if (!confirmed)` existant (un objet est toujours vrai). Le nouveau champ ne change donc le
  contrat que pour l'appelant qui le demande explicitement (`reasonLabel` fourni) — jamais pour
  ceux qui ne savent pas qu'il existe.

- **`page.waitForSelector('sélecteur:not([attribut])')` sur un `<dialog>` ne se résout jamais après
  fermeture.** L'état par défaut attendu est « visible » ; un `<dialog>` sans `open` devient
  `display: none` par défaut, donc justement invisible — la négation matche le sélecteur mais
  jamais l'état qu'on attend. Prouvé en isolant chaque étape du test en échec : le dialogue se
  fermait bel et bien (`dialog.open` valait déjà `false`), seule l'attente échouait. Attendre la
  disparition d'un `<dialog>` se fait sur le sélecteur positif avec `{ state: 'hidden' }`, jamais
  sur sa négation.

Le détail et les raisons sont dans [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
