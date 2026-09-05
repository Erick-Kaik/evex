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

# Mesmo resolvedor de Compose do up.sh (ver comentário lá).
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "ERRO: Compose não encontrado. Instale: sudo apt install docker-compose-v2"
  exit 1
fi

# Com Docker nativo no WSL (não mais Docker Desktop), o Blockscout — que roda em container — alcança
# o host pelo gateway da bridge (host.docker.internal -> host-gateway). Um nó Hardhat preso em
# 127.0.0.1 fica INALCANÇÁVEL de dentro do container: o Blockscout indexa ZERO blocos
# ("Index already caught up... last_block_number: null") e o discovery/E6 quebra sem erro visível.
# Fix: `yarn chain --hostname 0.0.0.0` (ver local-env/README.md §3.1 e docs/decisions.md DEC-069).
# Checagem só de aviso — não aborta, pra não atrapalhar quem já sabe o que está fazendo.
echo "==> Checando se o nó Hardhat é alcançável de dentro de um container..."
if ! docker run --rm --add-host host.docker.internal:host-gateway alpine sh -c \
    'wget -qO- --timeout=3 --post-data="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_blockNumber\",\"params\":[]}" --header="content-type: application/json" http://host.docker.internal:8545' >/dev/null 2>&1; then
  echo "    AVISO: o nó Hardhat NÃO respondeu de dentro de um container (host.docker.internal:8545)."
  echo "           Provável causa: o nó está em 127.0.0.1, não em 0.0.0.0 — o Blockscout vai indexar"
  echo "           zero blocos. Reinicie o Terminal 1 com:  yarn chain --hostname 0.0.0.0"
  echo "           (Seguindo mesmo assim — só um aviso.)"
fi

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

# O `docker/Dockerfile` do Blockscout compila as deps Elixir com
# `MIX_OS_DEPS_COMPILE_PARTITION_COUNT=$(nproc)` — sobe `nproc` (8 nesta máquina) processos OS `mix`
# (BEAM) em paralelo, que precisam se conectar a um coordenador via socket TCP local. Numa WSL2 com
# RAM apertada e outras coisas rodando, subir 8 BEAM de uma vez estoura o timeout de conexão do
# coordenador e o build morre com "could not start partition dependency compiler, no connection made
# to TCP port: {:error, :timeout}" (ver DEC-065). Fix: forçar partição = 1 (sem paralelismo entre
# VMs, sem o handshake TCP). Idempotente — reaplica se o clone for refeito.
DOCKERFILE="local-env-blockscout/docker/Dockerfile"
if grep -q 'MIX_OS_DEPS_COMPILE_PARTITION_COUNT=\$(nproc)' "$DOCKERFILE"; then
  echo "==> Ajustando $DOCKERFILE: MIX_OS_DEPS_COMPILE_PARTITION_COUNT \$(nproc) -> 1 (DEC-065)..."
  sed -i 's#MIX_OS_DEPS_COMPILE_PARTITION_COUNT=\$(nproc)#MIX_OS_DEPS_COMPILE_PARTITION_COUNT=1#' "$DOCKERFILE"
fi

echo "==> Subindo Blockscout (profile 'blockscout' — build automático se ainda não buildado; o fix de"
echo "    permissão dos volumes dets/logs da DEC-056 já roda sozinho, serviço 'dets-logs-init')..."
$DC --profile blockscout up -d --build

echo ""
echo "Pronto. API real do Blockscout: http://localhost:4001"
echo "Lembre de configurar EVEX_BLOCKSCOUT_URL=http://localhost:4001 no backend do Evex (IDE)."
echo "Pra derrubar: ./scripts/blockscout-down.sh"
