# Plans explicites et tâches durables

La vue **Pilotage → Tâches** prépare un plan à partir d'un objectif, affiche ses paramètres,
préconditions, dépendances et critères, puis sépare son approbation de son lancement.
Le plan peut aussi être fourni directement en JSON. La conversation existante reste disponible
pour les demandes simples. Ce parcours est désactivé par défaut.

## Activer et déclarer les outils

Les liaisons sont configurées par l'administrateur, jamais créées par le modèle. `read-only`
est une déclaration de confiance de l'exploitant : vérifier le comportement réel du serveur.
Les permissions MCP et les contrôles de `tools.rules` restent applicables à chaque invocation.

```yaml
kex:
  agent:
    tasks:
      enabled: true
      max-steps: 16
      max-concurrent: 4
      run-timeout: 10m
      call-timeout: 60s
      max-result-characters: 8000
      bindings:
        lag:
          connection: kafka-explorer
          tool: kex_consumer_lag
          read-only: true
```

Les identifiants de liaisons sont propres à ce parcours ; ils ne sont pas les noms qualifiés des
callbacks LLM. La planification consulte les schémas MCP disponibles, sans exécuter d'outil.
Le catalogue et les paramètres sont bornés ; les références JSON Schema externes sont interdites.
Un outil absent ou inaccessible empêche de proposer ou d'approuver un plan utilisant son contrat.
Les schémas sont des métadonnées externes ; leur exposition n'accorde aucun droit.

Une action nécessite une capacité explicite, autorisée par la politique actuelle, et une
approbation ADMIN du plan exact. Exemple avec des outils **propres à votre installation** :

```yaml
kex:
  agent:
    tasks:
      bindings:
        restart:
          connection: operations
          tool: restart_consumer
          capability: RESTART_CONSUMER
          idempotency-argument: requestId
          verifier:
            binding: consumer-state
            arguments:
              consumerId: orders-consumer
            pointer: /healthy
            expected: true
            observed-at-pointer: /observedAt
            measured-pointer: /measured
        consumer-state:
          connection: operations
          tool: consumer_state
          read-only: true
```

`requestId` doit être accepté et honoré par le serveur : Kex ne peut pas garantir l'idempotence
à sa place. Sa valeur stable est `taskId:stepId`. Le vérificateur est distinct de la mutation.
Ses paramètres sont configurés par l'exploitant ; `$taskId` et `$stepId` sont remplacés dans
les valeurs de premier niveau. Aucun résultat d'outil n'est interpolé comme commande.

## Contrat de plan et progression

```json
{
  "objective": "Vérifier que orders-consumer a repris",
  "preconditions": ["Vérifier le périmètre et la fenêtre de maintenance"],
  "steps": [{
    "id": "check", "description": "Mesurer l'état actuel", "binding": "consumer-state",
    "arguments": {"consumerId": "orders-consumer"}, "dependsOn": [],
    "expectation": {"pointer": "/healthy", "expected": true}
  }]
}
```

Les dépendances doivent désigner des étapes précédentes. Les cycles, identifiants dupliqués et
appels identiques répétés sont refusés. Le plan est immutable après création. Les préconditions
textuelles sont **à examiner humainement** ; elles ne sont pas présentées comme des contrôles
exécutables. Les contrôles déterministes portent sur les contrats, les droits, la pause,
les dépendances et les postconditions configurées.

- `DRAFT` : aucune étape exécutée ; plan à examiner.
- `APPROVED` : plan approuvé ; lancement séparé.
- `RUNNING` : réservation persistée et point de reprise écrit avant chaque effet.
- `COMPLETED` : plan terminé sans critère final ; objectif métier non vérifié.
- `VERIFIED` : critère final mesuré et satisfait, selon le plan approuvé.
- `FAILED` : erreur explicite de l'action ou critère mesuré non satisfait.
- `PAUSED` : lecture interrompue, budget atteint ou politique/contrat indisponible.
- `NEEDS_RECONCILIATION` : effet incertain ou preuve insuffisante ; aucune mutation rejouée.
- `CANCELLED` : arrêt des étapes suivantes ; aucun effet déjà lancé n'est annulé.

