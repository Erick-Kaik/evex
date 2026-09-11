# syntax=docker/dockerfile:1
#
# Build context: RAIZ DO REPOSITÓRIO, não `frontend/` — `npm run generate:api` (roda no `prebuild`)
# faz `redocly bundle ../backend/src/main/resources/swagger/base/base.yaml`, então o build precisa
# enxergar `backend/` também. Rodar como (a partir da raiz):
#   docker build -f frontend/Dockerfile -t evex-frontend .
# (é o que `deploy/deploy.sh` faz).

# ---- Build stage ------------------------------------------------------------------------------
FROM node:20-bookworm AS build

# `openapi-generator-cli` (usado por `npm run generate:api`) baixa um .jar na 1ª execução e roda via
# `java -jar` por baixo — precisa de JRE no ambiente de build, não só Node (gotcha só percebido ao
# containerizar; localmente passa despercebido porque quem já rodou o front antes tem Java instalado
# por causa do próprio backend Kotlin).
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /repo
COPY backend/src/main/resources/swagger ./backend/src/main/resources/swagger
COPY frontend ./frontend
WORKDIR /repo/frontend
RUN npm ci

# URL do backend Evex é assada no bundle JS pelo Vite em BUILD-TIME (`import.meta.env`, ver
# `src/api/config.ts`) — não é variável de runtime. Por isso o backend precisa estar deployado
# ANTES deste build (`deploy/deploy.sh` já respeita essa ordem).
ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# ---- Runtime stage: nginx servindo os estáticos, escutando na porta que o Cloud Run injeta -----
FROM nginx:1.27-alpine

# A imagem oficial do nginx faz envsubst automático de `/etc/nginx/templates/*.template` pra
# `/etc/nginx/conf.d/` no entrypoint padrão, substituindo só as env vars que existirem de fato no
# processo (não mexe em variáveis nativas do nginx tipo $uri/$host, que não são env vars) — não
# precisa de entrypoint customizado.
COPY frontend/deploy/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /repo/frontend/dist /usr/share/nginx/html

ENV PORT=8080
EXPOSE 8080
