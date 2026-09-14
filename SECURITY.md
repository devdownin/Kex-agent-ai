# Politique de sécurité

## Signaler une faille

**N'ouvrez pas d'issue publique.** Utilisez l'onglet Security du dépôt, « Report a vulnerability »,
qui ouvre un avis privé.

Merci d'inclure la version ou le commit, les étapes de reproduction, et l'impact tel que vous le
voyez. Une réponse est visée sous 7 jours.

## Surface à connaître avant de déployer

Trois points méritent une décision explicite, pas un défaut hérité.

**L'agent est fermé par défaut, gardez-le fermé.** Sans `kex.agent.api-key`, `/api/**` répond `503`.
Le bearer configuré est la seule barrière : il n'y a ni comptes, ni rôles, ni quotas par appelant.

**L'appel direct d'outil n'a pas de modèle dans la boucle.**
`POST /api/agent/mcp/servers/{connection}/tools/{tool}` exécute l'outil demandé. Ce que le serveur
MCP accepte, cet endpoint le fera faire. L'autorisation est entièrement à la charge de l'appelant.

**Les outils MCP s'exécutent avec les droits de leur serveur.** L'agent hérite des garde-fous du
serveur (lecture seule, deny-list, limitation, audit chez Kafka SQL Explorer) ; il n'en ajoute pas.
Un serveur MCP de confiance douteuse est un risque que l'agent ne filtre pas.

## Dispositions déjà en place

- Ports liés à la boucle locale par défaut dans les stacks compose.
- Jeton MCP injecté seulement sur le préfixe d'URL déclaré : le secret d'un serveur ne part pas
  vers un autre.
- `/actuator/prometheus` authentifié — il porte le modèle, les jetons consommés et les outils
  appelés. Seul `/actuator/health` est ouvert.
- Plafonds sur la boucle d'outils, la durée d'un échange et le débit des routes de chat.
- Conteneur exécuté sous l'UID `10001`.
- CodeQL par pull request et chaque semaine, Dependabot sur maven, actions et docker.
