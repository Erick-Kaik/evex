#!/bin/bash
# Sobe o essencial do local-env: contratos deployados + eventos de teste + Postgres do Evex + frontend
# do Evex. Fora daqui, de propósito (ver DEC-059):
#   - Nó Hardhat (`yarn chain`, roda à parte — ver README seção 3.1) e backend do Evex (sobe pela IDE,
#     ver README seção 3.5).
#   - UI do scaffold-eth-2 (`/debug`) — os eventos já saem daqui via `seed/emit-events.ts`, não precisa
#     subir um Next.js dev server só pra clicar em botões. Se quiser mesmo assim: `yarn start` em
#     `local-env-scaffold-eth-2/` (README seção 3.7).
#   - Blockscout — é a parte mais pesada do ambiente inteiro e só é necessária pra testar
#     discovery/E6. Sobe/derruba sob demanda: `./scripts/blockscout-up.sh` / `blockscout-down.sh`.
#
# Uso: `./scripts/up.sh` (de dentro de `local-env/`), com `yarn chain` já rodando em outro terminal.
set -e
cd "$(dirname "$0")/.."
LOG_DIR="$(pwd)/.logs"
mkdir -p "$LOG_DIR"

# Resolve o comando do Compose: plugin v2 (`docker compose`) ou o standalone legado (`docker-compose`).
# O Docker nativo do WSL/Ubuntu (pacote `docker.io`) NÃO traz o plugin do compose por padrão — sem ele
# `docker compose up` cai no CLI cru e dá "unknown shorthand flag: 'd' in -d". Instalar com:
#   sudo apt install docker-compose-v2
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "ERRO: Compose não encontrado (nem o plugin 'docker compose' v2, nem 'docker-compose')."
  echo "      Instale o plugin: sudo apt install docker-compose-v2"
  exit 1
fi

# Sem Docker Desktop, o daemon no WSL não sobe sozinho a menos que systemd esteja ligado no wsl.conf.
if ! docker info >/dev/null 2>&1; then
  echo "ERRO: o daemon do Docker não respondeu. Suba com 'sudo service docker start' (ou habilite"
  echo "      systemd em /etc/wsl.conf: [boot] systemd=true, e 'wsl --shutdown' no PowerShell)."
  exit 1
fi

echo "==> Checando se o nó Hardhat está no ar (127.0.0.1:8545)..."
if ! curl -s -o /dev/null --max-time 3 -X POST http://127.0.0.1:8545 \
    -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}'; then
  echo "Nó Hardhat não respondeu. Rode 'yarn chain' em outro terminal (dentro de local-env-scaffold-eth-2/) antes."
  exit 1
fi

echo "==> Copiando contratos de teste + deploy script pro clone do scaffold-eth-2..."
cp contracts/*.sol local-env-scaffold-eth-2/packages/hardhat/contracts/
cp deploy/01_deploy_test_contracts.ts local-env-scaffold-eth-2/packages/hardhat/deploy/

echo "==> Deploy dos contratos de teste..."
(cd local-env-scaffold-eth-2 && yarn deploy --tags TestContracts)

echo "==> Disparando eventos de teste (mint/safeMint/createWidget/...) via script..."
npx tsx seed/emit-events.ts

echo "==> Subindo Postgres do Evex ($DC, sem o profile 'blockscout')..."
$DC up -d

echo "==> Subindo o control-panel (aba 'Demonstração' do frontend — E17/DEC-075) em background..."
# Serviço que dispara os scripts deploy/emit-events/register-contracts pela UI. Só built-ins do Node,
# roda via tsx (mesma pegada de seed/*.ts). `setsid` pelo mesmo motivo do frontend abaixo.
setsid nohup npx tsx control-panel/server.ts > "$LOG_DIR/evex-control-panel.log" 2>&1 < /dev/null &
echo $! > "$LOG_DIR/evex-control-panel.pid"
disown -a

echo "==> Subindo o frontend do Evex em background..."
# `setsid` isola num grupo de processos próprio: `npm run dev` costuma sair logo depois de spawnar o
# `vite` de verdade como filho, deixando o PID salvo apontando pra um processo já morto e o dev server
# órfão rodando pra sempre — matando o grupo inteiro (down.sh) em vez de só o PID do `npm`, não sobra
# processo pra trás.
setsid nohup npm --prefix ../frontend run dev > "$LOG_DIR/evex-frontend.log" 2>&1 < /dev/null &
echo $! > "$LOG_DIR/evex-frontend.pid"
disown -a

echo ""
echo "Pronto. Logs em $LOG_DIR/. Falta só:"
echo "  1. Subir o backend do Evex pela IDE (EVEX_RPC_URL=http://localhost:8545; EVEX_BLOCKSCOUT_URL só"
echo "     importa se você também rodar ./scripts/blockscout-up.sh)."
echo "  2. Registrar os contratos: npx tsx seed/register-contracts.ts"
echo ""
echo "URLs:"
echo "  Frontend do Evex:  http://localhost:5173"
echo "  Control-panel:     http://127.0.0.1:8899  (aba 'Demonstração' — set VITE_LOCALENV_CONTROL_URL=http://localhost:8899 no frontend)"
echo ""
echo "Precisa testar discovery/E6 contra Blockscout real? ./scripts/blockscout-up.sh (pesado, sobe à parte)."
echo "Quer a UI de disparo manual do scaffold-eth-2? cd local-env-scaffold-eth-2 && yarn start (http://localhost:3000/debug)."
echo ""
echo "Pra derrubar o que este script subiu: ./scripts/down.sh"
