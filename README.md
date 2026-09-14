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

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./mvnw spring-boot:run
```

## API

| Méthode | Route | Rôle |
|---|---|---|
| `POST` | `/api/agent/chat` | Requête synchrone, retourne `{conversationId, content}` |
| `POST` | `/api/agent/chat/stream` | Même contrat, réponse en SSE token par token |
| `DELETE` | `/api/agent/conversations/{id}` | Purge la mémoire d'une conversation |
| `GET` | `/api/agent/mcp/servers` | Serveurs MCP connectés et outils découverts |

```bash
curl -X POST localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"Liste les fichiers de /tmp"}'
```

`conversationId` est optionnel : s'il est absent, un UUID est généré et renvoyé dans la réponse.

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

Serveur distant (streamable-HTTP) :

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            internal-tools:
              url: https://mcp.interne.example.com
              endpoint: /mcp
```

Alternative : pointer un fichier au format `claude_desktop_config.json` via
`spring.ai.mcp.client.stdio.servers-configuration: classpath:mcp-servers.json`.

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

## Tests

```bash
./mvnw test
```