Les postconditions utilisent un objet `structuredContent`, un pointeur JSON et une valeur
scalaire, sans jugement LLM. Une enveloppe `coverage` présente doit annoncer `complete:true`,
`stopReason:EXHAUSTED` et aucune ressource non atteinte. Une preuve manquante n'est pas un échec
métier mesuré. Pour une action, la preuve doit être datée après son acceptation et avant l'heure
courante ; un drapeau de mesure peut être exigé. Cette vérification constate l'état obtenu,
elle ne démontre pas à elle seule la causalité de l'action.

## Reprise et arrêt

Le worker continue indépendamment de la connexion HTTP. Après une interruption, un bail actif
empêche une autre réplique de reprendre. À l'expiration, seules les lectures peuvent être
rejouées. Une mutation marquée en cours avant un crash devient incertaine ; la reprise appelle
uniquement son vérificateur. Sans vérificateur, elle reste bloquée pour investigation humaine.
Un timeout ne prouve pas que le serveur distant a arrêté son travail.

Le changement de configuration ou de schéma invalide l'empreinte du plan approuvé ; créer un
nouveau plan après examen. La vue **Replanifier** utilise les résultats précédents bornés comme
données de référence et crée un nouveau brouillon, sans approbation héritée. Une tâche incertaine
ne peut pas être replanifiée avant réconciliation. Les compensations sont des actions distinctes,
à décrire et approuver dans un nouveau plan ; aucune compensation automatique n'est lancée.

Le modèle ne replanifie pas seul après une action incertaine. Cette limite conserve le contrôle
humain sur les nouvelles mutations. Chaque reprise conserve les résultats des étapes terminées.
Les sorties sont bornées et datées, avec SHA-256 du résultat complet lorsque tronquées.

## Stockage, accès et API

En mono-instance : fichiers atomiques privés sous `kex.agent.tasks.store-path`, par défaut
`${user.home}/.kex-agent-ai/tasks`. Dans Docker : `/var/lib/kex/tasks`, sur le volume Kex existant.
Sauvegarder ce volume. Ne pas partager ces fichiers entre processus ; utiliser `shared-memory`
pour plusieurs répliques. La migration V4 crée `kex_task` en PostgreSQL ; le remplacement
conditionné par la version arbitre les réservations concurrentes. La fenêtre de liste est bornée
à 100 tâches, sans suppression automatique de l'historique persistant.

Les tâches appartiennent au locataire authentifié. OPERATOR/ADMIN peuvent planifier, consulter,
arrêter et lancer ; un opérateur peut approuver uniquement les plans de lecture. ADMIN approuve
les plans comprenant une action, toujours dans son locataire. Le rôle CHAT n'accède pas à ce
parcours. L'audit conserve l'acteur et l'identifiant de tâche, sans recopier les paramètres.

| Route | Résultat |
|---|---|
| `GET /api/agent/tasks` | Les 100 dernières tâches du locataire |
| `POST /api/agent/tasks` | Créer un brouillon JSON explicite |
| `POST /api/agent/tasks/plan` | Proposer un plan depuis `objective` et facultativement `previousTaskId` |
| `GET /api/agent/tasks/{id}` | Plan, progression, preuves et approbation |
| `POST /api/agent/tasks/{id}/approve` | Approuver le plan exact |
| `POST /api/agent/tasks/{id}/run` | Lancer/reprendre, réponse 202 et réservation persistée |
| `POST /api/agent/tasks/{id}/cancel` | Arrêter les étapes suivantes |

## Vérifications

`TaskServiceTest` vérifie persistance après reconstruction, postconditions, preuves périmées,
reprise sans rejouer une mutation, révocation de politique, concurrence et interruption.
`JdbcTaskRepositoryTest` exerce la migration V4 et le remplacement conditionnel sur H2.
`TaskApiSecurityTest` utilise la chaîne HTTP réelle pour les rôles et le cloisonnement des locataires.
`src/test/browser/tasks.mjs` conduit les vrais modules statiques sur des réponses contrôlées.
La validation avec vos outils MCP, PostgreSQL et leurs garanties d'idempotence reste nécessaire.
