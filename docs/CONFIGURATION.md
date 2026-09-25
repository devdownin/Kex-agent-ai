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
| `max-message-characters` | `4000` | Plafond de taille d'un message **relu** des tours suivants — la fenêtre ci-dessus ne borne qu'un nombre. `0` lève la borne |
| `log-interactions` | `false` | Journalise prompts et réponses — le niveau `DEBUG` que `SimpleLoggerAdvisor` exige est déjà posé, la propriété suffit. Debug uniquement : données sensibles |
| `api-key` | *(vide)* | Bearer de l'API sous le principal anonyme `kex-agent-api`. Vide = API fermée (`503`) |
| `api-keys.<nom>` | *(vide)* | Bearers nommés, en plus ou à la place d'`api-key` : chaque nom devient le principal authentifié, donc l'acteur inscrit à l'audit de supervision |
| `api-key-roles.<nom>` | `CHAT` | Rôle d'un bearer nommé : `CHAT`, `OPERATOR` ou `ADMIN`. La clé historique `api-key` reste administrateur |
| `api-key-tenants.<nom>` | *(le nom)* | Locataire d'une clé : l'espace où vivent ses souvenirs, compétences, charte et automatisations. Deux clés du même locataire travaillent au même endroit tout en restant deux acteurs distincts à l'audit |
| `oidc.username-claim` | `preferred_username` | Claim porteur de l'acteur inscrit à l'audit. Repli sur `sub` |
| `oidc.roles-claim` | `roles` | Claim des rôles, liste de chaînes ou chaîne séparée par des espaces |
| `oidc.role-mappings.<valeur>` | *(vide)* | Valeur du claim → `CHAT`, `OPERATOR` ou `ADMIN`. Une valeur absente est essayée telle quelle contre ces trois noms, à la casse près |
| `oidc.default-role` | `CHAT` | Rôle d'un jeton valide dont rien ne se traduit — même plancher qu'une clé absente d'`api-key-roles` |
| `oidc.tenant-claim` | *(vide)* | Claim porteur du locataire. Vide, tous les porteurs partagent `default-tenant` |
| `oidc.default-tenant` | `default` | Locataire de tous les porteurs de jeton quand `tenant-claim` est vide |
| `request-timeout` | `120s` | Attente maximale d'un échange, tours d'outils compris |
| `rate-limit.enabled` | `true` | Limite de débit sur `/api/agent/chat`, `/chat/stream` et l'invocation directe `POST /mcp/servers/{connection}/tools/{tool}` — l'introspection MCP en lecture reste libre |
| `rate-limit.requests-per-minute` | `60` | Débit soutenu, **par principal authentifié** — un seau par nom d'`api-keys`, un seul avec `api-key`. Sous `shared-memory` le seau est partagé entre répliques et le seuil vaut pour l'installation ; sinon chaque réplique tient le sien |
| `rate-limit.burst` | `20` | Pointe tolérée au-delà du débit soutenu, par principal également |
| `memory.enabled` | `true` | Outils `remember_fact` / `recall_facts` (mémoire long-terme, distincte de `max-history-messages`) |
| `memory.capacity` | `200` | Souvenirs actifs conservés, le plus ancien évincé au-delà |
| `memory.max-content-length` | `500` | Un souvenir plus long est tronqué à l'écriture |
| `memory.retention` | `30d` | Au-delà, un souvenir n'est plus relu (sans être supprimé) |
| `token-budget.daily-limit` | `0` | Jetons consommés par jour, toutes conversations confondues. `0` lève la borne. Tenu en mémoire, par instance — comme `rate-limit` ci-dessus |

### `kex.agent.supervision.*` (nouveautés)

Le reste de la politique de supervision (mode, autonomie, seuils, actions) est décrit dans
`docs/ARCHITECTURE.md` et dans les commentaires d'`application.yml`. Trois blocs, ajoutés pour
qu'un cycle puisse partir sans qu'un humain clique :

