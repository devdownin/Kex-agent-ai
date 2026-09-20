# Audit des fonctionnalités existantes

Constats vérifiés sur le dépôt à la date de cet audit, chacun avec sa référence `fichier:ligne`.
Ce document liste des observations et des pistes ; il ne corrige rien lui-même — voir la colonne
« Suggestion » de chaque section pour la piste retenue, à trancher au cas par cas.

`docs/ARCHITECTURE.md` documente en détail `web/`, `agent/`, `mcp/` (le cœur), `config/` (le
choix du fournisseur), `supervision/`, `kafka/`, `memory/MemoryService` et la console. Cet audit
porte volontairement sur ce qui n'y figure pas encore : `automation/`, `channels/`, `isolation/`,
`knowledge/`, `skills/`, le second système de mémoire (`memory/Learning*`, `LongTermMemoryService`),
`mcp/catalog/`, `mcp/server/`, et le routage multi-modèles (`config/LlmRouting*`).

## 1. Sécurité

### 1.1 Aucun filtrage d'URL privée/loopback/metadata sur l'ajout d'un serveur MCP (SSRF)

`McpToolCatalog.validatedBaseUrl` (`src/main/java/com/kex/agent/mcp/McpToolCatalog.java:835-846`)
vérifie le schéma (`http`/`https`), l'absence d'`userinfo`/`query`/`fragment`, mais **rien** sur
l'hôte lui-même : `127.0.0.1`, `169.254.169.254` (métadonnées cloud) ou une adresse du réseau
interne du conteneur passent la validation. `POST /api/agent/mcp/servers` est protégé par
`ADMIN` (`SecurityConfig.java:77-80`), mais un jeton `ADMIN` compromis — ou une CI mal isolée —
peut ainsi faire sonder par l'agent n'importe quelle adresse interne sous couvert d'« ajouter un
serveur MCP ». Aucun test ne couvre ce cas (`McpToolCatalogTest` ne contient aucun scénario
d'URL privée).

**Suggestion** : liste noire d'IP (loopback, lien-local, plages privées RFC 1918, métadonnées
cloud connues) dans `validatedBaseUrl`, avec un test dédié — dans le même esprit que
`kex.mcp.runtime.allowed-stdio-commands`, déjà une liste blanche pour le cas STDIO.

### 1.2 Approuver une compétence a le même pouvoir qu'écrire dans la base de connaissance, sans la même exigence de rôle

`POST /api/agent/knowledge` est explicitement réservé à `ADMIN`
(`SecurityConfig.java:76`) parce que son contenu pèse sur chaque analyse future. Or
`POST /api/agent/skills/{id}/approve` (`SkillsController.java:46`) a exactement le même effet —
son Markdown est injecté durablement dans le prompt système de chaque conversation future du même
propriétaire (`LongTermMemoryService.context`, appelé depuis `AgentService.java:216-218`) — et
n'a **aucune** ligne dédiée dans `SecurityConfig.java` : la route retombe sur le filtre générique
`/api/agent/**` → `OPERATOR`/`ADMIN` (`SecurityConfig.java:87`). Un `OPERATOR` peut donc faire ce
qu'un `ADMIN` seul peut faire côté connaissance.

**Suggestion** : aligner `POST /api/agent/skills/*/approve` sur `ADMIN`, comme
`POST /api/agent/knowledge`.

### 1.3 La suppression d'un résumé durable échappe à la règle `ADMIN` de la mémoire

`DELETE /api/agent/memory/*` exige `ADMIN` (`SecurityConfig.java:85-86`). Mais
`DELETE /api/agent/memory/summaries/{id}` (`LongTermMemoryController.java:31-36`) a un segment de
plus : avec `AntPathMatcher`, `*` ne franchit pas `/`, donc ce chemin ne correspond pas au motif et
retombe sur `/api/agent/**` → `OPERATOR`/`ADMIN`. Un `OPERATOR` peut supprimer un résumé durable
alors qu'il ne peut pas supprimer un simple fait court — l'inverse de l'intention lisible dans le
reste du fichier.

