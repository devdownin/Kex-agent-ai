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

Le détail et les raisons sont dans [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
