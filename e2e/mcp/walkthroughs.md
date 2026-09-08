# Walkthroughs E2E via Playwright MCP

Roteiro pro Claude Code dirigir o Chromium (MCP `playwright`) contra a stack real, com você
assistindo. São os mesmos 9 fluxos da suíte automatizada + variações pra validar ponto a ponto.

**Antes:** stack no ar (ver `e2e/RUNBOOK.md` §"Pré-requisitos comuns") e MCP `playwright` conectado
(`/mcp` → `connected`). Base do frontend: `http://localhost:5173`. Pro walkthrough 10, o frontend
precisa ter sido servido com `VITE_LOCALENV_CONTROL_URL=http://localhost:8899` e o `control-panel` no
ar (sobe no `up.sh`).

**Como pedir:** _"Usando o playwright MCP, faça o walkthrough N. Reporte o resultado de cada passo e
pare se algo falhar."_ Dá pra pedir vários de uma vez, ou pedir pra repetir um com dados diferentes.

**Estado:** os walkthroughs 1–5, 7, 8 e 10 toleram estado preexistente. O 6 (backfill) precisa de
saldo zerado pro par carteira+contrato e o 9 (indicador de redecode) precisa de um contrato **sem
ABI** com histórico raw — se já rodou antes, peça _"antes do walkthrough N, trunque as tabelas do
Evex"_ (o Claude roda o `resetEvexDb` do `helpers/evex-api.ts`; lembre que o backend precisa ser
reiniciado depois — DEC-070).

---

## 1 — Cadastro de contrato + ABI colada

1. Ir pra `/contracts` → "Contratos monitorados" visível.
2. "Cadastrar" → preencher Endereço (um `0x…` de 40 hex qualquer, inventado), Nome, Rede = TESTNET → "Cadastrar" (E18: página dedicada `/contracts/new`, não mais modal).
3. Deve redirecionar pra `/contracts/<endereço>` e mostrar o nome + chip TESTNET.
4. Seção ABI: "Cadastrar nova versão" → origem "Colar texto" → colar um ABI mínimo
   (`[{"type":"event","name":"Transfer","inputs":[{"indexed":true,"name":"from","type":"address"},{"indexed":true,"name":"to","type":"address"},{"indexed":false,"name":"value","type":"uint256"}],"anonymous":false}]`)
   → "Cadastrar versão de ABI".
5. A tabela de versões deve ter 1 linha, origem `UPLOAD_TEXT`, marcada "vigente".

**Variação:** repetir o passo 4 com um JSON quebrado (`{ not json`) → a modal continua aberta e mostra
alerta de erro (`INVALID_ABI_CONTENT`).

## 2 — Discovery via Blockscout

_Precisa de Blockscout indexando + `verify-contracts.ts` já rodado._

1. `/contracts` → "Cadastrar". Na coluna "Importar do Blockscout" deve listar os contratos verificados
   (TestERC20, TestEventEmitter).
2. Clicar num item → o Endereço e o Nome do form são preenchidos.
3. Escolher Rede TESTNET → "Cadastrar" → vai pro detalhe.
4. Seção ABI → "Cadastrar nova versão" → origem "Buscar no Blockscout" → "Cadastrar versão de ABI".
5. A tabela de versões ganha uma linha com origem `BLOCKSCOUT`.

**Variação:** sem Blockscout no ar, a mesma coluna mostra o aviso "Discovery via Blockscout não está
disponível" (não é erro).

## 3 — Detecção automática de interface

_Precisa de eventos on-chain já indexados (rode `local-env/scripts/up.sh`, ou peça pro Claude
`emitEvents`)._

1. Garantir que TestERC20 (ou TestERC1155) está cadastrado **sem ABI** (peça pro Claude
   `ensureContract` só, sem `ensureAbi`).
2. Detalhe do contrato → "Ver sugestões automáticas" → "Detectar interfaces".
3. Deve listar `ERC20` (ou `ERC1155`) com nível `FULL` ou `PARTIAL`.
4. Se `FULL`: fechar essa modal, "Cadastrar nova versão" → origem "Detectar automaticamente" →
   "Detectar interfaces" → "Cadastrar versão de ABI" → linha `AUTO_DETECTED` na tabela.
5. Se `PARTIAL`: a UI bloqueia o cadastro automático e explica ("Nenhuma sugestão com match total").

## 4 — Eventos + redecode do histórico já gravado ⭐

_O fluxo central: "pegar eventos já gravados e decriptar"._

1. Cadastrar o TestEventEmitter **sem ABI**. Emitir eventos (`emitEvents(2)`).
2. `/events` → filtrar por esse contrato → as linhas mostram o chip **"sem ABI"** (não decodificado).
3. Ir no detalhe do contrato → cadastrar a ABI dele (colar o JSON — peça o ABI pro Claude via
   `deployedContracts`). Isso dispara o **redecode** no backend.
