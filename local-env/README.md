# local-env — passo a passo

> Ver [`CLAUDE.md`](CLAUDE.md) para o contexto e a stack. Este arquivo é o guia operacional (execução
> manual pelo desenvolvedor), não contexto de agente.

## Status

Ambiente completo e validado ponta a ponta (ver `docs/decisions.md` DEC-052 a DEC-057 e
`docs/roadmap.md` E12) — núcleo do Evex (poller→decode→redecode→API) **e as 3 integrações da E6**
(discovery, ABI via Blockscout, fallback de detecção de interface) testados com sucesso contra um
Blockscout **real** (não stub). A investigação encontrou e corrigiu 2 bugs reais no `BlockscoutClient.kt`
do backend (DEC-057) — não só coisa do ambiente de teste.

## 1. Clonar o scaffold-eth-2 (fora deste repositório do Evex)

Repositório: https://github.com/scaffold-eth/scaffold-eth-2

Clonado dentro deste módulo, para centralizar tudo relacionado ao ambiente de teste num único lugar
(`local-env/local-env-scaffold-eth-2/`) — **não é vendorizado** (não faz parte do histórico do Evex,
ver `.gitignore` deste módulo e `CLAUDE.md`):

```bash
cd local-env
git clone https://github.com/scaffold-eth/scaffold-eth-2.git local-env-scaffold-eth-2
cd local-env-scaffold-eth-2
yarn install
```

Depois de clonado, seguir o `README.md` do próprio scaffold-eth-2 para instalar dependências
(`yarn install`).

## 2. Como o scaffold-eth-2 funciona por dentro (levantado nesta etapa)

- **Deploy usa `rocketh`** (não `hardhat-deploy` puro, apesar de listado como dependência). Scripts em
  `packages/hardhat/deploy/NN_deploy_<nome>.ts`, formato `deployScript(fn, { tags: [...] })` (import de
  `../rocketh/deploy.js`). Rodar um script específico: `yarn deploy --tags <Tag>`.
- **Depois de `yarn deploy`**, endereço + ABI de cada contrato deployado são escritos automaticamente
  em `packages/nextjs/contracts/deployedContracts.ts` — é esse arquivo que o script de seed
  (`../seed/register-contracts.ts`, a criar) deve ler pra registrar o contrato no Evex, em vez de
  reprocessar artifacts do Hardhat na mão.
- **Rede local**: `chains.hardhat`, chainId **31337** (`packages/nextjs/scaffold.config.ts`,
  `targetNetworks`). RPC padrão em `http://127.0.0.1:8545` (`yarn chain`).
- **UI de disparo de eventos**: aba `/debug` (Next.js, `packages/nextjs/app/debug`) — gera botões para
  cada função da ABI automaticamente. Cogitada como caminho principal na DEC-052, mas o dia a dia
  passou a usar `seed/emit-events.ts` em vez dela (DEC-059, mais leve — sem subir o dev server Next.js
  só pra clicar em botões); a UI continua disponível pra quem preferir interagir manualmente.
- **Atenção**: o repo também tem um block explorer próprio embutido em `/blockexplorer` — **não**
  substitui o Blockscout pro nosso caso (a integração de discovery do Evex, E6, fala a API específica
  do Blockscout, não a desse explorer embutido).
- O próprio clone traz um `AGENTS.md` com uma skill `openzeppelin`
  (`.agents/skills/openzeppelin/SKILL.md`) — vale ler antes de escrever os contratos de teste baseados
  em OpenZeppelin (próxima etapa).

## 3. Passo a passo (ponta a ponta)

### 3.0.0 Pré-requisitos de Docker (WSL sem Docker Desktop)

Os scripts usam `docker compose` (plugin v2). O Docker nativo do Ubuntu/WSL (pacote `docker.io`) **não
traz o plugin** — sem ele, `docker compose up -d` cai no CLI cru e falha com
`unknown shorthand flag: 'd' in -d`. Instalar uma vez:

```bash
sudo apt install docker-compose-v2        # provê `docker compose`
sudo usermod -aG docker "$USER"            # rodar sem sudo (relogar depois)
```

