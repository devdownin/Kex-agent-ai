# Architecture

Ce document explique **comment l'agent est construit et pourquoi**. Les décisions y sont données
avec la raison qui les a produites — souvent un comportement observé, pas une préférence.

## Les pièces

```mermaid
flowchart TB
    subgraph web["web/"]
        AC[AgentController]
        SEC[SecurityConfig<br/>ApiKeyAuthFilter]
    end
    subgraph agent["agent/"]
        AS[AgentService]
    end
    subgraph mcp["mcp/"]
        CAT[McpToolCatalog]
    end
    subgraph config["config/"]
        CFG[AgentConfig]
        BT[McpBearerTokenCustomizer]
    end
    SEC --> AC
    AC --> AS
    AC --> CAT
    AS --> CC[ChatClient]
    CFG -.construit.-> CC
    CFG -.construit.-> CAT
    CC --> TCB[ToolCallbackProvider<br/>Spring AI]
    TCB --> CLIENTS[McpSyncClient]
    CAT --> CLIENTS
    BT -.en-tête Bearer.-> CLIENTS
```

| Classe | Rôle |
|---|---|
| `AgentController` | Les 7 routes REST et la traduction des erreurs MCP en codes HTTP |
| `AgentService` | Conversation : identifiant, mémoire, appel bloquant ou flux |
| `McpToolCatalog` | Introspection et invocation directe des serveurs MCP |
| `AgentConfig` | Construit le `ChatClient`, la mémoire, le catalogue |
| `McpBearerTokenCustomizer` | Injecte `Authorization: Bearer` sur les transports MCP HTTP |
| `SecurityConfig` + `ApiKeyAuthFilter` | Bearer statique sur `/api/**` |
| `SupervisionService` | Le cycle : observer, analyser, décider, agir ; la politique et l'audit |
| `SupervisionController` | Le Control Center côté API : vue d'ensemble, décisions, politique, audit |
| `CycleAnalysis` | Le schéma imposé au modèle et la lecture défensive de ce qu'il rend |
| `KafkaViewService` | Vue technique du cluster : traduit les outils MCP, ne recalcule aucune sémantique Kafka |
| `static/` | La Control Center : console d'exploitation servie par l'agent, sans étape de build |

## Le cycle d'une requête

```mermaid
sequenceDiagram
    participant C as Client
    participant A as AgentController
    participant S as AgentService
    participant CC as ChatClient
    participant M as Claude
    participant X as Serveur MCP

    C->>A: POST /api/agent/chat (Bearer)
    A->>S: ask(conversationId, message)
    S->>CC: prompt().user(...).advisors(conversationId)
    CC->>CC: MessageChatMemoryAdvisor charge l'historique
    CC->>X: tools/list (à chaque requête)
    CC->>M: messages + définitions d'outils
    M-->>CC: tool_use kex_list_topics
    CC->>X: tools/call
    X-->>CC: résultat
    CC->>M: résultat de l'outil
    M-->>CC: réponse finale
    CC-->>S: contenu
    S-->>A: AgentAnswer
    A-->>C: 200
```

La boucle outil ↔ modèle est tenue par Spring AI, pas par ce code. Ce qui est à nous : le choix des
outils exposés, la mémoire, les plafonds, et la traduction des erreurs.

## Décisions

### Initialisation paresseuse des clients MCP

`spring.ai.mcp.client.initialized: false`.

En initialisation hâtive (le défaut), l'autoconfiguration appelle `initialize()` sur chaque client
pendant le refresh du contexte. Constaté en démarrant le JAR avec l'Explorer éteint :

```
Failed to instantiate [java.util.List]: Factory method 'mcpSyncClients' threw exception
with message: Client failed to initialize by explicit API call
```

L'agent ne démarrait pas parce qu'un service tiers était éteint. En paresseux, il démarre, et
`McpToolCatalog` retente l'initialisation à chaque accès — un serveur qui arrive en retard est
rattrapé sans redémarrage. C'est aussi ce qui permet au service `agent` de docker compose de ne pas
attendre la sonde de santé de l'Explorer.

### Les serveurs sont adressés par clé de connexion

`/api/agent/mcp/servers/{connection}/...` prend la clé de configuration
(`…connections.<clé>`), pas le nom que le serveur annonce.

La raison est directe : le nom annoncé n'existe qu'après le handshake. Avec l'initialisation
paresseuse, adresser par nom annoncé rendrait un serveur non initialisé **inadressable**, donc
impossible à réveiller. La clé de connexion, elle, est connue dès le démarrage.

`GET /api/agent/mcp/servers` renvoie les deux : `connection` (la clé, celle qui adresse) et
`serverName` (ce que le serveur dit être, `null` tant qu'il n'a pas répondu).

### Le bearer MCP est filtré par préfixe d'URL

Le transport streamable-HTTP de Spring AI n'a pas de propriété d'en-tête. Le point d'extension est
`McpSyncHttpClientRequestCustomizer` — mais un tel bean s'applique à **toutes** les connexions HTTP
MCP. Tel quel, le jeton de l'Explorer partirait aussi vers n'importe quel autre serveur MCP
configuré.

D'où `kex.mcp.bearer-tokens[]`, une liste de couples `url-prefix` / `token` : le customizer ne pose
l'en-tête que sur les requêtes dont l'URI commence par le préfixe déclaré.

### Plafond de la boucle d'outils

```yaml
spring.ai.tools.limits.max-total-tool-calls: 20
spring.ai.tools.limits.on-limit-exceeded: return_error_response
```

Sans plafond, un modèle qui s'entête sur un outil tourne jusqu'au timeout HTTP, à vos frais.
`RETURN_ERROR_RESPONSE` plutôt que `THROW` : le modèle reçoit l'erreur et conclut, là où l'exception
rendrait une 500 sans réponse à l'appelant.

### Deux fournisseurs de modèle, un seul activé