| Propriété | Défaut | Rôle |
|---|---|---|
| `schedule.enabled` | `false` | Départ autonome du cycle. N'a d'effet que sous le profil `shared-memory`, seul endroit où le verrou partagé qu'il exige peut exister |
| `schedule.interval` | `5m` | Délai entre deux tentatives — une tentative, pas forcément un cycle : une réplique qui ne tient pas le verrou repart aussitôt |
| `schedule.lock-at-most-for` | `10m` | Expiration du verrou si la réplique qui le tenait tombe en plein cycle |
| `notify.webhook-url` | *(vide)* | Débouché par défaut de `NOTIFY` sans outil MCP lié — la seule capacité qui en a un |
| `notify.webhook-token` | *(vide)* | Bearer optionnel envoyé au webhook |
| `auto-adjust.enabled` | `true` | Relève tout seul le plancher de confiance d'une capacité qui se fait rejeter plus souvent qu'approuver |
| `auto-adjust.min-samples` | `5` | Verdicts humains minimum avant de tirer une conclusion |
| `auto-adjust.min-relevance` | `0.5` | En-deçà de ce taux d'approbation, le plancher est relevé |
| `auto-adjust.increment` | `0.05` | Ce qui est ajouté à chaque relèvement, jamais au-delà de `1.0` |
| `correlation.enabled` | `true` | Signale un incident corrélé quand plusieurs processus sont en anomalie au même cycle |
| `correlation.min-processes` | `3` | Nombre de processus distincts à partir duquel la concomitance est signalée — une heuristique grossière, pas un diagnostic |
| `simulate-unbound-actions` | `false` | Une capacité sans outil MCP lié se résout en `SIMULATED` plutôt qu'en `FAILED` — ce que l'agent aurait fait, sans le faire, pour calibrer la confiance avant qu'un exécuteur réel n'existe |

### `kex.agent.supervision.processes[].thresholds`

Seuils propres à un processus précis, en plus des seuils globaux ci-dessus. Chaque champ omis
hérite du seuil global — voir `Thresholds.withOverrides` — pas de valeur par défaut propre :

```yaml
kex.agent.supervision.processes[0].thresholds.consumer-lag: 5000
```

Un processus à faible trafic et un à fort débit ne devraient pas partager le même seuil : trop
sensible pour l'un, trop laxiste pour l'autre.

### Créer un processus depuis la conversation

Dans **Conversation → Créer avec l’assistant guidé**, renseigner le nom, l’objectif et au moins le
topic source. Les topics connus et les groupes liés au topic source sont suggérés si KafkaExplorer
est disponible ; une liste incomplète ou indisponible n’empêche pas la saisie manuelle. Avant
enregistrement, vérifier l’identifiant et le `hint` proposés. Seul un principal **ADMIN** peut
confirmer **Créer ce processus**. Le processus apparaît immédiatement avec l’état `UNKNOWN`, puis
est observé au prochain cycle. Le modèle n’enregistre rien depuis un simple message de chat.

`GET /api/agent/supervision/processes/definitions` rend les déclarations effectives, et
`POST /api/agent/supervision/processes` (ADMIN) accepte `id`, `name`, `description` et `hint`.
L’API refuse les identifiants existants avec `409` et les entrées invalides avec `400`.

Les déclarations de `kex.agent.supervision.processes` restent valables. Les processus créés dans
la console s’ajoutent à cette liste, sans la modifier : en mono-instance ils sont conservés dans
`kex.agent.supervision.process-store-path` (par défaut
`${user.home}/.kex-agent-ai/processes.json`, `/var/lib/kex/processes.json` dans l’image Docker).
Sous le profil `shared-memory`, ils sont stockés dans PostgreSQL pour être visibles de toutes les
répliques. Prévoir la sauvegarde du volume ou de la base comme pour les autres données de l’agent.

### Fenêtres de maintenance

Pas une propriété de configuration : déclarées à l'exécution, pour un déploiement qu'on n'a pas
forcément anticipé au démarrage.

| Endpoint | Rôle |
|---|---|
| `POST /api/agent/supervision/processes/{id}/maintenance` | `{"duration":"PT2H","reason":"..."}` — le processus ne produit ni alerte ni décision jusqu'à l'échéance |
| `DELETE /api/agent/supervision/processes/{id}/maintenance` | Lève la fenêtre avant terme |

