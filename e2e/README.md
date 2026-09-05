# Evex E2E — guia operacional

Suíte Playwright que valida os fluxos da PoC ponta a ponta (UI → backend → local-env). Contexto e
filosofia: [`CLAUDE.md`](CLAUDE.md). Etapa do roadmap: **E15**.

## 1. Instalar

```bash
cd e2e
npm ci
npx playwright install chromium      # não versionado — baixa ~150 MB uma vez
cp .env.example .env                 # ajuste as portas se necessário (todas têm default)
```

## 2. Subir a stack (3 terminais + IDE)

A suíte **não sobe** backend nem local-env — igual ao dia a dia do projeto. Ela sobe só o frontend
(via `webServer` do Playwright, reusa se já estiver rodando) e checa o resto no `global-setup`.

### Terminal 1 — nó Hardhat (local-env)

```bash
cd local-env/local-env-scaffold-eth-2
yarn chain --hostname 0.0.0.0
```

> **`--hostname 0.0.0.0` é obrigatório.** Com Docker nativo no WSL, o Blockscout (container) alcança o
> host pelo gateway da bridge (`172.18.0.1`); um nó preso em `127.0.0.1` fica inalcançável e o
> Blockscout indexa **zero blocos** (discovery quebra). Ver `local-env/README.md` §3.1.

### Terminal 2 — deploy dos contratos + eventos + Postgres (local-env)

```bash
cd local-env
./scripts/up.sh
```

### (Opcional) Terminal 3 — Blockscout, só pro spec `02-discovery`

```bash
cd local-env
./scripts/blockscout-up.sh
# espere a indexação: curl -s localhost:4001/api/v2/stats | grep total_blocks   (> 0)
npx tsx blockscout/verify-contracts.ts
```

### IDE / Terminal 4 — backend do Evex

```bash
cd backend
EVEX_RPC_URL=http://localhost:8545 \
EVEX_BLOCKSCOUT_URL=http://localhost:4001 \
./gradlew bootRun
```

(`EVEX_BLOCKSCOUT_URL` só importa pro spec de discovery. Sem ele, `02-discovery` é skipado, não falha.)

## 3. Rodar

### Jeito recomendado — `scripts/run.sh`

Faz pre-flight das 4 peças (Hardhat / backend / frontend / Blockscout) com mensagem clara do que
falta, garante `.env` + deps + Chromium, e chama o Playwright:

```bash
cd e2e
./scripts/run.sh                 # ou: npm run e2e
./scripts/run.sh --clean         # E2E_RESET_DB=1 E2E_RUN_SEED=1 (rodada determinística, habilita o 06)
./scripts/run.sh --headed        # navegador visível
./scripts/run.sh --ui            # modo interativo
./scripts/run.sh 04-events       # filtra specs (arg nativo do playwright test)
```

### Direto pelo Playwright

```bash
cd e2e
npm test                    # headless, todos os specs
npm run test:headed
npm run test:ui
npm test -- 04-events
npm run report              # abre o último relatório HTML
```

### Modos de estado

| Env | Efeito | Quando usar |
|---|---|---|
| _(nenhum)_ | Specs toleram estado preexistente (asserções monotônicas). `06-balance-backfill` é **skipado**. | Rodada rápida sobre uma stack que você já usou |
| `E2E_RESET_DB=1` | Trunca as tabelas do Evex antes da suíte (via `docker exec` no Postgres). Habilita `06`. | Rodada "limpa" / CI |
| `E2E_RUN_SEED=1` | O `global-setup` dispara **um round de eventos** de teste na rede (`emit-events.ts`). **Não** registra contratos/ABIs — cada spec faz isso. | Se você não rodou `up.sh` recentemente |

```bash
E2E_RESET_DB=1 E2E_RUN_SEED=1 npm test      # rodada completa e determinística
```

> **`E2E_RESET_DB=1` exige backend recém-iniciado.** O truncate do Postgres **não** invalida o cache
> de ABIs em memória do backend (`AbiCache`, populado só no boot). Se você já rodou a suíte contra
> este mesmo `bootRun`, **reinicie o backend** antes de rodar de novo com reset. O `global-setup`
> **aborta** com essa instrução (`assertAbiCacheFresh`) se detectar eventos decodificados logo após o
> truncate sem nenhum contrato cadastrado — não deixa mais a suíte rodar 40s pra falhar no spec 04.
> Em CI (backend novo a cada run) não acontece. Ver `docs/decisions.md` DEC-070.

## 4. Troubleshooting

| Sintoma | Causa provável | Fix |
|---|---|---|
| "preciso conectar um modelo de IA?" | confusão com Playwright MCP | **Não.** Esta suíte é Playwright puro — scripts determinísticos, zero LLM. Só `./scripts/run.sh`. |
| `global-setup` falha em "Hardhat" | nó não está rodando, ou em porta diferente | Terminal 1; ou `E2E_RPC_URL` |
| `global-setup` falha em "Backend" | `bootRun` não subiu / porta | Terminal 4; `/actuator/health` deve dar `UP` |
| `global-setup` falha em "Blockscout: total_blocks=0" | nó Hardhat em `127.0.0.1`, não `0.0.0.0` | reinicie o Terminal 1 com `--hostname 0.0.0.0`, depois `blockscout-up.sh` |
| `02-discovery` sempre skipado | `E2E_BLOCKSCOUT_URL` vazio ou Blockscout sem indexar | Terminal 3 + `verify-contracts.ts` |
| `deployedContracts.ts` não encontrado | contratos não deployados | `local-env/scripts/up.sh` |
| Spec de saldo/evento dá timeout esperando decodificação | polling do backend é 5s + redecode assíncrono | normal levar ~10–30s; se passar de 90s, ver logs do `bootRun` |
| Seletor não encontrado depois de mudança na UI | label/estrutura mudou no frontend | ajustar o Page Object em `fixtures/pages.ts` (nunca inline no spec) |
| `global-setup` aborta com "AbiCache do backend está velho" | rodou a suíte com `E2E_RESET_DB=1` contra um `bootRun` que já tinha ABIs em cache | reinicie o `bootRun` e rode de novo (ver box acima) |
| `emit-events` falha com `Nonce too low ... can't be queued when automining` | nó Hardhat sob carga (Blockscout indexando) respondendo `getTransactionCount` defasado | já mitigado (`NonceManager` em `local-env/seed/emit-events.ts`); se voltar, rode com o Blockscout parado |

## 5. CI (futuro)

Não há workflow ainda. O caminho: subir Postgres + Hardhat (headless) + backend (`bootRun` em
background) + `blockscout-up.sh` como serviços, `E2E_RESET_DB=1 E2E_RUN_SEED=1 npm test`, publicar
`playwright-report/`. Rastreado na E15.