**Suggestion** : ajouter `/api/agent/memory/summaries/*` au motif `ADMIN` existant.

### 1.4 Isolation Docker des serveurs MCP STDIO : désactivée par défaut

`StdioIsolationProperties.defaults().enabled()` vaut `false`. Sans l'activer, une commande STDIO
enregistrée (`kex.mcp.runtime.allowed-stdio-commands`) s'exécute directement sur l'hôte plutôt que
dans le conteneur `--read-only --network=none --cap-drop=ALL` que `IsolatedStdioCommand` sait
construire. La liste blanche de commandes reste le premier garde-fou (une clé `ADMIN` compromise
ne devient pas un shell arbitraire), mais l'installation par défaut n'a pas la seconde ligne de
défense.

**Suggestion** : documenter ce choix dans `docs/CONFIGURATION.md` à côté de
`allowed-stdio-commands`, pour qu'il soit délibéré plutôt que découvert.

### 1.5 Contrôle du rôle `ADMIN` du catalogue MCP porté par le contrôleur, pas par la chaîne de filtres

`McpCatalogController` vérifie lui-même le rôle `ADMIN` pour `POST /{id}/install`
(`McpCatalogController.java:40`), correct fonctionnellement, mais c'est la seule route mutante de
`mcp/` sans ligne dédiée dans `SecurityConfig.java` (les autres — `/mcp/servers`,
`/mcp/servers/*/secret`, etc. — l'ont toutes explicitement). Aucun test d'intégration ne vérifie
que la chaîne de filtres Spring Security elle-même refuse cette route à un non-`ADMIN` — seul le
contrôleur est testé.

**Suggestion** : ajouter la ligne dans `SecurityConfig.java` par cohérence avec le reste de
`mcp/`, même si le comportement actuel est déjà correct.

### 1.6 Dérivation de clé sans sel pour le stockage chiffré des serveurs MCP

`EncryptedMcpServerStore` chiffre en AES-256-GCM avec nonce aléatoire par écriture et fichier en
`rw-------` (`EncryptedMcpServerStore.java:39-127`) — correct. La clé, elle, vient de
`SHA-256(passphrase)` sans sel ni KDF lente (`:109-117`). Acceptable pour une passphrase
d'opérateur fournie hors bande, mais sans défense en profondeur si cette passphrase est faible.

**Suggestion** : PBKDF2/Argon2 sur `kex.mcp.runtime.storage-key`, en gardant la compatibilité
ascendante (ou en la cassant explicitement avec une note de migration, la fonctionnalité étant
récente).

## 2. Cohérence fonctionnelle

### 2.1 Deux systèmes de « mémoire long-terme » sous un seul nom

Le nom « mémoire long-terme » est déjà pris dans le code et la documentation par
`MemoryService`/`remember_fact`/`recall_facts` (`MemoryProperties.java:14`,
`application.yml:176`, `docs/ARCHITECTURE.md:295`). Un second système, interne à la classe
`LongTermMemoryService`, existe en parallèle et n'apparaît dans aucune doc :

- `MemoryService.remember` n'écrit que sur appel explicite de l'outil par le modèle — une garde
  d'écriture délibérée (`docs/ARCHITECTURE.md:295-308`).
- `LongTermMemoryService.recordSuccessfulTask` écrit, elle, **automatiquement** un résumé après
  chaque échange bloquant réussi (`AgentService.java:116-117,141-143`), sans outil ni choix du
  modèle — l'exact contraire de la garde documentée pour l'autre système.

Les deux sont pourtant gardées par le **même** indicateur `kex.agent.memory.enabled`
(`LearningConfig.java:18,22,28`, `MemoryConfig.java:22`) et réutilisent la même paire
`capacity`/`retention` de `MemoryProperties` (`LearningConfig.java:24,30`) — deux philosophies
opposées sous un seul interrupteur et un seul jeu de seuils.

**Suggestion** : documenter les deux séparément dans `ARCHITECTURE.md`, et évaluer si une
propriété `kex.agent.learning.*` dédiée (capacité, rétention, activation) ne devrait pas se
distinguer de `kex.agent.memory.*`.

### 2.2 Le chemin par défaut de la console n'alimente jamais le second système de mémoire

`AgentService.streamForTask` (`AgentService.java:165-194`, ce que `/chat/stream` — « la console
l'emprunte par défaut », commentaire `AgentService.java:154` — expose) appelle bien
`request(...)`, qui injecte `longTermMemory.context(...)` (lignes 216-218), mais n'appelle
**jamais** `longTermMemory.recordSuccessfulTask(...)`, contrairement à `ask`/`askStructured`
(lignes 116-117, 141-142). En usage normal via la console, aucun résumé ni aucune proposition de
compétence n'est donc jamais enregistré — seuls les appels via l'API bloquante (`/chat`,
`/chat/structured`) le déclenchent. Vérifié : `AgentServiceTest` ne teste ce branchement dans
aucun des deux sens.

**Suggestion** : soit brancher `recordSuccessfulTask` sur la fin du flux (dans `doFinally`, avec le
texte accumulé), soit documenter explicitement que le chemin flux n'alimente pas ce système —
l'état actuel silencieux est la pire des deux options.

### 2.3 `pom.xml` cible Java 21, tout le reste annonce Java 25

`pom.xml:19,24` (`<java.version>21</java.version>`, `<maven.compiler.release>21</maven.compiler.release>`)
contredit `CLAUDE.md`, `README.md`, `README.fr.md`, `docs/CONFIGURATION.md` et les quatre workflows
CI, qui épinglent tous JDK 25. Le build fonctionne (JDK 25 est ascendant-compatible avec
`release: 21`), donc ce n'est pas un défaut de fonctionnement, mais la mention de version fixe dans
le seul fichier qui devrait faire foi.

**Suggestion** : aligner `pom.xml` sur `25`.

### 2.4 `automation/` : erreurs métier rendues en 500 générique

`AutomationController` ne déclare aucun `@ExceptionHandler`, contrairement à `AgentController`
qui mappe systématiquement ses exceptions métier. Une expression cron invalide
(`AutomationSchedule.java:18-29`), une automatisation inconnue (`UnknownAutomationException`,
`JdbcAutomationRepository.java:76`) ou un conflit d'exécution (`IllegalStateException`,
`JdbcAutomationRepository.java:94,106`) remontent donc toutes en `500`, là où le reste de `web/`
rend `400`/`404`/`409`.

**Suggestion** : les mêmes `@ExceptionHandler` que `AgentController`, adaptés à ces trois cas.

### 2.5 `automation/` : bail d'exécution indépendant du plafond réel de la tâche

`AutomationService.tick()` borne la réclamation par `kex.agent.automation.timeout` (défaut 2 min,
`JdbcAutomationRepository.java:126-143`), mais l'exécution elle-même (`AgentService.askReadOnly`)
est bornée séparément par `kex.agent.request-timeout` (défaut 120 s), sans relation garantie entre
les deux. Si le bail expire avant la fin réelle du tour LLM, une autre réplique (le profil visé est
`shared-memory`, donc multi-instance) peut réclamer et exécuter la même automatisation en double.

**Suggestion** : soit dériver le bail de `request-timeout` avec une marge, soit documenter
l'invariant `automation.timeout ≥ request-timeout` et le vérifier au démarrage — dans l'esprit de
`SupervisionScheduleConsistencyCheck`, déjà utilisé pour un couplage de propriétés similaire.

## 3. Fonctionnalités livrées sans façade opérateur

Backend complet et testé côté serveur, mais **aucun** point d'entrée dans la console
(`src/main/resources/static/assets/*.js`) :

| Fonctionnalité | Route | Constat |
|---|---|---|
| Automatisations planifiées | `/api/agent/automations` | Aucune référence dans `resources/static/` ; aucun exemple dans `application.yml` alors que `knowledge`/`supervision`/`memory` en ont |
| Compétences proposées par le modèle | `/api/agent/skills` | Aucun `skills.js` ; pas d'écran d'approbation/rejet |
| Base de connaissance (CRUD documents) | `/api/agent/knowledge` | Seul un indicateur d'état (`llm.js:207`) apparaît côté console, pas de gestion des documents |
| Résumés durables | `/api/agent/memory/summaries` | Absents de `memory.js`, qui ne couvre que `MemoryService` |
| Catalogue MCP recommandé | `/api/agent/mcp/catalog` | Fonctionnel et testé (`McpCatalogControllerTest`), zéro appel dans `tools.js` |
| Canaux de notification (Slack/Teams/e-mail) | — (configuration seule) | Pas d'écran listant les canaux actifs, contrairement à `/supervision/notify/test` qui a son bouton |

**Suggestion** : prioriser par risque plutôt que tout construire — le catalogue MCP et les
compétences approuvées ont un impact direct sur ce que le modèle peut faire ou dire ; les
résumés/automatisations sont d'abord un manque de confort opérationnel.

## 4. Tests manquants sur des chemins déjà identifiés comme sensibles

- Aucun test n'exerce `validatedBaseUrl` contre une URL privée/loopback/métadonnées (§1.1).
- Aucun test de sécurité au niveau de la chaîne de filtres (`MockMvc` + rôle) pour
  `/api/agent/skills/**`, `/api/agent/memory/summaries/**`, `/api/agent/automations/**`,
  `/api/agent/mcp/catalog/**`.
- Aucun test de contexte Spring avec `kex.models.enabled=true` **et** un starter de modèle
  classique (Anthropic/OpenAI) actifs simultanément, pour vérifier que le `@Primary` de
  `LlmRoutingConfig` évite bien un conflit de bean `ChatModel`.
- `JdbcAutomationRepositoryTest` : un seul scénario nominal ; rien sur l'isolation par
  propriétaire, la contention sur `claim()`, ni un `claim_id` périmé lors de `finish()`.
- `ChannelNotifierTest` ne teste que la construction des payloads (`:91-99`) — jamais l'envoi HTTP
  réel de `SlackChannelAdapter`/`TeamsChannelAdapter`, ni le traitement d'une réponse non-2xx.
- `AgentServiceTest` ne teste ni le branchement de `LongTermMemoryService` sur `ask`/`askStructured`
  ni son absence sur `stream()` (§2.2).

## 5. Conventions mineures

- `WebhookNotifier.channels(...)` (`WebhookNotifier.java:43-46`) utilise l'injection par
  *setter* (`@Autowired(required = false)`) plutôt que par constructeur — seule dérogation
  observée dans les paquets audités, justifiée par le caractère optionnel du bean mais à documenter
  si elle doit rester une exception à la règle du projet.
- `kex.models.local-provider` (`application-ollama.yml:16`, `application-vllm.yml:16`) est lu
  directement via `Environment.getProperty(...)` dans `LlmViewService.java:123`, hors du mécanisme
  `@ConfigurationProperties` qu'utilisent les dix-sept autres classes de configuration du projet —
  un renommage de cette clé ne serait pas détecté par le binding Spring, seulement à l'exécution.

## Priorisation suggérée

| # | Constat | Impact | Effort |
|---|---|---|---|
| 1.1 | SSRF via URL de serveur MCP | Élevé | Faible (fonction pure + test) |
| 1.2 | Approbation de compétence sous-protégée | Élevé | Faible (une ligne de `SecurityConfig`) |
| 1.3 | Suppression de résumé sous-protégée | Moyen | Faible (une ligne de `SecurityConfig`) |
| 2.2 | Flux console n'alimente jamais l'apprentissage | Moyen | Moyen (choix de conception à trancher) |
| 2.4 | Erreurs `automation/` en 500 | Faible | Faible |
| 2.3 | Java 21 vs 25 dans `pom.xml` | Faible | Trivial |
| 2.5 | Bail d'automatisation découplé du timeout réel | Faible (pas d'exécuteur mutant aujourd'hui) | Moyen |
| 1.4 / 1.6 | Isolation STDIO et KDF, par défaut faibles | Faible aujourd'hui, à revoir si le périmètre s'élargit | Documentation / Moyen |
