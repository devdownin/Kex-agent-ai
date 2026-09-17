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
ferait exploser la cardinalité. Pour imputer un coût à un échange précis, lire la réponse plutôt que
les métriques : `POST /api/agent/chat` et `/chat/structured` rendent `usage.inputTokens` et
`usage.outputTokens` — `null` quand le fournisseur ne les compte pas, jamais `0`, une mesure absente
n'étant pas une consommation nulle.

Ces réponses portent aussi `finishReason`, dans le vocabulaire du fournisseur (`max_tokens` chez
Anthropic, `length` chez OpenAI) : sans lui, une réponse coupée au plafond `max-tokens` se lit
exactement comme une réponse complète. La console marque le tour concerné. Le chemin en flux ne le
rend pas encore — la métadonnée arrive dans le dernier fragment, que `stream()` ne collecte pas.

`kex.agent.token-budget.daily-limit` (`0` par défaut, illimité) plafonne ce que ces mêmes compteurs
accumulent par jour, toutes conversations confondues — le seul frein d'un cycle de supervision qui
part sans clic (`kex.agent.supervision.schedule.enabled`). Budget épuisé, l'état de l'agent passe
`DEGRADED` (`GET /api/agent/supervision/status`) plutôt que de le découvrir dans les métriques
après coup.
Tenu en mémoire, par instance, comme le seau de `rate-limit`.

### Outils

| Métrique | Couvre | Étiquettes |
|---|---|---|
| `spring_ai_tool_call_seconds` | Les outils choisis par le modèle, via Spring AI | `tool_name`, `error` |
| `kex_mcp_tool_call_seconds` | `POST /mcp/servers/{connection}/tools/{tool}`, l'appel direct | `connection`, `tool`, `error` |
| `kex_mcp_server_up` | 1 si le client MCP de cette connexion est initialisé, 0 sinon | `connection` |

Le second existe parce que le chemin direct ne passe pas par Spring AI : sans lui, la latence et les
échecs de cet endpoint ne seraient mesurés nulle part.

`GET /api/agent/mcp/metrics` agrège le même compteur par connexion et par outil (nombre d'appels,
durée moyenne) pour la console — plus lisible qu'un scrape Prometheus pour un opérateur qui regarde
le Control Center plutôt que Grafana.

### HTTP et JVM

`http_server_requests_seconds` (par `uri`, `status`, `outcome`) et les métriques JVM standard,
fournies par Spring Boot.

### Résilience

| Métrique | Origine | Étiquettes utiles |
|---|---|---|
| `resilience4j_circuitbreaker_state` | `mcp-tool-<connexion>` par serveur MCP, `mcp-tool` (chemin piloté par le modèle), `agent-model` | `name`, `state` (`closed`/`open`/`half_open`) |
| `resilience4j_circuitbreaker_calls_seconds` | idem | `name`, `kind` (`successful`/`failed`/`not_permitted`) |
| `resilience4j_retry_calls` | `mcp-tool` seulement | `name`, `kind` (`successful_without_retry`/`successful_with_retry`/`failed_with_retry`) |

Un disjoncteur ouvert (`resilience4j_circuitbreaker_state{state="open"} == 1`) dit qu'une
intégration échoue déjà assez pour que l'agent ait cessé de la solliciter — plus précis et plus
rapide que d'attendre la remontée des `503`/`502` HTTP.

L'appel direct (`POST /mcp/servers/{connection}/tools/{tool}`) a un disjoncteur par connexion
(`mcp-tool-<connexion>`) : un serveur MCP en panne n'échoue vite que ses propres appels, pas ceux
des autres. Le chemin piloté par le modèle reste sur le disjoncteur partagé `mcp-tool` — Spring AI
construit son propre callback (`SyncMcpToolCallback`) sans exposer la connexion dont il vient, donc
pas moyen de router vers le bon disjoncteur à cet endroit-là. `McpServerInfo.circuitBreakerState`
et la pastille de la vue technique ne portent donc que l'état du disjoncteur de l'appel direct.

Même état, sans Prometheus : `GET /api/agent/supervision/status` (`circuitBreakers`) et la fiche
Agent du Control Center l'affichent directement, une pastille par disjoncteur.

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

Cet advisor écrit en `DEBUG`, et le niveau est posé dans `application.yml` pour que la propriété se
suffise à elle-même — sans quoi elle promettait des journaux que le niveau par défaut n'imprimait
jamais, pendant que la console avertissait d'une fuite de prompts qui n'existait pas. L'advisor
n'étant enregistré que lorsque la propriété est vraie, ce niveau ne produit rien tant qu'elle reste
fausse. `KexAgentApplicationTests` le vérifie.

`spring.ai.chat.observations.log-prompt` et `log-completion` font de même au niveau des observations,
avec le même avertissement.
