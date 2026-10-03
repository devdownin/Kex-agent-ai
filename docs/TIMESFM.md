# Prévisions TimesFM dans Kex Agent AI

La vue **Pilotage → Prévisions** consulte les résultats déjà calculés par KafkaExplorer.
L’agent peut les expliquer dans une conversation et les rapprocher des mesures actuelles.
Kex ne charge pas le modèle, ne collecte pas de nouvelles données et n’active aucune série.

## Préparer KafkaExplorer

Utiliser une version contenant le pilote TimesFM et les cinq outils canoniques de la
[PR KafkaExplorer #454](https://github.com/devdownin/Kafkaexplorer/pull/454).
Cette évolution est fusionnée dans KafkaExplorer depuis le 3 octobre 2026.
Activer l’historique, l’inférence et le pilote côté KafkaExplorer, configurer PostgreSQL,
puis enrôler les séries avec leur environnement et leurs sources approuvées.
Le premier résultat exige un historique admissible ; sa qualité réalisée reste non mesurée
jusqu’à l’évaluation de prévisions arrivées à échéance.

Autoriser explicitement les environnements, topics et groupes dans le périmètre MCP de
KafkaExplorer. Une série hors périmètre n’apparaît pas dans son catalogue.

## Connecter Kex

Réutiliser la connexion MCP KafkaExplorer dans **Intégrations**. Sa clé doit correspondre à
`kex.agent.kafka.connection` (par défaut `kafka-explorer`). Si elle utilise une liste d’outils
autorisés, ajouter ces cinq noms sans élargir les autres permissions :

| Outil | Usage |
|---|---|
| `kex_list_forecastable_metrics` | Résoudre les séries approuvées et leurs environnements |
| `kex_metric_history` | Lire le contexte préparé et ses imputations |
| `kex_forecast_metric` | Lire une prévision persistée et sa provenance |
| `kex_list_predicted_threshold_breaches` | Lire les dépassements de seuils déclarés |
| `kex_get_forecast_quality` | Lire les erreurs réalisées et les scores des baselines |

Les lectures ne déclenchent ni SQL, ni inférence, ni alerte. Le jeton MCP reste côté serveur.
Les opérateurs et administrateurs Kex accèdent à la vue ; le rôle CHAT seul n’accède pas à ses API.
Les opérateurs de cette instance partagent le périmètre de la connexion configurée : ces lectures
ne délèguent pas l’identité utilisateur à KafkaExplorer. Utiliser des connexions/instances distinctes
pour des périmètres qui doivent rester séparés.

## Parcours opérateur

1. Ouvrir **Prévisions** et choisir un environnement puis une métrique du catalogue autorisé.
2. Lire l’état, le mode, la stratégie, la génération et l’échéance avant d’interpréter la courbe.
3. Comparer historique, valeur centrale et Q10/Q50/Q90. Les trous interrompent l’historique ;
   le tableau accessible précise les points imputés. La valeur centrale porte la statistique
   annoncée par le modèle ; elle n’est pas renommée arbitrairement « médiane ».
4. Consulter la qualité après échéance : MAE, MASE, pinball loss, couverture empirique,
   largeur d’intervalle et MAE des quatre baselines. La période et le nombre de points sont affichés.
5. Consulter les dépassements prédits de l’environnement choisi, leur seuil, direction,
   fenêtre, mode et provenance. Une liste vide ne prouve pas l’absence de risque.
6. Cliquer **Analyser avec l’agent** pour préparer une conversation sur la série et l’environnement.
   Le prompt est un brouillon : l’opérateur décide de l’envoyer.

**Actualiser les résultats** relit les résultats existants ; cette vue ne lance pas de sondage
périodique. L’activation des séries reste dans KafkaExplorer et sa gouvernance opérateur.

## Interpréter les états

- `SHADOW` : observation, sans alerte ni action produite par cette vue.
- `STALE` : horizon expiré, conservé comme résultat passé ; aucune conclusion de risque futur.
- `WARMING_UP` / historique insuffisant : acquisition en cours, pas de prévision exploitable.
- `DEGRADED` / stratégie baseline : repli explicite, sans bande de confiance.
- Non mesuré / indisponible : aucune valeur inventée, notamment aucun zéro de substitution.

Q10–Q90 est un intervalle nominal : il ne garantit pas une probabilité de dépassement. Une bonne
couverture passée ne garantit pas la prochaine prévision. Les consignes de conversation et de
supervision demandent de confirmer un incident avec des mesures opérationnelles actuelles,
sans créer une anomalie ou une action sur la seule base d’une prévision. Il s’agit de consignes
LLM, pas d’un nouveau moteur déterministe d’alertes prédictives.

## Contrats et limites

API GET authentifiées : `/api/agent/forecasts/metrics`, `/api/agent/forecasts/breaches`,
`/api/agent/forecasts/series/{seriesId}`. Pas de POST, activation ou inférence via ces routes.
Chaque détail exige l’appartenance au catalogue autorisé complet, puis KafkaExplorer vérifie
à nouveau les ressources sur chacun des trois outils. Aucun résultat n’est mis en cache côté Kex.

Les enveloppes incomplètes restent explicitement inexploitables. Les catalogues et listes sont
bornés à 256 éléments ; les contextes et horizons à 512 points dans cette vue. Les réponses hors
contrat sont indisponibles, sans affichage partiel rassurant. Les erreurs transport ne divulguent
pas leurs paramètres. Le changement de jeton efface la sélection et invalide les lectures en vol.
Si deux lectures ne concernent pas les mêmes empreintes d’entrée/profil, la courbe n’assemble
pas leur historique. La qualité constitue une lecture distincte datée ; elle n’est pas une
évaluation recalculée de la prévision affichée.

## Réception et vérification

- Catalogue autorisé visible ; série inconnue ou couverture incomplète refusée avant lecture.
- Provenance incohérente, timestamps invalides et quantiles croisés refusés.
- Zéro conservé ; mesure absente et erreur ne deviennent pas zéro.
- Pas de bande Q10–Q90 pour une baseline de repli.
- Horizon expiré marqué, aucune action d’activation dans Kex.
- Noms et contenus MCP rendus comme texte, sans HTML exécutable.
- Vue utilisable au clavier et à 390 px ; tableau des valeurs accessible.
- Changement d’environnement et de jeton sans conservation de résultats hors sélection.

```bash
./mvnw verify
PLAYWRIGHT_MODULE=/chemin/playwright/index.mjs node src/test/browser/forecasts.mjs
```

Le test navigateur utilise les vrais modules statiques et un serveur HTTP de fixtures, sans
Kafka, LLM ou TimesFM. Il est inclus dans le job navigateur de la CI. La vérification avec une
instance KafkaExplorer déployée demeure nécessaire pour le périmètre MCP de votre installation.
