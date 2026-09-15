# Configuration

Toutes les propriétés, les variables d'environnement, les profils, et comment lancer l'agent
hors Docker.

## Variables d'environnement

### Obligatoires

| Variable | Rôle |
|---|---|
| `ANTHROPIC_API_KEY` *ou* `OPENROUTER_API_KEY` | Clé du fournisseur retenu — voir [Choisir le fournisseur de modèle](#choisir-le-fournisseur-de-modele) |
| `KEX_AGENT_API_KEY` | Bearer exigé sur `/api/**`. Vide, l'API répond `503` |
| `EXPLORER_MCP_AUTH_TOKEN` | Bearer du serveur MCP de Kafka SQL Explorer, **identique des deux côtés** |

`docker compose` refuse de démarrer si l'un des deux bearers manque, plutôt que de laisser un
conteneur boucler. La clé du modèle, elle, n'est pas exigée par compose : il ne sait pas dire
« l'une ou l'autre », et l'exiger obligerait à poser une clé inutilisée. Un fournisseur retenu
sans clé est signalé au démarrage dans les logs, et chaque échange échoue jusqu'à ce qu'elle
arrive.

### Optionnelles

| Variable | Défaut | Rôle |
|---|---|---|
| `KEX_AGENT_LLM_PROVIDER` | `anthropic` | Fournisseur du modèle de conversation : `anthropic` ou `openai` (OpenRouter) |
| `OPENROUTER_MODEL` | `anthropic/claude-sonnet-4.5` | Modèle OpenRouter, au format `fournisseur/modèle` |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` | Toute autre passerelle compatible OpenAI se règle ici |
| `KAFKA_EXPLORER_URL` | `http://localhost:8080` | URL de l'Explorer, côté agent |
| `KEX_AGENT_PORT` | `8081` | Port publié de l'agent (côté hôte) |
| `EXPLORER_PORT` | `8080` | Port publié de l'Explorer |
| `KAFKA_PORT` | `9092` | Port publié du broker |
| `BIND_ADDR` | `127.0.0.1` | Interface d'écoute. `0.0.0.0` expose hors de la machine |
| `EXPLORER_IMAGE_TAG` | `2.0.3` | Version de l'image de l'Explorer. `latest` pour suivre les publications, au prix de la reproductibilité |
| `EXPLORER_IMAGE_NAMESPACE` | `compagnonsdudev` | Ou `ghcr.io/devdownin` |
| `EXPLORER_MCP_READONLY` | `true` | Laisser à `true` sauf besoin explicite d'écriture |
| `POSTGRES_PASSWORD` | `kex` | Surcouche `compose/shared-memory.yml` |

## Propriétés applicatives

### `kex.agent.*`

| Propriété | Défaut | Rôle |
|---|---|---|
| `system-prompt` | prompt outillé | Prompt système par défaut |
| `max-history-messages` | `40` | Fenêtre de mémoire, en messages par conversation |
| `log-interactions` | `false` | Journalise prompts et réponses. Debug uniquement : données sensibles |
| `api-key` | *(vide)* | Bearer de l'API. Vide = API fermée (`503`) |
| `request-timeout` | `120s` | Attente maximale d'un échange, tours d'outils compris |
| `rate-limit.enabled` | `true` | Limite de débit sur `/api/agent/chat` et `/chat/stream` |
| `rate-limit.requests-per-minute` | `60` | Débit soutenu. Limite **d'instance**, pas par appelant |
| `rate-limit.burst` | `20` | Pointe tolérée au-delà du débit soutenu |

### `kex.mcp.bearer-tokens[]`

| Propriété | Rôle |
|---|---|
| `url-prefix` | Préfixe d'URL auquel le jeton s'applique |
| `token` | Valeur du bearer. Une entrée sans jeton est ignorée |

Le filtrage par préfixe évite qu'un jeton parte vers un serveur MCP autre que le sien.

### Spring AI

| Propriété | Valeur ici | Rôle |
|---|---|---|
| `spring.ai.model.chat` | `anthropic` | Fournisseur activé. Les autres modalités sont à `none` — voir ci-dessous |
| `spring.ai.anthropic.chat.options.model` | `claude-opus-5` | Modèle, quand le fournisseur est `anthropic` |
| `spring.ai.anthropic.chat.options.max-tokens` | `4096` | Plafond de sortie |
| `spring.ai.anthropic.chat.options.temperature` | `0.2` | Basse : on veut des faits, pas du style |
| `spring.ai.openai.base-url` | `https://openrouter.ai/api/v1` | Passerelle, quand le fournisseur est `openai` |
| `spring.ai.openai.chat.options.model` | `anthropic/claude-sonnet-4.5` | Modèle OpenRouter |
| `spring.ai.tools.limits.max-total-tool-calls` | `20` | Plafond d'appels d'outils par échange |
| `spring.ai.tools.limits.on-limit-exceeded` | `return_error_response` | Le modèle conclut au lieu de lever une 500 |
| `spring.ai.mcp.client.initialized` | `false` | Initialisation paresseuse — voir ARCHITECTURE.md |
| `spring.ai.mcp.client.request-timeout` | `60s` | Timeout des appels MCP |
| `spring.ai.mcp.client.type` | `sync` | Client synchrone |
| `spring.threads.virtual.enabled` | `true` | Java 25 : un échange en attente coûte une pile, pas un thread noyau |
| `spring.mvc.async.request-timeout` | `150s` | Au-delà de `kex.agent.request-timeout`, pour que l'agent rende un `error` explicite |

## Profils

| Profil | Effet |
|---|---|
| *(aucun)* | Mémoire en mémoire process, connexion `kafka-explorer` déclarée |
| `shared-memory` | Mémoire de conversation en PostgreSQL, schéma créé au démarrage |
| `test` | Clé factice, client MCP désactivé — utilisé par la suite de tests |
| `mcp-it` | Test d'intégration MCP : serveur monté dans le test |

### Mémoire partagée

```bash
docker compose -f docker-compose.yml -f compose/shared-memory.yml up -d
```

Hors Docker :

```bash
SPRING_PROFILES_ACTIVE=shared-memory \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/kex \
./mvnw spring-boot:run
```

Le profil annule la liste `spring.autoconfigure.exclude` d'`application.yml` : sans cela, le starter
JDBC sur le classpath fait échouer le démarrage quand aucune base n'est configurée.

<a id="choisir-le-fournisseur-de-modele"></a>

## Choisir le fournisseur de modèle

Deux starters sont livrés sur le classpath, Anthropic et OpenAI. `KEX_AGENT_LLM_PROVIDER` désigne
celui qui s'active ; aucun code applicatif ne référence l'un ou l'autre.

### Anthropic en direct — le défaut

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

### OpenRouter

```bash
export KEX_AGENT_LLM_PROVIDER=openai
export OPENROUTER_API_KEY=sk-or-v1-...
export OPENROUTER_MODEL=anthropic/claude-sonnet-4.5   # optionnel
```

OpenRouter parle l'API d'OpenAI : c'est le même client Spring AI, une autre base d'URL. Trois
choses à savoir avant de basculer :

- **C'est une passerelle hébergée.** Les prompts, l'historique de conversation et les résultats
  d'outils — donc le contenu des messages Kafka que l'agent lit — quittent la machine et
  transitent par un tiers de plus qu'avec un appel direct au fournisseur.
- **L'appel d'outils est obligatoire ici.** Sans lui l'agent ne peut rien interroger, et tous les
  modèles d'OpenRouter n'en sont pas capables. Le catalogue le signale par modèle.
- **La sortie structurée varie selon le modèle.** Le cycle de supervision demande du JSON conforme
  à un schéma ; un modèle qui ne le respecte pas rend un cycle en échec, pas une analyse fausse.

`OPENROUTER_BASE_URL` pointe ailleurs pour toute autre passerelle compatible OpenAI — un LiteLLM
ou un vLLM interne, par exemple.

### Un autre fournisseur

| Fournisseur | Starter | `spring.ai.model.chat` | Propriétés |
|---|---|---|---|
| Anthropic | `spring-ai-starter-model-anthropic` | `anthropic` | `spring.ai.anthropic.*` |
| OpenAI / OpenRouter | `spring-ai-starter-model-openai` | `openai` | `spring.ai.openai.*` |
| Ollama (local) | `spring-ai-starter-model-ollama` | `ollama` | `spring.ai.ollama.*` |
| Bedrock | `spring-ai-starter-model-bedrock-converse` | `bedrock-converse` | `spring.ai.bedrock.*` |

Ajouter un starter ne suffit pas : chaque autoconfiguration de modèle s'active en l'absence de
propriété (`matchIfMissing`). Sans `spring.ai.model.chat` pour trancher, deux beans `ChatModel`
coexistent et le contexte échoue au démarrage. C'est aussi pourquoi `application.yml` met à `none`
les modalités que le starter OpenAI apporte sans qu'on les demande — embeddings, images,
modération, audio : elles réclameraient une clé, et un `EmbeddingModel` surgi de nulle part
satisferait en silence la base de connaissance, qui doit rester un choix explicite.

<a id="running-from-source"></a>
<a id="lancer-depuis-les-sources"></a>

## Lancer depuis les sources

JDK 25 requis.

```bash
export ANTHROPIC_API_KEY=sk-ant-...   # ou KEX_AGENT_LLM_PROVIDER=openai + OPENROUTER_API_KEY
export KEX_AGENT_API_KEY="$(openssl rand -hex 32)"

# Sans serveur MCP : l'agent démarre, le catalogue est simplement vide
./mvnw spring-boot:run

# Avec Kafka SQL Explorer déjà lancé ailleurs
export EXPLORER_MCP_AUTH_TOKEN=…
export KAFKA_EXPLORER_URL=http://localhost:8080
./mvnw spring-boot:run
```

L'agent écoute sur 8081 ; 8080 est laissé à l'Explorer.

## Stack de fumée

```bash
docker compose -f compose/smoke.yml up -d --wait
```

L'agent et un serveur MCP factice, sans Kafka ni Explorer. C'est ce que monte la CI pour vérifier
que la stack se parle vraiment — image, réseau, propagation du bearer, découverte MCP — sans faire
dépendre le résultat d'un pull Docker Hub anonyme.

## Documentation de l'API

| Route | Contenu |
|---|---|
| `/v3/api-docs` | Description OpenAPI 3.1, générée depuis le code |
| `/swagger-ui.html` | Interface d'exploration |

Les deux sont accessibles sans jeton : la forme de l'API est publique, ses effets ne le sont pas.
Le bouton « Authorize » de Swagger UI attend la valeur de `kex.agent.api-key`.

## Tests

```bash
./mvnw verify                                  # toute la suite
./mvnw test -Dtest=McpStreamableHttpIntegrationTest   # transport MCP réel
```

Aucun secret, aucun accès réseau : le profil `test` fournit une clé factice et désactive le client
MCP, et le test d'intégration monte son propre serveur.

## Construire l'image

```bash
docker build -t kex-agent-ai:dev .
docker run --rm -p 8081:8081 \
  -e ANTHROPIC_API_KEY=sk-ant-... -e KEX_AGENT_API_KEY=secret \
  kex-agent-ai:dev
```

Construction multi-étapes sur JDK 25, via le wrapper Maven, avec cache BuildKit du dépôt local.
L'exécution se fait sous l'UID `10001`, pas root.
