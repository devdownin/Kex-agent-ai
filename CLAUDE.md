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
```

JDK 25 requis. La CI construit aussi l'image Docker et monte la stack de fumée.

## Structure

| Paquet | Contenu |
|---|---|
| `web/` | Contrôleur REST, sécurité, filtre de débit |
| `agent/` | Conversation : mémoire, appel bloquant, flux SSE |
| `mcp/` | Introspection et invocation des serveurs MCP |
| `config/` | `ChatClient`, propriétés, bearer MCP, seau à jetons |
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

Le détail et les raisons sont dans [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
