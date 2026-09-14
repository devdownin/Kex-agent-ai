# kex-agent-ai

Agent IA Spring Boot 4 / Spring AI 2, Java 25. Client de serveurs MCP (stdio, SSE, streamable-HTTP) :
les outils exposés par les serveurs configurés sont automatiquement présentés au modèle.

## Stack

| Composant | Version |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| Modèle | Anthropic (`spring-ai-starter-model-anthropic`) |
| MCP | `spring-ai-starter-mcp-client` (client sync, transport JDK HttpClient + stdio) |

## Démarrage

### Docker Compose (agent + Kafka SQL Explorer + broker)

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export EXPLORER_MCP_AUTH_TOKEN="$(openssl rand -hex 32)"
docker compose up -d
```

Explorer sur http://localhost:8080, agent sur http://localhost:8081. L'Explorer est tiré depuis
l'image publiée `compagnonsdudev/kafkaexplorer:latest` avec son serveur MCP allumé ; l'agent est
construit depuis ce dépôt. Voir `.env.example` pour les variables optionnelles (épingler une
version de l'image, ouvrir les ports hors boucle locale, etc.).

Les deux services partagent le même bearer : `EXPLORER_MCP_AUTH_TOKEN` est passé à l'Explorer, qui
l'exige sur `/mcp`, et à l'agent, qui l'injecte dans ses appels. Compose refuse de démarrer si l'une
des deux variables obligatoires manque, plutôt que de laisser un conteneur boucler au démarrage.

### En local

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./mvnw spring-boot:run     # port 8081 (8080 est laissé à Kafka SQL Explorer)
```

## API

| Méthode | Route | Rôle |
|---|---|---|
| `POST` | `/api/agent/chat` | Requête synchrone, retourne `{conversationId, content}` |
| `POST` | `/api/agent/chat/stream` | Même contrat, réponse en SSE token par token |
| `DELETE` | `/api/agent/conversations/{id}` | Purge la mémoire d'une conversation |
| `GET` | `/api/agent/mcp/servers` | Connexions MCP, état d'initialisation et outils découverts |
| `POST` | `/api/agent/mcp/servers/{connection}/tools/{tool}` | Appel direct d'un outil MCP, sans passer par le modèle |
| `GET` | `/api/agent/mcp/servers/{connection}/resources` | Ressources exposées par le serveur |
| `GET` | `/api/agent/mcp/servers/{connection}/resource?uri=…` | Lecture d'une ressource |

```bash
curl -X POST localhost:8081/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"Quels topics Kafka ont reçu des messages aujourd\'hui ?"}'
```

`conversationId` est optionnel : s'il est absent, un UUID est généré et renvoyé dans la réponse.

Appel direct d'un outil (utile pour tester un serveur MCP ou l'orchestrer depuis du code) :

```bash
curl -X POST localhost:8081/api/agent/mcp/servers/kafka-explorer/tools/kex_list_topics \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"prefix":"demo."}}'
```

```json
{"connection":"kafka-explorer","tool":"kex_list_topics","error":false,"content":["demo.orders"],"structuredContent":null}
```

Ressources :

```bash
curl localhost:8081/api/agent/mcp/servers/kafka-explorer/resources
curl 'localhost:8081/api/agent/mcp/servers/kafka-explorer/resource?uri=kafka://cluster/topics'
```

`{connection}` est la clé de configuration (`…connections.<clé>`), pas le nom annoncé par le
serveur : elle est connue avant même que le serveur ait répondu. `GET /api/agent/mcp/servers`
renvoie les deux (`connection` et `serverName`).

| Situation | Code |
|---|---|
| Connexion inconnue | `404` |
| Serveur injoignable | `503` |
| Capacité `resources` non exposée par le serveur (lecture) | `501` |
| Erreur protocole MCP | `502` |
| Échec de l'outil lui-même (`isError`) | `200` avec `error: true` |

## Brancher un serveur MCP

Serveur local (processus enfant, stdio) :

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            filesystem:
              command: npx
              args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
              env:
                LOG_LEVEL: info
```

Serveur distant (streamable-HTTP) — Kafka SQL Explorer est câblé par défaut :

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            kafka-explorer:
              url: ${KAFKA_EXPLORER_URL:http://localhost:8080}
              endpoint: /mcp

kex:
  mcp:
    bearer-tokens:
      - url-prefix: ${KAFKA_EXPLORER_URL:http://localhost:8080}
        token: ${EXPLORER_MCP_AUTH_TOKEN:}
```

