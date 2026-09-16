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

### Résilience

| Métrique | Origine | Étiquettes utiles |
|---|---|---|
| `resilience4j_circuitbreaker_state` | `mcp-tool`, `agent-model` | `name`, `state` (`closed`/`open`/`half_open`) |
| `resilience4j_circuitbreaker_calls_seconds` | idem | `name`, `kind` (`successful`/`failed`/`not_permitted`) |
| `resilience4j_retry_calls` | `mcp-tool` seulement | `name`, `kind` (`successful_without_retry`/`successful_with_retry`/`failed_with_retry`) |

Un disjoncteur ouvert (`resilience4j_circuitbreaker_state{state="open"} == 1`) dit qu'une
intégration échoue déjà assez pour que l'agent ait cessé de la solliciter — plus précis et plus
rapide que d'attendre la remontée des `503`/`502` HTTP.

## Ce qui mérite une alerte

| Signal | Requête | Pourquoi |
|---|---|---|
| Dérive du coût | `sum(rate(gen_ai_client_token_usage_total[1h])) by (gen_ai_token_type)` | Une boucle d'outils ou un prompt qui grossit se voient ici avant la facture |
| Échecs d'outils | `sum(rate(kex_mcp_tool_call_seconds_count{error!="none"}[5m]))` | Un serveur MCP dégradé rend des réponses fausses plutôt qu'une erreur visible |
| Plafond atteint | `rate(http_server_requests_seconds_count{status="504"}[5m])` | Le plafond `kex.agent.request-timeout` se déclenche : échanges trop longs |
| Serveur MCP muet | `kex_mcp_tool_call_seconds_count{error!="none"}` en hausse avec `503` côté HTTP | Initialisation impossible, l'agent tourne sans ses outils |
| Disjoncteur ouvert | `resilience4j_circuitbreaker_state{state="open"} == 1` | Une intégration (serveur MCP ou fournisseur du modèle) échoue en série |

## Traçage

Éteint par défaut (`management.tracing.sampling.probability: 0`) : le pont OpenTelemetry crée un
`Tracer` même sans collecteur en face, mais rien n'est exporté tant qu'une installation ne le
demande pas explicitement. Pour l'activer :

```bash
export KEX_AGENT_TRACING_SAMPLING=1        # ou une fraction, 0.1 par exemple
export KEX_AGENT_OTLP_ENDPOINT=http://collecteur:4318/v1/traces
```

Une trace suit un échange à travers le contrôleur, le `ChatClient`, chaque appel MCP et le modèle —
ce qu'aucune métrique agrégée ne peut reconstituer après coup. L'identifiant de trace apparaît
aussi dans chaque ligne de journal dès qu'un `Tracer` existe, échantillonné ou non : c'est ce qui
relie une ligne de log à sa trace, une fois celle-ci exportée.

Les appels vers les serveurs MCP portent le contexte de trace courant (`traceparent` W3C) : un
serveur MCP lui-même instrumenté rattache sa propre trace à celle de l'agent plutôt que d'en
ouvrir une détachée.

## Journaux

`kex.agent.log-interactions: true` journalise prompts et réponses via `SimpleLoggerAdvisor`. À
réserver au debug : le contenu des échanges y passe en clair.

`spring.ai.chat.observations.log-prompt` et `log-completion` font de même au niveau des observations,
avec le même avertissement.
