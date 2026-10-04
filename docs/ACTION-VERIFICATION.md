# Vérification indépendante des actions

Une réponse MCP sans erreur confirme l'acceptation d'un appel. Elle ne prouve pas que le processus est revenu sous ses seuils. Chaque liaison d'action peut maintenant définir une postcondition administrateur, évaluée sans LLM par un second outil en lecture seule.

| État | Signification |
| --- | --- |
| `EXECUTED_UNVERIFIED` | Appel accepté ; aucun vérificateur indépendant configuré. |
| `VERIFIED` | Une mesure indépendante, structurée et fraîche confirme la postcondition. |
| `VERIFICATION_FAILED` | L'appel a été accepté, mais la mesure contredit la postcondition. |
| `VERIFICATION_UNKNOWN` | L'appel a été accepté, mais la preuve est indisponible, incomplète, périmée ou invalide. |
| `FAILED` | L'action elle-même a échoué. |
| `EXECUTED` | Ancien reçu d'exécution conservé pour les historiques ; aucune preuve indépendante implicite. |

Les états de vérification sont terminaux. Une approbation répétée ne rejoue pas la mutation. Le compteur `actionsExecuted` compte les appels acceptés, y compris ceux dont la vérification a échoué ou reste indéterminée ; ce compteur n'est pas un taux de réussite métier.

## Configuration

L'exemple ci-dessous définit un contrat illustratif pour les outils `restart` et `health` d'un serveur `ops`. Adapter les noms, arguments et pointeurs au schéma réel du serveur utilisé : ces outils ne sont pas fournis par Kex.

```yaml
kex:
  agent:
    tools:
      rules:
        "[ops__health]":
          read-only: true
    supervision:
      actions:
        RESTART_CONSUMER:
          connection: ops
          tool: restart
          arguments:
            consumerGroup: orders
          verification:
            connection: ops
            tool: health
            arguments:
              consumerGroup: orders
            result-pointer: /healthy
            expected-value: true
            measured-pointer: /measured
            observed-at-pointer: /observedAt
            max-age: 5m
```

La règle `read-only` est une autorisation locale administrateur. Une description ou annotation annoncée par le serveur MCP ne peut pas accorder ce droit. Les restrictions d'outils et d'arguments habituelles restent applicables. Le vérificateur doit être distinct de l'outil de mutation sur la connexion utilisée.

Le vérificateur doit retourner un objet `structuredContent`, par exemple :

```json
{
  "healthy": true,
  "measured": true,
  "observedAt": "2026-10-04T06:00:00Z",
  "coverage": {
    "complete": true,
    "stopReason": "EXHAUSTED",
    "topicsNotReached": [],
    "notReached": []
  }
}
```

`observedAt` doit dater la mesure effective après l'acceptation de l'action, ne pas être dans le futur et respecter `max-age` (5 minutes par défaut). Synchroniser les horloges de l'agent et du serveur de mesure. Un cache ancien ne constitue pas une preuve après action.

`result-pointer` et `observed-at-pointer` sont des JSON Pointers explicites. `expected-value` est un scalaire YAML typé : booléen, nombre ou chaîne. Les types doivent correspondre ; la chaîne `"true"` ne vaut pas le booléen `true`. Les nombres sont comparés par valeur. Les objets, tableaux, `null`, valeurs absentes et dates invalides rendent la vérification indéterminée.

`measured-pointer` est facultatif ; lorsqu'il est renseigné, il doit désigner le booléen `true`. Si une enveloppe `coverage` est présente, elle doit déclarer `complete: true` et `stopReason: EXHAUSTED`. Les listes `topicsNotReached` et `notReached`, lorsqu'elles sont présentes, doivent être des tableaux vides. Une réponse textuelle affirmant que l'action a réussi ne remplace jamais les données structurées.

Les arguments fixes du vérificateur reçoivent `processId` et `decisionId`, imposés par l'agent pour conserver la corrélation avec la décision. Les champs de preuve et le résultat de vérification apparaissent dans le résultat de décision et l'audit. La réception du webhook reste explicitement non vérifiée : elle ne prouve pas qu'un destinataire a traité la notification.

## Gouvernance et limites

La politique actuelle, la pause et les fenêtres de maintenance sont relues immédiatement avant l'exécution, y compris après une approbation humaine. Une capacité devenue interdite est bloquée. Une décision automatique devenue supervisée attend une validation humaine.

Le moteur effectue un seul relevé indépendant après l'action, sans nouvelle décision du modèle, sans attendre une convergence et sans compensation automatique. Si le système a besoin de temps pour se stabiliser, un relevé immédiat négatif reste un échec de postcondition à cet instant. L'opérateur examine la preuve avant de décider d'une nouvelle action. Une future procédure de convergence doit conserver un budget et un délai explicites, sans rejouer la mutation.

## Vérification du développement

Les tests de supervision couvrent : confirmation après appel, postcondition fausse sans réexécution, absence de vérificateur, panne ou erreur du vérificateur, lecture seule non autorisée, résultat textuel seul, preuve manquante, périmée ou incomplète, mutation refusée, changement de politique et pause avant approbation. Un test de binding conserve les configurations existantes et vérifie le contrat typé et la durée par défaut.

```sh
./mvnw -Dtest=SupervisionServiceTest,SupervisionCycleIntegrationTest,ActionBindingTest test
```