Le classpath porte les starters Anthropic et OpenAI ; `spring.ai.model.chat` — réglé par
`KEX_AGENT_LLM_PROVIDER` — désigne celui qui s'active. OpenRouter parle l'API d'OpenAI, c'est donc
le client OpenAI pointé sur `https://openrouter.ai/api/v1`, sans une ligne de code applicatif :
`AgentConfig` reçoit un `ChatClient.Builder` et ne sait pas d'où il vient.

Cette propriété n'est pas cosmétique. Chaque autoconfiguration de modèle de Spring AI s'active en
l'absence de propriété (`matchIfMissing`) : sans elle, les deux déclarent leur `ChatModel` et le
contexte échoue au démarrage sur un bean ambigu. Même raison pour les modalités que le starter
OpenAI apporte en prime — embeddings, images, modération, audio — toutes à `none` : elles
exigeraient une clé, et un `EmbeddingModel` apparu sans qu'on le demande satisferait en silence la
base de connaissance, qui doit rester un choix explicite. `LlmProviderTest` verrouille les deux
comportements plutôt que la documentation seule.

Ce que la bascule coûte est dit dans [CONFIGURATION.md](CONFIGURATION.md#choisir-le-fournisseur-de-modele) :
une passerelle hébergée voit passer les prompts et les résultats d'outils, l'appel d'outils — dont
cet agent ne peut pas se passer — dépend du modèle choisi, et la sortie structurée du cycle de
supervision aussi.

`LlmViewService` lit cette configuration dans l'`Environment` résolu, et sert à la fois
`GET /api/agent/llm` — l'écran Configuration de la console — et l'avertissement de démarrage. Une
seule lecture pour les deux : séparées, elles finiraient par diverger, et un écran qui contredit
les logs fait douter des deux. Rien n'en sort qui ressemble à un secret : la clé n'apparaît que
par sa présence, et la base d'URL est reconstruite sans ses identifiants ni sa chaîne de requête,
parce qu'un `https://jeton@passerelle/` recopié tel quel finirait dans une capture d'écran.

Le fournisseur retenu sans clé ne fait pas échouer le démarrage : `LlmProviderCheck` le signale et
laisse l'application monter. Même posture que `kex.agent.api-key` — `/actuator/health`, la console
et l'introspection MCP doivent rester joignables, puisque c'est là qu'on ira regarder pourquoi rien
ne répond.

Le défaut d'`OPENROUTER_MODEL` est `openai/gpt-oss-120b:free`, pas un modèle payant pris comme
exemple : appel d'outils natif et sortie structurée avec application du schéma, vérifiés avant de
le retenir plutôt que supposés — un tiers gratuit qui accepte `tools` sans imposer le schéma
rendrait un cycle en échec silencieux, pas une analyse fausse mais une régression qu'on ne verrait
pas venir. Le tiers gratuit limite en requêtes (20/min, 200/jour), pas en jetons ; un cycle qui
creuse plusieurs topics peut à lui seul approcher le plafond par minute, puisque
`spring.ai.tools.limits.max-total-tool-calls` autorise vingt allers-retours outil dans un seul
échange.

### L'état de l'agent n'est pas celui du dernier cycle

La pastille en tête d'écran répondait à « le dernier cycle s'est-il bien passé ? », pas à « cet
agent peut-il faire son travail ? ». Une instance sans clé de modèle affichait donc `OPERATIONAL`,
au-dessus d'un bandeau disant « Aucune analyse exécutée » : les deux moitiés de l'écran se
contredisaient, et c'est la verte qu'on croit.

`SupervisionService` consulte maintenant `ModelAvailability`, une interface d'une méthode que
`LlmViewService` implémente — la supervision n'a que faire du modèle retenu ni de sa température.
L'ordre des règles dit ce qui prime : pause, analyse en cours, échec constaté, clé manquante,
jamais analysé, données périmées, processus inconnus. L'échec passe avant l'absence de clé parce
que c'est un fait, là où la clé n'est qu'une cause probable — et l'écran Configuration la nomme.

`AgentState.UNKNOWN` est distinct de `DEGRADED` : rien n'a été mesuré, ce qui n'affirme ni que tout
va bien, ni que quelque chose va mal. `AgentStatus.stateReason` porte le motif, faute de quoi un
« DÉGRADÉ » envoie chercher la cause dans les journaux alors qu'elle est connue au moment du calcul.

L'absence n'est retenue que lorsqu'elle est **établie** : un fournisseur dont la configuration
n'est pas lue ici ne dégrade rien. Dégrader sur une ignorance rendrait l'indicateur faux dans
l'autre sens.

`stateReason` ne porte plus le détail brut d'un cycle en échec. `last.failure()` est le message
de l'exception qui a interrompu le cycle — pour un fournisseur qui refuse la clé, souvent le corps
JSON de sa propre réponse d'erreur — et ce texte se retrouvait tel quel dans le bandeau affiché en
tête de chaque écran, y compris ceux qui n'ont rien de technique. Le déroulé du cycle et l'audit
portent déjà ce même message (`CycleEvent` et `record(...)` le reçoivent l'un comme l'autre) ; le
bandeau se contente d'une phrase constante disant où le trouver.

### La console est vérifiée au navigateur, en CI

La suite Java sert les fichiers de la console et vérifie que les chemins d'API qu'ils citent
existent ; elle n'exécute pas une ligne de JavaScript. Les défauts d'interface corrigés jusqu'ici
ont tous été trouvés à la main — donc une fois, sans garantie de non-retour.

`src/test/browser/console.mjs` les rejoue dans Chromium : rechargement de l'écran après saisie du
jeton, pastille qui ne ment pas sur un agent qui n'a rien analysé, tri numérique avec les valeurs
absentes en bas, support d'outils non annoncé distinct d'absent, panneau qui retient le focus,
bouton Retour qui le referme, panneau rouvert depuis son adresse, filtre qui survit au
rechargement, bandeau hors ligne, tableau compact qui signale qu'il défile, et aucune erreur de
script sur le parcours.

Playwright n'est pas une dépendance du projet : le job de CI l'installe hors de l'arborescence et
son chemin arrive par `PLAYWRIGHT_MODULE`. Un `package.json` à la racine ferait vivre une seconde
chaîne de construction pour un seul fichier. Le script démarre lui-même une passerelle factice
réduite à `/models` : la CI ne sort jamais vers un tiers.

Ce script a payé le jour où il a été écrit : il a attrapé un panneau — celui du catalogue — ouvert
hors du registre d'adresses, que le bouton Retour ne refermait donc pas.

### Mémoire : en mémoire par défaut, partagée sur demande

`MessageWindowChatMemory` sur `InMemoryChatMemoryRepository`. Deux instances derrière un load
balancer ne verraient pas les mêmes conversations.

Le profil `shared-memory` bascule sur PostgreSQL. Il ne pouvait pas être le défaut : le simple fait
d'avoir le starter JDBC sur le classpath fait échouer le démarrage sans base
(`Failed to determine a suitable driver class`). Les autoconfigurations JDBC sont donc exclues dans
`application.yml`, et le profil **annule** cette liste — une liste de propriétés n'est pas fusionnée
entre sources, la source la plus prioritaire gagne en entier.

### Le flux SSE porte son identité et ses erreurs

`POST /chat/stream` rendait un flux de texte nu. Deux défauts qui n'en sont pas moins réels pour
être discrets :

- l'identifiant de conversation généré quand le client n'en fournit pas n'était **jamais rendu** :
  la conversation était écrite en mémoire sous une clé que personne ne connaissait, donc impossible
  à poursuivre et impossible à purger par `DELETE /conversations/{id}` ;
- une erreur en cours de flux fermait la connexion sans un mot, et une réponse tronquée est
  indiscernable d'une réponse complète côté client.

Le flux émet donc des événements nommés : `conversation` (l'identifiant, en premier), `token`, et
`error` en cas d'échec. Le détail de l'exception reste dans les journaux ; l'appelant reçoit un
message court.

### Les outils exécutés sont rendus à l'appelant

Les observations de Spring AI donnent des métriques agrégées ; elles ne disent pas ce qu'un échange
précis a fait. `RecordingToolCallbackProvider` enveloppe chaque outil et mesure son appel, et le
résultat sort par deux chemins : un événement `tool` dans le flux, et le champ `tools` de la réponse
bloquante.

Le collecteur voyage dans le `ToolContext` de la requête, **pas** dans un ThreadLocal : la boucle
d'outils du chemin en flux s'exécute sur des threads Reactor, où un ThreadLocal ne suit pas.

Ce choix a un piège, et il est testé : le convertisseur par défaut de Spring AI
(`ToolContextToMcpMetaConverter.defaultConverter()`) recopie **tout** le `ToolContext` dans le
`_meta` envoyé au serveur MCP à chaque appel d'outil. Laissé tel quel, le collecteur — non
sérialisable — serait poussé sur le réseau. Le convertisseur déclaré ici filtre les clés préfixées
`kex.` et laisse passer le reste, pour les serveurs qui exploitent ce champ.

### Sortie structurée contre un schéma d'appelant

Les convertisseurs de Spring AI partent d'un type Java ; ici le schéma n'est connu qu'à la requête.
`JsonSchemaOutputConverter` implémente `StructuredOutputConverter` en décrivant le schéma au modèle
et en renseignant aussi `getJsonSchema()`, que les fournisseurs capables de contraindre le décodage
utilisent au lieu d'espérer que le modèle lise la consigne.

Une réponse non conforme rend `502`, pas `400` : le contrat n'a pas été tenu en amont, l'appelant
n'a rien fait de mal. Un schéma vide, lui, rend `400`.

### Plafond de durée d'un échange

`kex.agent.request-timeout`, 120s par défaut. `spring.ai.mcp.client.request-timeout` borne *chaque*
appel MCP, pas l'échange : avec vingt tours d'outils autorisés, le pire cas gardait une connexion
HTTP ouverte une vingtaine de minutes.

Les deux chemins ne sont pas équivalents, et c'est assumé :

- **flux** — `Flux.timeout` annule réellement l'amont ;
- **bloquant** — l'appel de Spring AI n'est pas interruptible. Le plafond borne l'attente de
  l'appelant (`504`), pas le travail, qui continue jusqu'à son terme sur un thread virtuel où un
  orphelin coûte une pile et non un thread noyau. `spring.threads.virtual.enabled` est activé pour
  cette raison.

### Disjoncteur et réessai sur les intégrations externes

`kex.resilience`. Sans eux, un serveur MCP *lent* — pas en panne — consommait le plafond ci-dessus
à chaque appel avant de finalement échouer, et un fournisseur de modèle qui refuse en boucle
laissait chaque nouvelle requête redécouvrir la même panne au prix d'une attente pleine.

Pas le starter `resilience4j-spring-boot3` : sa propre autoconfiguration vérifie la version de
Spring Boot au démarrage et refuse explicitement Spring Boot 4 (`IncompatibleSpringBootVersionException`,
constaté en l'ajoutant). `ResilienceConfig` construit donc `CircuitBreakerRegistry` et
`RetryRegistry` à la main, à partir des seuls modules nus (`resilience4j-circuitbreaker`,
`-retry`, `-micrometer`), et `McpToolCatalog` / `AgentService` y puisent le disjoncteur et le
réessai qui les concernent par leur nom plutôt que par annotation.

Deux disjoncteurs, pas un seul : `mcp-tool` et `agent-model` ne partagent pas les mêmes pannes, et
un serveur MCP capricieux n'a pas à dégrader la disponibilité du modèle, ni l'inverse. Celui du
modèle ne compte que les exceptions des SDK Anthropic/OpenAI et notre propre plafond de temps —
une erreur de validation (schéma vide, connexion inconnue) n'est pas une panne du fournisseur et
ne doit pas ouvrir le disjoncteur pour un défaut de l'appelant.

Le réessai, lui, n'existe que côté MCP, et seulement sur `McpServerUnavailableException` —
l'indisponibilité *explicite* d'un serveur. Une erreur de protocole (outil inconnu, argument
refusé) resterait fausse rejouée. Il n'y en a pas côté modèle : un échange qui a déjà exécuté
plusieurs tours d'outils le rejouerait en entier au moindre échec, doublant les appels MCP déjà
faits. Le disjoncteur du modèle protège donc `ask`/`askStructured`, pas `stream` : ce dernier rend
déjà chaque échec en `event: error` sans jamais bloquer un appelant sur le plafond de temps, la
même raison qui l'exempte du plafond bloquant plus haut.

Un disjoncteur ouvert rend `503` (`CallNotPermittedException`, capté à côté des exceptions MCP et
fournisseur) plutôt que de laisser l'appelant redécouvrir la panne en silence jusqu'au timeout.

### Traçage distribué, opt-in

`management.tracing.sampling.probability` à `0` par défaut. Spring Boot 4 a éclaté
l'autoconfiguration du traçage — un seul bloc dans `spring-boot-starter-actuator` jusqu'en Boot 3 —
en trois modules séparés (constaté en les ajoutant un par un) : `spring-boot-micrometer-tracing`
crée un `Tracer`, mais un `NoopTracer` de repli tant que
`spring-boot-micrometer-tracing-opentelemetry` n'est pas là pour le brancher sur le SDK OpenTelemetry
réel ; sans lui, `Propagator` ne se résout même pas.

Un cycle traverse contrôleur, `ChatClient`, plusieurs appels MCP et le modèle : sans traçage, seules
des métriques agrégées les relient, jamais une trace corrélée d'une requête précise. Le
`Tracer` existe toujours, même sans collecteur en face — c'est ce qui pose l'identifiant de trace
dans chaque ligne de journal (`CONSOLE_LOG_PATTERN` le fait sans configuration dès qu'un `Tracer`
est présent). Rien n'est *exporté*, en revanche, tant que `KEX_AGENT_OTLP_ENDPOINT` ou la
probabilité d'échantillonnage ne sont pas réglés explicitement : une installation sans collecteur
OTLP ne doit pas dépenser de cycles à tenter de parler à personne.

`McpTraceContextCustomizer` — un second bean `McpSyncHttpClientRequestCustomizer`, à côté de celui
qui pose le bearer — propage le contexte de trace courant (`traceparent` W3C par défaut) sur les
appels HTTP vers les serveurs MCP. Sans lui, un serveur MCP lui-même instrumenté ouvrirait une
trace détachée de celle qui a déclenché l'appel.

### L'endpoint d'appel direct

<a id="the-direct-tool-endpoint"></a>

`POST /api/agent/mcp/servers/{connection}/tools/{tool}` exécute un outil MCP **sans modèle dans la
boucle**. C'est utile pour tester un serveur ou l'orchestrer depuis du code, et c'est une arme
chargée : rien ne filtre ce qui est appelé, à part le bearer de l'agent et les garde-fous du serveur
MCP lui-même.

Les codes de retour disent quoi :

| Situation | Code |
|---|---|
| Connexion inconnue | `404` |
| Serveur injoignable | `503` |
| Capacité `resources` non exposée (lecture) | `501` |
| Erreur protocole MCP | `502` |
| Échec de l'outil (`isError`) | `200` avec `error: true` |

Le dernier n'est pas une erreur HTTP : l'appel RPC a abouti, c'est l'outil qui a refusé. Confondre
les deux ferait passer « fichier absent » pour « serveur en panne ».

### Sécurité : fermé par défaut

Sans `kex.agent.api-key`, `/api/**` répond `503` — pas `200`, pas `401`. Un `401` laisserait croire
à une erreur d'appelant alors que rien ne peut réussir ; un `200` livrerait un agent ouvert.

Le refus est porté par l'`AuthenticationEntryPoint`, pas par le filtre. Première version : le filtre
court-circuitait en 503, ce qui bloquait aussi `/actuator/health` déclaré en `permitAll` — les
sondes de conteneur tombaient. Le test `ApiSecurityUnconfiguredTest` l'a attrapé, et c'est
maintenant la forme du code qui l'empêche : le filtre authentifie, l'entry point refuse, et il n'est
invoqué que pour une route réellement protégée.

### Plusieurs clés API nommées

`kex.agent.api-key` reste le bearer historique, sous le principal anonyme `kex-agent-api` — il ne
distingue jamais qui a agi, seulement qu'un appelant connaissait le secret partagé. `kex.agent.api-keys`
ajoute des jetons nommés : chaque nom devient le principal authentifié, donc l'acteur réellement
inscrit à l'audit de supervision (`AuditEntry.actor`, `Decision.resolvedBy`), là où un jeton unique
ne le pouvait pas.

`ApiKeyAuthFilter` compare le bearer présenté à chaque jeton connu, en temps constant
(`MessageDigest.isEqual`) pour chacun — un nombre de clés qui reste celui d'une poignée d'opérateurs,
pas un annuaire à l'échelle d'un système d'authentification. Les deux sources fusionnent dans
`SecurityConfig.tokensByName` : `api-key` seul reste le comportement historique inchangé, et
`/api/**` ne s'ouvre que si l'une des deux porte au moins un jeton — même posture que le jeton
unique d'avant : rien de configuré, `503`.

### Un échec du fournisseur du modèle ne recopie pas notre propre 401

`AnthropicException` et `OpenAIException` remontaient non attrapées depuis `/chat` et
`/chat/structured` : un `ANTHROPIC_API_KEY` ou `OPENROUTER_API_KEY` faux ou absent atteignait
l'appelant en `401`, **le même code** que celui que rend `ApiKeyAuthenticationEntryPoint` pour un
bearer `kex.agent.api-key` refusé. Un appelant dont le jeton kex était pourtant valide se voyait
répondre comme si c'était lui le problème, sans corps distinguant les deux — reproduit en pointant
l'agent vers une vraie clé Anthropic invalide : `401`, `Content-Length: 0`, rien pour dire lequel
des deux jetons était en cause.

Les deux SDK partagent la même forme : une exception racine par fournisseur
(`AnthropicException`, `OpenAIException`), une sous-classe par code HTTP amont
(`UnauthorizedException`, `RateLimitException`, …). `AgentController` capte les deux racines et
répond `502` — jamais `401`, jamais `429` : ces deux codes sont déjà pris par notre propre couche
(l'entry point d'authentification, `RateLimitFilter`), et les réutiliser pour un échec amont
recréerait exactement l'ambiguïté corrigée ici. Même position que `StructuredOutputException` et
`McpError` juste au-dessus : un fournisseur qui refuse est une panne amont, pas une erreur
d'appelant.

Le flux (`/chat/stream`) n'a pas ce défaut : `onErrorResume` y transforme déjà toute exception en
`event: error` sur un flux `200`, avant même que la question ne se pose.

### Le cycle de supervision ne part que sur demande

Pas de `@Scheduled`. En multi-instance, chaque réplique lancerait son propre cycle : deux analyses
sur les mêmes faits, donc deux décisions et deux exécutions de la même action. Ordonnancer suppose
un verrou partagé, donc une base — une décision d'exploitation qui n'a pas à être prise ici, et qui
serait invisible dans un fichier de configuration.

À l'intérieur d'une instance, un `ReentrantLock.tryLock()` refuse un second cycle concurrent plutôt
que de le mettre en file : une file ne ferait que différer le doublon.

La conversation du cycle est jetable (`supervision-<cycleId>`, purgée en `finally`). Réutiliser un
identifiant ferait grossir la mémoire à chaque exécution jusqu'au plafond, en payant du contexte
pour des faits périmés.

### L'autonomie se règle par capacité, le mode ne peut que restreindre

`SupervisionPolicy.effectiveAutonomy` croise le mode global et l'autonomie déclarée d'une capacité,
et ne retient jamais la plus permissive des deux. Sans cette règle, passer le mode en automatique
ouvrirait d'un coup des actions que quelqu'un avait délibérément mises sous supervision — le genre
d'élargissement qu'on ne voit qu'après coup.

Une capacité absente de la politique est `FORBIDDEN`, pas permissive : même posture que l'API, qui
répond `503` sans clé plutôt que de s'ouvrir. Et le droit d'agir ne suffit pas : sans outil MCP lié
à la capacité, l'exécution échoue en le disant, au lieu de réussir à moitié.

La même doctrine s'applique au plancher de confiance, sur une seconde dimension :
`confidenceThresholdOf` retient le maximum du plancher global et de celui déclaré pour la capacité.
Un réglage par capacité ne peut donc que *durcir*. Laisser l'inverse rendrait le plancher global
illisible — sa valeur ne dirait plus rien tant qu'on n'a pas relu chaque ligne de la politique pour
vérifier qu'aucune ne le contourne. Une capacité qui doit se déclencher plus facilement se traite
en abaissant le plancher global et en relevant celui des capacités coûteuses.

L'écran Agent affiche les deux colonnes — déclarée et effective — et la confiance à partir de
laquelle chaque action autonome part réellement. Régler une autonomie sans voir ce qu'elle donnera
vraiment, c'est régler à l'aveugle. Le champ de plancher affiche d'ailleurs la valeur *appliquée*
et non celle qui est stockée : un réglage sous le plancher global n'a aucun effet, et le laisser à
l'écran rendrait le champ invalide au regard de son propre minimum — formulaire insoumettable, sans
rien pour l'expliquer.

### La sortie du modèle est contrainte, pas garantie

`CycleAnalysis` impose un schéma, puis lit la réponse défensivement. Un champ manquant, un état
inconnu, une confiance à 3,2, un processus inventé : rien de tout cela ne fait échouer le cycle, et
rien n'est deviné. Un état illisible devient `UNKNOWN` — jamais `OK` : une anomalie mal formée ne
doit pas effacer les vingt-trois processus analysés correctement, et un processus dont on ne sait
rien ne doit pas ressembler à un processus sain.

Par la même logique, un processus déclaré mais absent de la réponse reste affiché en `UNKNOWN` au
lieu de disparaître : le faire sortir du tableau le ferait passer pour surveillé alors qu'il ne
l'est pas. `CycleAnalysisTest` fixe chacun de ces cas.

### Un relevé partiel prouve une présence, jamais une absence

Les outils MCP de Kafka SQL Explorer portent une enveloppe `coverage` : ce qui a été lu, ce qui ne
l'a pas été — **nommé, pas compté** — et pourquoi le relevé s'est arrêté. Elle existe pour une
raison précise, que sa propre spécification place en tête de ses risques : un agent qui reçoit une
liste vide conclut « ça n'existe pas » là où la phrase vraie est « ce n'était pas dans ce que j'ai
regardé ».

Notre cycle l'ignorait. Le prompt interdisait d'inventer une valeur, mais rien ne disait au modèle
de lire la couverture : un `kex_trace_key` coupé sur `TIME_BUDGET` pouvait donc ressortir en `OK`,
et un cycle vert masquer une panne restée dans la part non lue.

La règle est maintenant explicite des deux côtés. Le prompt exige de lire `stopReason` avant de
conclure, de suivre les verdicts des outils plutôt que de réinterpréter les nombres, de ne jamais
lire une valeur non mesurée comme un zéro, et de poursuivre un `resumeToken` quand le budget le
permet. Le schéma fait de `coverage` un champ **exigé** de chaque relevé.

Côté lecture, `CycleAnalysis.concluded` applique l'asymétrie qui compte :

- un `OK` rendu sur une passe **explicitement** incomplète redevient `UNKNOWN` — l'anomalie était
  peut-être précisément dans ce qui n'a pas été lu ;
- un `WARNING` ou un `ERROR` tiennent : ce qui a été vu a bien été vu ;
- une couverture simplement **non remontée** (`NOT_REPORTED`) ne dégrade rien. La plupart des
  serveurs MCP ne portent pas d'enveloppe, et tout basculer en `UNKNOWN` rendrait le tableau de
  bord inutilisable partout ailleurs que devant Kafka SQL Explorer. Ni complet, ni déclaré
  incomplet : l'écran le dit au lieu de trancher.

Un `complete: true` sans `stopReason: EXHAUSTED` n'est pas retenu : le drapeau est une opinion du
modèle, le motif d'arrêt est ce que l'outil a réellement rendu.

Comme un relevé partiel donne `UNKNOWN`, il fait basculer l'agent en `DEGRADED` par le chemin qui
existait déjà — un processus dont on ne sait rien ne ressemble pas à un processus sain.

### La vue technique traduit, elle ne recalcule pas

`kafka/` appelle `kex_list_topics` et `kex_consumer_lag` et traduit leur réponse. Toute la
sémantique Kafka reste chez Kafka SQL Explorer : les verdicts de retard (`CAUGHT_UP`, `BEHIND`,
`STALLED`) sont repris **tels quels** plutôt que relus depuis les nombres — c'est l'outil qui sait
qu'un retard sans membre assigné ne se résorbera pas de lui-même, et le recalculer ici reviendrait
à réimplémenter sa sémantique moins bien.

`MeasuredValue` prolonge la même règle que `Coverage` d'un cran : une mesure absente n'est pas
zéro. Un lag à `0` affirme « rattrapé » ; les confondre fait lire un consumer à l'arrêt comme un
consumer à jour. L'écran affiche « non mesuré » avec le motif en infobulle, jamais un tiret
ambigu ni un chiffre.

L'écart entre `groupsExamined` et `groupsInCluster` est affiché pour la même raison : le taire
ferait lire une liste courte comme une liste complète.

Rien n'y lève d'exception vers l'appelant. Un outil absent, un serveur injoignable ou une réponse
illisible produisent une vue vide **qui dit pourquoi**, en `200`. Un serveur MCP absent est une
information d'exploitation, pas une panne de l'agent : rendre une erreur HTTP ferait tomber l'écran
au lieu de l'informer.

Les noms d'outils sont configurables. L'agent peut être branché sur un autre serveur MCP, et une
vue qui échoue en nommant l'outil attendu se règle — une vue qui échoue en silence se contourne.

### Ce que le serveur MCP de Kafka SQL Explorer expose réellement

Vérifié dans son dépôt, pas dans sa spécification : quinze outils, **tous en lecture seule**
(`kex_list_topics`, `kex_describe_topic`, `kex_preview_messages`, `kex_infer_schema`,
`kex_sql_query`, `kex_list_tables`, `kex_trace_key`, `kex_resume_trace`, `kex_compare_traces`,
`kex_deduce_data_model`, `kex_build_join`, `kex_run_audit`, `kex_get_audit`, `kex_consumer_lag`,
`kex_suggest_kpis`). `MutatingMcpTools` est une interface sans implémentation, `readonly` vaut
`true` en dur, et les mutations d'administration sont hors de son périmètre déclaré.

Conséquence pour nos capacités : aucune n'a de contrepartie sur ce serveur. Devant lui, l'agent
observe et recommande — il n'agit pas. Ce n'est pas un cas dégradé, c'est l'état nominal, et le
chemin « aucun outil MCP lié à la capacité » le rend déjà explicite plutôt que de laisser croire à
une exécution.

### Une alerte est un symptôme dédupliqué, pas un relevé

Deux cycles qui voient le même retard sur le même processus signalent un incident, pas deux. Les
anomalies brutes sont regroupées par `processId + titre` : l'alerte porte alors un compteur, une
date de première apparition et la dernière analyse en date.

Seul le dernier cycle décide qu'une alerte est active. Une alerte qui ne réapparaît pas a cessé
d'être vraie, et la laisser à l'écran ferait traiter un incident déjà passé — mais son historique
reste, ce qui distingue un symptôme qui dure d'un pic isolé.

La priorisation est calculée côté serveur, pas dans la console : gravité d'abord, puis nombre de
relevés, puis fraîcheur. Une alerte porte aussi la décision en attente qui lui correspond, pour que
l'action soit à portée de clic plutôt qu'à chercher dans un autre écran.

`GET /api/agent/supervision/anomalies` a été retiré au profit de `/alerts` : un point d'entrée sans
consommateur est de la surface publique à maintenir pour personne. Les relevés bruts restent
internes au service.

### Mesurer l'agent sans inventer de chiffre

`GET /api/agent/supervision/performance` rend ce que l'agent fait de lui-même : cycles, détections,
décisions autonomes contre validations humaines, actions réussies ou en échec, délais.

Trois précautions y sont prises, et ce sont elles qui comptent :

- **Le taux de pertinence ne se calcule que sur les verdicts humains** — approbations contre refus.
  Une exécution autonome n'y entre pas : l'agent ne se confirme pas lui-même. Tant que personne n'a
  tranché, le taux est `null` et l'interface dit pourquoi, là où un `0` se lirait « l'agent se
  trompe toujours ».
- **La durée moyenne d'un cycle n'est pas présentée comme un délai de détection.** Celui-ci se
  compterait depuis le début de l'incident, que rien ici ne connaît. Le délai de dénouement d'une
  décision, lui, est réellement mesuré.
- **Tout porte sur la fenêtre d'historique conservée**, pas depuis le premier jour. L'interface
  l'affiche, faute de quoi un compteur qui retombe passerait pour une amélioration.

`DecisionStatus.EXPIRED` est distinct de `FAILED` pour la même raison : confondre « l'outil a
échoué » et « personne n'a répondu » masquerait un défaut d'organisation en défaut technique.

### L'historique est en mémoire, donc mono-instance

Cycles, anomalies, décisions et audit vivent dans des `History` bornés, en mémoire du processus.
Derrière un load balancer, chaque réplique tiendrait le sien et l'audit serait partiel. C'est
assumé et écrit ici plutôt que masqué : la persistance partagée est une décision d'exploitation,
au même titre que le profil `shared-memory` pour la mémoire de conversation.

L'acteur inscrit à l'audit est le principal authentifié. Avec le seul `kex.agent.api-key`
historique, il désigne le jeton, pas une personne : tracer une identité que le système ne connaît
pas serait une fiction, et l'audit n'en vaudrait rien. `kex.agent.api-keys` nomme les jetons —
chaque nom devient alors le principal, donc l'acteur réellement inscrit. Voir « Plusieurs clés API
nommées » plus bas.

### Une demande de validation expire

Approuvée trois heures après les faits, une action agirait sur une situation qui n'existe plus. Les
demandes dépassant `approval-timeout` basculent en `FAILED` à la lecture suivante, avec leur trace
d'audit. L'expiration est évaluée à la lecture et non par une tâche de fond : sans planificateur,
une tâche de plus serait le seul composant à tourner tout seul.

### L'image est scannée avant de partir, jamais republiée sous un tag déjà pris

`publish.yml` construit l'image deux fois. La première, chargée dans le démon local (`load`) et
jamais poussée, sert au scan Trivy ; la seconde, poussée (`push`), relit le même cache GHA et ne
reconstruit quasiment rien. Pousser d'abord et scanner ensuite publierait une image vulnérable
avant de savoir qu'elle l'est — l'ordre inverse coûte une construction de plus, pas cher au regard
de ce qu'il évite.

Le scan a fait son travail à la première publication réelle (`v0.2.0`) : bloqué avant que rien
n'atteigne un registre, sur trois `CRITICAL` et huit `HIGH`, tous avec un correctif disponible.
Deux natures de correctifs, pas une seule :

- **Dans notre arbre de dépendances** — Tomcat embarqué, géré par Spring Boot via la propriété
  `tomcat.version`. Documentée par Spring Boot pour avancer un composant géré sans attendre sa
  version mineure suivante ; `dependency:tree` confirme que la version corrigée se résout partout
  où Tomcat apparaît.
- **Hors de notre arbre de dépendances** — `pebble`, le superviseur de service de l'image Ubuntu
  de base (`eclipse-temurin:25-jre`), jamais invoqué puisque l'`ENTRYPOINT` lance `java`
  directement en PID 1. Aucune ligne de `pom.xml` ne peut le corriger, et attendre une image amont
  bloquerait chaque publication jusque-là : retiré dans l'étape finale du `Dockerfile`
  (`RUN rm -f /usr/bin/pebble`) plutôt que laissé mort et vulnérable dans une image publique.

`ignore-unfixed: true` ne dispense pas de ces deux-là — il ne laisse passer qu'une CVE **sans**
correctif disponible, celle qu'aucune version de cette image ne peut corriger seule. Une CVE
corrigible bloque, à raison : c'est ce qui vient de se passer.

Le tag `v0.2.0` n'a pas été retagué vers la version corrigée : un tag Git n'est pas récupérable une
fois poussé, et le déplacer casserait la règle que ce même workflow fait respecter à tout le
monde — le pom refuse de publier sous un tag qui ne coïncide pas avec sa propre version. `0.2.1`
porte le correctif sous un nom neuf.

L'image part aussi vers `ghcr.io`, en miroir, dans le même appel `docker/build-push-action` — donc
le même digest que Docker Hub, ce qui dispense de vérifier les deux séparément. Aucun secret de
plus : le jeton d'exécution du workflow (`packages: write`, ajouté au job `publish` seulement)
suffit. Un SBOM est attesté sur l'image poussée (`sbom: true`), interrogeable après coup par
quiconque la tire — à la différence du SBOM que `ci.yml` dépose en artefact de build, qui ne
voyage pas avec l'image publiée.

### La console est servie ouverte, mais n'ouvre rien

`src/main/resources/static/` porte la Control Center : conversation en flux, introspection MCP,
invocation directe d'un outil, base de connaissance, santé. Trois fichiers statiques, aucun
outillage JavaScript — le projet est construit par Maven, et ajouter npm ferait vivre deux chaînes
de build pour trois fichiers.

`GET /`, `/index.html` et `/assets/**` sont en `permitAll`, comme Swagger UI et pour la même raison :
c'est du HTML inerte qui ne porte aucun secret, et l'authentifier empêcherait le navigateur de
charger la page qui *demande* le jeton. Trois garde-fous encadrent cette ouverture, tous tenus par
`ConsoleTest` :

- elle est restreinte au `GET` — un `POST` sur les mêmes chemins reste authentifié ;
- les chemins sont énumérés plutôt que couverts par un joker de racine, pour qu'une future route
  servie ici n'hérite pas de l'ouverture ;
- `/api/**` et `/actuator/prometheus` répondent toujours `401` sans jeton.

Le jeton est saisi dans le navigateur et vit en `sessionStorage` : il disparaît à la fermeture de
l'onglet et n'est jamais écrit côté serveur. Les réponses du modèle et les contenus MCP sont
injectés par `textContent`, jamais par `innerHTML` : ce sont des données non fiables, et un serveur
MCP hostile pourrait sinon placer un XSS sur la même origine que l'API.

### Le système de design tient en quatre règles

`console.css` ne documente ces règles nulle part ailleurs que dans ses propres commentaires,
dispersés au fil des sélecteurs ; les réunir ici évite de les redécouvrir une à une à la prochaine
refonte.

**Des jetons, jamais une couleur en dur.** Tout vit dans des propriétés personnalisées posées sur
`:root` — `--bg`, `--surface`, `--ink`/`--ink-2`/`--ink-3`, `--accent`, et une paire `--ok`/`--warn`/
`--danger`/`--pending` avec leur variante `-soft` pour les fonds teintés. Le thème sombre redéfinit
le même jeu sous `@media (prefers-color-scheme: dark)`, gardé par `:root:not([data-theme="light"])`
pour que le bouton de thème (qui pose `data-theme` sur `<html>`) l'emporte dans les deux sens ; un
bloc `:root[data-theme="dark"]` identique couvre le bascule manuel hors media query. Aucune couleur
n'est écrite ailleurs qu'ici : changer d'accent ou retoucher le sombre se fait à un seul endroit.

**La couleur ne porte jamais l'état seule.** Un glyphe (`●`/`▲`/`✕`/`?`/`◷`) et un libellé
l'accompagnent toujours — `state-tag` et `stateMark` dans `core.js` sont le seul point qui les
associe, et tout ce qui compose sa propre pastille (les marques de KPI dans `supervision.js`, les
chips d'outils de `chat.js`, `.state.error`) réutilise le même glyphe plutôt que d'en inventer un.
Daltonisme et impression noir et blanc sont les deux cas qui font respecter cette règle à la lettre.

**Un accent gauche signale ce qui demande un geste.** `.kpi[data-state]`, `.card[data-state]` et
`.panel[data-state="PENDING"]` partagent le même motif — une bordure gauche de 3 px, neutre par
défaut, teintée quand il y a une raison réelle de la teinter (une alerte active, une décision qui
attend). Un panneau ne porte cet accent que lorsque les données qu'il affiche le justifient ; il ne
décore jamais un état neutre.

**Les icônes sont un jeu de traits SVG en ligne, pas une police.** `index.html` inline chaque icône
de la barre latérale (`viewBox="0 0 24 24"`, épaisseur de trait 1.7, coins ronds) plutôt que de
charger une police d'icônes ou un CDN — même contrainte que le reste de la console : aucun
outillage, aucune dépendance externe à un tiers pour une poignée de glyphes.

### Un tableau qui défile le dit, sans compter sur le chrome du navigateur

`.scroll-x` défilait déjà horizontalement — `overflow-x: auto` suffit — mais rien à l'écran ne le
disait. macOS et la plupart des Chromium masquent la barre tant qu'on n'a pas touché le pavé
tactile ; dans un panneau à moitié de largeur (le tableau compact de la vue d'ensemble, à côté de
« Demande une décision »), la dernière colonne semblait donc simplement coupée au bord.

Deux indices, indépendants l'un de l'autre : `scrollbar-width: thin` (et `::-webkit-scrollbar` en
repli) pose une barre fine mais toujours visible là où le navigateur personnalise le chrome de
défilement ; une ombre peinte avec le contenu tient partout ailleurs, y compris là où ce chrome ne
se laisse pas personnaliser (Safari récent ignore `::-webkit-scrollbar`). Cette ombre superpose
deux dégradés : un halo fixé à l'écran (`background-attachment: scroll`) et un « cache » qui se
déplace avec le contenu (`local`), posé au bord droit du tableau — arrivé en bout de défilement, le
cache glisse par-dessus le halo et l'efface, sans une ligne de JavaScript pour l'observer.

### Le logo est un portrait recadré, pas l'illustration entière

L'image fournie compose un robot, un pictogramme de graphe et une bulle « AI » dans un seul carré —
lisible en illustration, plus du tout à 32 px : à cette taille, une icône ne porte qu'un seul sujet.
`assets/logo.png` est un recadrage serré sur le visage du robot, seul élément qui reste
reconnaissable une fois réduit, exporté une fois pour toutes en 128 px — aucun outillage d'image
n'entre dans la chaîne de build, la même contrainte que pour le reste de la console.

## Ce que les tests couvrent

| Test | Ce qu'il verrouille |
|---|---|
| `McpStreamableHttpIntegrationTest` | Le transport MCP réel, bearer compris, sur un serveur HTTP monté dans le test |
| `ApiSecurityTest` / `…UnconfiguredTest` | 401 / 200 / 503, et la sonde de santé jamais bloquée |
| `McpToolCatalogTest` | Introspection, appel, ressources, serveur injoignable, capacité absente |
| `AgentServiceTest` | Propagation du `conversationId` à l'advisor de mémoire |
| `AgentControllerTest` | Contrat HTTP des 7 routes |
| `SharedMemoryProfileTest` | Le profil `shared-memory` remplace bien le dépôt en mémoire |
| `KexAgentApplicationTests` | Le contexte démarre sans aucun serveur MCP configuré |
| `ConsoleTest` | La console est servie sans jeton, n'ouvre ni `/api/**` ni le `POST`, et appelle les routes qui existent |
| `KafkaViewServiceTest` | La traduction des outils sur des charges utiles conformes à la forme réelle d'Explorer : mesure absente jamais lue comme zéro, verdict repris tel quel, serveur injoignable rendu en vue vide motivée |
| `SupervisionCycleIntegrationTest` | Le cycle jusqu'à un appel d'outil MCP réel : contexte Spring complet, transport streamable-HTTP, bearer, audit. Seul le modèle est simulé |
| `SupervisionServiceTest` | Autonomie, seuil de confiance, expiration, pause, cycle en échec, péremption des données |
| `CycleAnalysisTest` | Ce qui arrive quand le modèle rend autre chose que le schéma demandé, et l'asymétrie de la couverture : un `OK` partiel devient `UNKNOWN`, une erreur partielle reste une erreur |
| `SupervisionControllerTest` | Contrat HTTP du Control Center, dont 409 sur conflit d'état et 404 sur décision inconnue |
| `McpToolCatalogTest` (résilience) | Réessai sur un serveur MCP explicitement injoignable, puis fail-fast une fois le disjoncteur ouvert |
| `AgentServiceTest` / `AgentControllerTest` (disjoncteur) | Le disjoncteur `agent-model` ouvre après plusieurs pannes du fournisseur ; `CallNotPermittedException` rend `503` |
| `McpTraceContextCustomizerTest` | Propagation du contexte de trace courant sur les appels MCP, rien hors d'une trace en cours |
| `ApiKeyAuthFilterTest` | Principal nommé par jeton, jeton historique, jeton inconnu ou en-tête absent |
| `ApiKeyPrincipalTest` | Deux opérateurs nommés distincts dans l'audit de supervision |
