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
EXPOSE 8081
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
