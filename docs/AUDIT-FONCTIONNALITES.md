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

### 1.1 Aucun filtrage d'URL privée/loopback/metadata sur l'ajout d'un serveur MCP (SSRF) — **corrigé**

`McpToolCatalog.validatedBaseUrl` (`src/main/java/com/kex/agent/mcp/McpToolCatalog.java:835-846`)
vérifiait le schéma (`http`/`https`), l'absence d'`userinfo`/`query`/`fragment`, mais **rien** sur
l'hôte lui-même : `169.254.169.254` (métadonnées cloud AWS/GCP/Azure/Alibaba) passait la
validation. `POST /api/agent/mcp/servers` est protégé par `ADMIN` (`SecurityConfig.java:77-80`),
mais un jeton `ADMIN` compromis pouvait ainsi faire sonder par l'agent la passerelle de
métadonnées de l'hôte sous couvert d'« ajouter un serveur MCP ».

**Correctif** : `validatedBaseUrl` rejette maintenant un hôte lien-local
(`InetAddress.isLinkLocalAddress()`, couvre `169.254.0.0/16` et `fe80::/10`) — voir
`rejectLinkLocalHost`. Le réseau privé (RFC 1918) et le loopback restent volontairement autorisés :
un serveur MCP de développement tourne couramment sur la même machine ou le même réseau que
l'agent (`McpStreamableHttpIntegrationTest` en enregistre un sur `127.0.0.1` à chaud), et les
restreindre casserait des déploiements légitimes pour un risque que le rôle `ADMIN` couvre déjà —
seule la passerelle de métadonnées, qui n'a aucun usage MCP légitime, est visée. Testé par
`McpToolCatalogTest.refuse_un_hote_en_lien_local`.

### 1.2 Approuver une compétence a le même pouvoir qu'écrire dans la base de connaissance, sans la même exigence de rôle — **corrigé**

`POST /api/agent/knowledge` est explicitement réservé à `ADMIN`
(`SecurityConfig.java:76`) parce que son contenu pèse sur chaque analyse future. Or
`POST /api/agent/skills/{id}/approve` (`SkillsController.java:46`) a exactement le même effet —
son Markdown est injecté durablement dans le prompt système de chaque conversation future du même
propriétaire (`LongTermMemoryService.context`, appelé depuis `AgentService.java:216-218`) — et
n'avait **aucune** ligne dédiée dans `SecurityConfig.java` : la route retombait sur le filtre
générique `/api/agent/**` → `OPERATOR`/`ADMIN` (`SecurityConfig.java:87`).

**Correctif** : `POST /api/agent/skills/*/approve` rejoint désormais le motif `ADMIN` de
`POST /api/agent/knowledge`. Testé par
`ApiKeyPrincipalTest.seul_un_admin_peut_approuver_une_competence`.

### 1.3 La suppression d'un résumé durable échappait à la règle `ADMIN` de la mémoire — **corrigé**

`DELETE /api/agent/memory/*` exige `ADMIN` (`SecurityConfig.java:85-86`). Mais
`DELETE /api/agent/memory/summaries/{id}` (`LongTermMemoryController.java:31-36`) a un segment de
plus : avec `AntPathMatcher`, `*` ne franchit pas `/`, donc ce chemin ne correspondait pas au motif
et retombait sur `/api/agent/**` → `OPERATOR`/`ADMIN`. Un `OPERATOR` pouvait supprimer un résumé
durable alors qu'il ne peut pas supprimer un simple fait court.

**Correctif** : `/api/agent/memory/summaries/*` rejoint le motif `ADMIN` existant. Testé par
`ApiKeyPrincipalTest.seul_un_admin_peut_supprimer_un_resume_durable`.

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

### 2.2 Le chemin par défaut de la console n'alimentait jamais le second système de mémoire — **corrigé**