Sem Docker Desktop o daemon não sobe sozinho a cada boot do WSL. Ou `sudo service docker start` quando
precisar, ou habilitar systemd de vez em `/etc/wsl.conf`:

```ini
[boot]
systemd=true
```

(depois `wsl --shutdown` no PowerShell). `up.sh`/`down.sh` checam as duas coisas e falham com mensagem
clara se faltar.

### 3.0 Caminho rápido (recomendado) — `scripts/up.sh`

Desde a DEC-059, `up.sh` sobe só o essencial (contratos + eventos de teste + Postgres do Evex +
frontend do Evex) — Blockscout e a UI do scaffold-eth-2 ficam de fora de propósito (seção "Máquina com
pouco fôlego" abaixo explica por quê). Depois da primeira vez (clone do scaffold-eth-2 feito), o dia a
dia é só:

**Terminal 1** — nó Hardhat, sempre manual (ver por quê na seção 3.1):
```bash
cd local-env/local-env-scaffold-eth-2 && yarn chain
```

**Terminal 2** — o resto (copia contratos, deploya, dispara eventos de teste via script, sobe Postgres
do Evex via `docker-compose.yml`, sobe o frontend do Evex em background):
```bash
cd local-env && ./scripts/up.sh
```
Falha cedo com mensagem clara se o Terminal 1 não estiver rodando (em vez de travar). Logs do frontend
em `local-env/.logs/`.

**Depois**: suba o backend do Evex pela IDE (seção 3.5) e rode o seed de contratos (seção 3.6) — esses
dois continuam manuais de propósito (debug/hot-reload no backend; seed é um script pontual, não um
serviço de fundo). Pra derrubar tudo que o `up.sh` subiu: `./scripts/down.sh` (não mexe no nó Hardhat,
no backend nem no Blockscout — pare/derrube esses à parte).

Precisa testar discovery/E6 contra Blockscout real? `./scripts/blockscout-up.sh` /
`blockscout-down.sh` (seção 3.3) — separado do `up.sh` de propósito, sobe/derruba sob demanda.

`local-env/docker-compose.yml` (perfil `blockscout` opt-in, ver seção 3.3) consolida Blockscout (real,
buildado do source) + Postgres do Evex num único arquivo, via `extends:` das definições oficiais (não
duplica nada, só referencia — ver comentário no topo do arquivo). **Não** rode
`backend/docker-compose.yml` separado ao mesmo tempo — os dois publicam a porta 5432, colidem.

### 3.1 Por que o nó Hardhat fica fora do script/compose

```bash
cd local-env/local-env-scaffold-eth-2
yarn chain --hostname 0.0.0.0
```
Estado é in-memory, zera a cada restart — e é o único pedaço que faz sentido ver rodando/interagir
direto (logs de bloco, Ctrl+C manual). Dockerizar exigiria resolver `127.0.0.1` (host) vs.
`host.docker.internal` (containers) na config do Hardhat, sem ganho real.

**`--hostname 0.0.0.0` é obrigatório se você for subir o Blockscout** (`blockscout-up.sh`) ou rodar a
suíte `e2e/`. Com Docker nativo no WSL (não mais Docker Desktop), o Blockscout roda em container e
alcança o host pelo gateway da bridge (`host.docker.internal` → `host-gateway`); o `yarn chain`
padrão escuta só em `127.0.0.1`, que o container **não** consegue alcançar — o Blockscout indexa zero
blocos e o discovery quebra sem erro visível. `blockscout-up.sh` avisa quando detecta isso. Ver
`docs/decisions.md` DEC-069. Só pro backend do Evex (que roda no host), `yarn chain` sem flag também
serve.

### 3.2 O que o `up.sh` faz por baixo (detalhamento manual, se precisar depurar)

```bash
# Copia contratos + deploy script pro clone (se ainda não tiver copiado, ou se reclonou)
cp ../contracts/*.sol packages/hardhat/contracts/
cp ../deploy/01_deploy_test_contracts.ts packages/hardhat/deploy/
# Deploy
yarn deploy --tags TestContracts
```
Confirma que `packages/nextjs/contracts/deployedContracts.ts` ganhou os 4 contratos. Em seguida,
`up.sh` roda `npx tsx seed/emit-events.ts` — dispara `mint`/`safeMint`/`mintBatch`/`createWidget`/etc.
direto via ethers, assinando com a conta deployer padrão do Hardhat (ver cabeçalho do script para as
env vars). Substitui o uso da UI `/debug` do scaffold-eth-2 para esse fim (DEC-059).

### 3.3 Blockscout real (opt-in, sob demanda) — `scripts/blockscout-up.sh`/`blockscout-down.sh`

Fica **fora** do `up.sh` de propósito (DEC-059) — é a parte mais pesada do ambiente inteiro (Elixir +
Postgres próprio + Redis) e só é necessária pra testar discovery/E6. Sobe/derruba quando precisar:
```bash
cd local-env
./scripts/blockscout-up.sh    # profile "blockscout" do docker-compose.yml — builda a imagem localmente
                               # na primeira vez (só uma vez por máquina; a imagem publicada tem um bug
                               # conhecido, ver seção 5), o fix de permissão do volume já roda sozinho
./scripts/blockscout-down.sh  # derruba só o Blockscout, sem mexer no Postgres do Evex
```
API real fica em `http://localhost:4001`. Detalhes/receita manual completa (sem o compose consolidado)
em [`blockscout/README.md`](blockscout/README.md).

### 3.4 Postgres do Evex

Já incluído em `local-env/docker-compose.yml` (seção 3.0) — não precisa mais subir
`backend/docker-compose.yml` separado.

### 3.5 Backend do Evex

Sempre manual, de propósito (debug/hot-reload) — via `./gradlew bootRun` ou pela IDE (IntelliJ etc.).
Garanta estas env vars na run configuration (ou em `application-local.yml`):
- `EVEX_RPC_URL=http://localhost:8545`
- `EVEX_BLOCKSCOUT_URL=http://localhost:4001` — só importa se você também subiu o Blockscout
  (`./scripts/blockscout-up.sh`, seção 3.3); sem ele, discovery/E6 simplesmente fica indisponível, o
  resto do pipeline funciona normal.
- `EVEX_BLOCKCHAIN_TRANSPORT=POLLING` (pipeline normal, `@Scheduled` — default se você nem setar essa
  env var) — só use `NONE` pra isolar o teste do fallback de discovery sem o poller "atropelar" com
  eventos já observados (ver seção 4). `WEBSOCKET` (E26/DEC-091, `eth_subscribe("newHeads")`) ainda não
  foi validado ao vivo contra o Hardhat deste ambiente — se testar, também precisa de
  `EVEX_BLOCKCHAIN_WS_URL` apontando pro WS do nó (Hardhat expõe WS na mesma porta HTTP, `ws://localhost:8545`).

### 3.6 Registrar contratos no Evex + disparar mais eventos

Scripts pontuais, não são serviço de fundo — rodam depois que o backend (3.5) estiver de pé:
```bash
cd local-env
EVEX_API_URL=http://localhost:8080 npx tsx seed/register-contracts.ts   # cadastra os 4 contratos + ABI
npx tsx seed/emit-events.ts                                             # dispara mais um round de eventos
```
(`8080` é a porta padrão do Spring Boot — ajuste se você configurou outra). `up.sh` já roda o
`emit-events.ts` uma vez durante o deploy (seção 3.2) — rodar de novo manualmente gera mais eventos
(útil pra testar paginação, por exemplo; `ROUNDS=N` dispara N rounds de uma vez — ver cabeçalho do
script pras demais env vars).

### 3.7 Frontends

O frontend do Evex já sobe sozinho via `up.sh` (seção 3.0), em background. Se quiser rodar
manualmente/ver o output direto no terminal:

**Frontend do Evex** (produto, `frontend/`):
```bash
cd frontend && npm run dev
```
Sobe em `http://localhost:5173` (Vite). Já aponta pro backend em `http://localhost:8080` por padrão
(`VITE_API_BASE_URL`, ver `frontend/src/api/config.ts`) — só ajustar se você mudou a porta do backend.
Telas de contratos/eventos/saldos/webhooks (E9/E10) consomem a mesma API que os exemplos da seção 5.

**Frontend do scaffold-eth-2** (UI de debug/disparo de eventos, opcional):
```bash
cd local-env/local-env-scaffold-eth-2 && yarn start
```
Fica fora do `up.sh` de propósito (DEC-059) — `seed/emit-events.ts` (seção 3.6) já cobre o disparo de
eventos sem precisar desse dev server no ar. Sobe em `http://localhost:3000` se quiser mesmo assim; a
aba `/debug` gera botões automáticos pra cada função da ABI — `mint`/`safeMint`/`createWidget` etc. em
qualquer um dos 4 contratos.

### 3.8 Control-panel — aba "Demonstração" do frontend (E17)

`control-panel/server.ts` sobe junto com o `up.sh` (background, `127.0.0.1:8899`, log em
`.logs/evex-control-panel.log`). É o que a aba **"Demonstração"** do frontend chama pra rodar os
scripts sem terminal: `deploy` (redeploy dos 4 contratos), `emit-events` (com `ROUNDS`),
`register-contracts` (cadastra os 4 no Evex de uma vez).

- `GET http://127.0.0.1:8899/actions` — lista os botões + o **comando exato** que cada um roda.
- `POST http://127.0.0.1:8899/run/<id>` — dispara e faz stream do stdout via SSE.
- Uma ação por vez (409 se já houver uma rodando).
- Pro frontend mostrar a aba: `VITE_LOCALENV_CONTROL_URL=http://localhost:8899` (a aba some se a env
  não estiver setada ou o serviço não responder).
- **Não** é parte do produto — mesma família de `seed/*.ts`, roda só localmente (DEC-075).

Rodar manualmente (ver output no terminal): `cd local-env && npx tsx control-panel/server.ts`.

### 3.9 Cofre upgradeável (proxy UUPS/ERC1967) — cenário 11 do guia de uso

`contracts/TestUpgradeableVaultV1.sol`/`V2.sol`/`Proxy.sol` + 2 scripts validam de ponta a ponta o
suporte do Evex a proxy upgradável (ABI versionada por faixa de blocos, `docs/guia-de-uso.md` §11) —
diferente dos outros 4 contratos de teste, este exige uma sequência de 2 passos porque o upgrade
acontece **depois** do deploy, não junto:

```bash
cd local-env
npx tsx seed/deploy-upgradeable-vault.ts     # implementação V1 + proxy inicializado + 2 deposit()
# ... cadastre o contrato (endereço do proxy) e a ABI de V1 no Evex, "Bloco inicial de validade = 0" ...
npx tsx seed/upgrade-upgradeable-vault.ts    # implementação V2 + upgradeToAndCall + deposit()+withdraw()
# ... cadastre a ABI de V2 com "Bloco inicial de validade = <bloco impresso pelo script>" ...
```

Cada script imprime o endereço/ABI/bloco exatos a usar no passo seguinte (a ABI também é salva em
`.logs/vault-v1-abi.json`/`vault-v2-abi.json` — cole o conteúdo do arquivo na tela "Cadastrar ABI"),
e `deploy-upgradeable-vault.ts` grava o endereço do proxy em `.logs/upgradeable-vault.json`, que
`upgrade-upgradeable-vault.ts` lê sozinho (sem precisar copiar o endereço na mão entre os 2 passos).
Requer que `yarn deploy --tags TestContracts` (seção 3.2, ou a ação "Deploy dos contratos de teste" do
control-panel) já tenha rodado ao menos uma vez — é o que compila os 3 `.sol` novos.

Também disponível pela aba "Demonstração" (seção 3.8): ações "Deploy do cofre upgradeável (V1)" e
"Upgrade do cofre pra V2".

### 3.10 Cenário Computador+Cofre (eventos enriquecidos) — cenário de validação oficial da E19

`contracts/TestComputer.sol` + `seed/computer-vault-scenario.ts` provam de ponta a ponta o ponto mais
crítico da E19 (`docs/roadmap.md`): um Perfil de Enriquecimento resolve o `eth_call` **no bloco em que
o evento disparador ocorreu**, não no bloco atual. Reaproveita o `TestUpgradeableVault` (3.9, já
deployado/upgradado pra V2) como "a moeda":

```bash
cd local-env
npx tsx seed/deploy-upgradeable-vault.ts      # se ainda não tiver rodado
npx tsx seed/upgrade-upgradeable-vault.ts     # obrigatório — a 3ª rodada do cenário saca (withdraw), só existe em V2
npx tsx seed/computer-vault-scenario.ts       # cria o Computador, troca peças 3x em blocos espaçados (hardhat_mine)
```

O script cria o computador `id=1` (`COMPUTER_ID` sobrescreve) e dispara `create()`/`deposit()`, depois
3 rodadas de `changeParts()` + `deposit()`/`withdraw()` proporcional ao `powerDelta`, em blocos bem
espaçados (`hardhat_mine` avança blocos vazios entre cada rodada, sem gastar gas à toa). Imprime os
blocos de cada evento do Cofre e a config esperada do Computador em cada um, e grava um resumo em
`.logs/computer-vault-scenario.json`. Também disponível pela aba "Demonstração": ação "Cenário
Computador+Cofre (eventos enriquecidos, E19)", com `computerId` como campo.

Pra gerar mais eventos/entregas sem repetir o roteiro fixo (ex.: testar o webhook de novo depois de já
ter configurado tudo), `seed/change-computer-power.ts` faz um ajuste avulso — mantém as peças do
computador `id` informado e aplica só o `powerDelta` que você quiser (positivo deposita, negativo saca):

```bash
npx tsx seed/change-computer-power.ts   # COMPUTER_ID=1 POWER_DELTA=50 por padrão
```

Também disponível na aba "Demonstração": ação "Ajustar poder do Computador (aumentar/diminuir)", com
`computerId`/`powerDelta` como campos (aceita negativo). Reverte com uma mensagem clara se o computador
ainda não existir, ou com o erro do próprio contrato se o delta deixaria o poder negativo.

**Passos manuais no Evex** (o script não automatiza cadastro via API, só imprime os valores exatos):
registrar o `TestComputer` (ação "Registrar os 4 contratos no Evex" — passa a registrar 5), criar um
Perfil de Enriquecimento apontando `getConfig(id)` do Computador (4 saídas mapeadas por posição), e um
webhook no `Deposited`/`Withdrawn` do Cofre referenciando esse perfil. **Importante**: crie/atualize o
Perfil com o `id` do computador **antes** de rodar o script — a config é resolvida "ao vivo" (referência
viva, DEC-082) pelo poller assim que cada evento é ingerido, não só quando você consulta depois; atualizar
o perfil depois que os eventos já foram processados só afeta entregas futuras, não as já disparadas.

**Validado de ponta a ponta** (2026-09-06, sessão principal, DEC-085): 3 entregas de webhook reais
(payload capturado por um HTTP local de teste) trouxeram 3 configs **visivelmente diferentes** — a
inicial, a intermediária e a do "bloco-prova" —, batendo exatamente com o `eth_call` bruto feito nos
mesmos blocos via `curl` direto no nó Hardhat. Achado real corrigido no processo: `ddl-auto: update` não
conseguia adicionar as colunas `enrichment_profile_ids`/`entity_model_ids` (JSONB `NOT NULL`) num banco
com `webhook_configs` pré-existente — mesma classe de bug já documentada na DEC-041/V2, resolvida com a
migration `V4__webhook_config_enrichment_columns.sql` (`ADD COLUMN IF NOT EXISTS ... DEFAULT '[]'::jsonb`).

### 3.11 Cenário Biblioteca de Jogos (Modelo de Entidade) — cenário de validação da E25

`contracts/TestGameLibrary.sol` + `seed/game-library-scenario.ts` provam o 3º pilar da E19 nunca
validado ao vivo antes: um Modelo de Entidade acumula campos de **eventos diferentes** (`ComputerCreated`/
`PartsChanged`, do `TestComputer` — E19 — e `GameRunSucceeded`, do `TestGameLibrary`) sobre a mesma
entidade lógica, correlacionados por `id`/`computerId`. `TestGameLibrary` só deixa um jogo rodar se o
`TestComputer` referenciado tiver poder suficiente — a checagem é um `eth_call` on-chain de verdade
(`ITestComputer.getConfig`), e a falta de poder reverte com um erro customizado real
(`InsufficientPower`), útil também pro Explorador de ABI (E24):

```bash
cd local-env
npx tsx seed/deploy-game-library.ts     # deploya apontando pro TestComputer já deployado
npx tsx seed/game-library-scenario.ts   # cria o computador, registra 2 jogos, revert real + sucesso
```

Também disponíveis na aba "Demonstração": ações "Deploy da Biblioteca de Jogos" e "Cenário Biblioteca
de Jogos (Modelo de Entidade, E25)" (esta com campo `computerId`, default `1000` — bem longe dos ids
usados por `computer-vault-scenario.ts`, pra não colidir).

