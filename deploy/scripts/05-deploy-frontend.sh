#!/bin/bash
# Sobe/atualiza o serviço Cloud Run do frontend — estático (nginx), sem os requisitos de "sempre
# ligado" do backend (sem poller/job em background aqui), pode escalar a zero.
#
# Depende de: 04-build-frontend.sh já ter rodado pra essa tag existir no Artifact Registry.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./lib.sh
load_env
require_vars GCP_PROJECT_ID GCP_REGION FRONTEND_SERVICE
require_gcloud

IMAGE="$(frontend_image)"
echo "==> Deploy do frontend ($FRONTEND_SERVICE) com a imagem $IMAGE..."
gcloud run deploy "$FRONTEND_SERVICE" \
  --image="$IMAGE" \
  --region="$GCP_REGION" \
  --platform=managed \
  --allow-unauthenticated \
  --port=8080 \
  --min-instances=0 \
  --max-instances=2 \
  --cpu=1 \
  --memory=256Mi \
  --quiet

FRONTEND_URL="$(gcloud run services describe "$FRONTEND_SERVICE" --region="$GCP_REGION" --format='value(status.url)')"
state_set frontend-url "$FRONTEND_URL"
echo "==> OK. Frontend em: $FRONTEND_URL"