`AgentService.streamForTask` (`AgentService.java:165-194`, ce que `/chat/stream` — « la console
l'emprunte par défaut », commentaire `AgentService.java:154` — expose) appelait bien
`request(...)`, qui injecte `longTermMemory.context(...)`, mais n'appelait **jamais**
`longTermMemory.recordSuccessfulTask(...)`, contrairement à `ask`/`askStructured`. En usage normal
via la console, aucun résumé ni aucune proposition de compétence n'était donc jamais enregistré —
seuls les appels via l'API bloquante (`/chat`, `/chat/structured`) le déclenchaient.

**Correctif** : le texte émis par le flux est accumulé (`doOnNext(answer::append)` sur
`.content()`), et `recordSuccessfulTask` est appelé depuis `doFinally` **seulement** quand le
signal terminal est `SignalType.ON_COMPLETE` — jamais sur une erreur, un plafond de durée dépassé
ou une déconnexion client, pour ne pas faire passer une réponse tronquée pour un échange réussi
(la même garde que documente déjà `LongTermMemoryService.recordSuccessfulTask` : « Call only after
a normally completed response »). Testé par
`AgentServiceTest.alimente_la_memoire_long_terme_a_la_fin_d_un_flux_reussi` et
`AgentServiceTest.n_alimente_pas_la_memoire_long_terme_quand_le_flux_echoue_par_timeout`.

### 2.3 `pom.xml` cible Java 21, tout le reste annonce Java 25

`pom.xml:19,24` (`<java.version>21</java.version>`, `<maven.compiler.release>21</maven.compiler.release>`)
contredit `CLAUDE.md`, `README.md`, `README.fr.md`, `docs/CONFIGURATION.md` et les quatre workflows
CI, qui épinglent tous JDK 25. Le build fonctionne (JDK 25 est ascendant-compatible avec
`release: 21`), donc ce n'est pas un défaut de fonctionnement, mais la mention de version fixe dans
le seul fichier qui devrait faire foi.

**Suggestion** : aligner `pom.xml` sur `25`.

### 2.4 `automation/` : erreurs métier rendues en 500 générique — **corrigé**

`AutomationController` ne déclarait aucun `@ExceptionHandler`, contrairement à `AgentController`
qui mappe systématiquement ses exceptions métier. Une expression cron invalide
(`AutomationSchedule.java:18-29`), une automatisation inconnue (`UnknownAutomationException`,
`JdbcAutomationRepository.java:76`) ou un conflit d'exécution (`IllegalStateException`,
`JdbcAutomationRepository.java:94,106`) remontaient donc toutes en `500`, là où le reste de
`web/` rend `400`/`404`/`409`.

**Correctif** : les trois mêmes `@ExceptionHandler` que `SupervisionController` (même famille
d'exceptions métier : validation, ressource inconnue, conflit d'état) —
`IllegalArgumentException` → `400`, `UnknownAutomationException` → `404`,
`IllegalStateException` → `409`. Testé par `AutomationControllerTest`.

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

| # | Constat | Impact | Effort | État |
|---|---|---|---|---|
| 1.1 | SSRF via URL de serveur MCP | Élevé | Faible (fonction pure + test) | Corrigé |
| 1.2 | Approbation de compétence sous-protégée | Élevé | Faible (une ligne de `SecurityConfig`) | Corrigé |
| 1.3 | Suppression de résumé sous-protégée | Moyen | Faible (une ligne de `SecurityConfig`) | Corrigé |
| 2.2 | Flux console n'alimente jamais l'apprentissage | Moyen | Moyen (choix de conception à trancher) | Corrigé |
| 2.4 | Erreurs `automation/` en 500 | Faible | Faible | Corrigé |
| 2.3 | Java 21 vs 25 dans `pom.xml` | Faible | Trivial | Ouvert |
| 2.5 | Bail d'automatisation découplé du timeout réel | Faible (pas d'exécuteur mutant aujourd'hui) | Moyen | Ouvert |
| 1.4 / 1.6 | Isolation STDIO et KDF, par défaut faibles | Faible aujourd'hui, à revoir si le périmètre s'élargit | Documentation / Moyen | Ouvert |