Le transport MCP de Spring AI n'a pas de propriété d'en-tête : `McpBearerTokenCustomizer` injecte
`Authorization: Bearer …` et le restreint au préfixe d'URL déclaré, pour qu'un jeton ne parte pas
vers un autre serveur MCP.

Alternative : pointer un fichier au format `claude_desktop_config.json` via
`spring.ai.mcp.client.stdio.servers-configuration: classpath:mcp-servers.json`.

## Kafka SQL Explorer ([devdownin/Kafkaexplorer](https://github.com/devdownin/Kafkaexplorer))

Son serveur MCP (`kafka-explorer-mcp`) est un module du même JAR, en streamable-HTTP sur `/mcp`,
authentifié par bearer et désactivé par défaut. Côté Explorer :

```bash
export EXPLORER_MCP_ENABLED=true
export EXPLORER_MCP_AUTH_TOKEN=$(openssl rand -hex 32)
export EXPLORER_MCP_REQUIRE_TLS=false   # uniquement pour une stack locale en clair
```

Côté agent, le même jeton et l'URL de l'Explorer :

```bash
export EXPLORER_MCP_AUTH_TOKEN=…
export KAFKA_EXPLORER_URL=http://localhost:8080
./mvnw spring-boot:run     # l'agent écoute sur 8081, l'Explorer occupe 8080
```

L'Explorer déclare 15 outils `kex_*`, tous en lecture (`kex_list_topics`, `kex_describe_topic`,
`kex_preview_messages`, `kex_infer_schema`, `kex_sql_query`, `kex_list_tables`, `kex_build_join`,
`kex_trace_key`, `kex_resume_trace`, `kex_compare_traces`, `kex_deduce_data_model`,
`kex_consumer_lag`, `kex_run_audit`, `kex_get_audit`, `kex_suggest_kpis`). Ils sont automatiquement
présentés au modèle et appelables directement.

```bash
curl -X POST localhost:8081/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Quels topics dépassent 1000 messages et lesquels ont une DLQ qui se remplit ?"}'
```

L'Explorer applique lecture seule, deny-list, rate limit et audit côté serveur : l'agent hérite de
ces garde-fous, il ne les remplace pas.

Sa surface MCP est aujourd'hui **uniquement des outils** : aucun `@McpResource` ni `@McpPrompt`
dans `src/main/java` (vérifié sur `main`, commit `50d54ea`). `/resources` renvoie donc une liste
vide sur cette connexion — les ressources `kafka://cluster/*` sont spécifiées (SPEC-MCP) mais pas
implémentées. Les endpoints ressources de l'agent restent génériques et fonctionnent avec tout
serveur MCP qui déclare la capacité.

## Configuration applicative

| Propriété | Défaut | Rôle |
|---|---|---|
| `kex.agent.system-prompt` | prompt outillé | Prompt système par défaut |
| `kex.agent.max-history-messages` | `40` | Fenêtre de mémoire par conversation |
| `kex.agent.log-interactions` | `false` | Journalise prompts/réponses (debug uniquement) |

## Points d'attention

- La mémoire de conversation est en mémoire process (`InMemoryChatMemoryRepository`) : pour du
  multi-instance, ajouter `spring-ai-starter-model-chat-memory-repository-jdbc` (ou redis) — le
  bean `ChatMemoryRepository` est alors remplacé sans changer le code.
- `/api/agent/mcp/servers` déclenche un `listTools` synchrone par serveur : ne pas l'exposer
  publiquement ni le mettre sur un chemin chaud sans cache.
- Les outils MCP s'exécutent avec les droits du processus : restreindre la racine des serveurs
  filesystem et n'activer que les serveurs de confiance.
- `POST /api/agent/mcp/servers/{connection}/tools/{tool}` exécute l'outil sans médiation du modèle :
  l'autorisation est entièrement à la charge de l'appelant, à protéger avant toute exposition.
- Les clients MCP sont initialisés paresseusement (`spring.ai.mcp.client.initialized: false`) :
  sans cela un serveur distant indisponible fait échouer le démarrage de l'agent. Chaque accès
  retente l'initialisation, et `GET /api/agent/mcp/servers` montre l'état réel de chaque connexion.

## Tests

```bash
./mvnw test
```
