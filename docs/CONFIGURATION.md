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
| `OPENROUTER_MODEL` | `openai/gpt-oss-120b:free` | Modèle OpenRouter, au format `fournisseur/modèle` |
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
| `KEX_AGENT_TRACING_SAMPLING` | `0` | Probabilité d'échantillonnage des traces. `0` : aucun export tant qu'aucun collecteur n'est déclaré |
| `KEX_AGENT_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | Collecteur OTLP, ignoré tant que l'échantillonnage reste à `0` |

## Propriétés applicatives

### `kex.agent.*`

| Propriété | Défaut | Rôle |
|---|---|---|
| `system-prompt` | prompt outillé | Prompt système par défaut |
| `max-history-messages` | `40` | Fenêtre de mémoire, en messages par conversation |
| `log-interactions` | `false` | Journalise prompts et réponses. Debug uniquement : données sensibles |
| `api-key` | *(vide)* | Bearer de l'API sous le principal anonyme `kex-agent-api`. Vide = API fermée (`503`) |
| `api-keys.<nom>` | *(vide)* | Bearers nommés, en plus ou à la place d'`api-key` : chaque nom devient le principal authentifié, donc l'acteur inscrit à l'audit de supervision |
| `request-timeout` | `120s` | Attente maximale d'un échange, tours d'outils compris |
| `rate-limit.enabled` | `true` | Limite de débit sur `/api/agent/chat` et `/chat/stream` |
| `rate-limit.requests-per-minute` | `60` | Débit soutenu. Limite **d'instance**, pas par appelant |
| `rate-limit.burst` | `20` | Pointe tolérée au-delà du débit soutenu |

### `kex.resilience.*`

Disjoncteur et réessai des intégrations externes (serveurs MCP, fournisseur du modèle) — voir
ARCHITECTURE.md. Les exceptions qui comptent comme échec ou déclenchent un réessai sont fixées en
code par instance (`mcp-tool`, `agent-model`), pas ici.

| Propriété | Défaut | Rôle |
|---|---|---|
| `sliding-window-size` | `10` | Appels glissants sur lesquels le taux d'échec est calculé |
| `minimum-number-of-calls` | `5` | En deçà, le disjoncteur reste fermé quoi qu'il arrive |
| `failure-rate-threshold` | `50` | Taux d'échec (%) au-delà duquel le disjoncteur ouvre |
| `wait-duration-in-open-state` | `30s` | Attente avant de retenter un appel, disjoncteur ouvert |
| `permitted-calls-in-half-open-state` | `3` | Appels d'essai pour décider si le disjoncteur referme |
| `retry-max-attempts` | `3` | Tentatives totales pour un serveur MCP explicitement injoignable |
| `retry-wait-duration` | `500ms` | Attente entre deux tentatives, doublée à chaque fois |

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
| `spring.ai.openai.chat.options.model` | `openai/gpt-oss-120b:free` | Modèle OpenRouter |
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
export OPENROUTER_MODEL=openai/gpt-oss-120b:free   # optionnel, c'est déjà le défaut
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
  Le défaut, `openai/gpt-oss-120b:free`, applique le schéma plutôt que de s'en approcher —
  vérifié avant de le retenir, tous les modèles gratuits ne le garantissent pas.
- **Le tiers gratuit limite en requêtes, pas en jetons.** 20/min, 200/jour, observé sur
  `openai/gpt-oss-120b:free` — et `spring.ai.tools.limits.max-total-tool-calls` autorise jusqu'à
  20 allers-retours outil *dans un seul cycle*, chacun un appel au modèle. Un cycle qui creuse
  plusieurs topics peut à lui seul approcher le plafond par minute.

`OPENROUTER_BASE_URL` pointe ailleurs pour toute autre passerelle compatible OpenAI — un LiteLLM
ou un vLLM interne, par exemple.

### Lire la configuration appliquée

L'écran **Configuration** de la console affiche ce qui tourne réellement : fournisseur, modèle,
point d'accès, présence d'une clé, plafonds de l'échange et prompt système. C'est
l'`Environment` résolu qui est lu — variables d'environnement et profils compris — et non un
fichier, donc un `docker compose` qui surcharge une valeur s'y voit. `GET /api/agent/llm` rend la
même chose en JSON.

Aucune clé n'y figure, jamais, et la base d'URL est rendue sans ses éventuels identifiants : seule
sa présence est affichée. L'écran est en lecture seule — le client du modèle est câblé au
démarrage du contexte, un champ modifiable y accepterait une valeur que l'échange suivant
ignorerait.

### Savoir quel modèle mettre dans `OPENROUTER_MODEL`

Sur une passerelle, le panneau **Modèles disponibles** de l'écran Configuration lit le catalogue
qu'elle publie (`GET /api/agent/llm/models`) et dit, pour chacun, s'il sait **appeler des outils**.
C'est la capacité dont cet agent ne peut pas se passer : un modèle qui ne l'a pas le rend muet.

Trois réponses et non deux — « Outils », « Sans outils », « Non annoncé ». Une passerelle qui ne
publie pas ses paramètres ne refuse rien, et afficher « non » écarterait des modèles utilisables.
Le catalogue est celui que la passerelle déclare : il n'est pas vérifié par un appel réel.

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

<a id="publier-l-image"></a>

## Publier l'image

Le workflow `publish.yml` pousse sur Docker Hub **et sur GHCR, en miroir** — le même digest sous
`ghcr.io/devdownin/kex-agent-ai`, sans secret de plus : GHCR se pousse avec le jeton que GitHub
fournit déjà à chaque exécution. **Rien n'est saisi au déclenchement** : la version est celle de
`pom.xml`, lue par Maven. Une version tapée à la main finit par diverger du jar qu'elle étiquette,
et une image mal étiquetée est pire qu'une image absente — personne ne saura ce qui tourne.

### Réglages GitHub

`Settings → Secrets and variables → Actions`

| Nom | Type | Défaut si vide | Rôle |
|---|---|---|---|
| `DOCKERHUB_USERNAME` | Variable | *(aucun — échec explicite)* | Identifiant Docker Hub |
| `DOCKERHUB_TOKEN` | Secret | *(aucun — échec explicite)* | Jeton d'accès, **pas** le mot de passe |
| `DOCKERHUB_NAMESPACE` | Variable | `compagnonsdudev` | Organisation ou compte |
| `DOCKERHUB_IMAGE` | Variable | `kex-agent-ai` | Nom du dépôt d'images |

L'identifiant est une variable et non un secret : ce n'en est pas un, et le ranger comme tel le
masque dans les journaux au moment précis où on cherche pourquoi l'authentification a échoué. Le
jeton se crée dans Docker Hub sous `Account settings → Personal access tokens`. La portée
`Read & Write` suffit pour `docker push` ; la synchronisation de la description du dépôt (plus bas)
exige en plus la portée `Delete`, faute de quoi seule cette étape-là échoue — la publication de
l'image, elle, aboutit.

### Publier une version

```bash
# 1. La version du projet, une fois — c'est elle qui étiquettera l'image
sed -i 's|<version>0.1.0-SNAPSHOT</version>|<version>0.2.0</version>|' pom.xml
git commit -am "chore: version 0.2.0"

