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

Le dashboard présente un bloc personnalisable **Risques à venir**. Il liste les trois prochaines
échéances de dépassements futurs, avec environnement, génération, mode et qualité réalisée datée.
Un lien ouvre directement la série dans son environnement. Les lectures restent bornées à deux
listes et trois détails au maximum par chargement du bloc ; les erreurs et couvertures incomplètes
ne deviennent pas une absence de risque. La qualité est lue séparément de la prévision : elle
décrit une période réalisée, pas une garantie sur le risque affiché.

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

La courbe superpose les seuils des dépassements rendus par MCP uniquement lorsque génération et
empreintes d’entrée/profil correspondent au résultat affiché. Les points futurs dans la fenêtre
du seuil sont marqués si Q10 dépasse le seuil haut ou Q90 est sous le seuil bas. L’absence de
trait ne prouve pas l’absence de seuil configuré : le MCP ne rend que les dépassements prédits.
Un résultat de repli n’a pas de superposition de seuil ni de bande de quantiles.

Les états et modes portent des libellés explicites ; les aides **Comprendre les modes et les
prévisions** et **Comprendre la qualité** expliquent quantiles, MAE, MASE, pinball loss et baselines.
L’analyse guidée demande cinq parties : constat actuel, prévision, qualité, limites et vérifications
proposées. L’historique est daté et ne tient pas lieu de vérification de l’état actuel. Les sources
et causes ne doivent pas être inventées ; aucune action n’est lancée par le brouillon.

**Actualiser les résultats** relit les résultats existants. L’option **Actualiser automatiquement
(60 s)** est désactivée par défaut. Elle relit au plus une fois par minute, sans chevaucher une
lecture en cours, et se suspend lorsque la vue est quittée ou l’onglet masqué. Le retour visible
reprend au prochain intervalle ; le changement de jeton arrête l’option et annule les requêtes.
La date de dernière lecture reste distincte de la date de calcul de la prévision. L’activation
des séries reste dans KafkaExplorer et sa gouvernance opérateur.

## Ressources et processus liés

Le catalogue MCP peut ajouter `sources` : `definitionVersion`, `topics`, `groups`, `complete`.
Cet enrichissement est fourni par [KafkaExplorer #456](https://github.com/devdownin/Kafkaexplorer/pull/456).
KafkaExplorer n’annonce que les sources de séries autorisées. Les identifiants masqués par la DLP
ne deviennent pas des liens ; une provenance incomplète ou absente désactive ce panneau sans
masquer les courbes. Les sources ne sont pas reconstruites depuis le nom de métrique.

Le panneau **Ressources et processus liés** ouvre les groupes/lag du topic sur la même connexion
KafkaExplorer. Un lien de groupe prépare un diagnostic en lecture seule dans le chat, que
l’opérateur choisit d’envoyer. Les processus sont des associations explicites configurées dans Kex,
par série et environnement exacts ; seuls les processus actuellement définis sont affichés :

```yaml
kex:
  agent:
    forecasts:
      process-links:
        - series-id: "<identifiant approuvé de la série>"
          environment: production
          process-ids: [order-integration]
```

Les descriptions, hints LLM et ressemblances de noms ne servent jamais d’association. Le panneau
refuse une définition de source différente du contexte affiché. La route GET
`/api/agent/forecasts/series/{seriesId}/resources` exige le catalogue courant complet et refuse
les identifiants non autorisés ; aucun lien n’est conservé au changement de jeton. Limites :
128 topics/groupes par série et 50 associations de processus rendues. Le catalogue d’anciennes
versions de KafkaExplorer reste compatible : le panneau explique que la provenance est absente.

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


## Assistant de création de processus

Dans **Conversation → Créer avec l’assistant guidé**, la dernière question propose une série
TimesFM facultative. Les options affichent environnement, métrique et identifiant exact du
catalogue autorisé. Un catalogue incomplet, tronqué ou indisponible ne fournit aucun choix de
série ; la création reste possible sans prévision.

Le choix ajoute la série et son environnement au hint enregistré. L’aperçu final génère aussi
le bloc `kex.agent.forecasts.process-links` avec l’identifiant de processus que vous validez.
Après confirmation ADMIN, le YAML reste disponible dans **Configuration proposée du processus**.
Appliquer le bloc d’association à la configuration existante et redémarrer Kex pour afficher le
lien ; le wizard ne modifie pas cette configuration. Ne pas dupliquer dans `supervision.processes`
un processus déjà enregistré. Aucune série n’est activée ou enrôlée par ce parcours.

Voir les [prompts et la démonstration](EXEMPLES.md#timesfm--démonstration-et-prompts-opérationnels)
et la [configuration du wizard](CONFIGURATION.md#créer-un-processus-depuis-la-conversation).
