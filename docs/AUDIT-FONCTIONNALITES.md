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

### 1.4 Isolation Docker des serveurs MCP STDIO : désactivée par défaut — **corrigé (documentation)**

`StdioIsolationProperties.defaults().enabled()` vaut `false`. Sans l'activer, une commande STDIO
enregistrée (`kex.mcp.runtime.allowed-stdio-commands`) s'exécute directement sur l'hôte plutôt que
dans le conteneur `--read-only --network=none --cap-drop=ALL` que `IsolatedStdioCommand` sait
construire. La liste blanche de commandes reste le premier garde-fou (une clé `ADMIN` compromise
ne devient pas un shell arbitraire), mais l'installation par défaut n'a pas la seconde ligne de
défense.

**Correctif** : ce choix est documenté dans `docs/CONFIGURATION.md` (« Extensions governed by
default »), à côté de `kex.mcp.runtime.isolation.enabled` — quelle commande s'exécute où par
défaut, ce que la liste blanche couvre déjà seule, et ce que l'isolation ajoute (une image
approuvée par commande, sans repli sur l'hôte). Aucun changement de comportement par défaut :
activer l'isolation par défaut exigerait Docker disponible sur tout déploiement, y compris ceux
qui ne déclarent aucune commande STDIO.

### 1.5 Contrôle du rôle `ADMIN` du catalogue MCP porté par le contrôleur, pas par la chaîne de filtres — **corrigé**