L'état réel du processus reste relevé et affiché normalement : la fenêtre mute l'alerte et la
décision, jamais l'observation.

### `kex.agent.supervision.schedule.adaptive.*`

Cadence du cycle autonome indexée sur ce que le dernier a vu. Éteinte par défaut — l'intervalle reste
alors seul maître, comme avant.

```yaml
kex.agent.supervision.schedule.adaptive:
  enabled: true
  degraded: 5m    # délai minimum entre deux cycles après une anomalie, ou après un cycle en échec
  healthy: 30m    # délai minimum quand le dernier cycle n'a rien vu
```

`interval` reste le battement : c'est lui qui rythme les tentatives, et chaque battement décide
seulement s'il y a lieu d'analyser. Une cadence unique doit choisir entre réagir vite à un incident et
ménager le budget de jetons journalier quand rien ne bouge ; ces deux délais lèvent ce compromis. Un
cycle en échec compte comme dégradé : il n'affirme rien sur l'état du cluster.

### `kex.agent.supervision.learning.*`

Ce que l'agent tire des refus humains, au-delà du plancher de confiance.

```yaml
kex.agent.supervision.learning:
  enabled: true
  min-refusals: 3   # refus concordants sur une même capacité avant de proposer quoi que ce soit
  window: 30d
```

Au-delà du seuil, une compétence candidate reprend les motifs de refus et part en revue comme toute
compétence — jamais active d'elle-même. Elle appartient à l'opérateur dont les verdicts l'ont
provoquée. Sans `kex.agent.memory.enabled`, personne n'écoute : les refus ne font alors que durcir le
plancher, comme avant.

### `kex.agent.memory.skills.*`

Ce que la bibliothèque de compétences approuvées pèse dans le prompt.

```yaml
kex.agent.memory.skills:
  injected: 5                # compétences réellement injectées, les plus récemment approuvées
  characters-per-skill: 3000
  stale-after: 90d           # au-delà, signalée comme candidate à la revue — jamais retirée seule
```

| Endpoint | Rôle |
|---|---|
| `GET /api/agent/skills/curation` | Ce qui agit, ce qui dort au-delà du plafond, les doublons de titre, ce qui n'a plus été revu |
| `GET /api/agent/skills/review-queue` | `ADMIN`. Tout ce qui est `PENDING`, tous propriétaires confondus, le plus ancien d'abord — la revue (`approve`/`reject`) reste elle-même transverse, voir ARCHITECTURE.md |
| `POST /api/agent/skills/{id}/retire` | `{"reason":"..."}` — `ADMIN`, motif obligatoire, tracé dans l'audit |

Le curateur signale, il ne retire rien de lui-même : retirer une compétence change le comportement de
toutes les conversations suivantes du même propriétaire, exactement ce qu'une approbation humaine
nommée existe pour trancher.

La console les réunit avec la charte et la curation sous l'onglet **Gouvernance** de la vue Agent —
un seul écran plutôt que trois entrées de navigation pour une question unique : que peut dire
l'agent de plus que son prompt système, et qui l'a autorisé.

`GET /api/agent/whoami` rend `{"name","tenant","roles"}` du principal courant — ouverte à `CHAT`
comme aux autres rôles, une lecture pure n'étant pas un privilège de plus que converser. C'est elle
qui alimente le menu du jeton dans la console : jusqu'ici, savoir à quel locataire sa propre clé
appartenait exigeait de relire la configuration du serveur.

### `POST|GET /api/agent/charter`

Consignes durables de l'exploitant, éditables sans redéployer, là où le prompt système reste figé au
démarrage.

| Endpoint | Rôle |
|---|---|
| `GET /api/agent/charter` | La charte en vigueur, avec son auteur et le motif du dernier changement |
| `GET /api/agent/charter/versions` | L'historique, de la plus récente à la plus ancienne |
| `PUT /api/agent/charter` | `{"markdown":"...","reason":"..."}` — `ADMIN`, motif obligatoire |

La charte entre en tête du contexte durable, sous le même avertissement que les résumés et les
compétences : donnée de référence, jamais gouvernance. Elle peut restreindre ce que l'agent propose,
jamais élargir une autonomie, une permission ou une approbation.

### `kex.agent.channels.inbound.*`

