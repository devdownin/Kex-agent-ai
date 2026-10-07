# Stack locale complète KafkaExplorer + TimesFM

Prérequis : Docker avec Compose v2, BuildKit, Git, Python 3 et OpenSSL. Prévoir 4 CPU et 8 Gio
pour TimesFM **en plus** de Kafka, des deux JVM, PostgreSQL et des constructions.
Le premier démarrage télécharge le modèle épinglé et vérifie son SHA-256 ; sa durée
dépend de la connexion. KafkaExplorer et TimesFM sont construits depuis la même
révision Git épinglée dans `compose/forecasts.yml`.

```sh
cp .env.example .env
# Renseigner ANTHROPIC_API_KEY, ou le fournisseur OpenRouter et sa clé dans .env.
bin/forecast-stack.sh
```

Le script génère et conserve les secrets MCP, API agent, PostgreSQL et TimesFM dans
`.forecast-stack/.env` (permissions 0600). Ce fichier complète `.env` et prend la priorité
pour ces secrets. Il ne remplace ni les clés LLM ni la configuration approuvée.
La clé API à saisir dans l'agent est `KEX_AGENT_API_KEY` de `.forecast-stack/.env`.

- KafkaExplorer : http://localhost:8080.
- Kex-anHarness : http://localhost:8081, page **Prévisions**.
- Kafka : localhost:9092 ; les conteneurs utilisent kafka:29092.
- PostgreSQL et TimesFM : aucun port publié sur l'hôte.

`bin/forecast-stack.sh --prepare-only` prépare secrets et répertoire de configuration
sans démarrer les conteneurs. Pour utiliser un checkout local de KafkaExplorer,
renseigner `KAFKAEXPLORER_BUILD_CONTEXT=../Kafkaexplorer` et
`TIMESFM_BUILD_CONTEXT=../Kafkaexplorer/services/timesfm` dans `.env`.

## Configurer les prévisions

L'infrastructure démarre avec les cinq outils MCP de prévision actifs et un catalogue
vide. L'historique reste désactivé jusqu'à l'approbation des métriques ; aucun identifiant,
source ou historique artificiel n'est ajouté automatiquement.

1. Créer ou sélectionner une métrique compatible dans KafkaExplorer.
2. Dans **Metrics Forecast**, utiliser **Configure a forecast**, déclarer les identités
   stables du cluster et du collecteur, les sources et l'environnement `local`.
3. Valider puis exporter le YAML. Enregistrer le fichier dans
   `.forecast-stack/config/forecasts.yml` ; les paramètres PostgreSQL et TimesFM sont
   déjà fournis par le Compose.
4. Relancer `bin/forecast-stack.sh` pour recharger cette configuration dans KafkaExplorer.
5. Attendre l'historique valide : 512 points à une minute représentent 512 minutes.
   Les premiers résultats sont en SHADOW ; l'activation demande une validation distincte.

Pour un autre environnement, définir `FORECAST_ENVIRONMENTS` dans
`.forecast-stack/.env` conformément aux sources approuvées. La clé MCP est partagée
automatiquement avec l'agent ; les lectures MCP ne déclenchent pas d'inférence.

## Vérifier et arrêter

```sh
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml config --quiet
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml ps
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml logs timesfm explorer agent
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml down
```

Si `.env` n'existe pas et que la clé LLM est exportée, omettre `--env-file .env`.
Utiliser `config --quiet` pour ne pas afficher les secrets interpolés.
Les volumes conservent données Kafka, état de l'agent, historique PostgreSQL et modèle.
`down -v` supprime ces données. Les ports web restent liés à la boucle locale par défaut.

## Démonstration et diagnostic

```sh
bin/forecast-stack.sh --demo
bin/forecast-diagnose.sh
```

Le mode `--demo` lance le service du profil Compose `forecast-demo`, puis crée
la métrique via l’API KafkaExplorer. Il crée le topic `forecast.demo.orders`, trois messages, le groupe
`forecast-demo` et une métrique `CONSUMER_TIME_LAG` nommée `forecast-demo-lag`.
Une relance conserve la métrique existante et ajoute trois messages de démonstration.
Utiliser une stack locale dédiée ; ce mode écrit des données Kafka et une configuration
métrique, mais ne valide pas les sources et ne fabrique pas d'historique.

Le démarrage indique quatre étapes : validation, construction, démarrage/préchargement,
puis vérification des services et de MCP. Compose affiche le téléchargement du modèle.
Le diagnostic vérifie les conteneurs, PostgreSQL avec authentification, TimesFM prêt,
le refus MCP sans token, les cinq outils MCP et la connexion de l'agent. Il retourne
un code non nul pour une panne. Un catalogue vide est signalé « À CONFIGURER » :
l'infrastructure peut être saine sans prévision disponible. Aucun secret n'est affiché.
Les ports personnalisés sont lus dans la configuration Compose effective.

La page **Prévisions** de l'agent présente un parcours dépliable « Créer ma première
prévision ». La progression réelle des 512 points reste visible dans Metrics Forecast
chez KafkaExplorer ; elle n'est pas estimée à partir de la durée de fonctionnement.

## Vérification CI

Le workflow **Forecast stack** construit les vrais composants, télécharge le modèle
épinglé, démarre une stack isolée, crée la métrique démo et vérifie santé, PostgreSQL,
TimesFM, authentification MCP/agent et découverte des cinq outils. Il ne requiert
aucune clé LLM réelle et n'appelle pas le LLM. Le catalogue doit rester vide avant
approbation. Il ne prétend pas mesurer la justesse des prévisions ou la qualité du modèle.
Les volumes CI sont supprimés après le job ; les volumes locaux sont conservés.

### Noms des variables Spring

Les clés Docker suivent la conversion Spring : points remplacés par `_`, tirets
supprimés, puis majuscules. Ainsi `explorer.mcp.require-tls` devient
`EXPLORER_MCP_REQUIRETLS`, et non `EXPLORER_MCP_REQUIRE_TLS`. La stack locale
positionne cette propriété à `false` car ses échanges MCP se font en HTTP.
Le diagnostic signale explicitement HTTP 426 si le serveur exige encore TLS.
Les URLs JDBC/inférence et la liste des environnements suivent la même convention.
