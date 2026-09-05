# Evex E2E — runbook: como rodar e configurar cada caso

Dois modos de teste ponta a ponta, complementares:

| Modo | O que é | Quando |
|---|---|---|
| **A. Suíte automatizada** (`e2e/tests/*.spec.ts`) | Playwright puro, determinístico, 8 fluxos fixos | regressão, CI, gate de etapa |
| **B. Playwright MCP** (Claude dirige o Chromium) | exploratório, ao vivo, você assiste | investigar, validar ponto a ponto, demo |

Nenhum dos dois precisa de "conectar um modelo de IA" pra rodar os testes em si — o modo B usa o
Claude Code que você já está usando.

---

## Pré-requisitos comuns (os dois modos)

Suba nesta ordem, cada um no seu terminal (o backend pode ser pela IDE):

```bash
# 0. Docker daemon (WSL sem Docker Desktop)
docker info >/dev/null 2>&1 || sudo service docker start

# 1. Nó Hardhat — OBRIGATÓRIO em 0.0.0.0 (Blockscout em container não alcança 127.0.0.1)
cd local-env/local-env-scaffold-eth-2 && yarn chain --hostname 0.0.0.0

# 2. Contratos + eventos + Postgres do Evex + frontend + control-panel (aba Demonstração, spec 09)
cd local-env && ./scripts/up.sh

# 3. Backend do Evex (IDE, ou:)
cd backend && EVEX_RPC_URL=http://localhost:8545 EVEX_BLOCKSCOUT_URL=http://localhost:4001 ./gradlew bootRun

# 4. Blockscout — só pro caso "discovery" (spec 02 / walkthrough 2)
cd local-env && ./scripts/blockscout-up.sh
#    espere indexar: curl -s localhost:4001/api/v2/stats | grep total_blocks   (> 0)
npx tsx blockscout/verify-contracts.ts   # de dentro de local-env/
```

Checagem rápida: `cd e2e && ./scripts/run.sh` faz um pre-flight das 4 peças e diz o que falta.

---

## Modo A — suíte automatizada

### Instalar (uma vez)

```bash
cd e2e
npm ci
npx playwright install chromium      # ~150 MB, vai pro cache do SO, não pro repo
cp .env.example .env
```

### Rodar

```bash
cd e2e

./scripts/run.sh                 # tudo · tolera estado preexistente · specs 06 e 02/07 podem skipar
./scripts/run.sh --clean         # E2E_RESET_DB=1 + E2E_RUN_SEED=1 (rodada determinística; habilita o 06)
./scripts/run.sh --headed        # navegador visível (você vê a suíte clicando)
./scripts/run.sh --ui            # Playwright UI: pausa, time-travel, pick locator
./scripts/run.sh 04-events       # filtra por arquivo/título
npm run report                   # abre o relatório HTML da última rodada
```

### Configuração (`e2e/.env` — tudo tem default)

| Var | Default | Pra quê |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:5173` | frontend |
| `E2E_API_URL` | `http://localhost:8080` | backend |
| `E2E_RPC_URL` | `http://127.0.0.1:8545` | nó Hardhat (pre-flight) |
| `E2E_BLOCKSCOUT_URL` | `http://localhost:4001` | Blockscout. **Vazio → spec 02 skipa** |
| `E2E_DB_URL` / `E2E_DB_CONTAINER` | `postgresql://evex:evex@localhost:5432/evex` / `local-env-evex-postgres-1` | reset de estado |
| `E2E_RESET_DB` | `0` | `1` = trunca as tabelas do Evex antes (via `docker exec psql`) |
| `E2E_RUN_SEED` | `0` | `1` = `global-setup` dispara 1 round de eventos na rede |
| `E2E_WEBHOOK_SITE_BASE` | `https://webhook.site` | endpoint de terceiro do spec 07. Inacessível → spec 07 skipa |
| `E2E_CONTROL_PANEL_URL` | `http://localhost:8899` | `local-env/control-panel` (aba Demonstração, spec 09). Fora do ar → os 2 testes da aba skipam. O `webServer` do Playwright repassa como `VITE_LOCALENV_CONTROL_URL` pro frontend que ele sobe |

### Os 8 fluxos, pré-requisitos e o que cada um prova

| # | Spec | Precisa de | Prova |
|---|---|---|---|
| 1 | `01-contract-and-abi` | backend + frontend | cadastro de contrato via UI; ABI colada como texto vira versão vigente; ABI malformada → erro na modal |
| 2 | `02-discovery` | **+ Blockscout indexando + `verify-contracts.ts`** | lista contratos verificados; importa um pro form; ABI `source=BLOCKSCOUT` |
| 3 | `03-auto-detection` | + nó Hardhat com eventos | detecção de ERC20/1155 pelos `topic0` observados; sugestão FULL vira cadastro `AUTO_DETECTED` |
| 4 | `04-events-and-redecode` | + nó Hardhat com eventos | evento **raw** na tela → cadastra ABI → redecode → **mesmo evento vira decodificado**; filtro **guiado** (`<ContractAutocomplete>`/`<EventPicker>`) + coluna "Contrato" + chip "sem ABI" + fallback de endereço livre (E16) |
| 5 | `05-balance-monitoring` | + nó Hardhat com eventos | config de saldo CREDIT no modo **guiado** (rede herdada, params por select) antes dos eventos → projeção ao vivo → saldo na tela; pausar/reativar; consulta pelo fallback manual |
| 6 | `06-balance-backfill` | **+ `E2E_RESET_DB=1`** | config criada depois do redecode → saldo vazio → botão "Reprocessar" → saldo correto → re-disparo no-op |
| 7 | `07-webhooks` | **+ internet de saída pro webhook.site** | webhook no modo **guiado** → Transfer decodificado → `POST` chega no webhook.site → entrega vira `DELIVERED`; filtro no modo **manual** (evento inexistente nunca dispara) |
| 8 | `08-guided-visual-forms` | backend + nó Hardhat com eventos | modo **visual** (`<ParamBindingCanvas>` por clique) da config de saldo e do webhook: vínculo por clique, recusa de tipo incompatível, mesmo payload do guiado; `<PayloadTemplateBuilder>`; persistência do `<FormModeToggle>` no reload (E16) |
| 9 | `09-live-status-and-demo` | **+ `local-env/control-panel` no ar** (sobe no `up.sh`) pros 2 testes da aba Demonstração | SSE de status (E17): `/contracts/:addr` e `/balances` assinam os streams `text/event-stream` via `EventSource`; indicador de redecode empurrado e some sem reload. Aba **"Demonstração"**: comando exato exibido antes de rodar, `emit-events` dispara e os eventos entram no índice, 2ª ação simultânea → 409 na UI |

