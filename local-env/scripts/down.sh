#!/bin/bash
# Derruba tudo que `up.sh` subiu (frontend do Evex + control-panel + Postgres do Evex). Não mexe no nó Hardhat (`yarn
# chain`), no backend do Evex (IDE) nem no Blockscout (sobe/derruba à parte — ver blockscout-down.sh).
set -e
cd "$(dirname "$0")/.."
LOG_DIR="$(pwd)/.logs"

# Mesmo resolvedor de Compose do up.sh (ver comentário lá).
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "ERRO: Compose não encontrado. Instale: sudo apt install docker-compose-v2"
  exit 1
fi

stop_group() { # $1 = label, $2 = pidfile
  local pidfile="$2"
  if [ -f "$pidfile" ]; then
    local pid
    pid=$(cat "$pidfile")
    # Mata o grupo de processos inteiro (`up.sh` sobe via `setsid`, PID = líder do grupo) — o processo
    # real (vite / tsx) costuma ser filho do `npm`/`npx`, que sai antes; matar só "$pid" deixa órfão.
    kill -- "-$pid" 2>/dev/null && echo "  parado: grupo $pid ($1)" || echo "  já não estava rodando: $1"
    rm -f "$pidfile"
  fi
}

echo "==> Parando o frontend do Evex..."
stop_group "frontend do Evex" "$LOG_DIR/evex-frontend.pid"

echo "==> Parando o control-panel..."
stop_group "control-panel" "$LOG_DIR/evex-control-panel.pid"

echo "==> Derrubando o Postgres do Evex..."
$DC down

echo ""
echo "Pronto. Nó Hardhat, backend do Evex e Blockscout (se estiverem rodando) precisam ser parados"
echo "manualmente (Blockscout: ./scripts/blockscout-down.sh)."