**Passos manuais no Evex antes de rodar o script** (referência viva, mesma lição da E19 — configure
**antes**, não depois): registre `TestGameLibrary` (endereço impresso pelo `deploy-game-library.ts`),
crie um Modelo de Entidade com 3 contribuintes — `(TestComputer, ComputerCreated, correlationParam=id)`,
`(TestComputer, PartsChanged, correlationParam=id)`, `(TestGameLibrary, GameRunSucceeded,
correlationParam=computerId)` —, e um webhook no `GameRunSucceeded` do `TestGameLibrary` referenciando
esse Modelo, com `payloadTemplate` usando `{{model.<campo>}}` pros campos mapeados em cada contribuinte.

**Validado de ponta a ponta** (2026-09-06, sessão principal, DEC-088): a entrega do evento-prova
(`GameRunSucceeded` do jogo que só passou a rodar depois do poder aumentar) trouxe corretamente os
campos acumulados dos 3 eventos, **incluindo a própria contribuição do evento que disparou o webhook** —
o que só passou a funcionar depois de um bug real corrigido: `EntityModelProjectionService.projectBatch`
precisava rodar **antes** de `WebhookMatchingService.matchAndEnqueue` (estava na ordem inversa desde a
E19), senão um evento nunca via sua própria contribuição no `{{model.*}}` da entrega que ele mesmo
dispara. Achado secundário nos scripts (não no produto): `ethers.NonceManager` incrementa o nonce antes
de saber que uma transação vai reverter — precisa de `signer.reset()` logo após capturar um revert
esperado, senão a próxima transação real usa nonce errado.

