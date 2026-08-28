# Blockscout local, contra o nó Hardhat do scaffold-eth-2

Segue a mesma lógica do scaffold-eth-2 (ver [`../README.md`](../README.md)): **não vendorizado**,
clonado à parte. Não existe `docker-compose.yml` próprio deste módulo — o Blockscout já tem um
arquivo pronto oficial pra exatamente este cenário, não faz sentido reescrever.

## 1. Clonar

```bash
cd local-env
git clone https://github.com/blockscout/blockscout.git local-env-blockscout
```

## 2. Bug conhecido na imagem publicada — precisa compilar do source (ver DEC-055/DEC-056)

A imagem publicada (`ghcr.io/blockscout/blockscout:latest`/`:master`, ambas travadas em `explorer
9.0.2`, ~1 ano desatualizadas) crash-loopa com `Postgrex.Error) ssl not available` mesmo com
`ECTO_SSL_MODE=disable` correto — bug real do Postgrex/`db_connection` empacotado nessa versão,
corrigido no source atual (`mix.exs` local: `11.2.8`) mas nunca republicado como imagem. **Não existe
tag estável pra trocar** — a solução é compilar a imagem localmente:

```bash
cd local-env-blockscout
docker build -f docker/Dockerfile \
  --build-arg RELEASE_VERSION=11.2.8 \
  --build-arg BLOCKSCOUT_VERSION=v11.2.8 \
  -t blockscout-local:11.2.8 .
docker tag blockscout-local:11.2.8 ghcr.io/blockscout/blockscout:11.2.8-local
```

Build é só Elixir/Alpine (sem Rust/Node) — na prática levou só alguns minutos. `docker/Dockerfile` do
próprio repo, nada nosso.

## 3. Subir

O arquivo `docker-compose/hardhat-network.yml` do próprio repo já vem configurado para um nó Hardhat
local (`CHAIN_ID: '31337'`, RPC via `host.docker.internal:8545`, `INDEXER_INTERNAL_TRANSACTIONS_TRACER_TYPE: 'opcode'`
— o tracer por opcode, que o Hardhat suporta, em vez do tracer customizado que ele **não** suporta via
`debug_traceBlockByNumber`; ver DEC-053). Esse arquivo faz `extends` de `./services/*.yml`, então
precisa ser rodado de dentro da pasta `docker-compose/` do clone (não copiado isolado). Usa a imagem
compilada no passo 2 via [`override-local-build.yml`](override-local-build.yml) (copiar pra dentro do
clone) e publica a API em `4001` no host:

```bash
cp ../blockscout/override-local-build.yml local-env-blockscout/docker-compose/
cd local-env-blockscout/docker-compose
docker compose -f hardhat-network.yml -f override-local-build.yml up -d
```

Requisito: `yarn chain` (nó Hardhat do scaffold-eth-2) já rodando em `127.0.0.1:8545` antes de subir
o Blockscout.

**Segundo bug corrigido (packaging, não config)**: o container roda como usuário não-root
(`blockscout`, uid 10001), mas os volumes `./services/dets/`/`./services/logs/` são criados pelo
Docker como `root` na primeira subida — o indexer falha com `eacces` ao tentar escrever. Corrigir uma
vez, antes de subir o `backend`:

```bash
docker run --rm \
  -v $(pwd)/services/dets:/fix-dets -v $(pwd)/services/logs:/fix-logs \
  alpine chown -R 10001:10001 /fix-dets /fix-logs
```

## 4. Acessar

- API real (usada pelo Evex, `EVEX_BLOCKSCOUT_URL=http://localhost:4001`): `http://localhost:4001/api/v2/...`.
- UI completa (frontend+proxy, não testada nesta sessão — só `db`/`redis-db`/`backend` foram
  necessários pra validar a API de discovery): subir também os serviços `frontend`/`proxy`/`stats` do
  mesmo `hardhat-network.yml` e acessar `http://localhost`.

## Validado nesta sessão (E12, ver DEC-056)

Com o backend real rodando, o fallback de detecção de interface da E6
(`GET /v1/contracts/{address}/abi/suggestions`, `InterfaceDetectionService`) foi testado de ponta a
ponta contra o Blockscout **real** (não stub): endereço sem eventos observados pelo Evex (polling
desligado de propósito) → Evex chama `GET /api/v2/addresses/{address}/logs` do Blockscout real → topic0
do `Transfer` real (de um `safeMint` de teste) veio certo → `ERC721`/`ERC20` retornados como `PARTIAL`,
exatamente o esperado (só 1 de 2-3 eventos de cada template observado).

**Não validado**: verificação de contrato (`source=BLOCKSCOUT` pra ABI, e listagem de contratos
verificados) — a ferramenta de verificação do scaffold-eth-2 (`rocketh-verify blockscout`) fala a
API antiga estilo Etherscan (`module`/`action`), incompatível com o formato que este Blockscout real
espera (`POST /api/smart-contracts/{address}/verification/via/standard-input`, retorna 400). Não é bug
do Blockscout nem do Evex — desencontro entre duas ferramentas de terceiros. Pendência em aberto, não
bloqueante (o fallback via logs, que é o caminho documentado como não-crítico no próprio código —
`InterfaceDetectionService` —, já está validado).

## Limitação conhecida (transações internas)

Transações internas (chamadas contrato→contrato) não são indexadas por padrão com o tracer `opcode`
da mesma forma completa que um Geth/Erigon real ofereceria — aceitável para este ambiente, não afeta
a detecção de ERC20/721/1155 da E6 (baseada em logs/eventos, não em trace).

## Possível conflito de porta

Não há — o Postgres do Blockscout publica `7432` no host (`services/db.yml`), diferente do `5432` do
Postgres do Evex (`backend/docker-compose.yml`). A API do backend do Blockscout é publicada em `4001`
via `override-local-build.yml` (o `4000` interno colidiria com o stub em `../blockscout-stub/`, se
usado).

## Alternativa mais leve — stub HTTP (`../blockscout-stub/`)

Se não quiser compilar o Blockscout (ou pra testes rápidos do lado do cliente Evex, sem precisar de
um Blockscout de verdade no ar), existe um stub HTTP em `../blockscout-stub/server.ts` simulando os 3
endpoints consumidos pelo `BlockscoutClient`. Não substitui a validação real (não teve que passar por
nenhuma indexação de verdade), mas é bem mais rápido de subir.