4. Esperar o redecode terminar (Claude checa `GET /v1/redecode/status`).
5. Voltar em `/events`, filtrar pelo mesmo contrato → as **mesmas** linhas agora mostram o nome do
   evento (ex. `WidgetCreated`), e "ver parâmetros" abre a tabela de parâmetros decodificados.

**Variação:** filtrar `/events` também por nome de evento e conferir que só aquele aparece.

## 5 — Monitoramento de saldo ao vivo

_(E18: cadastro virou página dedicada, não seção inline — `<StatusToggle>` substitui os antigos botões "Pausar"/"Ativar".)_

1. Garantir TestERC20 cadastrado **com ABI**.
2. `/balances` → listagem "Configurações de monitoramento" → "Cadastrar" (navega pra
   `/balances/monitors/new`): Contrato = TestERC20, Rede TESTNET, Nome do evento `Transfer`,
   Comportamento `CREDIT`, Parâmetro da carteira `to`, Parâmetro do montante `value` → "Cadastrar" →
   pousa direto no detalhe da config recém-criada (`/balances/monitors/:id`, sem mensagem inline de
   sucesso — a navegação já é a confirmação).
3. Emitir um mint (`emitEvents(1)` — o mint manda Transfer 0x0 → conta 1 do Hardhat
   `0x70997970…79C8`).
4. Esperar decodificar. Em "Consultar saldos", filtrar por Carteira = `0x70997970…79C8` + Contrato =
   TestERC20 → deve aparecer uma linha com saldo > 0.
5. Na listagem de configs, clicar no `Chip` de status da linha (mostra "Ativa") → menu abre → clicar
   "Pausada" → chip vira "Pausada" → clicar de novo → menu → "Ativa" → volta.

## 6 — Backfill de saldo sob demanda

_Precisa de estado limpo — peça o truncate antes._

1. Truncar tabelas do Evex. Cadastrar TestERC20 **sem ABI**, emitir eventos (`emitEvents(2)`),
   **depois** cadastrar a ABI → redecode decodifica o histórico (nenhuma config de saldo existe ainda).
2. `/balances` → criar a config de saldo (igual walkthrough 5) → já pousa no detalhe dela.
3. Consultar saldo pra a carteira `0x70997970…79C8` + TestERC20 → **vazio** (a config nasceu depois do
   redecode; a projeção ao vivo não reprocessa o histórico).
4. No detalhe da config (card "Reprocessar histórico" — E18 moveu esse card da listagem pro detalhe) →
   "Reprocessar histórico" → aparece o progresso ao vivo via SSE ("processados N · deltas N · restam
   N"), depois "concluído".
5. Consultar o saldo de novo → agora aparece, com o valor certo.
6. Clicar "Reprocessar histórico" de novo → no-op (o saldo não muda; status global `caughtUp: true`).

## 7 — Webhooks (via webhook.site)

_Precisa de internet de saída._

1. Claude cria um token no `https://webhook.site` (`createToken` do `helpers/webhook-site.ts`) e abre a
   página desse token numa aba (pra você ver as requisições chegando ao vivo).
2. Garantir TestERC20 **com ABI**.
3. `/webhooks` → "Cadastrar" (navega pra `/webhooks/new`): Contrato = TestERC20, Rede TESTNET, Nome do
   evento `Transfer`, URL de destino = a URL do token → "Cadastrar" → pousa direto no detalhe do
   webhook recém-criado (`/webhooks/:id` — a submissão já leva pra lá, não precisa abrir a linha da
   tabela depois).
4. Emitir um Transfer (`emitEvents(1)`). Esperar decodificar.
5. Na aba do webhook.site: uma requisição `POST` chega, com o payload do evento (contém `Transfer` e o
   endereço do contrato).
6. Recarregar o detalhe do webhook no Evex → seção "Entregas" tem uma linha, status **`DELIVERED`**;
   "ver tentativas" mostra a tentativa com "Sucesso: sim" e HTTP 200.

**Variação (filtro):** no toggle "Guiado / Visual / Manual" do form, trocar pra **Manual**; cadastrar
um webhook pra um evento que os seeds não emitem (`EventoQueNaoExiste`, texto livre) → emitir eventos
→ **nenhuma** requisição chega no webhook.site, "Entregas" fica vazio.

---

## 8 — Formulários guiado / visual (E16)

_Não precisa de internet nem Blockscout._

1. Garantir TestEventEmitter **com ABI** (`ensureContract` + `ensureAbi`).
2. `/balances` → "Cadastrar" (`/balances/monitors/new`) → toggle **Visual**.
3. Contrato = TestEventEmitter no `<ContractAutocomplete>` (repare: **não há campo "Rede"** — é herdada).
4. Nome do evento = `WidgetCreated` no `<EventPicker>`.
5. No canvas: clicar no chip `creator (address)`, depois no slot **"Carteira"** → vincula. Clicar no
   chip `value (uint256)`, depois no slot **"Montante/delta"** → vincula.
6. Trocar "Comportamento" pra **TRANSFER** → os slots viram "Origem (from)" / "Destino (to)".
7. Clicar no chip `value (uint256)`, depois no slot **"Origem (from)"** → **recusado** ("Tipo
   incompatível"), slot continua vazio.
8. Voltar pra **CREDIT** → "Cadastrar" → pousa no detalhe da config recém-criada (sem mensagem inline
   de sucesso). Conferir em `GET /v1/balance-monitors` que `network=TESTNET`, `walletParam=creator`,
   `valueParam=value`.
9. `/webhooks` → "Cadastrar" (`/webhooks/new`) → toggle **Visual** → contrato/evento iguais →
   "adicionar filtro" → vincular `value` no slot "Filtro 1" pelo canvas, preencher o valor → no
   `<PayloadTemplateBuilder>` clicar em "Inserir `{{eventName}}`" e ver o token entrar no "Template
   montado" → "Cadastrar" → pousa no detalhe do webhook.
10. **Persistência:** na página de cadastro, trocar o toggle pra **Manual**, recarregar a página → o
    form volta em **Manual**. Trocar pra **Visual**, recarregar → volta em **Visual**.

---

## 9 — Status assíncrono ao vivo via SSE (E17) ⭐

_Precisa de um contrato **sem ABI** com histórico raw — trunque antes e reinicie o backend, ou use o
`TestERC1155` numa rodada sem `--clean`._

1. Cadastrar o `TestERC1155` **sem ABI**. Emitir eventos (`emitEvents(2)`). `/events` filtrado por ele
   → chip **"sem ABI"**.
2. Abrir `/contracts/<TestERC1155>`. No devtools (aba Network), confirmar uma requisição
   `redecode/status/stream` do tipo **eventsource** (não fetch/xhr repetido) — a tela **assina** o
   status via SSE.
3. Na própria tela, seção ABI → "Cadastrar nova versão" → colar o ABI do `TestERC1155` → "Cadastrar
   versão de ABI". Isso dispara o **redecode global**.
4. **Sem recarregar a página:** aparece o `<CircularProgress>` + "Decodificando o histórico… (N
   processados)". O N sobe. Quando o redecode termina, o indicador **some sozinho** (frame
   `event: done` do SSE) — sem reload.
