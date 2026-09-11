#!/bin/bash
# Roda os 6 scripts de deploy/scripts/ em ordem. Cada um é independente e idempotente (ver
# deploy/README.md) — rode este arquivo pra fazer tudo de uma vez, ou os scripts individualmente
# pra repetir só uma etapa (ex.: mudou só o front, roda direto o 04 e o 05).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

for step in scripts/01-artifact-registry.sh \
            scripts/02-build-backend.sh \
            scripts/03-deploy-backend.sh \
            scripts/04-build-frontend.sh \
            scripts/05-deploy-frontend.sh \
            scripts/06-update-cors.sh; do
  echo ""
  echo "=================================================================================="
  echo "  $step"
  echo "=================================================================================="
  ./"$step"
done

echo ""
echo "==> Deploy concluído."
echo "    Backend:  $(cat .state/backend-url)"
echo "    Frontend: $(cat .state/frontend-url)"
