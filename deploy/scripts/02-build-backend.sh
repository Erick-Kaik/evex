#!/bin/bash
# Builda a imagem do backend via Cloud Build e sobe pro Artifact Registry. Não builda local — não
# depende de Docker instalado na sua máquina, só do gcloud.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./lib.sh
load_env
require_vars GCP_PROJECT_ID GCP_REGION AR_BACKEND_REPO
require_gcloud

IMAGE="$(backend_image)"
echo "==> Build da imagem do backend: $IMAGE"
gcloud builds submit "$REPO_ROOT/backend" --tag="$IMAGE"

echo "==> OK. Imagem: $IMAGE"
