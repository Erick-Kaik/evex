#!/bin/bash
# Builda a imagem do frontend via Cloud Build, já com a URL real do backend assada no bundle JS
# (build-arg VITE_API_BASE_URL — é build-time, não runtime, ver frontend/src/api/config.ts).
#
# Depende de: 03-deploy-backend.sh já ter rodado (lê a URL do backend salva por ele).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./lib.sh
load_env
require_vars GCP_PROJECT_ID GCP_REGION AR_FRONTEND_REPO
require_gcloud

BACKEND_URL="$(state_get backend-url)"
IMAGE="$(frontend_image)"

echo "==> Build da imagem do frontend: $IMAGE (backend: $BACKEND_URL)"
gcloud builds submit "$REPO_ROOT" \
  --config="$REPO_ROOT/frontend/deploy/cloudbuild.yaml" \
  --substitutions="_IMAGE=${IMAGE},_VITE_API_BASE_URL=${BACKEND_URL}"

echo "==> OK. Imagem: $IMAGE"