Rodada "prova tudo": `./scripts/run.sh` (ou `--clean`) com Blockscout no ar, internet e control-panel → **22/22 sem skip**. Skipam sem as dependências: 2 testes do `02` (Blockscout), 2 do `07` (internet), 2 do `09` (control-panel); `06` (backfill) só roda com `--clean`, e o teste "indicador de redecode ao vivo" do `09` só roda **sem** `--clean` (precisa do `TestERC1155` sem ABI) — os dois se compensam, o total é 22 nos dois modos.

---

## Modo B — Playwright MCP (Claude dirige, você assiste)

### Ativar (uma vez, precisa de você)

O servidor está em [`../.mcp.json`](../.mcp.json). Como é novo, o Claude Code ainda não conectou:

1. No Claude Code, rode **`/mcp`** → deve listar `playwright` pedindo aprovação → **aprove**.
   - Se não aparecer: saia e reabra o Claude Code neste diretório (ele detecta o `.mcp.json` na inicialização e pergunta).
2. Confirme com `/mcp` que `playwright` está **`connected`**.
3. `npx -y @playwright/mcp@latest` baixa o servidor na 1ª vez. O Chromium é o mesmo do modo A.
4. Headed por padrão — a janela do Chromium aparece (WSLg renderiza no Windows 11).

### Rodar

Com a stack no ar (pré-requisitos comuns acima), você pede o walkthrough em linguagem natural. Ex.:

> "Usando o playwright MCP, faça o walkthrough 4 (eventos + redecode) do e2e/mcp/walkthroughs.md e me
> diga o resultado de cada passo."

O passo a passo de cada cenário está em [`mcp/walkthroughs.md`](mcp/walkthroughs.md) — os mesmos
fluxos da suíte + variações pra validar ponto a ponto. O Claude abre o Chromium, clica/digita, tira
snapshot da árvore de acessibilidade a cada passo, e te reporta. Traces/screenshots em
`e2e/.mcp-output/` (git-ignored).

### MCP vs. suíte

| | Suíte (modo A) | MCP (modo B) |
|---|---|---|
| Repetível / CI | ✅ | ❌ |
| Escrever/manter | código nos `.spec.ts` | linguagem natural na hora |
| Ver na tela | `--headed` / `--ui` | sempre |
| Bom pra | regressão dos 8 fluxos | investigar, fluxo novo sem spec, validar variações, demo |

Ciclo saudável: explora com o MCP → quando estabiliza, vira `.spec.ts`.

---

## Troubleshooting

| Sintoma | Fix |
|---|---|
| pre-flight falha em Hardhat/backend/frontend | suba a peça (pré-requisitos comuns) |
| `Blockscout: total_blocks=0` | nó Hardhat está em `127.0.0.1` — reinicie com `--hostname 0.0.0.0`, depois `blockscout-up.sh` |
| spec 02 sempre skipa | `E2E_BLOCKSCOUT_URL` vazio, ou Blockscout sem indexar, ou `verify-contracts.ts` não rodou |
| spec 06 sempre skipa | rode com `--clean` (ou `E2E_RESET_DB=1`) |
| spec 07 sempre skipa | sem internet de saída pro `webhook.site` (ou `E2E_WEBHOOK_SITE_BASE` errado) |
| spec 09 (aba Demonstração) skipa | `local-env/control-panel` fora do ar (`curl localhost:8899/health`) — sobe no `up.sh`; ou a aba não apareceu: `npm run dev` reaproveitado sem `VITE_LOCALENV_CONTROL_URL` (deixe o Playwright subir o frontend, ou exporte a var antes do `npm run dev`) |
| spec 09 "indicador de redecode" skipa | rodou com `--clean` (o spec 06 registra a ABI do `TestERC1155`); esse teste precisa do contrato sem ABI — rode sem `--clean` |
| `deployedContracts.ts` não encontrado | `cd local-env && ./scripts/up.sh` |
| `/mcp` não mostra `playwright` | reabra o Claude Code neste diretório |
| janela do Chromium não aparece (MCP/headed) | WSLg ativo? (`echo $DISPLAY` não-vazio no WSL2 do Win11) |
| seletor quebrou depois de mudança na UI | ajustar no Page Object `e2e/fixtures/pages.ts`, nunca inline no spec |