Approuver ou refuser une demande de validation depuis la messagerie qui l'a annoncée. **Éteint par
défaut**, et c'est la seule route de l'API qui tranche sans bearer — Slack et Teams n'en émettent pas.

```yaml
kex.agent.channels:
  enabled: true
  inbound:
    enabled: true
    secret: "${KEX_CHANNEL_INBOUND_SECRET}"  # exigé : sans lui, la route refuse de démarrer
    tolerance: 5m                            # fenêtre d'horodatage acceptée
    operators:
      U0123456789: alice                     # identifiant chez le fournisseur → acteur d'audit
```

L'appelant signe `<horodatage>.<corps brut>` en HMAC-SHA256 avec ce secret, et transmet :

| En-tête | Contenu |
|---|---|
| `X-Kex-Timestamp` | Secondes Unix, dans la fenêtre `tolerance` |
| `X-Kex-Signature` | HMAC-SHA256 en hexadécimal |

```json
{"sender": "U0123456789", "decisionId": "…", "approve": false, "reason": "pas en heures ouvrées"}
```

`sender` est l'identifiant chez le fournisseur, jamais un nom d'affichage — celui-ci se change. Un
expéditeur non déclaré dans `operators` est refusé plutôt que rattaché à un acteur générique : sans
cela, l'audit dirait « approuvé par slack », qui n'est pas une personne. La réponse ne distingue pas
les motifs de refus — dire « signature invalide » plutôt que « horodatage hors fenêtre » renseignerait
qui tâtonne ; le détail reste dans les journaux.

### `kex.agent.channels.slack-signing-secret` — interactivité Slack

Sans elle, `SlackChannelAdapter` n'envoie qu'un lien vers la console, comme avant que cette
propriété existe. Avec elle, une approbation gagne deux boutons — Approuver, Refuser — qui postent
directement à Kex depuis Slack :

```yaml
kex.agent.channels:
  enabled: true
  slack-webhook-url: "${SLACK_WEBHOOK_URL}"
  slack-signing-secret: "${SLACK_SIGNING_SECRET}"   # émis par Slack, pas choisi par l'exploitant
  inbound:
    enabled: true                                    # requis : c'est operators qui résout l'acteur
    secret: "${KEX_CHANNEL_INBOUND_SECRET}"
    operators:
      U0123456789: alice
```

Le secret vient de Slack lui-même, à la création de l'App, une fois « Interactivity & Shortcuts »
activée avec une URL de requête pointant vers `POST /api/agent/channels/slack/interactivity` —
un webhook entrant seul ne le porte pas. `operators` est la même table que pour l'entrée générique
ci-dessus : un identifiant Slack qui en est absent est refusé, jamais rattaché à un acteur
générique. La signature suit l'algorithme que Slack documente pour son propre mécanisme
(`X-Slack-Signature`, `X-Slack-Request-Timestamp`), distinct de celui de l'entrée générique — les
deux secrets ne s'échangent pas.

Teams n'a pas d'équivalent : une Adaptive Card ne livre nulle part sans une inscription complète au
Bot Framework, bien au-delà d'un webhook. Son message reste un lien vers la console.

### `kex.resilience.*`

Disjoncteur et réessai des intégrations externes (serveurs MCP, fournisseur du modèle) — voir
ARCHITECTURE.md. Les exceptions qui comptent comme échec ou déclenchent un réessai sont fixées en
code par instance (`mcp-tool-<connexion>` par serveur MCP pour l'appel direct, `mcp-tool` pour le
chemin piloté par le modèle, `agent-model`), pas ici — ces réglages s'appliquent à toutes les
instances créées sous ces noms.

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

Le filtrage par préfixe évite qu'un jeton parte vers un serveur MCP autre que le sien. Un préfixe
sans jeton (le défaut pour Kafka Explorer, qui ne demande aucune authentification) est ignoré en
silence. Un jeton déclaré sans préfixe, en revanche, ne s'appliquera jamais à aucune requête et est
ignoré avec un avertissement au démarrage (`WARN McpBearerTokenCustomizer`) — sans lui, cette
entrée-là se découvrait par le 401 du serveur distant, sans qu'aucun journal ne rapproche l'un de
l'autre.