### Máquina com pouco fôlego pra tudo isso junto?

Mesmo com Blockscout e a UI do scaffold-eth-2 fora do dia a dia (DEC-059), `yarn chain` + Gradle/Kotlin
+ Docker (Postgres do Evex) + dev server do frontend do Evex já pesa. Se for testar discovery/E6 e
somar o Blockscout (`blockscout-up.sh` — Elixir + Postgres próprio + Redis) ao mesmo tempo, pesa bem
mais — vale derrubar (`blockscout-down.sh`) assim que terminar esse teste específico. Se notar tudo
muito lento (`uptime`/`load average` bem acima do número de cores), pode valer aumentar os limites do
WSL (`%UserProfile%\.wslconfig` no Windows, `wsl --shutdown` depois pra aplicar — reinicia a VM inteira,
mata qualquer coisa rodando nela, inclusive uma sessão do
Claude Code se estiver de pé). Não é obrigatório — o ambiente funciona mesmo sob carga alta, só mais
devagar.

## 4. Como testar

**Pipeline principal (poller→decode→redecode→API, E4/E5):**
```bash
curl "http://localhost:8080/v1/events?contractAddress=<endereço>"
```
ou pelo Swagger UI (`http://localhost:8080/swagger-ui.html`). Validado nesta sessão com `Transfer`
(ERC20) e `WidgetCreated` (evento customizado do `TestEventEmitter`, com `string`/`uint256` não
indexados) — inclusive o cenário de ingestão **antes** da ABI existir, com redecode automático (E5)
assim que a ABI é cadastrada.

