# syntax=docker/dockerfile:1
#
# Build context: `backend/` (roda como `docker build -t evex-backend backend/`, ou via
# `deploy/deploy.sh`) — diferente do frontend, o build do backend não precisa enxergar nada fora
# desta pasta.

# ---- Build stage ------------------------------------------------------------------------------
# eclipse-temurin:21-jdk é Debian (Jammy) por baixo — dá pra instalar Node via NodeSource sem trocar
# de imagem base. Node é necessário aqui porque a task `bundleEvexApi` (build.gradle.kts) chama
# `npx @redocly/cli bundle` antes do plugin openapi-generator rodar — uma imagem JDK pura não builda.
FROM eclipse-temurin:21-jdk-jammy AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

# ---- Runtime stage ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Cloud Run injeta $PORT (server.port em application.yml honra a env var; default 8080 fora dele).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
