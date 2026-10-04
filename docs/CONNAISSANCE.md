# Base de connaissance (RAG)

Les outils MCP disent ce qui **est** dans le cluster. Ils ne disent pas ce que votre équipe **sait** :
la convention de nommage des topics, le runbook d'une DLQ qui se remplit, la raison pour laquelle
`demo.orders` a une rétention de 7 jours. C'est ce que cette base apporte.

Activée, chaque question est d'abord cherchée dans la base et les passages pertinents sont ajoutés
au prompt. Le modèle répond alors avec le contexte de la maison, pas seulement avec ses outils.

## Droits, environnement et preuves

L'ingestion associe chaque document au locataire authentifié ; un champ `owner` fourni dans les
métadonnées ne permet pas de choisir un autre propriétaire. L'environnement du déploiement
(`kex.agent.knowledge.environment`, défaut `default`) et le droit `readRole` (`TENANT`, `CHAT`,
`OPERATOR`, `ADMIN`, `INTERNAL`) sont filtrés avant la recherche des meilleurs voisins.
`TENANT` ouvre le document aux membres du même locataire. Un rôle ADMIN ne contourne pas ce
filtre de locataire. Les rôles proviennent de l'authentification, jamais d'un paramètre du modèle.
Les traitements internes sans identité d'opérateur ne voient que les documents `TENANT` et `INTERNAL`.

Une date future est refusée ; une preuve expirée ou plus ancienne que `max-age` (défaut 30 jours)
est exclue. `observedAt` doit dater l'observation de la source. Sans cette métadonnée, sa valeur est
la date d'ingestion, explicitement distincte d'une vérification de la justesse du contenu. `validUntil`
ne peut prolonger le document au-delà de `max-age`. Les anciens documents sans métadonnées d'accès
sont exclus : les réimporter avec leurs droits et leur provenance.

Après filtrage, le classement combine similarité (90 %) et fraîcheur (10 %), puis garde `top-k`
passages. Une seconde vérification rejette tout document que le magasin renverrait hors droits.
L'ancien advisor global sans filtre est retiré. Le contexte documentaire est fourni sur les trois
chemins de conversation ; l'historique du chat est séparé par ensemble de rôles lorsque la connaissance
est active, pour qu'une baisse de droits ne relise pas une ancienne réponse privilégiée.

Le modèle reçoit l'instruction de citer `[source:<id>]` pour chaque conclusion fondée sur un document
et d'identifier les inférences. Les réponses JSON exposent aussi `sources` (identifiant, source,
date, validité, extrait) ; le flux SSE expose un événement `sources` et le chat permet de consulter
ces extraits. Cette provenance est déterminée par le serveur. La présence d'une source ne vérifie
pas à elle seule toutes les assertions rédigées librement par le modèle.

Exemple de métadonnées :

```json
{"source":"runbook:dlq-v3#replay","environment":"production","readRole":"OPERATOR",
 "observedAt":"2026-10-04T06:00:00Z","validUntil":"2026-10-11T06:00:00Z"}
```

## Pourquoi c'est éteint par défaut

Un RAG a besoin d'un modèle d'embeddings, donc d'une infrastructure : une clé d'API chez un
fournisseur, ou un modèle local à télécharger. L'agent démarre sans rien ; ces beans ne sont donc
déclarés que sur `kex.agent.knowledge.enabled=true`. Les déclarer inconditionnellement ferait
échouer le démarrage de l'application entière faute de modèle d'embeddings — exactement l'erreur
que le starter JDBC de la mémoire partagée provoquait.

## Allumer

Il faut un modèle d'embeddings sur le classpath. Trois voies, selon ce que vous acceptez de faire
tourner :

| Voie | Dépendance | Ce que ça coûte |
|---|---|---|
| OpenAI | `spring-ai-starter-model-openai` | Une seconde clé d'API, et les textes sortent chez un tiers |
| Ollama | `spring-ai-starter-model-ollama` | Un serveur Ollama à côté, rien ne sort |
| ONNX local | `spring-ai-starter-model-transformers` | Un modèle téléchargé au premier démarrage, rien ne sort ensuite |

Puis :

```yaml
kex:
  agent:
    knowledge:
      enabled: true
      top-k: 4
      similarity-threshold: 0.6
      store-path: /app/data/knowledge.json
```

## Alimenter

```bash
curl -X POST localhost:8081/api/agent/knowledge \
  -H "Authorization: Bearer $KEX_AGENT_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '[{"text":"La rétention des topics demo est de 7 jours.",
        "metadata":{"source":"runbook","equipe":"data"}}]'
```

```json
["4b1f…"]
```

Les identifiants sont rendus pour pouvoir retirer précisément ce qu'on a ajouté :

```bash
curl -X DELETE localhost:8081/api/agent/knowledge \
  -H "Authorization: Bearer $KEX_AGENT_API_KEY" \
  -H 'Content-Type: application/json' -d '["4b1f…"]'
```

## Voir ce que le modèle verra

```bash
curl -G localhost:8081/api/agent/knowledge \
  -H "Authorization: Bearer $KEX_AGENT_API_KEY" \
  --data-urlencode 'query=quelle rétention sur demo.orders ?'
```

La route exécute **la même recherche** que celle faite avant chaque échange, seuil compris. Une
réponse décevante se diagnostique ici : si le passage attendu n'y est pas, le problème est dans la
base ou le seuil, pas dans le modèle.

## Réglages

| Propriété | Défaut | Effet |
|---|---|---|
| `top-k` | `4` | Passages injectés. Au-delà, on paie du contexte pour du bruit |
| `similarity-threshold` | `0.6` | Sous ce score, rien n'est injecté : un passage hors sujet oriente le modèle à côté, il vaut moins que pas de passage |
| `store-path` | *(vide)* | Fichier de persistance. Vide, la connaissance meurt avec le processus |

## Le magasin

`SimpleVectorStore` par défaut — en mémoire, sauvegardé à l'arrêt et rechargé au démarrage si
`store-path` est défini. La connaissance d'un agent tient souvent en quelques centaines de
passages ; une base vectorielle dédiée serait de l'infrastructure pour rien.

Au-delà, déclarer un autre bean `VectorStore` (pgvector, Redis, Elasticsearch — les starters
existent) le remplace sans toucher au reste : la condition est `@ConditionalOnMissingBean`.
