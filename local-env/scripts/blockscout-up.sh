#!/bin/bash
# Sobe só o Blockscout real (build do source na primeira vez — pode levar alguns minutos), via o
# profile "blockscout" do mesmo docker-compose.yml (DEC-059). Separado do up.sh de propósito: é a
# parte mais pesada do ambiente inteiro (Elixir + Postgres próprio + Redis) e só é necessária pra
# testar discovery/E6 especificamente — sobe/derruba sob demanda, não no dia a dia normal.
#
# Uso: `./scripts/blockscout-up.sh` (de dentro de `local-env/`), com `yarn chain` + `up.sh` já rodando.
# API real fica em http://localhost:4001.
set -e
cd "$(dirname "$0")/.."

# `yarn chain` é in-memory (zera a cada restart), mas o Postgres do Blockscout é um bind mount em
# disco (não some com `blockscout-down.sh`) — sem limpar, o indexer fica preso reprocessando jobs
# (debug_traceTransaction) de transações de uma instância antiga do Hardhat que não existe mais,
# gerando "Unable to find a block containing transaction ..." pra sempre no log do nó (achado real,
# ver DEC-061). Como este Blockscout é só ferramenta de teste descartável, o mais simples e seguro é
# sempre recomeçar do zero — reindexar uma chain de teste pequena é rápido.
echo "==> Limpando estado anterior do Blockscout (Postgres + Redis, bind mounts em disco)..."
docker run --rm \
  -v "$(pwd)/local-env-blockscout/docker-compose/services/blockscout-db-data:/wipe-db" \
  -v "$(pwd)/local-env-blockscout/docker-compose/services/redis-data:/wipe-redis" \
  alpine sh -c 'rm -rf /wipe-db/* /wipe-db/.[!.]* /wipe-redis/* /wipe-redis/.[!.]* 2>/dev/null; true'

echo "==> Subindo Blockscout (profile 'blockscout' — build automático se ainda não buildado; o fix de"
echo "    permissão dos volumes dets/logs da DEC-056 já roda sozinho, serviço 'dets-logs-init')..."
docker compose --profile blockscout up -d --build

echo ""
echo "Pronto. API real do Blockscout: http://localhost:4001"
echo "Lembre de configurar EVEX_BLOCKSCOUT_URL=http://localhost:4001 no backend do Evex (IDE)."
echo "Pra derrubar: ./scripts/blockscout-down.sh"
