# Contrôles des outils et contexte sélectif

Les permissions MCP par connexion et la liste d'outils autorisés d'une tâche restent les premières
bornes. Des règles administrateur contrôlent ensuite les valeurs exactes d'arguments et les
ressources avant l'invocation. Le modèle, les descriptions d'outils et leurs annotations ne peuvent
ni créer ces règles ni élargir la liste autorisée.

## Configuration

Exemple à adapter aux noms exacts exposés par votre serveur :

```yaml
kex:
  agent:
    tools:
      selection-enabled: true
      max-selected-tools: 12
      max-input-characters: 32000
      max-result-characters: 12000
      denied-input-patterns:
        - 'SECRET-[0-9]+'
      denied-output-patterns:
        - 'SECRET-[0-9]+'
      rules:
        export_data:
          allowed-arguments:
            '[/topic]': [orders.prod]
          destinations:
            '[/callback]':
              hosts: [ops.example.org]
              schemes: [https]
        restart_cluster:
          denied: true
        kex_list_topics:
          read-only: true
```

Dans les maps Spring Boot, les crochets autour d'une clé JSON Pointer conservent son `/` littéral.
Chaque pointeur déclaré est obligatoire. Une valeur absente, un objet à la place d'une valeur,
une ressource hors liste, une destination avec identifiants, un fragment d'URL ou un protocole
non autorisé font refuser l'appel avant l'effet de bord. Les arguments ne sont jamais corrigés ou
réécrits. Les valeurs sensibles ne sont pas recopiées dans l'erreur de refus.

Les noms qualifiés `connexion__outil` utilisent le préfixe de callback MCP : les caractères hors
lettres, chiffres, `_` et `-` de la connexion sont remplacés par `_`. Une règle qualifiée prend
priorité sur la règle portant uniquement le nom de l'outil. Une règle portant le nom simple
s'applique aux callbacks qualifiés comme aux appels directs. Pour les callbacks statiques de
Spring AI, utiliser le nom exact exposé par ce provider.

La règle de destination compare des hôtes exacts en minuscules et des protocoles exacts. Elle
contrôle l'argument transmis ; elle ne suit pas les redirections, ne contrôle pas la résolution
DNS dans le serveur distant et ne constitue pas un pare-feu. Appliquer aussi les restrictions de
sortie réseau au serveur MCP, notamment pour interdire les redirections vers des réseaux privés.
Les politiques exactes ne remplacent pas les contrôles de droits dans le serveur MCP lui-même.

## Chemins couverts

- Callbacks statiques Spring AI et dynamiques MCP utilisés par le modèle, avec ou sans `ToolContext`.
- Outils locaux de mémoire exposés au modèle.
- `McpToolCatalog.call`, donc les appels directs de la supervision et des processus.

Les motifs d’arguments sensibles sont contrôlés sur les valeurs JSON décodées, les tableaux imbriqués
et les noms de champs, avant toute invocation. Les échappements Unicode JSON ne contournent donc
pas ce contrôle. Ne fournir que des motifs simples et bornés pour éviter des expressions régulières
coûteuses ; ce mécanisme ne détecte pas tous les secrets ni toutes les transformations possibles.

Les refus de résultats portent sur tous les contenus textuels et structurés des appels directs,
ainsi que sur le résultat remis au modèle. Le contrôle des motifs sensibles précède la troncature :
un secret au-delà du plafond n'est pas rendu acceptable par le fait qu'il serait masqué. Les motifs
sont définis par l'exploitant ; aucun détecteur universel de secrets n'est présumé. Un résultat
refusé **n'annule pas une mutation déjà exécutée** : il faut réconcilier son état avant toute reprise.

Les appels MCP directs ne sont réessayés que lorsque `read-only: true` est explicitement déclaré
par l'administrateur. Un outil non classé, même décrit par son serveur comme lecture seule, ne sera
pas automatiquement rejoué après une erreur ambiguë. Après migration, ajouter ces règles pour
les véritables lectures dont on souhaite conserver les réessais transitoires. Les annotations
MCP externes ne suffisent pas à autoriser un réessai.

## Sélection et résultats volumineux

La sélection est désactivée par défaut pour conserver la visibilité du catalogue existant. Même
désactivée, une liste de tâche restreint les outils réellement envoyés au modèle. Une liste vide
n'envoie aucun outil et évite la découverte des serveurs, notamment pendant la planification.
Aucun callback par défaut n'est ajouté par le `ChatClient` après la sélection.

Activée, la sélection classe les outils déjà autorisés selon les mots de leur nom présents dans
la demande, puis les départage par nom et limite leur nombre. En l'absence de correspondance, le
repli suit ce même ordre déterministe. Les descriptions et schémas externes ne servent pas au
classement. Ce mécanisme lexical est volontairement simple : tester les noms métier de votre
catalogue avant activation ; il ne résout pas la pertinence sémantique et peut omettre un outil
utile. La liste sélectionnée est aussi imposée à l'exécution, y compris aux tours ultérieurs.

Les résultats transmis au modèle sont bornés par `max-result-characters` (caractères UTF-16,
avant échappement XML). Un résultat tronqué porte `TRUNCATED`, sa taille originale et son SHA-256.
Le hash permet de reconnaître le résultat complet sans le stocker ni révéler une URL de lecture.
Pour poursuivre, demander à l'outil une plage ou une ressource plus précise ; aucune donnée tronquée
ne doit être présentée comme exhaustive. Les résultats complets des appels API directs demeurent
accessibles aux appelants autorisés et ne sont pas tronqués pour ne pas fausser leurs calculs.

Le balisage des résultats comme données non fiables continue d'exister. Ces contrôles réduisent
les effets possibles d'une injection indirecte ; ils ne garantissent pas qu'un modèle ignorera
une instruction malveillante. Les descriptions MCP et les schémas restent des métadonnées externes
à examiner lors de l'enregistrement du serveur.

## Vérification

`ToolInvocationPolicyTest`, `ToolSelectionServiceTest`, `McpToolPolicyTest`,
`RecordingToolCallbackProviderTest` et `AgentToolSelectionIntegrationTest` couvrent les refus avant
invocation, les listes vides, le non-élargissement des permissions, les destinations interdites,
les JSON ambigus, les sorties sensibles après le plafond et le non-réessai d'une mutation.
