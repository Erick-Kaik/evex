# Evex — Suíte de testes E2E (Playwright)

> Ler primeiro: [`../CLAUDE.md`](../CLAUDE.md) (decisões travadas), [`../docs/roadmap.md`](../docs/roadmap.md) **E15** e [`../docs/decisions.md`](../docs/decisions.md) **DEC-069**.
> Se a tarefa mexer em seletores/telas, ler também [`../frontend/CLAUDE.md`](../frontend/CLAUDE.md).

## O que é

Testes de ponta a ponta que **dirigem o navegador** (Playwright + Chromium) contra o **frontend real**
do Evex, que fala com o **backend real**, que indexa do **local-env real** (Hardhat + contratos de
teste + Blockscout). **Sem mock em nenhuma camada** — é a validação de que as funcionalidades da PoC
funcionam juntas, da UI até a rede EVM.

Cobre os fluxos que o usuário exercita na UI mínima (doc §6):

| Spec | Fluxo | Funcionalidades exercidas |
|---|---|---|
| `01-contract-and-abi` | Cadastro de contrato + ABI colada | doc §4.1, E2/E3 |
| `02-discovery` | Discovery via Blockscout (listar verificados, importar, ABI `source=BLOCKSCOUT`) | doc §7, E6 — **skipa** sem Blockscout |
| `03-auto-detection` | Detecção automática de interface ERC20/721/1155 pelos eventos observados | doc §4.5, E6 |
| `04-events-and-redecode` | Poller ingere raw → cadastra ABI → **redecode do histórico já gravado** → eventos viram decodificados na tela; filtro **guiado** (`<ContractAutocomplete>` + `<EventPicker>`) + coluna "Contrato" + chip "sem ABI" + fallback de endereço livre | doc §4.2/§4.3, E4/E5, E16 |
| `05-balance-monitoring` | Config de saldo (modo **guiado**, rede herdada) antes dos eventos → projeção ao vivo → saldo na tela; fallback de endereço livre na consulta | doc §4.4, E7, E16 |
| `06-balance-backfill` | Config criada **depois** do redecode → saldo vazio → backfill → saldo correto → re-disparo no-op | doc §4.4, E14 — **exige `E2E_RESET_DB=1`** |
| `07-webhooks` | Cadastra webhook pela UI (modo **guiado**) → `Transfer` decodificado → `POST` chega no `webhook.site` → entrega vira `DELIVERED` na tela; + filtro em modo **manual** (evento não-emitido nunca dispara) | doc §4.6, E8, E16 — **skipa** sem internet de saída |
| `08-guided-visual-forms` | Modo **visual** (`<ParamBindingCanvas>` por clique) da config de saldo e do webhook + `<PayloadTemplateBuilder>` + persistência do `<FormModeToggle>` no reload | E16 — não precisa de internet nem Blockscout |
| `09-live-status-and-demo` | SSE de status ao vivo: `/contracts/:addr` e `/balances` assinam os 2 streams `text/event-stream` (`EventSource`); indicador de redecode empurrado e some sem reload. Aba **"Demonstração"** (dispara `emit-events` do `local-env` sem terminal, mostra o comando exato, 409 na 2ª ação) | E17 — aba **skipa** sem o `local-env/control-panel` no ar; o teste do indicador **skipa** com `E2E_RESET_DB=1` (precisa do `TestERC1155` sem ABI) |

## Não é parte do produto entregável

Nenhum cliente BBChain recebe este módulo. É infraestrutura de teste/CI. Vive fora de
`backend/`/`frontend/` (como o `local-env/`), não tem agente dedicado em `.claude/agents/` — é tratado
ad-hoc pela sessão principal (ou `frontend-dev` pra seletores, `reviewer` pra fechar a etapa).

## Filosofia (ver DEC-069)

- **A asserção do comportamento é sempre pela UI.** O que o `helpers/evex-api.ts` faz é só a "linha do
  tempo" — polling determinístico de `GET /v1/events`, `/v1/redecode/status`,
  `/v1/balance-monitors/backfill/status` — pra o teste saber **quando** olhar a tela. Nunca
  reimplementa regra de negócio nem substitui a verificação visual.
- **Não sobe a stack pesada.** Backend e local-env rodam à parte (IDE / `local-env/scripts/*`), igual
  ao dia a dia. O `global-setup.ts` só checa que tudo está no ar e **falha cedo** com instrução
  apontando pro script certo. O `webServer` do Playwright sobe só o frontend (leve) e reusa se já
  estiver rodando.
- **Seletores acessíveis primeiro** (`getByRole`/`getByLabel`/`getByText`, labels em português). **Sem
  `data-testid` no frontend** nesta fase — só adicionar (via `frontend-dev`) se algum spec provar
  flaky, e registrar em `decisions.md`.
- **Serial, 1 worker.** O Evex tem 1 Postgres e cursor de indexação global — paralelizar specs cria
  corrida. `fullyParallel: false`, `workers: 1`.
