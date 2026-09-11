#!/bin/bash
# Helpers compartilhados pelos scripts de deploy/scripts/*.sh — nunca executado sozinho, só via
# `source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"` no topo de cada um.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$DEPLOY_DIR")"
STATE_DIR="$DEPLOY_DIR/.state"
mkdir -p "$STATE_DIR"

# --- .env ----------------------------------------------------------------------------------------
load_env() {
  local env_file="$DEPLOY_DIR/.env"
  if [ ! -f "$env_file" ]; then
    echo "ERRO: $env_file não existe. Copie o template e preencha:"
    echo "      cp deploy/.env.example deploy/.env"
    exit 1
  fi
  # shellcheck disable=SC1090
  source "$env_file"
}

require_vars() {
  local v
  for v in "$@"; do
    if [ -z "${!v:-}" ]; then
      echo "ERRO: variável $v vazia em deploy/.env."
      exit 1
    fi
  done
}

# --- gcloud ----------------------------------------------------------------------------------------
require_gcloud() {
  if ! command -v gcloud >/dev/null 2>&1; then
    echo "ERRO: gcloud CLI não encontrado. Ver deploy/README.md seção 1 (instalação)."
    exit 1
  fi
  if ! gcloud auth list --filter=status:ACTIVE --format='value(account)' | grep -q .; then
    echo "ERRO: nenhuma conta ativa no gcloud. Rode 'gcloud auth login' primeiro."
    exit 1
  fi
  gcloud config set project "$GCP_PROJECT_ID" >/dev/null
}

# --- tag/imagem --------------------------------------------------------------------------------
# Short SHA do commit atual — mesma tag em todos os scripts da mesma rodada, sem precisar passar
# por variável de ambiente entre eles (determinística: mesmo commit = mesma tag).
image_tag() {
  git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S
}

backend_image() {
  echo "${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_BACKEND_REPO}/evex-backend:$(image_tag)"
}

frontend_image() {
  echo "${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_FRONTEND_REPO}/evex-frontend:$(image_tag)"
}

# --- estado entre scripts (URLs descobertas por um script e lidas por outro) --------------------
# deploy/.state/ é gitignored — só cache local entre etapas de uma mesma rodada, não é segredo.
state_set() { echo -n "$2" > "$STATE_DIR/$1"; }
state_get() {
  local file="$STATE_DIR/$1"
  if [ ! -f "$file" ]; then
    echo "ERRO: $STATE_DIR/$1 não existe — rode o script anterior primeiro (ver deploy/README.md)." >&2
    exit 1
  fi
  cat "$file"
}