5. Abrir `/contracts/<outro contrato>` enquanto o redecode ainda roda → o mesmo indicador aparece lá
   (o redecode é global, DEC-078 — o texto é genérico de propósito).
6. Abrir o **detalhe** de uma config de saldo já cadastrada (`/balances/monitors/:id`, card
   "Reprocessar histórico") → devtools mostra outra requisição **eventsource** pro
   `balance-monitors/backfill/status/stream` (E18 moveu essa assinatura da listagem `/balances` pro
   detalhe de cada config — não é mais em `/balances` direto).
7. **Queda e recuperação:** com o detalhe da config aberto, `docker stop` no container do backend (ou
   pare o `bootRun`) → a UI não quebra, cai pra polling REST. Suba o backend de novo → o
   `EventSource` **reconecta sozinho** (nativo do browser, achado de 2026-09-07 corrigido — antes disso
   o código fechava a conexão no erro e nunca mais tentava de novo) e o indicador volta a atualizar via
   SSE, sem precisar recarregar a página.

## 10 — Aba "Demonstração" (dispara os scripts do local-env sem terminal, E17/DEC-075)

_Precisa do `local-env/control-panel` no ar (`up.sh`) e do frontend servido com
`VITE_LOCALENV_CONTROL_URL=http://localhost:8899`._

1. Abrir `/` → a aba **"Demonstração"** aparece na navegação (só quando a env var está setada **e** o
   health check do control-panel passou). Sem isso, a aba não existe e `/demo` redireciona.
2. Entrar na aba → 3 cards: "Deploy dos contratos de teste", "Disparar eventos de teste", "Registrar
   os 4 contratos no Evex". Cada um mostra, num `<pre>`, **o comando exato** que vai rodar (ex.:
   `ROUNDS=1 npx tsx seed/emit-events.ts`) — antes de executar.
3. No card "Disparar eventos de teste", mudar "Rounds" pra 2 → "Executar". O `<pre>` de output
   preenche ao vivo (`Round 1/2 …`), o botão vira "Executando…", e no fim aparece o chip **"exit 0"**.
4. Ir em `/events` → os eventos recém-emitidos estão indexados.
5. **Uma ação por vez:** com o "Disparar eventos" ainda rodando (use Rounds alto), clicar "Executar"
   noutro card → alerta de erro **"Já há uma ação em execução no control-panel — aguarde ela
   terminar."** (HTTP 409).
6. **Some quando cai:** parar o `control-panel` (`down.sh` ou matar o processo) + recarregar `/` → a
   aba "Demonstração" desaparece.

---

## Reportar

Pro Claude: a cada passo, um snapshot da árvore de acessibilidade + ✅/❌ + o que apareceu. No fim,
uma tabela: fluxo → passos ok → onde parou (se parou). Traces e screenshots ficam em
`e2e/.mcp-output/`.
