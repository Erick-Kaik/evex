#!/bin/bash
# Derruba só os serviços do Blockscout (profile "blockscout" — ver blockscout-up.sh/DEC-059). Não mexe
# no Postgres do Evex, que segue no ar via `down.sh` normal.
set -e
cd "$(dirname "$0")/.."

# Mesmo resolvedor de Compose do up.sh (ver comentário lá).
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "ERRO: Compose não encontrado. Instale: sudo apt install docker-compose-v2"
  exit 1
fi

echo "==> Derrubando Blockscout..."
# `docker compose down` ignora o profile pra decidir o que remover (profile só filtra o `up`) — sem
# listar os serviços explicitamente aqui, ele derruba o projeto inteiro, `evex-postgres` incluso.
$DC down redis-db db-init db dets-logs-init blockscout-backend

echo ""
echo "Pronto."