`McpCatalogController` vérifie lui-même le rôle `ADMIN` pour `POST /{id}/install`
(`McpCatalogController.java:40`), correct fonctionnellement, mais c'était la seule route mutante de
`mcp/` sans ligne dédiée dans `SecurityConfig.java` (les autres — `/mcp/servers`,
`/mcp/servers/*/secret`, etc. — l'ont toutes explicitement).

**Correctif** : `SecurityConfig.java` porte désormais `/api/agent/mcp/catalog/*/install` au même
titre que `/api/agent/mcp/catalog/discover/*/*/install` (ajoutés ensemble lors de la découverte de
catalogue). Le second manquait toutefois encore de preuve au niveau de la chaîne de filtres plutôt
que du seul contrôleur : `ApiKeyPrincipalTest.seul_un_admin_peut_installer_depuis_le_catalogue_mcp_recommande`
le vérifie désormais sur le vrai contexte Spring Security, sur le même modèle que son équivalent
pour la découverte.

### 1.6 Dérivation de clé sans sel pour le stockage chiffré des serveurs MCP — **corrigé**

`EncryptedMcpServerStore` chiffre en AES-256-GCM avec nonce aléatoire par écriture et fichier en
`rw-------` — correct. La clé, elle, venait de `SHA-256(passphrase)` sans sel ni KDF lente :
acceptable pour une passphrase d'opérateur fournie hors bande, mais sans défense en profondeur si
cette passphrase est faible.

**Correctif** : la clé AES dérive maintenant de `kex.mcp.runtime.storage-key` par
PBKDF2-HMAC-SHA256 (210 000 itérations, OWASP 2023), avec un sel aléatoire de 16 octets généré à
**chaque écriture** — jamais réutilisé — et transporté avec le fichier chiffré plutôt que dans un
registre séparé. Rupture explicite de format plutôt que compatibilité ascendante silencieuse :
`KEXMCP2` (avec sel) succède à `KEXMCP1` (hachage nu), et un fichier de l'ancien format échoue à se
déchiffrer avec un message clair — la fonctionnalité est récente, et les serveurs MCP persistés se
recréent en quelques clics dans la console, une migration automatique n'a donc pas semblé
justifiée. Testé par `EncryptedMcpServerStoreTest` : deux écritures successives produisent des sels
différents, et un fichier `KEXMCP1` est refusé explicitement.

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

### 2.3 `pom.xml` ciblait Java 21, tout le reste annonce Java 25 — **corrigé**

`pom.xml:19,24` (`<java.version>21</java.version>`, `<maven.compiler.release>21</maven.compiler.release>`)
contredisait `CLAUDE.md`, `README.md`, `README.fr.md`, `docs/CONFIGURATION.md` et les quatre
workflows CI, qui épinglent tous JDK 25.

**Correctif** : `pom.xml` aligné sur `25`. Vérifié en installant `openjdk-25-jdk-headless` dans
l'environnement d'audit (absent par défaut, seul JDK 21 y était disponible) et en rejouant
`./mvnw verify` sous ce JDK — build et couverture inchangés.

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

### 2.5 `automation/` : bail d'exécution indépendant du plafond réel de la tâche — **corrigé**

`AutomationService.tick()` borne la réclamation par `kex.agent.automation.timeout` (défaut 2 min,
`JdbcAutomationRepository.java:126-143`), mais l'exécution elle-même (`AgentService.askReadOnly`)
est bornée séparément par `kex.agent.request-timeout` (défaut 120 s), sans relation garantie entre
les deux. Si le bail expire avant la fin réelle du tour LLM, une autre réplique (le profil visé est
`shared-memory`, donc multi-instance) peut réclamer et exécuter la même automatisation en double.

**Correctif** : `AutomationTimeoutConsistencyCheck`, sur le même modèle que
`SupervisionScheduleConsistencyCheck` — un avertissement au démarrage
(`ApplicationReadyEvent`), pas un échec, quand `automation.enabled=true` et
`automation.timeout < request-timeout`. Une dérivation automatique du bail à partir de
`request-timeout` a été écartée : elle aurait fait dépendre silencieusement une propriété d'une
autre dans un sens non évident, alors qu'un avertissement explicite laisse l'opérateur choisir la
marge. Testé par `AutomationTimeoutConsistencyCheckTest`.

## 3. Fonctionnalités livrées sans façade opérateur

Backend complet et testé côté serveur, mais **aucun** point d'entrée dans la console
(`src/main/resources/static/assets/*.js`) au moment de cet audit :

| Fonctionnalité | Route | Constat |
|---|---|---|
| Automatisations planifiées | `/api/agent/automations` | Aucune référence dans `resources/static/` ; aucun exemple dans `application.yml` alors que `knowledge`/`supervision`/`memory` en ont |
| ~~Compétences proposées par le modèle~~ — **corrigé** | `/api/agent/skills` | `skills.js` : onglet Gouvernance (charte, file de revue, curation) |
| ~~Base de connaissance (CRUD documents)~~ — **corrigé** | `/api/agent/knowledge` | `knowledge.js` : recherche par similarité (même seuils qu'avant un échange réel), ajout, retrait — VectorStore n'exposant aucune énumération, une recherche reste le seul moyen de voir ce qui existe |
| Résumés durables | `/api/agent/memory/summaries` | Absents de `memory.js`, qui ne couvre que `MemoryService` |
| ~~Catalogue MCP recommandé~~ — **corrigé** | `/api/agent/mcp/catalog` | Panneau « Catalogue recommandé » dans `tools.js`, à côté de la découverte qui couvrait déjà `/discover` |
| Canaux de notification (Slack/Teams/e-mail) | — (configuration seule) | Pas d'écran listant les canaux actifs, contrairement à `/supervision/notify/test` qui a son bouton |

**Suggestion** : prioriser par risque plutôt que tout construire — le catalogue MCP et les
compétences approuvées avaient un impact direct sur ce que le modèle peut faire ou dire ; la base
de connaissance pèse sur ce qu'il *sait*, un cran en dessous. Les trois sont désormais couverts.
Résumés/automatisations restent d'abord un manque de confort opérationnel, suivis par les canaux
de notification.

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
| 2.3 | Java 21 vs 25 dans `pom.xml` | Faible | Trivial | Corrigé |
| 2.5 | Bail d'automatisation découplé du timeout réel | Faible (pas d'exécuteur mutant aujourd'hui) | Moyen | Corrigé |
| 1.4 / 1.6 | Isolation STDIO et KDF, par défaut faibles | Faible aujourd'hui, à revoir si le périmètre s'élargit | Documentation / Moyen | Corrigé |

Les huit constats de cet audit sont désormais traités : sept par un changement de code ou de
configuration avec test dédié, un (1.4) par une documentation explicite d'un choix déjà
raisonnable.
