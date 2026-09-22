# Kex, agent généraliste à gouvernance forte

Kex n'est pas limité à Kafka : ses outils MCP peuvent observer des fichiers, GitHub, une base SQL ou un système métier. Kafka reste un exemple d'intégration, pas une dépendance conceptuelle.

## Mémoire et apprentissage

La fenêtre de conversation reste bornée. En complément, Kex conserve des résumés et des faits par identité authentifiée. Les procédures déduites d'une tâche réussie sont enregistrées en `PENDING` ; elles ne sont jamais chargées comme compétences exécutables avant l'approbation nominative d'un opérateur. Le profil `shared-memory` persiste ces données en PostgreSQL ; le mode local les écrit dans `~/.kex/memory`.

## Canaux et planification

Slack, Teams et e-mail sont des sorties configurées par l'administrateur. Par défaut, un message d'approbation ouvre la console ; le clic ne donne aucun droit et l'action exige toujours une authentification, une décision encore pendante et les seuils Kex. Avec `channels.slack-signing-secret` renseignée, Slack gagne aussi deux boutons Approuver/Refuser qui agissent directement — la signature propre à l'interactivité de Slack et une correspondance expéditeur → opérateur déclaré remplacent alors le bearer, jamais le contournent. Teams n'a pas d'équivalent : une Adaptive Card ne livre nulle part sans un Bot Framework enregistré, son message reste un lien.

Les automatisations sont désactivées par défaut. Une tâche récurrente possède un cron à six champs, un fuseau IANA et une liste d'outils en lecture seule configurée par l'administrateur. La revendication atomique en base évite les doublons entre instances.

## Modèles locaux

Les profils `application-ollama.yml` et `application-vllm.yml` utilisent leurs endpoints OpenAI-compatibles locaux. Le routage `CHAT`, `TRIAGE`, `DIAGNOSTIC` et la bascule ne rejouent pas une boucle d'outils déjà exécutée ; une bascule de flux n'est permise qu'avant le premier élément reçu et uniquement pour un échec transport, HTTP 429 ou 5xx.

## Extensibilité et isolation

Kex peut exposer un serveur MCP HTTP opt-in (`/api/agent/mcp-server`). Sa première surface est volontairement en lecture seule : statut, vue d'ensemble, alertes, incidents corrélés et décisions en attente. Les cycles, pauses, approbations et autres mutations restent dans l'API et la console opérateur jusqu'à ce que leur modèle d'autorisation et d'audit MCP soit défini. Le catalogue installe des connexions connues, désactivées par défaut. Les serveurs stdio peuvent être isolés par tâche dans Docker, sans montage du socket, avec réseau désactivé, utilisateur non privilégié, système de fichiers en lecture seule et limites de ressources. Il n'existe aucun repli silencieux vers l'hôte.

`bin/kex migrate --source <openclaw> --output <directory>` fonctionne en simulation par défaut et importe uniquement les compétences Markdown comme propositions soumises à revue ; les secrets et exécutions arbitraires sont ignorés.

## Mode local sans Docker

Le JAR Spring Boot suffit pour un déploiement local :

```sh
./mvnw -DskipTests package
java -jar target/kex-agent-ai-*.jar
```

Pour une image native, la compilation GraalVM est une étape de distribution optionnelle ; les transports MCP dynamiques et les modèles doivent être déclarés dans les hints de compilation de l'installation cible.

Exemples non-Kafka : connecter le serveur MCP filesystem pour comparer des fichiers de livraison, GitHub en lecture seule pour diagnostiquer une release, ou un serveur SQL en lecture seule pour contrôler un rapprochement. Dans tous les cas, autonomie, seuils de confiance et audit restent les mêmes.
