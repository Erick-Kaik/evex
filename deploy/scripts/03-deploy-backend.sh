#!/bin/bash
# Sobe/atualiza o serviço Cloud Run do backend. Container único (sem sidecar/manifesto YAML) — a
# conexão com o banco é uma JDBC URL comum (SPRING_DATASOURCE_URL), a mesma env var que o backend já
# lê localmente (ver backend/src/main/resources/application.yml); o endereço é alcançável porque o
# banco está na mesma org/VPC e o Cloud Run sai por ela via --vpc-connector.
#
# Depende de: 02-build-backend.sh já ter rodado pra essa tag existir no Artifact Registry.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./lib.sh
load_env
require_vars GCP_PROJECT_ID GCP_REGION BACKEND_SERVICE VPC_CONNECTOR \
  EVEX_RPC_URL EVEX_NETWORK SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
require_gcloud

IMAGE="$(backend_image)"
# CORS: placeholder até 05-deploy-frontend.sh existir de fato — 06-update-cors.sh sobrescreve com a
# URL real do front depois, só variável de ambiente, sem rebuild.
CORS_PLACEHOLDER="${EVEX_CORS_ALLOWED_ORIGINS:-http://localhost:5173}"

echo "==> Deploy do backend ($BACKEND_SERVICE) com a imagem $IMAGE..."
gcloud run deploy "$BACKEND_SERVICE" \
  --image="$IMAGE" \
  --region="$GCP_REGION" \
  --platform=managed \
  --allow-unauthenticated \
  --port=8080 \
  --vpc-connector="$VPC_CONNECTOR" \
  --no-cpu-throttling \
  --min-instances=1 \
  --max-instances=1 \
  --timeout=3600 \
  --set-env-vars="^;^EVEX_RPC_URL=${EVEX_RPC_URL};EVEX_NETWORK=${EVEX_NETWORK};EVEX_BLOCKSCOUT_URL=${EVEX_BLOCKSCOUT_URL:-};SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL};SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME};SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD};EVEX_CORS_ALLOWED_ORIGINS=${CORS_PLACEHOLDER}" \
  --quiet

BACKEND_URL="$(gcloud run services describe "$BACKEND_SERVICE" --region="$GCP_REGION" --format='value(status.url)')"
state_set backend-url "$BACKEND_URL"
echo "==> OK. Backend em: $BACKEND_URL"
