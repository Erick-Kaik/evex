#!/bin/bash
# Derruba tudo que `up.sh` subiu (frontend do Evex + Postgres do Evex). Não mexe no nó Hardhat (`yarn
# chain`), no backend do Evex (IDE) nem no Blockscout (sobe/derruba à parte — ver blockscout-down.sh).
set -e
cd "$(dirname "$0")/.."
LOG_DIR="$(pwd)/.logs"

echo "==> Parando o frontend do Evex..."
pidfile="$LOG_DIR/evex-frontend.pid"
if [ -f "$pidfile" ]; then
  pid=$(cat "$pidfile")
  # Mata o grupo de processos inteiro (`up.sh` sobe via `setsid`, PID = líder do grupo) — o processo
  # real do vite é filho do `npm`, que costuma sair antes; matar só "$pid" deixaria o vite órfão vivo.
  kill -- "-$pid" 2>/dev/null && echo "  parado: grupo de processos $pid ($pidfile)" || echo "  já não estava rodando: $pidfile"
  rm -f "$pidfile"
fi

echo "==> Derrubando o Postgres do Evex..."
docker compose down

echo ""
echo "Pronto. Nó Hardhat, backend do Evex e Blockscout (se estiverem rodando) precisam ser parados"
echo "manualmente (Blockscout: ./scripts/blockscout-down.sh)."