### `kex.mcp.health-check`

| Propriété | Défaut | Rôle |
|---|---|---|
| `enabled` | `true` | Retente périodiquement l'initialisation des clients MCP encore muets |
| `interval` | `1m` | Délai entre deux tentatives |

Sans lui, un serveur MCP revenu après une panne ne redevient joignable qu'au prochain appel — la
prochaine ouverture de la vue technique, ou le prochain outil que le modèle choisit d'invoquer.
Chaque réplique retente ses propres clients, sans verrou : contrairement au cycle de supervision, ce
n'est pas une action partagée qui partirait en double.

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
| `shared-memory` | Mémoire de conversation **et audit de supervision** en PostgreSQL, schéma créé au démarrage |
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

Le même bascule fait passer en base tout ce dont la divergence entre répliques produirait une
action fausse, et rien d'autre. Schéma posé par `db/migration/V1__baseline.sql`, sous Flyway —
voir « Le schéma des tables propres à l'agent est versionné » dans ARCHITECTURE.md :

| Table | Contenu | Pourquoi elle n'est pas restée en mémoire |
|---|---|---|
| `kex_supervision_audit` | L'audit (`GET /api/agent/supervision/audit`) | Une pièce de conformité partielle ne vaut rien |
| `kex_supervision_decision` | Décisions, avec leur réservation d'exécution | Une décision qui n'existe que sur une réplique rend `404` à l'opérateur qui approuve depuis une autre |
| `kex_supervision_flag` | La pause de l'agent | En pause sur une réplique, l'agent continuait d'agir depuis les autres |
| `kex_supervision_maintenance` | Les fenêtres de maintenance | Déclarée sur une réplique, elle ne taisait les alertes que là |
| `kex_supervision_lock` | Le verrou du cycle autonome | Sans lui, chaque réplique lancerait le sien — voir `SupervisionScheduler` |
| `kex_rate_limit` | Les seaux à jetons | Trois répliques accordaient trois fois le seuil annoncé |
| `kex_agent_memory`, `kex_agent_learning` | Mémoire long terme, compétences, charte | Voir « Mémoire partagée » ci-dessus |
| `kex_automation`, `kex_automation_audit` | Automatisations planifiées | Un cycle qui part sans clic n'a de sens qu'avec un état partagé |

Cycles, anomalies brutes et relevés de processus restent en mémoire du processus : leur divergence
se voit et ne coûte qu'un rafraîchissement — voir « Ce qui peut diverger entre répliques, et ce qui
ne le peut pas » dans ARCHITECTURE.md.

### Authentification OIDC

Les clés API restent le défaut et continuent de fonctionner. Déclarer un émetteur ajoute une
seconde forme d'authentification sur le même en-tête :

```bash
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://idp.exemple.fr/realms/exploitation KEX_AGENT_OIDC_ROLE_MAPPINGS_KEX_ADMINS=ADMIN KEX_AGENT_OIDC_ROLE_MAPPINGS_KEX_OPS=OPERATOR ./mvnw spring-boot:run
```

L'audit inscrit alors le claim de nom — une personne — là où une clé ne nomme qu'une intégration.
Sans `issuer-uri` (ou `jwk-set-uri`), Spring Boot ne déclare aucun `JwtDecoder` et la chaîne JWT
n'est pas branchée : rien ne change pour une installation existante.

### Stockage durable en conteneur

L'image pose `/var/lib/kex` (`KEX_AGENT_MEMORY_STORAGE_DIRECTORY`,
`KEX_MCP_RUNTIME_STORAGE_PATH`) et le déclare en `VOLUME`. `docker-compose.yml` y monte un volume
nommé. Hors Docker, la valeur par défaut reste `${user.home}/.kex/memory`.

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

### Évals de jugement du modèle

Exclus de `./mvnw verify` (`@Tag("eval")`) : ils appellent un vrai fournisseur, donc payants et
non déterministes. Trois scénarios (couverture incomplète, mesure absente, verdict d'outil
réinterprété) à rejouer à la main après un changement de modèle ou de prompt de supervision :

```bash
ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Dtest=ModelJudgmentEvalTest -DexcludedGroups=
```

