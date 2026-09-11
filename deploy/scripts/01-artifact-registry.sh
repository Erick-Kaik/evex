#!/bin/bash
# Cria os 2 repositórios Docker no Artifact Registry (1 por app) e configura a autenticação do
# `docker`/Cloud Build pra eles. Idempotente — pula o que já existir.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./lib.sh
load_env
require_vars GCP_PROJECT_ID GCP_REGION AR_BACKEND_REPO AR_FRONTEND_REPO
require_gcloud

ensure_ar_repo() {
  local repo="$1"
  if gcloud artifacts repositories describe "$repo" --location="$GCP_REGION" >/dev/null 2>&1; then
    echo "==> repositório '$repo' já existe."
  else
    echo "==> criando repositório '$repo'..."
    gcloud artifacts repositories create "$repo" \
      --repository-format=docker \
      --location="$GCP_REGION" \
      --description="Evex — imagens de $repo" \
      >/dev/null
  fi
}

ensure_ar_repo "$AR_BACKEND_REPO"
ensure_ar_repo "$AR_FRONTEND_REPO"
gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet >/dev/null
echo "==> OK."
