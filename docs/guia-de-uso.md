# Evex — guia de uso por cenário

> Guia prático de **como usar** o Evex, cenário a cenário. Para *o que é* e *por que* das decisões,
> ver [`../base/indexer-product-draft.md`](../base/indexer-product-draft.md) (produto) e
> [`decisions.md`](decisions.md) (ADR). Para *onde o projeto está*, ver [`roadmap.md`](roadmap.md).

O Evex é uma **API de indexação de eventos EVM genérica e configurável** para redes privadas. Você
cadastra os contratos que quer monitorar, dá a ABI de cada um, e o Evex:

- lê **todos** os eventos da rede por polling (`eth_getLogs` agendado, sem filtro de endereço),
- grava cada evento uma vez (idempotente por `tx_hash`+`log_index`),
- **decodifica** os eventos dos contratos que têm ABI cadastrada (os demais ficam "raw"),
- projeta **saldos históricos** de carteiras conforme regras que você define,
- dispara **webhooks** quando um evento decodificado bate com um filtro que você configurou.

Não há autenticação nesta PoC. Uma instância do Evex por rede/cliente; a segregação interna é pelo
campo `network` (`MAINNET` | `TESTNET`).

---

> As imagens deste guia são do **modo mock** (`npm run dev:mock`) — o front rodando sozinho, sem
> backend, com dados de exemplo. Ver [§0.1](#01-rodar-só-o-front-modo-mock-sem-backend).

## Índice

- [0. Subir o ambiente local](#0-subir-o-ambiente-local)
- [0.1. Rodar só o front (modo mock, sem backend)](#01-rodar-só-o-front-modo-mock-sem-backend)
- [1. Monitorar um contrato conhecido (cadastro + ABI colada)](#1-monitorar-um-contrato-conhecido-cadastro--abi-colada)
- [2. Descobrir e importar contratos via Blockscout](#2-descobrir-e-importar-contratos-via-blockscout)
- [3. Detectar automaticamente a interface (ERC20 / 721 / 1155)](#3-detectar-automaticamente-a-interface-erc20--721--1155)
- [4. Pegar eventos já gravados e decodificar (redecode do histórico)](#4-pegar-eventos-já-gravados-e-decodificar-redecode-do-histórico)
- [5. Monitorar saldo de carteiras ao vivo](#5-monitorar-saldo-de-carteiras-ao-vivo)
- [5.1. Saldo de eventos em lote (arrays)](#51-saldo-de-eventos-em-lote-arrays)
- [6. Backfill de saldo (config criada depois dos eventos)](#6-backfill-de-saldo-config-criada-depois-dos-eventos)
- [7. Configurar um webhook (notificação de saída)](#7-configurar-um-webhook-notificação-de-saída)
- [8. Os 3 modos de formulário (guiado / visual / manual)](#8-os-3-modos-de-formulário-guiado--visual--manual)
- [9. Acompanhar jobs assíncronos ao vivo (SSE)](#9-acompanhar-jobs-assíncronos-ao-vivo-sse)
- [10. Aba "Demonstração" — disparar scripts sem terminal](#10-aba-demonstração--disparar-scripts-sem-terminal)
- [11. Contrato proxy upgradável (ABI versionada por faixa de blocos)](#11-contrato-proxy-upgradável-abi-versionada-por-faixa-de-blocos)
- [11.1. Importar histórico de versões de ABI (proxy UUPS)](#111-importar-histórico-de-versões-de-abi-proxy-uuups)
- [12. Consultar saldo numa data / histórico](#12-consultar-saldo-numa-data--histórico)
- [13. Eventos enriquecidos no payload de webhook](#13-eventos-enriquecidos-no-payload-de-webhook)
- [14. Chave de correlação do Modelo de Entidade](#14-chave-de-correlação-do-modelo-de-entidade)
- [15. Pré-visualizar e reenviar webhooks (sem mexer em dados)](#15-pré-visualizar-e-reenviar-webhooks-sem-mexer-em-dados)
- [Referência rápida](#referência-rápida)
- [Troubleshooting](#troubleshooting)

---

## 0. Subir o ambiente local

O `local-env/` é um ambiente descartável (Hardhat + Blockscout + 4 contratos de teste) só para
validar/demonstrar o Evex. **Não faz parte do produto.** Cada peça roda no seu próprio terminal.

```bash
# 0. Docker (WSL sem Docker Desktop)
docker info >/dev/null 2>&1 || sudo service docker start

# 1. Nó Hardhat — OBRIGATÓRIO em 0.0.0.0 (o Blockscout roda em container e não alcança 127.0.0.1)
cd local-env/local-env-scaffold-eth-2 && yarn chain --hostname 0.0.0.0

# 2. Contratos de teste + 1 round de eventos + Postgres do Evex + frontend + control-panel
cd local-env && ./scripts/up.sh

# 3. Backend do Evex (pela IDE, ou:)
cd backend && EVEX_RPC_URL=http://localhost:8545 EVEX_BLOCKSCOUT_URL=http://localhost:4001 ./gradlew bootRun

# 4. Blockscout — só para os cenários 2 e 3 (discovery / detecção via source verificado)
cd local-env && ./scripts/blockscout-up.sh
#    espere indexar: curl -s localhost:4001/api/v2/stats | grep total_blocks   (> 0)
npx tsx blockscout/verify-contracts.ts   # verifica TestERC20 e TestEventEmitter no Blockscout
```

| Peça | URL | Papel |
|---|---|---|
| Frontend do Evex | `http://localhost:5173` | UI mínima da PoC |
| Backend do Evex | `http://localhost:8080` | API (Swagger em `/swagger-ui.html`) |
| Nó Hardhat | `http://localhost:8545` | rede EVM local (chainId 31337) |
| Blockscout | `http://localhost:4001` | explorer real (discovery de ABI) |
| control-panel | `http://127.0.0.1:8899` | dispara os scripts do `local-env` (aba Demonstração) |
| Postgres do Evex | `localhost:5432` (`evex`/`evex`) | índice central |

**Para habilitar a aba "Demonstração"** no frontend, sirva-o com a variável setada (antes do `up.sh`,
ou rode o `npm run dev` você mesmo):

```bash
echo 'VITE_LOCALENV_CONTROL_URL=http://localhost:8899' >> frontend/.env.local
```

**Estado limpo:** para começar do zero, trunque as tabelas do Evex e **reinicie o backend por
completo** (Stop + Start — não HotSwap):

```bash
docker exec local-env-evex-postgres-1 psql -U evex -d evex -c \
  "DO \$\$ DECLARE r RECORD; BEGIN FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history') LOOP EXECUTE 'TRUNCATE TABLE public.'||quote_ident(r.tablename)||' RESTART IDENTITY CASCADE'; END LOOP; END \$\$;"
```

O truncate **não** invalida o cache de ABIs em memória do backend (ver [DEC-070](decisions.md) e o
Troubleshooting) — por isso o restart completo é obrigatório numa rodada limpa.

---

## 0.1. Rodar só o front (modo mock, sem backend)

Para **demonstrar as telas** ou tirar prints sem subir Hardhat + Blockscout + backend + Postgres, o
frontend tem um **modo mock**: um Service Worker ([MSW](https://mswjs.io)) intercepta as chamadas da
API e responde com dados estáticos (`frontend/src/mocks/`). Nenhum código de produto muda — os mesmos
componentes rodam contra o cliente gerado.

```bash
cd frontend
npm ci
npm run generate:api   # 1x — gera o cliente TS (precisa de Java); já roda no dev/build/test normais
npm run dev:mock       # http://localhost:5173 — sem backend
```

- Dados de exemplo: 4 contratos (ERC20 com ABI, "Registro de Widgets" com ABI, "Coleção NFT ERC1155"
  **sem** ABI, "Cofre" proxy com 2 versões de ABI), ~14 eventos (decodificados + "sem ABI"), 2 configs
  de saldo, 2 webhooks com entregas, discovery Blockscout, sugestão de interface. **Recarregar a
  página zera o estado.**
- Mutações funcionam em memória: cadastrar contrato → aparece na lista; cadastrar ABI num contrato
  "sem ABI" → os eventos raw dele viram decodificados; pausar/ativar config; disparar backfill.
- A aba **"Demonstração"** aparece (o `control-panel` também é mockado) e o "Executar" mostra um output
  simulado.
- Os 2 streams SSE de status não são interceptáveis pelo MSW — o front cai pro polling do `/status`
  (que devolve "nada rodando"). É o mesmo caminho de degradação graciosa do [cenário 9](#9-acompanhar-jobs-assíncronos-ao-vivo-sse).

> **Não use o modo mock para validar integração** — ele não fala com a rede nem com o banco. É só
> para a camada visual. Para o fluxo ponta a ponta, suba a stack ([§0](#0-subir-o-ambiente-local)).

---

## 1. Monitorar um contrato conhecido (cadastro + ABI colada)

![Lista de contratos monitorados](img/contratos-lista.png)

**Quando:** você sabe o endereço e tem a ABI em mãos (JSON).

1. **Contratos → "Novo contrato"**.
2. Preencha **Endereço** (`0x…`, 40 hex), **Nome**, **Rede** (`TESTNET` ou `MAINNET`). `Descrição` e
   `Versão` são opcionais.
3. **"Cadastrar"** → você vai para a tela de detalhe do contrato (`/contracts/<endereço>`).
4. Na seção **ABI → "Cadastrar nova versão"**:

   ![Detalhe do contrato: status, dados e seção ABI](img/contrato-detalhe-abi.png)

   - Origem **"Colar texto"** → cole o JSON da ABI (array de fragmentos; funções podem ficar, só os
     `type: "event"` importam para a decodificação).
   - **"Bloco inicial de validade"** — deixe `0` (default) para "vale desde sempre". Use outro valor
     só no cenário de proxy (cenário 11).
   - **"Cadastrar versão de ABI"**.
5. A tabela de versões ganha uma linha: `Bloco inicial 0` · `vigente` · `Origem UPLOAD_TEXT` ·
   `Storage DATABASE`.

**O que acontece nos bastidores:** ao cadastrar a ABI, o backend
(a) passa a **decodificar ao vivo** os novos eventos desse contrato na ingestão, e
(b) dispara um **redecode** dos eventos desse contrato que já estavam gravados como "raw" (cenário 4).

**Variações:**
- **ABI malformada** (`{ not json`) → a modal continua aberta e mostra erro `INVALID_ABI_CONTENT`.
- **Enviar arquivo `.abi`** em vez de colar → origem `UPLOAD_FILE`.
- **Status do contrato** (`ACTIVE` / `PAUSED` / `ARCHIVED`) — botões no topo do detalhe. `PAUSED` para
  de indexar eventos novos daquele contrato; a transição é livre nos dois sentidos.

**API equivalente:**
```
POST /v1/contracts            { address, name, network, description?, version? }
POST /v1/contracts/{address}/abi   { source: "UPLOAD_TEXT", abi: "<json>", validFromBlock?: 0 }
```

---

## 2. Descobrir e importar contratos via Blockscout

**Quando:** a rede tem um Blockscout com contratos **verificados** e você quer puxar endereço + ABI de
lá em vez de digitar.

*Pré-requisito:* `EVEX_BLOCKSCOUT_URL` setado no backend e o Blockscout indexando.

![Dialog "Novo contrato" com a coluna "Importar do Blockscout"](img/contrato-novo-discovery.png)

1. **Contratos → "Novo contrato"**. Na coluna **"Importar do Blockscout"** aparecem os contratos
   verificados (no local-env: `TestERC20`, `TestEventEmitter`).
2. Clique num item → **Endereço** e **Nome** do formulário são preenchidos automaticamente.
   Cada item mostra um selo **Proxy**/**Implementação** e os avisos do backend. Ao clicar numa
   **Implementação** com um único proxy, o formulário recebe o endereço do **proxy** (com um aviso na
   coluna); com 2+ proxies, você escolhe qual proxy cadastrar. O cadastro nunca é bloqueado.
3. Escolha a **Rede** → **"Cadastrar"** → vai para o detalhe.
4. Na seção **ABI → "Cadastrar nova versão" → origem "Buscar no Blockscout" → "Cadastrar versão de
   ABI"**. O Evex busca a ABI direto do Blockscout.
5. A tabela de versões ganha uma linha com **origem `BLOCKSCOUT`**.

Se o bloco inicial (`validFromBlock`) não for informado, o Evex usa automaticamente o **bloco de criação do contrato** obtido no Blockscout (ou 0, se o Blockscout não tiver esse dado). Um valor informado explicitamente, inclusive 0, é sempre respeitado; para faixas de ABI por upgrade de proxy, use a importação de histórico.

Itens `kind = PROXY` aparecem com o nome da implementação mais recente do proxy presente na lista de
verificados seguido de "(Proxy)" (ex.: `TokenCoin (Proxy)`), em vez do genérico `ERC1967Proxy` do Blockscout;
sem implementação derivável na lista, o nome original é mantido.

**Proxy upgradável (DEC-104):** cada item do discovery traz também `kind`, `proxyAddresses`,
`implementationAddress`, `lastUpgradeBlock` e `warnings`, calculados a partir dos eventos
`Upgraded(address)` **já ingeridos** (sem consulta extra à rede):

- `kind = PROXY`: o endereço emitiu `Upgraded`; `implementationAddress` é a implementação do último
  `Upgraded` observado e `lastUpgradeBlock` o bloco dele (inclui o `Upgraded` emitido no deploy do proxy).
  Este é o endereço certo para cadastrar.
- `kind = IMPLEMENTATION`: algum proxy aponta para o endereço; `proxyAddresses` lista os proxies
  vigentes (pode haver vários, ex.: um proxy por classe). Cadastre o proxy, não a implementação — os
  eventos são emitidos pelo proxy.
- `kind = STANDALONE`: **nenhum** `Upgraded` observado nos eventos ingeridos. Não prova que o contrato
  não é proxy/implementação: depende da cobertura da ingestão (`warnings` avisa quando a ingestão ainda
  não iniciou ou está atrás do bloco do `Upgraded`). Só `Upgraded(address)` é detectado (não Beacon/Diamond).

Os avisos são informativos e nunca bloqueiam o cadastro.

**Complemento pelos logs do Blockscout (DEC-107):** além dos eventos ingeridos, a classificação consulta
os logs `Upgraded` da rede inteira no Blockscout, então um proxy/implementação já aparece corretamente
mesmo que a ingestão ainda não tenha chegado ao bloco do upgrade. Quando a evidência vem só do
Blockscout, o `warnings` diz isso e informa até onde a ingestão foi; se a lista do Blockscout vier
truncada ou o Blockscout falhar nessa consulta, a classificação segue só com os eventos ingeridos e um
aviso. Desligue com `indexer.blockscout.upgrade-logs-enabled=false` (volta ao comportamento só-ingeridos).

**Variação:** sem Blockscout no ar, a mesma coluna mostra *"Discovery via Blockscout não está
disponível"* (não é erro — o cadastro manual continua funcionando).

**API equivalente:**
```
GET  /v1/discovery/blockscout/contracts                # lista verificados
POST /v1/contracts/{address}/abi   { source: "BLOCKSCOUT" }
```

---

## 3. Detectar automaticamente a interface (ERC20 / 721 / 1155)

**Quando:** você não tem a ABI, mas o contrato já emitiu eventos e você suspeita que ele segue um
padrão conhecido (ERC20 / ERC721 / ERC1155).

O Evex compara os `topic0` dos primeiros logs observados com templates built-in.

1. Garanta que o contrato está **cadastrado sem ABI** e que já há eventos dele indexados (rode o
   cenário 10 "Disparar eventos de teste", ou espere o poller).
2. No detalhe do contrato → **"Ver sugestões automáticas" → "Detectar interfaces"**.
   ![Sugestões de detecção automática: ERC1155 — FULL](img/deteccao-automatica.png)

3. O Evex lista as interfaces reconhecidas com um **nível**:
   - **`FULL`** — todos os eventos do template batem. Dá para cadastrar a ABI automaticamente:
     feche a modal → **"Cadastrar nova versão" → origem "Detectar automaticamente" → "Detectar
     interfaces" → "Cadastrar versão de ABI"** → linha **`AUTO_DETECTED`** na tabela.
   - **`PARTIAL`** — match parcial. A UI **bloqueia** o cadastro automático e explica *"Nenhuma
     sugestão com match total"*. Use o cadastro manual (cenário 1).

**API equivalente:**
```
GET  /v1/contracts/{address}/abi/suggestions
POST /v1/contracts/{address}/abi   { source: "AUTO_DETECTED", templateName: "ERC20" }
```

---

## 4. Pegar eventos já gravados e decodificar (redecode do histórico)

**⭐ O fluxo central do produto** ("indexar tudo primeiro, decodificar depois").

**Cenário:** o poller ingeriu eventos de um contrato **antes** de você cadastrar a ABI dele. Esses
eventos ficaram gravados como **"raw"** (topics + data brutos, sem nome nem parâmetros). Ao cadastrar
a ABI, o Evex **reprocessa** esses eventos já gravados e os transforma em decodificados — **sem tocar
a rede de novo** (os logs de blocos confirmados são imutáveis, o `RawLog` é reconstruído do banco).

**Passo a passo (UI):**

1. Cadastre o contrato **sem ABI** (cenário 1, pare no passo 3). Deixe o poller ingerir eventos (ou
   dispare-os pela aba Demonstração — cenário 10).
2. **Eventos** → filtre por esse contrato → as linhas mostram o chip **"sem ABI"** (laranja) na
   coluna *Evento*. A coluna *Contrato* mostra o nome com link para o detalhe (contrato monitorado)
   ou o endereço encurtado (não monitorado).

   ![Tela de eventos: decodificados e "sem ABI" lado a lado](img/eventos-lista.png)
3. Volte ao detalhe do contrato → cadastre a ABI (colar / Blockscout / detectar).
4. **Sem recarregar a página**, um indicador *"Decodificando o histórico… (N processados)"* aparece
   na seção ABI enquanto o redecode roda, e some sozinho ao terminar (cenário 9 — via SSE).
5. Volte em **Eventos**, filtre pelo mesmo contrato → as **mesmas linhas** agora mostram o **nome do
   evento** (ex.: `Transfer`, `WidgetCreated`). Clique em **"ver parâmetros"** para ver a tabela de
   parâmetros decodificados (`from` / `to` / `value` etc.).

   ![Evento decodificado com "ver parâmetros" expandido](img/eventos-parametros.png)

> **Nota de ambiente:** num local-env pequeno (dezenas de eventos por contrato), o redecode termina
> em menos de 2 segundos — o indicador pode piscar rápido demais para ser notado. O mecanismo está
> correto; o efeito é só de escala.

**Filtros da tela de Eventos:** contrato (autocomplete de monitorados **ou** "digitar endereço
manualmente" para um endereço fora da lista), nome do evento (catálogo do contrato quando há um
monitorado selecionado; texto livre no modo manual), faixa de blocos, faixa de datas.

**Redecode manual (opcional):** `POST /v1/redecode` dispara um redecode global de tudo que está
pendente (`decoded=false`). `GET /v1/redecode/status` devolve `{ running, processedCount,
decodedCount, remainingCount }`.

---

## 5. Monitorar saldo de carteiras ao vivo

**Quando:** você quer que o Evex mantenha o saldo de token de cada carteira, derivado dos eventos.

O Evex **não** consulta `balanceOf` na rede — ele **projeta** o saldo a partir dos eventos
decodificados, conforme uma **configuração de monitoramento** que você define por contrato+evento.

**Comportamentos (`behavior`):**

| `behavior` | Efeito no saldo | Exemplo | Parâmetros exigidos |
|---|---|---|---|
| `CREDIT` | sempre **soma** | `TokenMinted(recipient, amount)` | `walletParam`, `valueParam` |
| `DEBIT` | sempre **subtrai** | `TokenBurned(holder, amount)` | `walletParam`, `valueParam` |
| `TRANSFER` | **deduz** de um endereço e **credita** em outro | `Transfer(from, to, value)` | `fromParam`, `toParam`, `valueParam` |
| `DELTA` | o parâmetro **já é** o delta (pode ser negativo) | `BalanceUpdated(wallet, int256 delta)` | `walletParam`, `valueParam` |

`tokenIdParam` é opcional — use para ERC1155 / ERC721 (o saldo passa a ser por `(carteira, contrato, tokenId)`).

![Tela de saldos: consulta, configs de monitoramento e "Nova configuração" no modo guiado](img/saldos.png)

**Passo a passo (UI, modo guiado):**

1. Garanta o contrato **com ABI** (para o catálogo de eventos e a decodificação ao vivo).
2. **Saldos → "Configurações de monitoramento" → "Nova configuração"**:
   - **Contrato** — escolha no autocomplete. **Não há campo "Rede"**: ela é **herdada** do contrato.
   - **Nome do evento** — escolha no catálogo (`<EventPicker>`, alimentado por
     `GET /v1/contracts/{address}/events`).
   - **Comportamento** — `CREDIT` / `DEBIT` / `TRANSFER` / `DELTA`. Os campos de parâmetro abaixo
     mudam conforme a escolha.
   - **Parâmetros** — cada um é um select filtrado por tipo: "Parâmetro da carteira" só lista os
     inputs `address` do evento; "Parâmetro do montante/delta" só lista os numéricos; etc.
3. **"Cadastrar"** → *"Configuração cadastrada com sucesso."* + linha na tabela.
4. Dispare eventos (mint etc.) e espere decodificar. Em **"Consultar saldos"**, filtre por
   **Carteira** + **Contrato** → aparece a linha com o saldo projetado.
5. Na tabela de configs: **"Pausar"** → coluna *Ativa* vira "não" (a projeção para de considerar
   novos eventos dessa config); **"Ativar"** volta.

**Ordem importa:** a projeção ao vivo só considera eventos que chegam **depois** da config existir (e
serem decodificados). Para incorporar histórico anterior, veja o cenário 6.

**API equivalente:**
```
GET  /v1/contracts/{address}/events           # catálogo de eventos (nome + inputs tipados)
POST /v1/balance-monitors    { contractAddress, network, eventName, behavior,
                               walletParam?|fromParam?+toParam?, valueParam, tokenIdParam?, active? }
GET  /v1/balances?walletAddress=&contractAddress=&tokenId=
PATCH /v1/balance-monitors/{id}/status   { active: false }
```

---

## 5.1. Saldo de eventos em lote (arrays)

Alguns contratos emitem **um único evento com vários lançamentos** em parâmetros do tipo array — o caso
típico é o `TransferBatch(operator, from, to, uint256[] ids, uint256[] values)` de um ERC-1155 genérico.
A config de saldo tem a flag `batch` para isso.

**Como configurar** — por contrato ERC-1155 são **2 configs**, uma por evento (o `behavior` é o mesmo,
`TRANSFER`):

```jsonc
// 1) lançamentos individuais (batch omitido = false)
{ "contractAddress": "0xbbbb...02", "network": "TESTNET", "eventName": "TransferSingle",
  "behavior": "TRANSFER", "fromParam": "from", "toParam": "to", "valueParam": "value", "tokenIdParam": "id" }

// 2) lançamentos em lote — valueParam e tokenIdParam são arrays
{ "contractAddress": "0xbbbb...02", "network": "TESTNET", "eventName": "TransferBatch",
  "behavior": "TRANSFER", "fromParam": "from", "toParam": "to", "valueParam": "values", "tokenIdParam": "ids",
  "batch": true }
```

**Regras**

- Com `batch=true`, cada posição `i` dos arrays vira um lançamento independente, aplicado com o mesmo
  `behavior`; `from`/`to`/`wallet` continuam escalares e valem para todas as posições. Endereço zero é
  tratado por posição (mint só credita o destinatário, burn só debita o remetente).
- **Descarte atômico**: se os arrays tiverem tamanhos diferentes, algum parâmetro não for lista ou algum
  elemento não for numérico, o evento **inteiro** é descartado (nenhuma posição é aplicada) com um WARN
  deduplicado por config; o restante do lote segue. Arrays vazios não geram nenhum lançamento. Ids repetidos
  no mesmo lote somam.
- Com `batch=false`, um parâmetro de valor/tokenId que chega como lista também descarta o evento, com WARN
  sugerindo `batch=true`.
- **Validação contra a ABI** (só no cadastro/edição): se o contrato tem ABI cadastrada e o evento existe,
  `batch=true` exige `valueParam`/`tokenIdParam` do tipo array numérico (ex.: `uint256[]`) e `batch=false`
  exige escalares. São consideradas **todas** as versões da ABI (proxy upgradável): se **nenhuma** for
  compatível, a API responde `400`; se **só algumas** forem, a config é aceita e a resposta traz
  `warnings` (texto em português) com as faixas de blocos afetadas, onde o evento será descartado. Sem ABI
  cadastrada, nada é validado.
- **Editar uma config para `batch=true` não reprojeta o histórico.** Para contabilizar eventos já indexados,
  crie uma config nova (ela nasce com a marca d'água do cursor) e rode o backfill (seção 6). Backfill e
  redecode usam o mesmo caminho da ingestão ao vivo e chegam ao mesmo saldo.

**Pela UI:** em **Saldos → Cadastrar** (ou Editar) a configuração tem o checkbox **"Lote (batch)"**,
nos 3 modos do formulário. No **guiado**, ao escolher um evento que só oferece parâmetros array
(ex.: `TransferBatch`) o Lote é marcado sozinho (dá para desmarcar) e os selects de montante/tokenId
passam a listar só arrays numéricos (sem Lote, só escalares); no **visual**, os slots ganham o sufixo
"(array)" e vincular um array ao montante sugere o Lote; no **manual** é um checkbox simples. Um aviso
amarelo aparece se o tipo do parâmetro não combina com o Lote (o backend continua sendo a garantia final:
`400` mostrado no formulário). Se a resposta do cadastro/edição trouxer `warnings`, o detalhe da config
abre com um alerta "Configuração salva com avisos". Configs em lote exibem o chip **"Lote"** na listagem
e no detalhe.

**Limitação de precisão** — `balance` e `tokenId` da API de saldos são `string` e o banco usa inteiro
exato, então saldos e ids grandes (até 2^256-1) estão corretos. Já os `decodedParameters` da **API de
eventos** são números JSON: ids/valores acima de 2^53 perdem precisão em clientes JavaScript que usam
`JSON.parse` direto (o dado gravado continua exato). Para ids grandes, use a API de saldos.

---

## 6. Backfill de saldo (config criada depois dos eventos)

**Cenário:** você criou a config de saldo **depois** que o histórico já foi indexado e decodificado.
A projeção ao vivo não volta atrás → `GET /v1/balances` fica vazio para esse par até você rodar o
backfill.

O **backfill** reprojeta os eventos **já indexados e decodificados** abaixo da "marca d'água" da
config (o bloco do cursor de indexação no momento da criação da config) contra ela — **sem
reconsultar a rede**. É um job global assíncrono em lotes (um por instância, DEC-068), resumível e
exactly-once (cursor `(block, logIndex)` persistido na config).

**Passo a passo (UI):**

1. Cenário: contrato **com ABI** e histórico já decodificado (cenário 4), **depois** você cria a
   config de saldo (cenário 5).
2. Consulte o saldo → **vazio**.
3. Na linha da config, botão **"Reprocessar histórico"**.
4. Aparece o progresso inline: *"processados N · deltas N · restam N"* + barra; ao terminar, *"Reprocessamento concluído."*.
5. Consulte o saldo de novo → agora aparece, com o valor certo.
6. Clicar **"Reprocessar histórico"** de novo → **no-op** (o saldo não muda; `GET
   /v1/balance-monitors/backfill/status` reporta `caughtUp: true`).

**Enquanto um backfill de OUTRA config roda,** o botão das demais fica desabilitado com o aviso
*"Há um reprocessamento de outra configuração em andamento"* (job global, DEC-068).

**API equivalente:**
```
POST /v1/balance-monitors/{id}/backfill
GET  /v1/balance-monitors/backfill/status   # { running, configId, processedCount, deltaCount, remainingCount, caughtUp }
```

---

## 7. Configurar um webhook (notificação de saída)

**Quando:** você quer que um sistema externo receba um `POST` sempre que um evento decodificado bate
com um filtro.

Webhooks **não** têm a ver com a conexão com a rede (que é só polling). É notificação de **saída**:
evento decodificado → bate com a config → `POST` no endpoint que você configurou → fila com retry e
log de tentativas.

**Passo a passo (UI, modo guiado):**

1. Contrato **com ABI**.
2. **Webhooks → "Novo webhook"**:
   - **Contrato** + **Nome do evento** (catálogo) — rede herdada, igual ao saldo.
   - **URL de destino** — para onde vai o `POST`.
   - **Filtros (opcional)** — "adicionar filtro" → escolha o **parâmetro** (select por tipo),
     **operador** (`GT`/`GTE`/`LT`/`LTE`/`EQ`/`NEQ` — comparação numérica quando possível, senão
     string) e **valor**. Só dispara quando **todos** os filtros passam.
   - **Template de payload (opcional)** — ver abaixo. Sem template, o Evex manda o envelope padrão.
3. **"Cadastrar"** → *"Webhook cadastrado com sucesso."*.
4. Clique na linha para abrir o **detalhe** do webhook.
5. Dispare um evento que bate com o filtro → espere decodificar → na seção **"Entregas"** aparece uma
   linha; status **`PENDING` → `DELIVERED`** (ou `FAILED` se esgotar as tentativas). **"ver
   tentativas"** mostra cada tentativa com o HTTP status e o corpo.

![Detalhe do webhook: config no modo visual (filtro + template) e tabela de entregas](img/webhook-detalhe-entregas.png)

**Envelope padrão** (quando `payloadTemplate` é nulo):
```json
{
  "contractAddress": "0x…", "network": "TESTNET", "eventName": "Transfer",
  "blockNumber": 123, "blockHash": "0x…", "txHash": "0x…", "logIndex": 0,
  "blockTimestamp": "2026-09-02T…Z",
  "decodedParameters": { "from": "0x…", "to": "0x…", "value": "1000" }
}
```

**Template customizado** — interpolação simples de `{{campo}}` e `{{decodedParameters.<nome>}}`.
Campos fixos: `{{eventName}}`, `{{contractAddress}}`, `{{network}}`, `{{blockNumber}}`, `{{txHash}}`,
`{{blockHash}}`, `{{logIndex}}`, `{{blockTimestamp}}`. No modo **visual** há um construtor com botões
que inserem os tokens. Cada placeholder já resolve pro literal JSON completo (string vem com aspas
embutidas) — **não** colocar aspas manuais ao redor dele. Exemplo:
```json
{"evento": {{eventName}}, "de": {{decodedParameters.from}}, "valor": {{decodedParameters.value}}}
```
Template malformado é rejeitado **no cadastro** (não silenciosamente no primeiro disparo).

**API equivalente:**
```
POST /v1/webhooks   { contractAddress, network, eventName, url,
                      filters?: [{ paramName, operator, value }], payloadTemplate?, active? }
GET  /v1/webhooks/{id}/deliveries
PATCH /v1/webhooks/{id}/status   { active: false }
```

---

## 8. Os 3 modos de formulário (guiado / visual / manual)

Os formulários de **saldo**, **webhook** e o **filtro de eventos** têm um seletor de modo
(`Guiado / Visual / Manual`). A escolha **persiste no reload** (por formulário, `localStorage`).

| Modo | Como funciona | Quando usar |
|---|---|---|
| **Guiado** (default) | Selects em cascata sobre os dados cadastrados: contrato → evento (catálogo) → parâmetros (filtrados por tipo). Rede herdada do contrato. | O caminho normal — contrato conhecido, você não digita endereço nem nome de parâmetro. |
| **Visual** | Um **canvas** com os parâmetros do evento como "chips" arrastáveis (ou clicáveis) que você vincula aos "slots" (Carteira, Montante, Origem, Destino…). Recusa vínculo de tipo incompatível. Para webhook, inclui o construtor de `payloadTemplate` por clique. | Apresentar o mapeamento de forma explícita; explorar o que cada evento oferece. |
| **Manual** | Campos de texto livre (endereço, nome do evento, nomes de parâmetro) + `<NetworkSelect>` explícito. É o comportamento pré-E16. | Evento que **não está no catálogo** (ex.: contrato sem ABI ainda, ou um evento que você quer filtrar antes de decodificar), ou integração via colar/copiar. |

Os 3 modos montam **o mesmo payload** — a diferença é só a forma de preencher.

![Formulário de webhook no modo visual: canvas de filtros + construtor de payloadTemplate](img/webhook-form-visual.png)

**Herança de rede:** nos modos guiado/visual, escolher um contrato cadastrado **remove** o campo
"Rede" — ela vem do `ContractRef`. Não dá para submeter com rede vazia.

---

## 9. Acompanhar jobs assíncronos ao vivo (SSE)

O status dos jobs assíncronos (**redecode** do cenário 4, **backfill** do cenário 6) é **empurrado**
do backend para o front em tempo real via **Server-Sent Events**, sem polling e sem recarregar a
página.

- **Tela do contrato** (`/contracts/<endereço>`) assina `GET /v1/redecode/status/stream`. Enquanto um
  redecode global roda, aparece `<CircularProgress>` + *"Decodificando o histórico… (N processados)"*
  na seção ABI — em **qualquer** tela de contrato (o redecode é global, o texto é genérico de
  propósito). Some sozinho quando termina.
- **Tela de Saldos** (`/balances`) assina `GET /v1/balance-monitors/backfill/status/stream`. O
  progresso inline (`processados · deltas · restam` + barra) chega por esse stream — abra `/balances`
  em duas abas e dispare o backfill numa: a outra atualiza ao vivo.

**Frames SSE:** `event: status` (o mesmo DTO dos endpoints REST `/status`), `event: heartbeat` (a
cada ~15s, mantém a conexão), `event: done` (quando o job vai de `running: true → false`).

**Degradação graciosa:** se o backend cair com o stream aberto, o `EventSource` detecta e o front
**cai para polling** do endpoint REST `/status` correspondente (1,5s) — a UI não quebra. Quando o
backend volta, as telas com polling (`/balances`, `/contracts/:addr`) se recuperam sozinhas; telas de
query pura (`/events`) mostram *"Network Error"* até a próxima ação (Filtrar / reload). O
`EventSource` **não** re-assina sozinho para o mesmo path após um erro (ver [DEC-074](decisions.md)
complemento) — em sessão de demo (< 30 min) isso não incomoda.

Os 2 endpoints `/stream` **não** entram no cliente TypeScript gerado (o `openapi-generator` não
modela `text/event-stream`) — são consumidos via `EventSource` nativo. É a exceção da
[DEC-078](decisions.md) à regra "OpenAPI-first".

---

## 10. Aba "Demonstração" — disparar scripts sem terminal

**Só aparece** quando `VITE_LOCALENV_CONTROL_URL` está setado **e** o `local-env/control-panel`
responde ao health check. **Não faz parte do produto** — é ferramenta de demonstração local.

Cada card mostra **o comando exato que vai rodar**, antes de executar, e exibe o output ao vivo num
`<pre>` + um chip de status (`rodando` / `exit 0` / `exit N` / `falhou`).

![Aba "Demonstração": os 3 cards, comando exato e output do "Disparar eventos de teste"](img/demonstracao.png)

| Card | O que faz |
|---|---|
| **Deploy dos contratos de teste** | Copia os `.sol` de `local-env/contracts/` para o clone do scaffold-eth-2 e roda `yarn deploy --tags TestContracts`. |
| **Disparar eventos de teste** | Roda `seed/emit-events.ts`: `mint`/`safeMint`/`mintBatch`/`createWidget`/`changeStatus`/`processBatch` nos 4 contratos. Campo **Rounds** repete o lote inteiro (gera volume / testa paginação). |
| **Registrar os 4 contratos no Evex** | Roda `seed/register-contracts.ts`: `POST /v1/contracts` + `POST /abi` para os 4 de uma vez (estado "tudo cadastrado" sem clicar). |

**Uma ação por vez:** disparar uma segunda ação enquanto a primeira roda → **HTTP 409**, renderizado
como alerta *"Já há uma ação em execução no control-panel — aguarde ela terminar."*. Fechar a aba no
meio de uma execução mata o processo filho (não deixa órfão).

Fluxo típico de demo: **Disparar eventos** (Rounds=2) → ver os eventos entrando em `/events` →
cadastrar a ABI de um contrato pela UI → ver o redecode (cenário 4).

---

## 11. Contrato proxy upgradável (ABI versionada por faixa de blocos)

**Cenário:** um proxy (ex.: `ERC1967Proxy`) cujo comportamento — e portanto a ABI dos eventos — muda
num bloco de upgrade. Eventos antigos precisam ser decodificados com a ABI **da época**, os novos com
a atual.

O Evex versiona `contract_abis` por **faixa de blocos** por endereço: cada versão tem
`validFromBlock` e `validToBlock` (aberto = `null`, "vigente"). Ao decodificar um evento, o Evex
escolhe a versão cuja faixa contém `event.blockNumber`.

**Contrato real pra testar isso** (não precisa simular): `local-env/contracts/` tem um proxy UUPS/
ERC1967 de verdade — `TestUpgradeableVaultV1.sol` (evento `Deposited`) → upgrade → `V2.sol` (mantém
`Deposited`, acrescenta `Withdrawn`) — e 2 scripts que fazem o deploy/upgrade de ponta a ponta
(`local-env/README.md` §3.9, também disponíveis na aba "Demonstração"):

```bash
cd local-env
npx tsx seed/deploy-upgradeable-vault.ts     # implementação V1 + proxy inicializado + 2 deposit()
npx tsx seed/upgrade-upgradeable-vault.ts    # implementação V2 + upgradeToAndCall + deposit()+withdraw()
```

**Passo a passo (UI):**

1. Rode `deploy-upgradeable-vault.ts`. Cadastre o contrato no Evex com o **endereço do proxy** que o
   script imprime (não o da implementação) e cole o conteúdo de `.logs/vault-v1-abi.json` na tela
   "Cadastrar ABI" com `Bloco inicial de validade = 0`.
2. Rode `upgrade-upgradeable-vault.ts`. Ele upgrada o mesmo proxy pra V2 e imprime o bloco `B` exato da
   transação de upgrade. Cadastre a **nova versão** da ABI (conteúdo de `.logs/vault-v2-abi.json`) com
   **`Bloco inicial de validade = B`**. O Evex fecha automaticamente a versão anterior em `B - 1`.
3. A tabela de versões passa a mostrar duas linhas: `[0 … B-1]` e `[B … vigente]`.

   ![Contrato proxy com 2 versões de ABI por faixa de blocos](img/proxy-abi-versoes.png)
4. `POST /v1/redecode` (ou o redecode automático do cadastro) reprocessa cada evento contra a versão
   correta para o bloco dele. Confirme em `GET /v1/events`: os blocos antes de `B` só têm `Deposited`,
   os de `B` em diante podem ter `Deposited` **e** `Withdrawn`.

**Regra:** a versão aberta (`validToBlock = null`) exige `validFromBlock` estritamente crescente a
cada novo cadastro — cadastrar uma versão com bloco ≤ ao da vigente devolve `400
ABI_VERSION_ORDER_INVALID`.

---

## 11.1. Importar histórico de versões de ABI (proxy UUPS)

**Cenário:** o proxy já passou por vários upgrades e você quer uma versão de ABI por upgrade, sem
cadastrar uma a uma (DEC-105). O Evex monta a linha do tempo de `Upgraded(address)` a partir dos
eventos **ingeridos** e dos logs do Blockscout, busca a ABI verificada de cada implementação (composta
com os eventos do proxy, para não perder o próprio `Upgraded`) e cadastra tudo de uma vez.

**Pela UI:** no detalhe do contrato, seção **ABI → "Importar histórico de versões do Blockscout"**:
escolha a fonte, revise o plano (tabela com diff expansível) e clique **"Aplicar importação"** →
confirmar. O opt-in **"Substituir versões legadas importadas do Blockscout"** (`replaceBlockscoutLegacy`)
fica no cabeçalho do diálogo, junto da fonte: ao marcá-lo o dry-run é refeito e já mostra o plano com a
substituição e um alerta com o que será apagado (só versões importadas do Blockscout com a ABI do proxy,
nunca uploads manuais).

**Passo a passo (API):**

1. **Dry-run** (não grava nada): `GET /v1/contracts/{address}/abi/history-plan?historySource=AUTO`.
   `historySource` aceita `AUTO` (união ingeridos + Blockscout, default), `INGESTED_ONLY` e
   `BLOCKSCOUT_ONLY`. A resposta traz uma linha por upgrade (`validFromBlock`/`validToBlock`,
   `implementationAddress`, `txHash`, `upgradeSource`, `diffFromPrevious` com eventos/métodos
   adicionados e removidos, `identicalToPrevious`), `warnings` e um `fingerprint`.
2. **Aplicar:** `POST /v1/contracts/{address}/abi/history-import` com corpo opcional
   `{"historySource": "AUTO", "expectedFingerprint": "<fingerprint do dry-run>", "replaceBlockscoutLegacy": false}`.
   O servidor sempre recalcula o plano e cadastra só os itens `APPLY`, em ordem crescente, numa única
   transação, e dispara **um** redecode ao final (`redecodeStarted`; se vier `false`, já havia um redecode
   em curso: dispare `POST /v1/redecode`). Se o plano mudou desde o dry-run, devolve `409
   ABI_HISTORY_PLAN_CHANGED` sem aplicar nada.
3. Reexecutar é seguro: o que já foi importado volta como `SKIP_ALREADY_IMPORTED`.

**Status dos itens** (string extensível; trate um valor desconhecido como "não aplicável"):

| Status | Significado |
|---|---|
| `APPLY` | Será cadastrado. |
| `SKIP_ALREADY_IMPORTED` | Já existe versão neste bloco (importada antes ou cadastrada manualmente). |
| `SKIP_IDENTICAL` | Já existe versão neste bloco com conteúdo idêntico. |
| `CONFLICT_MANUAL` | Existe versão diferente neste bloco, ou o bloco não é posterior à versão aberta. Nada é sobrescrito. |
| `PENDING_NO_VERIFIED_ABI` | A implementação não tem ABI verificada no Blockscout. |
| `BLOCKED_AFTER_PENDING` | Vem depois de um item pendente/em conflito: o import aplica só o prefixo, sem deixar buraco na linha do tempo. |

**Limitação importante:** só aparece ABI para implementações **verificadas** no Blockscout. Se a
implementação de um upgrade não foi verificada, o item fica `PENDING_NO_VERIFIED_ABI` (o
`validFromBlock` dele é a sugestão de bloco) e os seguintes `BLOCKED_AFTER_PENDING`. Resolva
cadastrando manualmente essa versão em `POST /v1/contracts/{address}/abi` (`UPLOAD_TEXT`, com o bloco
sugerido) ou verificando a implementação no Blockscout, e rode o dry-run de novo: os itens seguintes
passam a `APPLY`.

**Notas:**

- A 1ª versão sempre começa no bloco 0 (nenhum log do proxy existe antes do deploy); o `warnings` avisa
  quando o histórico pode estar incompleto (truncado, Blockscout fora do ar, cursor de indexação atrás).
- Se o Blockscout estiver fora do ar em `AUTO`, o plano usa só os upgrades ingeridos e avisa.
- Versão cadastrada antes da DEC-105 com a ABI do proxy (`source=BLOCKSCOUT`, sem implementação) aparece
  como `CONFLICT_MANUAL`. `replaceBlockscoutLegacy=true` apaga **só** essas versões legadas (nunca
  `UPLOAD_*`) e importa o histórico, na mesma transação.
- No bloco exato de um upgrade, logs anteriores à transação de upgrade nesse mesmo bloco usam a ABI nova
  (a decodificação é por bloco, não por `logIndex`).

---

## 12. Consultar saldo numa data / histórico

Na tela **Saldos → "Consultar saldos"**, o rádio **"Saldo numa data"** consulta o saldo projetado
**como estava** numa data/hora — a partir dos snapshots diários (`asset_balance_snapshots`,
consolidados por um job `@Scheduled` noturno) + os eventos desde o último snapshot.

- **"Saldo atual"** → `GET /v1/balances?walletAddress=&contractAddress=&tokenId=`
- **"Saldo numa data"** → `GET /v1/balances/history?walletAddress=&contractAddress=&date=<YYYY-MM-DD>`

Sem carteira ou contrato preenchido, a consulta traz a página inteira (útil para inspeção).

---

## 13. Eventos enriquecidos no payload de webhook

**Cenário:** o `payloadTemplate` de um webhook (seção 7) só traz o que já veio no próprio evento
decodificado (`{{decodedParameters.x}}`). Às vezes o payload precisa de **estado on-chain que não está
no evento** — de qualquer contrato já cadastrado, lido **no bloco em que o evento disparou**, não no
bloco atual. É pra isso que serve o "Perfil de Enriquecimento" (`{{enriched.*}}`) e, pra acumular
campos de eventos diferentes ao longo do tempo sobre a mesma entidade, o "Modelo de Entidade"
(`{{model.*}}`).

- **Perfil de Enriquecimento** (`/enrichment-profiles`): lista ordenada de chamadas de leitura
  (`view`/`pure`) — contrato-alvo (pode ser diferente do contrato do evento), método, argumentos
  (literais, `{{decodedParameters.x}}` do evento, ou `{{enriched.y}}` de uma chamada anterior no mesmo
  perfil) e as saídas que viram `{{enriched.<placeholder>}}` no payload. É um recurso **próprio e
  reaproveitável**, referenciado por 1+ webhooks — editar o perfil depois afeta todo webhook que o
  referencia (referência viva, não uma cópia).
- **Modelo de Entidade** (`/entity-models`): acumula campos de **eventos diferentes** sobre a mesma
  entidade lógica (ex.: `Created`→`Approved`→`Minted` de um NFT) num registro persistente, correlacionado
  por um campo comum (ex.: `id`). Um webhook só pode referenciar um modelo do qual seu **próprio**
  evento também participa como contribuinte.

**Contrato real pra testar isso** (não precisa simular): `local-env/contracts/TestComputer.sol` — um
"computador" com peças trocáveis (`motherboard`/`gpu`/`cpu`) e um `processingPower` que muda a cada
`changeParts()` — e `TestUpgradeableVault` (seção 11, já cadastrado) representando "a moeda": a cada
troca de peça, deposita ou saca no cofre proporcionalmente. `local-env/seed/computer-vault-scenario.ts`
roda o roteiro inteiro (criação + 3 trocas de peça em blocos **bem espaçados**, pra que a config no meio
do caminho seja visivelmente diferente da final):

```bash
cd local-env
npx tsx seed/computer-vault-scenario.ts     # requer TestUpgradeableVault já em V2 (seção 11)
```

O script imprime os blocos exatos de cada evento do Cofre e a config esperada do Computador em cada
um — guarde esses números pra conferir o resultado no passo 5.

**Passo a passo (UI) — configure ANTES de rodar o script** (o perfil é referência viva; o poller
ingere/despacha quase em tempo real, então configurar depois que os eventos já passaram só afeta
entregas futuras):

1. **Cadastre o Computador**: `TestComputer` sai registrado sozinho se você rodar a ação "Registrar os
   4 contratos no Evex" da aba Demonstração (passa a registrar 5) — ou cadastre manualmente pelo
   endereço que o `yarn deploy --tags TestContracts` imprime.
2. **Crie o Perfil de Enriquecimento** (`/enrichment-profiles` → "Cadastrar"): escolha o Computador
   como contrato-alvo da chamada — o seletor de método (`<MethodPicker>`) carrega os métodos
   `view`/`pure` da ABI cadastrada dele automaticamente. Escolha `getConfig`, preencha o argumento
   `id` (literal, com o id do computador que o script vai usar — por padrão `1`, sobrescrevível via
   `COMPUTER_ID`). As 4 saídas (`motherboard`/`gpu`/`cpu`/`power`) vêm pré-marcadas com um placeholder
   sugerido cada uma — mantenha ou renomeie.
3. **Crie/edite um webhook** (`/webhooks`) no evento `Deposited` (ou `Withdrawn`) do **Cofre**
   (contrato diferente do Perfil, de propósito) apontando pra uma URL que você controle (ex.:
   [webhook.site](https://webhook.site), ou um servidor HTTP local só de teste). Na seção
   "Enriquecimento do payload (opcional)", selecione o Perfil criado no passo 2. No editor do
   `payloadTemplate`, use os placeholders novos que aparecem no hint — ex.:
   ```json
   {"block": {{blockNumber}}, "amount": {{decodedParameters.amount}}, "config": {"motherboard": {{enriched.motherboard}}, "gpu": {{enriched.gpu}}, "cpu": {{enriched.cpu}}, "power": {{enriched.power}}}}
   ```
   **Nunca coloque aspas ao redor de um placeholder de valor** (`{{campo}}`, não `"{{campo}}"`) — quem
   decide se o valor final é string/número/`null` é sempre o renderer, botar aspas na mão gera JSON
   inválido (aspas duplicadas).
4. Rode `npx tsx seed/computer-vault-scenario.ts`. O poller ingere/decodifica os eventos e dispara o
   webhook sozinho — não precisa de `POST /v1/redecode` manual pra eventos novos (só pra reprocessar
   histórico já indexado).
5. Abra `/webhooks/<id>` → aba de entregas. **A prova**: a entrega do bloco "do meio" (a 2ª troca de
   peça) tem que trazer uma config **visivelmente diferente** da entrega final (3ª troca) — motherboard/
   gpu/cpu/power diferentes, não só um número que cresceu. Se o `eth_call` estivesse lendo o bloco
   atual em vez do bloco do evento, as duas entregas trariam a config final, idênticas.

**Modelo de Entidade, resumo rápido:** em `/entity-models` → "Cadastrar", cada contribuinte é
`(contrato, evento, campo de correlação, mapeamento de campos decodificados → campo do modelo)`. Depois
de referenciado por um webhook (`entityModelIds`), os campos acumulados aparecem como
`{{model.<campo>}}` no `payloadTemplate` **daquele mesmo webhook** — só funciona se o próprio evento do
webhook também for um dos contribuintes do modelo. **Contrato real pra testar isso**:
`local-env/contracts/TestGameLibrary.sol` + `seed/game-library-scenario.ts` — jogos que só rodam se o
`TestComputer` (contrato acima) tiver poder suficiente, acumulando `ComputerCreated`+`PartsChanged`+
`GameRunSucceeded` (2 contratos diferentes, 3 eventos diferentes) num só Modelo (`local-env/README.md`
§3.11).

**Limitação conhecida (não bloqueia os cenários acima):** argumento de `EnrichmentCall` só aceita tipos
escalares (`address`/`bool`/`string`/`bytesN`/`uintN`/`intN`) — array/tupla como **entrada** de uma
chamada ainda não tem convenção definida (saída com múltiplos valores funciona normalmente).

---

## 14. Chave de correlação do Modelo de Entidade

Cada contribuinte de um Modelo diz **qual chave** identifica a entidade. Dois eventos só acumulam no
mesmo registro se as chaves finais forem **iguais**. A chave é uma lista ordenada de partes
(`correlationKey`); o atalho antigo `correlationParam: "id"` continua valendo e equivale a
`[PARAM(id)]` (quando os dois vêm, `correlationKey` vence). Tipos de parte (strings extensíveis, não é
enum fechado): `PARAM` (arg = parâmetro decodificado), `EMITTER_ADDRESS` (contrato que emitiu o log),
`TX_HASH`, `BLOCK_NUMBER`, `LITERAL` (arg = constante). Tipo desconhecido é rejeitado com 400 no
cadastro/edição.

Exemplos (endereços fictícios):

| Situação | Chave de cada contribuinte | O que resolve |
|---|---|---|
| O mesmo `id` aparece em contratos diferentes (token A e token B) e não podem se misturar | `[EMITTER_ADDRESS, PARAM(id)]` em todos | `A\|7` e `B\|7` viram registros distintos |
| A entidade é identificada por 2 parâmetros | `[PARAM(org), PARAM(id)]` | chave composta; `\|` e `\` nos valores são escapados |
| Eventos de contratos diferentes na mesma transação | `[TX_HASH]` em todos | um registro por transação, campos de todos os contratos |
| Registro singleton do modelo | `[LITERAL("global")]` em todos | uma única entidade (cuidado: um registro só acumula tudo) |

**O que NÃO resolve (limites):** `EMITTER_ADDRESS` é o endereço do **próprio** emissor do evento, então só
correlaciona eventos do **mesmo emissor**. Para unir contratos **diferentes** as partes precisam avaliar
**igual** nos dois lados: um parâmetro que traga o endereço do outro contrato (ex.: o registry emite
`TokenRegistered(address token, uint256 id)` com `[PARAM(token), PARAM(id)]` e o token usa
`[EMITTER_ADDRESS, PARAM(id)]` — endereços são comparados em minúsculas), `TX_HASH` ou `LITERAL`.
**Eventos que não carregam a chave não correlacionam entre contratos.** Parte ausente/nula/lista/struct
faz o evento não correlacionar (nenhum registro, aviso no log por modelo). Todos os contribuintes do
mesmo modelo precisam ter o **mesmo número de partes** (os tipos podem diferir).

**Mudar a chave de um modelo já populado deixa os registros antigos ÓRFÃOS** (chave diferente): eventos
novos criam registros novos e não existe reprojeção histórica de modelo nesta entrega. Para o histórico,
crie um modelo novo (eventos anteriores à criação do modelo também não entram).
*(Nota: manter um modelo por organização, cada um com chave própria, é um contorno possível, mas não é
a recomendação — prefira a chave composta.)*

**Campo `source` no mapeamento:** cada `fieldMapping` pode trazer `source` (default `PARAM` = lê
`decodedParam`, como sempre). Fontes iniciais: `EMITTER_ADDRESS`, `TX_HASH`, `BLOCK_NUMBER` e
`BLOCK_TIMESTAMP`, que ignoram `decodedParam`. Útil para um registro chaveado por `TX_HASH` guardar o
endereço emissor de cada evento. `BLOCK_TIMESTAMP` só preenche se o timestamp do bloco já estiver
gravado no evento (a projeção nunca consulta a rede); senão o campo é omitido naquele evento, com aviso.
`source` desconhecida = 400 no cadastro.

**Pela UI:** em **Modelos de Entidade → Cadastrar/Editar**, cada evento contribuinte tem o **construtor
de chave**: lista ordenada de partes (adicionar, remover, subir/descer), cada uma com tipo (`PARAM`,
`EMITTER_ADDRESS`, `TX_HASH`, `BLOCK_NUMBER`, `LITERAL`) e o argumento só quando aplicável (`PARAM` →
parâmetro do evento; `LITERAL` → texto), com a pré-visualização da chave (ex.:
`EMITTER_ADDRESS | PARAM(id)`). Modelos legados (só `correlationParam`) aparecem como uma parte
`PARAM` e são salvos exatamente como vieram se a chave não for tocada; ao alterá-la, a UI passa a
enviar `correlationKey` e avisa que os registros antigos ficam órfãos. O formulário exige ao menos uma
parte, argumento preenchido e o mesmo número de partes em todos os contribuintes. Em cada campo
mapeado há o seletor de **fonte** (`PARAM`, `EMITTER_ADDRESS`, `TX_HASH`, `BLOCK_NUMBER`,
`BLOCK_TIMESTAMP`); o parâmetro do evento só aparece para `PARAM`. O modo leitura mostra a chave e a
fonte dos campos. (O editor de modelo tem um único modo — selects/construtor —, sem guiado/visual/manual.)

---

## 15. Pré-visualizar e reenviar webhooks (sem mexer em dados)

Serve para testar um webhook (template, filtros, enriquecimento) contra eventos **já indexados** e,
se quiser, mandar de novo uma notificação — sem reprocessar nada e sem tocar em eventos, saldos nem
modelos.

**Fluxo (3 passos):**

1. **Selecionar os eventos que casam** — `GET /v1/webhooks/{id}/matching-events` com a janela por
   **data** (`fromDate`/`toDate`) e/ou **bloco** (`fromBlock`/`toBlock`). Só devolve eventos que casam
   com o webhook (contrato + evento + rede + decodificados + os filtros do webhook), do mais recente
   para o mais antigo, paginados por `cursor` ("carregar mais"). Os filtros são avaliados em memória: a
   varredura tem teto (`scanned`/`scanTruncated`); se truncar, refine a janela ou continue pelo
   `nextCursor`. O filtro por **data** só enxerga eventos com timestamp de bloco gravado — eventos sem
   timestamp ficam de fora e aparecem em `warnings` (contagem).
2. **Pré-visualizar** — `POST /v1/webhooks/{id}/preview` com `eventIds` (até 5 por requisição por padrão).
   Devolve, por evento, o payload final **como seria enviado**, se casa (`matches`/`notMatchingReason`;
   um evento que não casa também é renderizado, útil para testar o template), o estado do modelo e o
   relatório do enriquecimento **por chamada** (OK/ERRO com o `code`, a mensagem do nó, `rpcCode`/`rpcData`
   brutos e o bloco usado), mais o resumo agregado de falhas. `diagnose: true` reexecuta as chamadas que
   falharam em `latest` e no bloco do evento e devolve um veredicto — é **indício**, não causa. O
   preview **nunca grava nada nem faz POST**: nenhuma linha em nenhuma tabela, nenhum envio.
3. **Reenviar** — `POST /v1/webhooks/{id}/resend` com `eventIds` **ou** `range` (nunca os dois; `range`
   enfileira os eventos que casam, até o teto por requisição). Responde **202** na hora: cada evento aceito
   vira uma entrega `PENDING` (`accepted[{eventId, deliveryId}]`) e o job de dispatch a processa com o
   mesmo retry/backoff/histórico do disparo normal. O que não foi enfileirado vem em `skipped` com o
   motivo (`NOT_MATCHING`, `ALREADY_PENDING`, `EVENT_NOT_DECODED`, `EVENT_NOT_FOUND`). Reenviar o mesmo
   evento enquanto o anterior ainda está `PENDING` é `ALREADY_PENDING` (proteção contra duplo clique).
   Webhook pausado não reenvia (409 `WEBHOOK_INACTIVE`), salvo com URL de teste válida; o preview
   funciona sempre.

**Em qual bloco o enriquecimento roda** (`enrichmentBlock.mode`, string extensível; padrão `EVENT_BLOCK`):

| Modo | O que faz | Observações |
|---|---|---|
| `EVENT_BLOCK` | `eth_call` no bloco do evento (o comportamento do disparo normal) | Exige um nó que **guarde estado histórico** (não podado). Um `-32603` genérico **não prova** isso: pode ser revert, ABI/argumento incorreto ou falha do nó — use `diagnose` ou compare com `LATEST` antes de concluir. |
| `LATEST` | estado **atual** da cadeia | Re-resolvido a **cada tentativa** da entrega (um retry tardio vê o estado do momento do retry); o bloco usado fica registrado por tentativa. |
| `BLOCK_NUMBER` | bloco fixo (`blockNumber`) | Congelado na entrega. |
| `BLOCK_AT_DATE` | último bloco **até** `blockDate` (arredondamento para baixo) | Resolvido **uma vez** no envio do reenvio e congelado como número. Limites: resolução **aproximada** (só usa eventos ingeridos com timestamp e, se o nó responder, bisseção por RPC — o resultado diz `approximate`/`source`); data anterior ao gênesis = 422; e, como nos demais, só serve se o nó tiver estado histórico. |

**Estado do modelo no reenvio = ATUAL.** O `{{model.*}}` usa o registro do Modelo de Entidade **de agora**
(o registro é acumulado e não guarda versões), que pode ter avançado depois do evento. A resposta traz
`modelState.stateAt` (última atualização lida) e `newerThanEvent`, com o aviso
`MODEL_STATE_NEWER_THAN_EVENT`; o `modelStateAt` fica gravado na entrega. Não há como reconstituir o
estado "da época" nesta versão.

**Efeito zero nos dados.** O preview não grava nada. O reenvio escreve **só** em `webhook_deliveries` (e
as tentativas, ao serem processadas): não altera `decoded`/parâmetros decodificados, não reprojeta saldo
nem modelo, não dispara redecode.

**Histórico de entregas** (`GET /v1/webhooks/{id}/deliveries`): cada entrega mostra a origem (`origin`:
`MATCH` = disparo normal, `RESEND` = reenvio; `resend` derivado), o modo/número do bloco, `modelStateAt` e
a URL de teste **redigida** (só `esquema://host[:porta]`). Cada **tentativa** mostra o `sentPayload` (o
payload final de fato enviado, depois do enriquecimento — gravado em **toda** tentativa, inclusive no
disparo normal), o bloco usado (`enrichmentBlockUsed`) e o relatório por chamada
(`enrichmentResults`, com a mensagem do nó preservada). Entregas antigas não têm esses campos (aparecem
vazios).

**URL de teste (`overrideUrl`) — desligada por padrão.** Sem autenticação na PoC, um endpoint que faz
POST para uma URL qualquer seria um proxy aberto para a rede interna; por isso `overrideUrl` informado com a
capacidade desligada responde 400 `URL_OVERRIDE_DISABLED`. Para habilitar (ambiente de laboratório):

```
EVEX_WEBHOOK_RESEND_ALLOW_URL_OVERRIDE=true
# opcional — hosts exatos e/ou CIDRs liberados mesmo em faixa privada (ex.: um sink local de teste):
EVEX_WEBHOOK_RESEND_URL_ALLOWLIST=<host-ou-cidr>,<host-ou-cidr>
# opcional — portas de destino permitidas (padrão 80,443):
EVEX_WEBHOOK_RESEND_ALLOWED_PORTS=80,443
```

`GET /v1/capabilities` diz se está ligada. Quando ligada, a URL passa por uma política (400
`URL_NOT_ALLOWED`, mensagem genérica que não ecoa URL nem IP): só `http`/`https`; sem usuário/senha na URL; porta na
lista permitida; o host é **resolvido no servidor** e a URL é bloqueada se **qualquer** endereço resolvido
for loopback, link-local (inclui o endpoint de metadata de nuvem), privado, CGNAT, ULA, não especificado,
multicast ou IPv4 embutido em IPv6 dessas faixas (literais IP idem). A política é **revalidada imediatamente
antes de cada envio** (se falhar, a tentativa fica `FAILED` com código genérico e nada é enviado); redirects
(3xx) **não são seguidos**; o corpo da resposta **não é gravado nem exposto** nas entregas com URL de teste e os
erros de rede viram códigos genéricos (`OVERRIDE_URL_BLOCKED`, `OVERRIDE_CONNECT_FAILED`, `OVERRIDE_TIMEOUT`,
`OVERRIDE_UNREACHABLE`...). **Risco residual conhecido (aceito na PoC):** DNS rebinding — um DNS controlado
por quem opera o destino pode responder um IP público na checagem e outro na conexão; a revalidação reduz a
janela mas não a fecha. Mantenha desligado fora de laboratório. *(Reservado, ainda sem efeito:
`EVEX_WEBHOOK_URL_POLICY_MODE=OFF|WARN|ENFORCE` — aplicará a mesma política à URL **cadastrada** em uma
entrega futura.)*

**Limites de abuso** (valem para preview e reenvio; estado em memória por instância, zera no restart):

| Limite | Padrão | Ao exceder |
|---|---|---|
| eventos por reenvio (`eventIds`/`range`) | 50 | 400 `TOO_MANY_EVENTS` (nada é enfileirado); em `range`, enfileira até o teto e avisa `RANGE_TRUNCATED` |
| eventos por preview | 5 | 400 `TOO_MANY_EVENTS` |
| reenvios por minuto por webhook | 10 | 429 `RATE_LIMITED` + `Retry-After` (segundos) |
| requisições (preview + reenvio) por minuto na instância | 30 | 429 `RATE_LIMITED` + `Retry-After` |
| reenvios `PENDING` na fila | 500 | 429 `RESEND_QUEUE_FULL` + `Retry-After` (tudo-ou-nada) |

Os valores efetivos saem em `GET /v1/capabilities`. Entregas de reenvio têm prioridade menor que o disparo
normal na fila, então um reenvio grande não atrasa as notificações em tempo real.

**Pela UI:** abra o detalhe de um webhook (`/webhooks/<id>`, modo leitura) e use **Pré-visualizar** ou
**Reenviar** — os dois abrem o mesmo painel, em 3 passos: (1) **Eventos que casam** — filtro **por data**
(de/até, `datetime-local`, enviado em ISO-8601 com o offset do seu fuso, mostrado abaixo do campo) **ou por
bloco** (de/até; há um auxiliar "Resolver data → bloco" que usa `GET /v1/blocks/by-date` e mostra
`source`/`approximate`/avisos), lista paginada por cursor ("Carregar mais"), aviso quando a varredura é
truncada, avisos traduzidos e seleção múltipla com contador (limites vindos de `GET /v1/capabilities`; se a
consulta falhar, valem os padrões conservadores 5/50); (2) **Bloco do enriquecimento** — select com
`EVENT_BLOCK`/`LATEST`/`BLOCK_NUMBER`/`BLOCK_AT_DATE` e os campos de cada modo; (3) **Pré-visualizar**
(com a opção "Diagnosticar falhas"; mostra payload final, estado do modelo, cada chamada de enriquecimento
com o erro bruto da rede e o diagnóstico rotulado como indício — nada é enviado nem gravado) ou
**Reenviar…** (diálogo de confirmação; a opção de URL de teste só aparece com
`webhookUrlOverrideEnabled=true`; um 429 mostra "tente novamente em N s" e desabilita o botão). O resultado
lista os aceitos (com atalho para o histórico), os ignorados com o motivo e os avisos. Em **Entregas**, cada
linha mostra a origem (Reenvio/Normal), o modo de bloco, o `modelStateAt` e a URL de teste redigida, e
"ver tentativas → detalhes" mostra o payload efetivamente enviado e o resultado do enriquecimento por chamada.
No modo mock (`npm run dev:mock`) os cenários são fictícios; acrescente `?urlOverride=1` à URL do front para
ligar a capacidade de URL de teste (o 3º reenvio da sessão simula o 429).

**Limite genérico do produto:** o Evex reenvia o que **ele** indexou e já decodificou, com o enriquecimento
e o modelo disponíveis **agora**; ele não reconstitui o estado do passado nem garante que o destino
processe o mesmo evento duas vezes de forma idempotente — quem recebe deve tratar reentrega.

---

## Referência rápida

### Telas (UI mínima — doc §6)

| Rota | Tela |
|---|---|
| `/contracts` | Lista + cadastro de contratos monitorados |
| `/contracts/<endereço>` | Detalhe: status, dados, seção ABI (versões + cadastro), sugestões automáticas |
| `/events` | Eventos indexados com filtros (contrato, evento, blocos, datas) + "ver parâmetros" |
| `/balances` | Consulta de saldo (atual / por data) + configs de monitoramento + backfill |
| `/webhooks` · `/webhooks/<id>` | Lista/cadastro + detalhe com entregas e tentativas |
| `/wallets` · `/wallets/<endereço>` | Lista/cadastro + detalhe de carteiras conhecidas (E22) |
| `/enrichment-profiles` · `/enrichment-profiles/<id>` | Perfis de Enriquecimento — `eth_call` reaproveitável por webhooks (E19) |
| `/entity-models` · `/entity-models/<id>` | Modelos de Entidade — acumulação de campos entre eventos, reaproveitável por webhooks (E19) |
| `/demo` | Aba "Demonstração" (só com `VITE_LOCALENV_CONTROL_URL` + control-panel no ar) |

### Enums

| Enum | Valores |
|---|---|
| `network` | `MAINNET`, `TESTNET` |
| `contractStatus` | `ACTIVE`, `PAUSED`, `ARCHIVED` |
| `abiSource` | `UPLOAD_FILE`, `UPLOAD_TEXT`, `BLOCKSCOUT`, `AUTO_DETECTED` |
| `abiStorageType` | `DATABASE`, `LOCAL`, `HYBRID` (default `DATABASE` — `indexer.abi-storage.default`) |
| `detectionLevel` | `FULL` (aplica), `PARTIAL` (só sugere) |
| `balanceBehavior` | `CREDIT`, `DEBIT`, `TRANSFER`, `DELTA` |
| `filterOperator` | `GT`, `GTE`, `LT`, `LTE`, `EQ`, `NEQ` |
| `webhookDeliveryStatus` | `PENDING`, `DELIVERED`, `FAILED` |

### Endpoints principais

```
# Contratos e ABI
GET/POST   /v1/contracts
GET/PATCH  /v1/contracts/{address}
PATCH      /v1/contracts/{address}/status
GET/POST   /v1/contracts/{address}/abi
GET        /v1/contracts/{address}/abi/suggestions
GET        /v1/contracts/{address}/abi/history-plan   # dry-run do histórico de ABIs de proxy (DEC-105)
POST       /v1/contracts/{address}/abi/history-import # aplica o plano (1 redecode)
GET        /v1/contracts/{address}/events           # catálogo p/ os forms guiados
GET        /v1/discovery/blockscout/contracts       # discovery

# Eventos e redecode
GET        /v1/events?contractAddress=&eventName=&fromBlock=&toBlock=&fromDate=&toDate=
POST       /v1/redecode                             # redecode global (filtro opcional)
GET        /v1/redecode/status
GET        /v1/redecode/status/stream               # SSE (fora do cliente gerado)

# Saldos
GET/POST   /v1/balance-monitors
GET/PATCH  /v1/balance-monitors/{id}
PATCH      /v1/balance-monitors/{id}/status
POST       /v1/balance-monitors/{id}/backfill
GET        /v1/balance-monitors/backfill/status
GET        /v1/balance-monitors/backfill/status/stream   # SSE
GET        /v1/balances?walletAddress=&contractAddress=&tokenId=
GET        /v1/balances/history?walletAddress=&contractAddress=&date=YYYY-MM-DD

# Webhooks
GET/POST   /v1/webhooks
GET/PATCH  /v1/webhooks/{id}
PATCH      /v1/webhooks/{id}/status
GET        /v1/webhooks/{id}/deliveries
GET        /v1/webhooks/{id}/matching-events         # eventos que casam (data/bloco), cursor
POST       /v1/webhooks/{id}/preview                 # dry-run: não grava, não envia
POST       /v1/webhooks/{id}/resend                  # 202; cria entregas PENDING (origin=RESEND)
GET        /v1/capabilities                          # flags/limites do preview e reenvio

# Eventos enriquecidos (E19)
GET        /v1/contracts/{address}/methods          # catálogo de métodos view/pure p/ o <MethodPicker>
GET/POST   /v1/enrichment-profiles
GET/PUT    /v1/enrichment-profiles/{id}
PATCH      /v1/enrichment-profiles/{id}/status
GET/POST   /v1/entity-models
GET/PUT    /v1/entity-models/{id}
PATCH      /v1/entity-models/{id}/status
```

Swagger UI completo em `http://localhost:8080/swagger-ui.html`.

---

## Troubleshooting

| Sintoma | Causa / correção |
|---|---|
| **Cadastrei a ABI mas os eventos históricos continuam "sem ABI"** | Redecode não rodou, ou a ABI tem `validFromBlock` acima do bloco dos eventos. Rode `POST /v1/redecode`; confira a faixa de validade da versão (cenário 11). |
| **Eventos aparecem decodificados mesmo com `contract_abis` vazio, logo após truncar o banco** | O `AbiCache` em memória do backend **sobrevive** ao truncate (populado no boot, sem evict — [DEC-070](decisions.md)). Faça **Stop + Start** completo do backend (não HotSwap / "Update classes" da IDE). |
| **Poller parado / cursor à frente do head da cadeia** | Se você reiniciou o `yarn chain` (blocos voltam ao 0) sem truncar o Evex, o cursor de indexação fica à frente. Trunque as tabelas do Evex (`ingestion_cursor` incluso) e reinicie o backend. |
| **`GET /v1/contracts?size=500` → 400 `VALIDATION_ERROR`** | O parâmetro `size` tem `maximum: 200` na spec. O front respeita isso ([DEC-072](decisions.md) complemento); se você chamar a API na mão, use `size ≤ 200`. |
| **Blockscout `total_blocks: 0`** | O nó Hardhat está em `127.0.0.1` — reinicie com `--hostname 0.0.0.0`, depois `blockscout-up.sh` + `verify-contracts.ts`. |
| **Aba "Demonstração" não aparece** | `VITE_LOCALENV_CONTROL_URL` não setado no frontend, ou o `control-panel` fora do ar (`curl localhost:8899/health`). O `up.sh` sobe o control-panel; a env var precisa estar no `.env.local` **antes** do `npm run dev`. |
| **Webhook não dispara** | (a) o evento não decodificou (ABI faltando); (b) um filtro não passou; (c) o webhook está `PAUSED`; (d) o evento chegou **antes** do webhook existir (não há backfill de webhook — só redecode reenfileira). |
| **`EPIPE` em `verify-contracts.ts`** | Blockscout ainda subindo (`total_blocks: 0`). Espere ~90s e rode de novo. |
| **Config de saldo não projeta o histórico** | Esperado — a projeção ao vivo só pega eventos posteriores à config. Use o backfill (cenário 6). |

---

*Última revisão: 2026-09-02 (E16 + E17). Ver `roadmap.md` para o estado das etapas.*