**Discovery via Blockscout real (E6):**
```bash
curl "http://localhost:8080/v1/contracts/<endereço do TestERC721 ou TestERC1155>/abi/suggestions"
```
Se o contrato ainda não tiver evento observado pelo Evex, essa chamada cai no fallback real contra
`http://localhost:4001/api/v2/addresses/{address}/logs`. Validado nesta sessão: `safeMint` real no
`TestERC721`, sugestão retornou `ERC721`/`ERC20` como `PARTIAL` (só `Transfer` observado até então —
ambíguo entre os dois templates, comportamento correto).

Pra testar `GET /v1/discovery/blockscout/contracts`/`source=BLOCKSCOUT` (exemplos completos na
seção 5), o contrato precisa estar **verificado** no Blockscout primeiro — ver
[`blockscout/verify-contracts.ts`](blockscout/verify-contracts.ts) (`npx tsx blockscout/verify-contracts.ts`
de dentro de `local-env/`, verifica `TestERC20`/`TestEventEmitter` via API v2 real, sem depender do
`rocketh-verify` — ver `docs/decisions.md` DEC-057).

## 5. Exemplos de uso da API

Três fluxos de cadastro de contrato, do jeito que a UI (frontend do Evex) exercita por baixo dos panos.

### 5.1 Cadastro via discovery (Blockscout)

