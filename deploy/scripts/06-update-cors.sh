#!/bin/bash
# Fecha o CORS do backend com a URL real do front — só variável de ambiente (EVEX_CORS_ALLOWED_ORIGINS,
# lida por config/SecurityConfig.kt), sem rebuild de imagem nenhuma.
#
# Depende de: 03-deploy-backend.sh e 05-deploy-frontend.sh já terem rodado.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./lib.sh
load_env
require_vars GCP_PROJECT_ID GCP_REGION BACKEND_SERVICE
require_gcloud

FRONTEND_URL="$(state_get frontend-url)"
echo "==> Atualizando EVEX_CORS_ALLOWED_ORIGINS do backend para: $FRONTEND_URL"
gcloud run services update "$BACKEND_SERVICE" \
  --region="$GCP_REGION" \
  --update-env-vars="EVEX_CORS_ALLOWED_ORIGINS=${FRONTEND_URL}" \
  --quiet >/dev/null

echo "==> OK."
