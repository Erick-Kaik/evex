#!/usr/bin/env bash
# Roda a suíte E2E do Evex (ver e2e/CLAUDE.md / e2e/README.md).
#
# NÃO sobe a stack — isso você faz à parte, nos seus terminais/IDE:
#   1. Hardhat:    cd local-env/local-env-scaffold-eth-2 && yarn chain --hostname 0.0.0.0
#   2. local-env:  cd local-env && ./scripts/up.sh          (contratos + eventos + Postgres + frontend)
#   3. backend:    pela IDE  (EVEX_RPC_URL=http://localhost:8545, EVEX_BLOCKSCOUT_URL=http://localhost:4001)
#   4. discovery:  cd local-env && ./scripts/blockscout-up.sh   (opcional — só pro spec 02)
#
# Este script só: garante .env + deps + Chromium, faz um pre-flight rápido com mensagem clara, e
# chama o `playwright test`.
#
# Uso:
#   ./scripts/run.sh                # roda tudo (tolera estado preexistente; spec 06 skipa)
#   ./scripts/run.sh --clean        # = E2E_RESET_DB=1 E2E_RUN_SEED=1 (rodada determinística, habilita o 06)
#   ./scripts/run.sh --headed       # navegador visível
#   ./scripts/run.sh --ui           # modo interativo do Playwright
#   ./scripts/run.sh 04-events      # filtra por arquivo/título (arg nativo do playwright test)
#   flags desconhecidas são repassadas pro `playwright test`.
set -euo pipefail
cd "$(dirname "$0")/.."

# ---- .env (as portas customizadas entram no pre-flight; o dotenv do config.ts relê o mesmo arquivo) ----
if [ ! -f .env ]; then
  echo "==> criando .env a partir de .env.example"
  cp .env.example .env
fi
set -a; . ./.env; set +a

BASE_URL="${E2E_BASE_URL:-http://localhost:5173}"
API_URL="${E2E_API_URL:-http://localhost:8080}"
RPC_URL="${E2E_RPC_URL:-http://127.0.0.1:8545}"
BLOCKSCOUT_URL="${E2E_BLOCKSCOUT_URL:-}"

# ---- deps + browser ----
if [ ! -d node_modules ]; then
  echo "==> npm ci"
  npm ci
fi
echo "==> garantindo o Chromium do Playwright (no-op se já instalado)..."
npx playwright install chromium >/dev/null

# ---- pre-flight ----
ok=1
rpc_up()  { curl -sf -m3 -X POST "$1" -H 'content-type: application/json' \
              -d '{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}' >/dev/null; }
http_up() { curl -sf -m3 "$1" >/dev/null; }

echo "==> Pre-flight da stack:"
if rpc_up  "$RPC_URL";                 then echo "  ✅ nó Hardhat       ($RPC_URL)"; else echo "  ❌ nó Hardhat       ($RPC_URL)"; ok=0; fi
if http_up "$API_URL/actuator/health"; then echo "  ✅ backend do Evex  ($API_URL)"; else echo "  ❌ backend do Evex  ($API_URL)"; ok=0; fi
if http_up "$BASE_URL";                then echo "  ✅ frontend do Evex ($BASE_URL)"; else echo "  ❌ frontend do Evex ($BASE_URL)"; ok=0; fi
if [ -n "$BLOCKSCOUT_URL" ]; then
  if http_up "$BLOCKSCOUT_URL/api/v2/stats"; then
    blocks="$(curl -sf -m3 "$BLOCKSCOUT_URL/api/v2/stats" | grep -o '"total_blocks":"[0-9]*"' | grep -o '[0-9]*' || echo 0)"
    if [ "${blocks:-0}" -gt 0 ]; then echo "  ✅ Blockscout       ($BLOCKSCOUT_URL, $blocks blocos)"
    else echo "  ⚠️  Blockscout       ($BLOCKSCOUT_URL, indexou 0 blocos — spec 02 vai skipar; nó Hardhat em 0.0.0.0?)"; fi
  else
    echo "  ⚠️  Blockscout       ($BLOCKSCOUT_URL fora do ar — spec 02 vai skipar)"
  fi
else
  echo "  ·  Blockscout       (E2E_BLOCKSCOUT_URL vazio — spec 02 vai skipar)"
fi

if [ "$ok" != 1 ]; then
  cat <<EOF

Falta subir alguma peça obrigatória. Nos seus terminais:
  1. Hardhat:    cd local-env/local-env-scaffold-eth-2 && yarn chain --hostname 0.0.0.0
  2. local-env:  cd local-env && ./scripts/up.sh
  3. backend:    pela IDE, com EVEX_RPC_URL=http://localhost:8545  (e EVEX_BLOCKSCOUT_URL p/ discovery)
  4. discovery:  cd local-env && ./scripts/blockscout-up.sh   (opcional)
EOF
  exit 1
fi

# ---- flags ----
PW_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --clean) export E2E_RESET_DB=1 E2E_RUN_SEED=1 ; echo "==> modo --clean: E2E_RESET_DB=1 E2E_RUN_SEED=1" ;;
    *) PW_ARGS+=("$arg") ;;
  esac
done

echo "==> playwright test ${PW_ARGS[*]:-}"
exec npx playwright test ${PW_ARGS[@]+"${PW_ARGS[@]}"}
