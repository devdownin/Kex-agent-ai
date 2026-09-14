# Observabilité

Un service dont chaque requête coûte de l'argent se surveille d'abord par le coût. Cette page dit
quelles métriques existent, où les lire et lesquelles méritent une alerte.

## Où

`GET /actuator/prometheus`, au format d'exposition Prometheus.

L'endpoint **n'est pas ouvert** : il porte le modèle utilisé, le volume de jetons consommés et les
outils appelés. Seul `/actuator/health` est en `permitAll`, pour les sondes de conteneur. Le scraper
doit présenter le même bearer que l'API :

```yaml
scrape_configs:
  - job_name: kex-agent-ai
    metrics_path: /actuator/prometheus
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/kex-agent-token
    static_configs:
      - targets: ["kex-agent-ai:8081"]
```

## Ce qui est mesuré

### Coût

| Métrique | Origine | Étiquettes utiles |
|---|---|---|
| `gen_ai_client_token_usage` | Spring AI | `gen_ai_token_type` (`input` / `output`), `gen_ai_request_model` |
| `gen_ai_client_operation_seconds` | Spring AI | `gen_ai_operation_name`, `gen_ai_request_model`, `error` |

Les compteurs de jetons n'existent que parce qu'un `MeterRegistry` est présent :
`ChatObservationAutoConfiguration` n'enregistre `ChatModelMeterObservationHandler` qu'à cette
condition. `KexAgentApplicationTests` vérifie que le bean est bien là, pour qu'un retrait de la
dépendance Prometheus ne rende pas le coût silencieusement invisible.

Les compteurs sont agrégés par modèle, **pas par conversation** : un `conversationId` en étiquette
ferait exploser la cardinalité. Pour imputer un coût à un utilisateur, passer par les journaux, pas
par les métriques.

### Outils

| Métrique | Couvre | Étiquettes |
|---|---|---|
| `spring_ai_tool_call_seconds` | Les outils choisis par le modèle, via Spring AI | `tool_name`, `error` |
| `kex_mcp_tool_call_seconds` | `POST /mcp/servers/{connection}/tools/{tool}`, l'appel direct | `connection`, `tool`, `error` |

Le second existe parce que le chemin direct ne passe pas par Spring AI : sans lui, la latence et les
échecs de cet endpoint ne seraient mesurés nulle part.

### HTTP et JVM

`http_server_requests_seconds` (par `uri`, `status`, `outcome`) et les métriques JVM standard,
fournies par Spring Boot.

## Ce qui mérite une alerte

| Signal | Requête | Pourquoi |
|---|---|---|
| Dérive du coût | `sum(rate(gen_ai_client_token_usage_total[1h])) by (gen_ai_token_type)` | Une boucle d'outils ou un prompt qui grossit se voient ici avant la facture |
| Échecs d'outils | `sum(rate(kex_mcp_tool_call_seconds_count{error!="none"}[5m]))` | Un serveur MCP dégradé rend des réponses fausses plutôt qu'une erreur visible |
| Plafond atteint | `rate(http_server_requests_seconds_count{status="504"}[5m])` | Le plafond `kex.agent.request-timeout` se déclenche : échanges trop longs |
| Serveur MCP muet | `kex_mcp_tool_call_seconds_count{error!="none"}` en hausse avec `503` côté HTTP | Initialisation impossible, l'agent tourne sans ses outils |

## Journaux

`kex.agent.log-interactions: true` journalise prompts et réponses via `SimpleLoggerAdvisor`. À
réserver au debug : le contenu des échanges y passe en clair.

`spring.ai.chat.observations.log-prompt` et `log-completion` font de même au niveau des observations,
avec le même avertissement.
