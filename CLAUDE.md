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
- **Ne pas ajouter le starter JDBC hors du profil `shared-memory`** : sa seule présence sur le
  classpath fait échouer le démarrage quand aucune base n'est configurée.
- **Ne pas désactiver CSRF globalement** — CodeQL le signale, à juste titre. Il est levé sur
  `/api/**` seulement.
- **Le refus d'authentification appartient à l'`AuthenticationEntryPoint`**, pas au filtre :
  dans le filtre, il bloque aussi les routes en `permitAll` comme `/actuator/health`.

- **Le nom d'un `@PathVariable` se retrouve dans la spécification OpenAPI.** Il doit correspondre
  au vocabulaire de la documentation, pas à une variable interne — le test de la spécification a
  attrapé un `{server}` là où tout le reste disait `{connection}`.

Le détail et les raisons sont dans [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