- **Toleração de estado preexistente.** Sem `E2E_RESET_DB=1`, as specs usam asserções monotônicas
  ("saldo aumentou", "contém evento X") em vez de igualdade exata. `06-balance-backfill` é a exceção
  (precisa de "vazio antes") e por isso é skipada sem reset.

## Estrutura

```
e2e/
├── CLAUDE.md                 # este arquivo
├── README.md                 # guia operacional (pré-requisitos, como rodar, troubleshooting)
├── RUNBOOK.md                # guia completo dos 2 modos (suíte automatizada + Playwright MCP)
├── package.json              # @playwright/test + tsx + dotenv (não versiona node_modules nem browsers)
├── playwright.config.ts      # serial, webServer = só frontend, globalSetup
├── tsconfig.json
├── .env.example              # E2E_BASE_URL / _API_URL / _RPC_URL / _BLOCKSCOUT_URL / _RESET_DB / _RUN_SEED / _WEBHOOK_SITE_BASE
├── global-setup.ts           # assertStackReady() + reset opcional (+ assertAbiCacheFresh) + seed opcional
├── scripts/
│   └── run.sh                # pre-flight das 4 peças + garante .env/deps/Chromium + roda o Playwright (`npm run e2e`)
├── helpers/
│   ├── config.ts             # env vars → config (única fonte; carrega e2e/.env via dotenv)
│   ├── stack.ts              # health checks (Hardhat/backend/frontend/Blockscout) + mensagens de fix
│   ├── deployed.ts           # endereço+ABI dos 4 contratos (via subprocesso tsx — dump-deployed.ts)
│   ├── dump-deployed.ts      # importa deployedContracts.ts (.ts) e imprime JSON (rodado pelo deployed.ts)
│   ├── evex-api.ts           # polling determinístico + precondições (ensureContract/ensureAbi) + leitura de config saldo/webhook + reset/seed
│   ├── webhook-site.ts       # cliente da API pública do webhook.site (spec 07)
│   └── control-panel.ts      # health/idle/running do local-env/control-panel (spec 09, aba Demonstração)
├── fixtures/
│   ├── pages.ts              # Page Objects (todos os seletores centralizados aqui)
│   └── test.ts               # `test` estendido com os Page Objects + `evexApi`
├── mcp/
│   ├── README.md             # modo B — Playwright MCP (Claude dirige o Chromium ao vivo)
│   └── walkthroughs.md       # roteiro dos fluxos + variações pro walkthrough via MCP
└── tests/
    └── 0N-*.spec.ts          # um arquivo por fluxo (tabela acima — 9 specs)
```

## Regras

- **Importar `test`/`expect` de `../fixtures/test`**, nunca direto de `@playwright/test` (perde os
  Page Objects e o `evexApi`).
- **Seletor novo? Vai pro Page Object** (`fixtures/pages.ts`), não inline no spec.
- **Nada de `waitForTimeout` fixo.** Espera em condição real: `evexApi.waitFor(...)`, `expect(...).toBeVisible()`,
  `waitForRedecodeIdle()`, `waitForBackfillIdle()`. **Única exceção sancionada** (DEC-071): a asserção
  negativa do spec 07 ("webhook de evento não-emitido nunca dispara") usa um settle curto e limitado —
  não há condição pra esperar.
- **Precondição ≠ alvo do teste.** Setup de estado que não é o que está sendo testado (registrar um
  contrato pra depois testar a ABI) vai por API (`evexApi.ensureContract`), não clicando na UI —
  mantém o spec focado e rápido. O fluxo que o spec **testa** é sempre pela UI.
- **Idioma:** identificadores em inglês; comentários, `test('...')` e mensagens em português (mesma
  regra do resto do repo — E11/DEC).
- **Browsers e `node_modules` não versionados** (`.gitignore`) — `npm ci && npx playwright install chromium`.
- Ao terminar qualquer unidade de trabalho aqui: atualizar `../docs/roadmap.md` (E15) e, se houve
  decisão não trivial, `../docs/decisions.md`.

## Pré-requisitos de execução (resumo — detalhe em README.md)

1. `local-env`: nó Hardhat **em `0.0.0.0`** (`yarn chain --hostname 0.0.0.0` — obrigatório pro
   Blockscout, que roda em container, alcançar o nó; ver `../local-env/README.md` §3.1), contratos
   deployados (`local-env/scripts/up.sh`), e — só pro spec de discovery — Blockscout
   (`local-env/scripts/blockscout-up.sh` + `local-env/blockscout/verify-contracts.ts`).
2. Backend do Evex no ar (`cd backend && ./gradlew bootRun`, ou IDE) com `EVEX_RPC_URL` apontando pro
   nó e `EVEX_BLOCKSCOUT_URL` se for testar discovery.
3. `cd e2e && ./scripts/run.sh` (ou `npm run e2e`) — faz pre-flight, garante `.env`/deps/Chromium e
   roda. `--clean` pra rodada determinística. **Não precisa conectar nenhum modelo de IA** — é
   Playwright puro.