# 2. Le tag déclenche la publication
git tag v0.2.0
git push origin main --follow-tags
```

Le workflow **refuse de publier** si le tag et la version du `pom.xml` divergent, ou si la version
porte `SNAPSHOT` : un tag Git n'est pas récupérable une fois poussé, et une image dont le contenu
change sous le même nom ne veut rien dire.

Sur un tag, trois étapes de plus s'ajoutent après la publication : une **release GitHub** est créée
(notes générées depuis les commits, marquée préversion pour un tag `-rc`/`-beta`/…), la **description
du dépôt Docker Hub** est resynchronisée depuis `README.md`, et un **SBOM** est attesté sur l'image
elle-même — interrogeable après coup (`docker buildx imagetools inspect --format '{{ json .SBOM }}'
compagnonsdudev/kex-agent-ai:0.2.0`), à la différence du SBOM que `ci.yml` dépose en artefact de
build, qui ne voyage pas avec l'image publiée. Aucune des trois ne tourne sur `edge` : une
exécution manuelle n'a rien à annoncer.

### Ce qui est publié

| Déclencheur | Tags |
|---|---|
| Tag `v0.2.0` | `0.2.0`, `0.2`, `0`, `latest`, `sha-<court>` |
| Tag `v0.2.0-rc1` | `0.2.0-rc1`, `sha-<court>` |
| Lancement manuel | `edge`, `sha-<court>` |

Une préversion ne déplace ni `latest` ni les tags mobiles : un `docker pull` sans tag ne doit pas
ramener du code qu'on n'a pas fini de juger.

La suite complète (`./mvnw verify`) tourne **avant** la publication, sur la même révision : le
workflow CI ne se déclenche pas sur un tag, s'y fier publierait le résultat d'une autre révision.
L'image est aussi **passée au scanner de vulnérabilités** (Trivy) avant de partir sur un registre
public : construite en local, scannée, puis reconstruite — depuis le cache, donc quasiment gratuite
la seconde fois — et poussée seulement si rien de `CRITICAL` ou `HIGH` avec correctif disponible n'y
traîne. Une CVE sans correctif dans l'image de base ne bloque pas : bloquer dessus serait bloquer
indéfiniment sur quelque chose qu'aucune version de cette image ne peut corriger seule.

Après le `push`, l'image est retirée du cache local, retéléchargée depuis le registre et démarrée —
un `push` réussi dit que les couches sont parties, pas que le manifeste est servable.

`linux/amd64` seulement. Une image arm64 supposerait QEMU et une compilation Maven émulée, soit un
ordre de grandeur de plus sur la durée.

### Utiliser l'image publiée

```bash
docker run --rm -p 8081:8081 \
  -e ANTHROPIC_API_KEY=sk-ant-... -e KEX_AGENT_API_KEY=secret \
  compagnonsdudev/kex-agent-ai:latest

# Ou son miroir GHCR — même digest, pas de compte Docker Hub à créer pour le tirer
docker run --rm -p 8081:8081 \
  -e ANTHROPIC_API_KEY=sk-ant-... -e KEX_AGENT_API_KEY=secret \
  ghcr.io/devdownin/kex-agent-ai:latest
```

`docker-compose.yml` construit l'image localement (`build: .`) : c'est une stack de développement,
et bâtir ce qu'on vient de modifier est ce qu'on y attend.

## Construire l'image

```bash
docker build -t kex-agent-ai:dev .
docker run --rm -p 8081:8081 \
  -e ANTHROPIC_API_KEY=sk-ant-... -e KEX_AGENT_API_KEY=secret \
  kex-agent-ai:dev
```

Construction multi-étapes sur JDK 25, via le wrapper Maven, avec cache BuildKit du dépôt local.
L'exécution se fait sous l'UID `10001`, pas root.
