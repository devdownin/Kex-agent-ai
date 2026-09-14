# Journal des modifications

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).
Versionnement [sémantique](https://semver.org/lang/fr/).

## [Non publié]

### Ajouté

- Base de connaissance optionnelle (RAG) : ingestion, recherche et purge par API, recherche
  automatique avant chaque échange. Éteinte par défaut.
- Événements `tool` dans le flux SSE et champ `tools` sur la réponse bloquante.
- Route de sortie structurée contre un schéma JSON fourni par l'appelant.

- Agent conversationnel Spring Boot 4 / Spring AI 2 sur Java 25, client de serveurs MCP
  (stdio, SSE, streamable-HTTP).
- Connexion par défaut au serveur MCP de Kafka SQL Explorer, avec injection du bearer restreinte au
  préfixe d'URL déclaré.
- API REST : chat synchrone, chat en flux SSE, purge de conversation, introspection des serveurs
  MCP, appel direct d'un outil, listage et lecture de ressources.
- Authentification par bearer sur `/api/**`, fermée par défaut (`503` sans clé configurée).
- Limite de débit sur les routes de chat, plafond de 20 appels d'outils par échange, plafond de
  durée d'un échange.
- Métriques de coût et d'outils exposées en Prometheus, endpoint authentifié.
- Mémoire de conversation partagée en option (profil `shared-memory`, PostgreSQL).
- Stacks Docker Compose : stack complète avec Kafka et l'Explorer, surcouche mémoire partagée,
  stack de fumée pour la CI.
- Documentation : README bilingue, architecture, guide MCP, configuration, observabilité.
- CI : tests, construction et démarrage de l'image, stack de fumée montée, CodeQL, Dependabot,
  publication d'image multi-arch sur GHCR au tag.
