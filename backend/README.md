# Evex Backend

Kotlin 2.4.10 + Spring Boot 4.1.1 (Java 21), arquitetura hexagonal, API OpenAPI-first. Ver
[`CLAUDE.md`](CLAUDE.md) para decisões técnicas/estrutura e [`../docs/roadmap.md`](../docs/roadmap.md)
para o estado atual das etapas. Este arquivo é só o guia operacional ("como rodar"), não contexto de
decisão.

## Pré-requisitos

- Java 21 (toolchain do Gradle).
- **Gradle wrapper 9.x** — já vem no repo (`./gradlew`), não precisa instalar Gradle à parte; só não
  troque por uma instalação global 8.x (Spring Boot 4.1.1 exige 8.14+/9.x).
- Docker (Postgres 16 via `docker-compose.yml` deste diretório) — ou um Postgres 16 próprio, ajustando
  as variáveis `SPRING_DATASOURCE_*` abaixo.
- Node.js 22+ **só** se for regenerar a spec/cliente manualmente — a build do backend já baixa e roda o
  `openapi-generator-cli` sozinha via `./gradlew`, sem precisar de Node instalado à parte para isso.

## Subindo localmente

```bash
docker compose up -d        # Postgres 16 em localhost:5432 (db/user/senha: evex/evex/evex)
./gradlew bootRun           # gera a API a partir do OpenAPI + sobe em localhost:8080
```

`bootRun` já encadeia a geração do cliente OpenAPI (`generateEvexApi`, ver abaixo) e a criação/
atualização do schema (`ddl-auto: update` + Flyway parcial, `DEC-006`) — não precisa rodar nada à parte
antes.

## OpenAPI first — nunca escrever controller à mão

A spec-fonte é multi-arquivo em `src/main/resources/swagger/base/base.yaml`. Duas tasks Gradle
(grupo `openapi`):

- `./gradlew bundleEvexApi` — bundla a spec inteira num único `.yaml` (Redocly CLI) em
  `build/generated-openapi/evex-api.bundled.yaml`.
- `./gradlew generateEvexApi` — gera controller + interface `*ApiService` + models a partir do bundle,
  em `build/generated/evex-api/`. Roda automaticamente antes de `compileKotlin`/`processResources` —
  não precisa chamar manualmente no dia a dia.

O único código handwritten do lado de entrada é a `*ApiServiceImpl` (fina, delega pra
`application`/`domain`/`adapters/outbound`) — ver `docs/hexagonal-architecture.md` e
`docs/openapi-first-workflow.md`.

## Testes

```bash
./gradlew test          # JUnit 5 + MockK + Testcontainers (Postgres real, precisa de Docker)
./gradlew clean build    # build completa + testes
```

## Variáveis de ambiente (`application.yml`, `indexer.*`)

Todas têm default (funciona sem configurar nada pra rodar contra o `local-env`). As mais usadas:

| Variável | Default | Papel |
|---|---|---|
| `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` | `jdbc:postgresql://localhost:5432/evex` / `evex` / `evex` | Conexão Postgres. |
| `EVEX_NETWORK` | `TESTNET` | `MAINNET`\|`TESTNET` — tag aplicada a todo evento ingerido por esta instância (uma instância = uma rede). |
| `EVEX_RPC_URL` | `http://localhost:8545` | Endpoint HTTP do nó EVM (`eth_getLogs`/`eth_call`/etc.). |
| `EVEX_CONFIRMATIONS` | `0` | Blocos de cauda fora do `safeHead` por segurança de reorg. |
| `EVEX_POLL_INTERVAL_MS` | `5000` | Intervalo do gatilho `POLLING`. |
| `EVEX_POLL_BATCH_SIZE`/`EVEX_POLL_MIN_BATCH_SIZE` | `1000`/`1` | Janela da ingestão + piso do auto-shrink em erro de range do nó. |
| `EVEX_BLOCKCHAIN_TRANSPORT` | `POLLING` | `POLLING`\|`WEBSOCKET`\|`NONE` — qual gatilho dispara a ingestão (E26). `WEBSOCKET` ainda não foi validado contra um nó real, só via mock (ver `docs/decisions.md` DEC-091). |
| `EVEX_BLOCKCHAIN_WS_URL` | `ws://localhost:8546` | Endpoint WebSocket do nó — só usado quando `EVEX_BLOCKCHAIN_TRANSPORT=WEBSOCKET`. |
| `EVEX_BLOCKSCOUT_URL` | vazio | URL do Blockscout — vazio desabilita discovery/import de ABI/fallback de detecção de interface. |
| `EVEX_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Origens liberadas pro frontend (`indexer.cors`). |
| `EVEX_ABI_LOCAL_STORAGE_PATH` | `./data/abis` | Caminho do storage `local`/`hybrid` de ABI. |

Lista completa, com o racional de cada uma comentado inline: `src/main/resources/application.yml`.

## Documentos técnicos (`docs/`)

Extraídos do código base Anbima antes da remoção, ou definidos na análise inicial do projeto — ver
[`CLAUDE.md`](CLAUDE.md) para o índice comentado de cada um (`hexagonal-architecture.md`,
`openapi-first-workflow.md`, `abi-strategy-design.md`, `ingestion-pipeline-reference.md`,
`performance-and-ops-notes.md`).

## Rodando ponta a ponta

Sozinho, o backend não tem nó EVM nem contratos pra indexar. Para validar de verdade, ver
[`../local-env/README.md`](../local-env/README.md) (ambiente Hardhat + Blockscout + contratos de
teste) e [`../docs/guia-de-uso.md`](../docs/guia-de-uso.md) (guia por cenário, com prints).
