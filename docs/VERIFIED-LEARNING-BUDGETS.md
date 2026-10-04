# Mémoire vérifiée et budgets partagés

## Réponse terminée et résultat vérifié

Une réponse normale, bloquante, structurée ou en flux, conserve un résumé `UNVERIFIED`.
Un outil ayant échoué produit `FAILED`. Aucun de ces résumés ne génère une compétence
ni ne revient dans le contexte durable comme résultat établi.

Le moteur de [tâches durables](DURABLE-TASKS.md) fournit les preuves : une tâche `VERIFIED`
avec toutes ses étapes `VERIFIED`, une date d'observation et une empreinte de résultat par étape,
peut produire un résumé vérifié et une compétence `PENDING`. La réponse du modèle ou l'absence
d'exception d'un outil ne peut pas attribuer cet état. L'approbation humaine reste obligatoire.

Le champ `verification` contient le résultat, l'identifiant `task:<id>`, la date d'observation,
la date de fin de validité, les contradictions, la version du contrat des liaisons, les paramètres
observés, les préconditions du plan et les contrôles exécutés. Les paramètres sont des valeurs
observées : l'opérateur doit vérifier leur adéquation et les données sensibles avant approbation.
Ces procédures restent du contexte documentaire ; elles n'autorisent aucune action et ne remplacent
ni la validation de schéma ni les approbations du moteur d'exécution.

Les preuves expirées sont exclues de l'injection. Les résumés anciens sans preuve structurée restent
consultables mais ne sont plus injectés. Les procédures manuelles déjà approuvées restent compatibles ;
leur preuve textuelle reste sous la responsabilité du réviseur.

Un administrateur du locataire peut contredire un résumé, avec une nouvelle source et un motif :

```http
POST /api/agent/memory/summaries/<id>/contradict
Content-Type: application/json

{"sourceId":"incident:2026-123","reason":"Le contrôle de lag utilisait une métrique périmée"}
```

Le résumé passe à `CONTRADICTED`. Les compétences issues de la même tâche sont retirées (`RETIRED`),
y compris celles encore en attente. Leur contenu, le réviseur et le motif restent conservés.
`POST /api/agent/skills/<id>/retire` conserve également l'historique au lieu de supprimer la procédure.
L'écran des résumés distingue les états et affiche la provenance et la validité ; la file de revue
présente la version et la preuve de chaque procédure générée.

Les faits de `recall_facts` exposent aussi leur conversation source, leur date et leur fin de
validité, avec l'état `ASSERTED` : une déclaration mémorisée n'est pas un résultat mesuré.
Le mécanisme `replaces` retire de la récupération le fait explicitement contredit.

## Comptabilisation et réservation

Chaque tentative de fournisseur réserve un budget avant l'appel, y compris les tours intermédiaires
de la boucle d'outils et chaque destination de secours. Les réessais internes des SDK OpenAI et
Anthropic sont désactivés à cette frontière pour éviter une dépense cachée. Le plafond de sortie
configuré par fournisseur est conservé ; en son absence, `output-reservation` devient aussi le plafond
transmis au fournisseur. Le nombre d'octets UTF-8 des messages et contrats d'outils fournit une
estimation conservative de l'entrée textuelle ; les entrées multimodales demandent une tarification adaptée.

Trois compteurs sont débités atomiquement : global journalier, locataire journalier, tâche.
Pour un échange, la tâche est une exécution de requête et couvre tous ses tours ; une tâche durable
utilise son identifiant persistant et cumule ses étapes et reprises. La planification LLM constitue
une exécution distincte de la tâche durable qu'elle propose. Les compteurs journaliers suivent le fuseau
horloge du serveur ; le budget d'une tâche ne se réinitialise pas à minuit.

Une réponse complète avec usage mesuré réconcilie la réservation avec la consommation réelle.
Le flux utilise le dernier compteur cumulatif, sans additionner ses instantanés. Si le fournisseur
ne rapporte pas l'usage, si le flux est interrompu ou si le processus meurt, la réservation reste
imputée comme estimation : l'absence de preuve de facturation ne devient jamais une dépense nulle.
Une consommation réelle supérieure à l'estimation est enregistrée et bloque les appels suivants ;
ces estimations ne garantissent pas un plafond exact de facture fournisseur.

Les outils utilisent un tarif fixe configuré, réservé avant exécution. Les échecs et tentatives
de lecture retentées restent imputés. Les tarifs sont en millionièmes d'une même devise choisie
par le déploiement, pas des prix automatiquement récupérés auprès des fournisseurs. Des tarifs
à zéro ne produisent aucune mesure monétaire : renseigner les tarifs pour utiliser les limites de coût.

```yaml
kex:
  agent:
    token-budget:
      daily-limit: 2000000
      tenant-daily-limit: 400000
      task-limit: 80000
      daily-cost-micros: 10000000
      tenant-daily-cost-micros: 2000000
      task-cost-micros: 500000
      output-reservation: 4096
      input-token-cost-micros: 1  # Exemple de tarif, pas un prix fournisseur.
      output-token-cost-micros: 3
      default-tool-cost-micros: 100
      tool-cost-micros:
        kafka-explorer:kex_consumer_lag: 200
      store-path: /app/data/budgets.json
```

Sans `shared-memory`, un fichier atomique persiste les compteurs d'une instance. Ne pas le partager
entre plusieurs processus. L’image Docker place ce fichier dans le volume existant `/var/lib/kex/budgets.json`, accessible à son utilisateur non root. Avec `shared-memory`, les migrations V5/V6 ajoutent les preuves et le
registre JDBC ; transactions et verrouillage des lignes dans un ordre stable protègent les répliques.
Une réservation refusée ne débite aucun des trois compteurs. Les réservations incertaines ne sont pas
libérées automatiquement après redémarrage. Conserver ce registre dans les sauvegardes ; sa taille
croît avec les tâches et les jours comptabilisés.