Sans `ANTHROPIC_API_KEY`, le test se déclare simplement désactivé plutôt que d'échouer
(`@EnabledIfEnvironmentVariable`). Voir « Un éval, pas un test, pour le jugement du modèle » dans
ARCHITECTURE.md.

<a id="publier-l-image"></a>

## Publier l'image

Le workflow `publish.yml` pousse sur Docker Hub **et sur GHCR, en miroir** — le même digest sous
`ghcr.io/devdownin/kex-agent-ai`, sans secret de plus : GHCR se pousse avec le jeton que GitHub
fournit déjà à chaque exécution. **Rien n'est saisi au déclenchement** : sur un tag `vX.Y.Z`, la
version publiée est celle du tag — le pom du checkout est réaligné dessus avant de construire,
jamais commité. Le pom sur `main` garde sa propre valeur ; il n'y a plus de bump à synchroniser à
la main avec le tag.

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
git tag v0.2.0
git push origin main --follow-tags
```

Rien de plus : `main` porte la prochaine version `-SNAPSHOT`, puis le workflow réaligne le pom sur le
tag dans son propre checkout (`versions:set`, jamais commité). La même version alimente le JAR,
`/actuator/info` et l'identité du client MCP. Le tag reste refusé s'il porte `SNAPSHOT` : un tag n'est
pas récupérable une fois poussé, et une image dont le contenu change sous le même nom ne veut rien
dire.

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

La page du dépôt Docker Hub est poussée depuis [`DOCKERHUB.md`](../DOCKERHUB.md), pas depuis le
`README.md` : la vitrine du dépôt s'adresse à qui lit le code, la page du registre à qui lance
l'image sans l'avoir clonée — tags disponibles, variables d'environnement, sondes. Les liens relatifs
du README, eux, ne mènent nulle part depuis Docker Hub.

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

## Extensions governed by default

- `kex.agent.channels.enabled=false` by default. When enabled, configure only HTTPS Slack/Teams webhooks or SMTP recipients; approval links always return to the authenticated console.
- `kex.agent.automation.enabled=false` by default and requires `shared-memory`. Set `kex.agent.automation.read-only-tools` explicitly; an empty allowlist refuses startup rather than allowing arbitrary MCP tools.
- `kex.models.enabled=false` by default. Routes are operator-owned under `kex.models.routes`; `local-only=true` rejects hosted endpoints. `application-ollama.yml` and `application-vllm.yml` provide local OpenAI-compatible profiles.
- `kex.mcp.server.enabled=false` by default. If enabled, set explicit `kex.mcp.server.allowed-origins` values.
- `kex.mcp.runtime.isolation.enabled=false` par défaut : une commande STDIO enregistrée s'exécute
  alors directement sur l'hôte, pas dans le conteneur `docker run --read-only --network=none
  --cap-drop=ALL` que `IsolatedStdioCommand` sait construire. La première ligne de défense reste
  `kex.mcp.runtime.allowed-stdio-commands` (`KEX_MCP_STDIO_ALLOWED_COMMANDS`, liste blanche vide par
  défaut) : une clé ADMIN compromise ne devient pas un shell arbitraire, isolation activée ou non.
  Activer l'isolation ajoute une seconde ligne, mais exige alors une image approuvée par commande
  autorisée (`kex.mcp.runtime.isolation.images`) — sans repli sur l'hôte si aucune image n'est
  déclarée pour la commande.
- Automatic procedures are stored as pending skills and need an authenticated operator review before they enter durable context.
- `kex.mcp.catalog.sources.docker.enabled=false` et `kex.mcp.catalog.sources.official-registry.enabled=true`
  par défaut : lister `GET /api/agent/mcp/catalog/discover` consulte le registre officiel MCP.
  Définir `KEX_MCP_CATALOG_OFFICIAL_REGISTRY_ENABLED=false` pour désactiver cet accès réseau ;
  Docker Hub reste désactivé tant que l'opérateur ne l'active pas. Voir `docs/MCP.md#découvrir-des-serveurs-depuis-des-registres-externes`
  pour la note de confiance et les critères éliminatoires qui gardent l'installation ADMIN-seule.
