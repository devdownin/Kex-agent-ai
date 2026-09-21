# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
# Le wrapper Maven plutôt qu'une image maven:*-temurin-25 : la version de Maven est celle
# du dépôt, et la construction ne dépend pas de l'existence d'un tag amont.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# Cache BuildKit du dépôt local : une reconstruction après modification du code ne
# retélécharge pas les dépendances, sans étape dependency:go-offline distincte.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
# Pas de HEALTHCHECK ici : l'image JRE n'embarque ni curl ni wget, et les installer pour
# sonder /actuator/health coûterait une couche apt. La sonde est dans docker-compose.yml.
#
# `pebble` (le superviseur de service de l'image Ubuntu de base, pas notre code) n'est jamais
# invoqué — l'ENTRYPOINT lance `java` directement, en PID 1. Retiré plutôt que laissé mort :
# `publish.yml` scanne l'image avant de la publier, et un binaire qu'on ne peut ni corriger (il
# n'est pas dans notre arbre de dépendances) ni justifier de garder bloquerait chaque publication
# jusqu'à ce qu'une image de base amont le corrige. `-f` : ne pas faire échouer la construction si
# une future image de base ne le porte plus.
RUN rm -f /usr/bin/pebble

# Tout ce que l'agent retient hors base : souvenirs long terme, compétences approuvées, charte,
# et les connexions MCP chiffrées créées dans la console. Le chemin est posé explicitement plutôt
# que laissé à `user.home` : l'uid 10001 n'a pas d'entrée dans /etc/passwd, `getpwuid` n'a donc
# rien à rendre, et la valeur de repli varie selon la JVM. Un chemin absolu ne dépend d'aucune de
# ces deux choses.
#
# VOLUME pour que ces fichiers n'atterrissent jamais dans la couche d'écriture du conteneur, qui
# part avec lui : sans lui, une compétence approuvée par un humain disparaissait au premier
# `docker compose up --force-recreate`. Un volume nommé reste préférable, et docker-compose.yml
# en monte un — celui-ci n'est que le filet.
ENV KEX_AGENT_MEMORY_STORAGE_DIRECTORY=/var/lib/kex/memory \
    KEX_MCP_RUNTIME_STORAGE_PATH=/var/lib/kex/mcp-servers.enc
RUN install -d -m 700 -o 10001 -g 10001 /var/lib/kex /var/lib/kex/memory
VOLUME /var/lib/kex

EXPOSE 8081
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