Requer o contrato já **verificado** no Blockscout (seção 4 acima):
```bash
# 1. Descobrir contratos verificados
curl "http://localhost:8080/v1/discovery/blockscout/contracts"

# 2. Cadastrar o contrato encontrado
curl -X POST "http://localhost:8080/v1/contracts" \
  -H "Content-Type: application/json" \
  -d '{"address":"0x5fbdb2315678afecb367f032d93f642f64180aa3","name":"TestERC20 (via discovery)","network":"TESTNET"}'

# 3. Buscar a ABI direto do Blockscout (não precisa colar nada)
curl -X POST "http://localhost:8080/v1/contracts/0x5fbdb2315678afecb367f032d93f642f64180aa3/abi" \
  -H "Content-Type: application/json" \
  -d '{"source":"BLOCKSCOUT"}'
```

### 5.2 Cadastro via ABI (upload/texto)

Sem depender do Blockscout — cola o JSON da ABI direto:
```bash
curl -X POST "http://localhost:8080/v1/contracts" \
  -H "Content-Type: application/json" \
  -d '{"address":"0xcf7ed3acca5a467e9e704c703e8d87f634fb0fc9","name":"TestEventEmitter (ABI manual)","network":"TESTNET"}'

curl -X POST "http://localhost:8080/v1/contracts/0xcf7ed3acca5a467e9e704c703e8d87f634fb0fc9/abi" \
  -H "Content-Type: application/json" \
  -d '{
    "source": "UPLOAD_TEXT",
    "abi": "[{\"type\":\"event\",\"name\":\"WidgetCreated\",\"inputs\":[{\"name\":\"id\",\"type\":\"uint256\",\"indexed\":true},{\"name\":\"creator\",\"type\":\"address\",\"indexed\":true},{\"name\":\"name\",\"type\":\"string\",\"indexed\":false},{\"name\":\"value\",\"type\":\"uint256\",\"indexed\":false}]}]"
  }'
```
("Upload de arquivo" na UI é o mesmo endpoint — o frontend só lê o arquivo local e manda o conteúdo
neste mesmo campo `abi`, como texto.)

### 5.3 Configurar um webhook

Dispara um `POST` toda vez que o evento indicado bate no contrato indicado:
```bash
curl -X POST "http://localhost:8080/v1/webhooks" \
  -H "Content-Type: application/json" \
  -d '{
    "contractAddress": "0x5fbdb2315678afecb367f032d93f642f64180aa3",
    "network": "TESTNET",
    "eventName": "Transfer",
    "url": "https://webhook.site/<seu-endpoint-de-teste>"
  }'
```
`filters`/`payloadTemplate` são opcionais (sem eles: sem filtro adicional, payload no envelope JSON
padrão). Gere uma URL de teste em https://webhook.site pra ver a entrega chegando de verdade.

## 6. Encerrar

```bash
cd local-env
./scripts/down.sh              # frontend do Evex + Postgres do Evex (o que o up.sh subiu)
./scripts/blockscout-down.sh   # só se tiver subido o Blockscout (seção 3.3)
# + parar os processos yarn chain / gradlew bootRun (ou IDE) / yarn start (scaffold-eth-2, se tiver subido) — Ctrl+C
```
