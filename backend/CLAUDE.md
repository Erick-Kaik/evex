# Evex Backend

> Ler primeiro: [`../CLAUDE.md`](../CLAUDE.md) (decisões travadas) e [`../base/indexer-product-draft.md`](../base/indexer-product-draft.md) (produto).
> Código base de referência técnica: [`../base/indexer`](../base/indexer) (projeto Anbima — ver o que reaproveitar/adaptar/reescrever abaixo). **`base/indexer` pode ser removido** — tudo que valia a pena preservar dele já foi extraído para `docs/` (abaixo) como algoritmo/padrão, não como cópia de código.

## Documentos técnicos (`docs/`)

Extraídos do código base antes da remoção, ou definidos nesta fase de análise:

- [`docs/hexagonal-architecture.md`](docs/hexagonal-architecture.md) — fluxo completo de uma requisição e a regra **"nunca escrever controller manual"** (o OpenAPI já gera controller + interface de service; só a `*ApiServiceImpl` é handwritten, e deve ser fina — delega tudo pra `application`/`domain`/`adapters/outbound`).
- [`docs/openapi-first-workflow.md`](docs/openapi-first-workflow.md) — task Gradle do generator, o que é gerado vs. handwritten, convenções de spec (tags, paginação, erro).
- [`docs/abi-strategy-design.md`](docs/abi-strategy-design.md) — design com **Strategy Pattern** para os 4 eixos de variação do fluxo de ABI: storage (`AbiStoragePort`), cálculo de saldo (`BalanceBehaviorStrategy`), detecção de interface (`InterfaceTemplate`), origem do cadastro (`AbiIngestionStrategy`) — mais o design de versionamento de ABI por faixa de blocos (proxy upgradável).
- [`docs/ingestion-pipeline-reference.md`](docs/ingestion-pipeline-reference.md) — algoritmos do poller (janela de blocos, auto-shrink), do padrão de reindexação assíncrona em lotes, e cenários de teste que a Anbima já validou em produção (a replicar no Evex).
- [`docs/performance-and-ops-notes.md`](docs/performance-and-ops-notes.md) — ajustes de performance validados em produção (batching JDBC, `allocationSize` de sequence, `@BatchSize` em `@ElementCollection`, baseline do Flyway).

## Stack (validada por spike de compilação real)

| Item | Versão | Observação |
|---|---|---|
| Linguagem | Kotlin | 2.4.10 (última estável) |
| JVM | Java 21 | toolchain Gradle |
| Framework | Spring Boot | 4.1.1 (última estável; 4.2.0 só tem milestone) |
| Build | Gradle Kotlin DSL | **wrapper precisa ser Gradle 9.x** (Spring Boot 4.1.1 exige 8.14+/9.x; ambiente local tinha 8.8) |
| Banco | PostgreSQL 16 (JSONB para ABIs) | schema via `ddl-auto: update` + Flyway parcial quando necessário |
| Blockchain | Web3j 6.0.0 — direto, sem BBChain Keeper | APIs usadas no código base (`Event`, `TypeReference`, `EventEncoder`, `Contract.staticExtractEventParameters`, `EthFilter`, `Web3j.build(HttpService)`) validadas compilando nesta versão |
| HTTP client | Spring `RestClient` | para Blockscout e dispatch de webhooks |
| Contrato de API | OpenAPI 3.1 — `openapi-generator` (gerador `kotlin-spring`) | plugin Gradle 7.24.0 |
| Docs da API | springdoc-openapi-starter-webmvc-ui | **3.1.0** (2.x é pra Spring Boot 3/Framework 6 — quebra no boot com Spring Boot 4; descoberto em E1, ver DEC-015) |
| Assíncrono | Spring `@Async` + Coroutines | reindexação em lote |
| Testes | JUnit 5 + MockK + Testcontainers (Postgres real) | **não** Mockito-Kotlin/H2 como no código base |

## Arquitetura hexagonal (domain sem dependência de framework)

```
br.com.bbchain.evex.indexer/
├── domain/                    entidades e regras puras (sem JPA/Spring aqui)
├── application/                casos de uso / orquestração (services)
├── adapters/
│   ├── inbound/
│   │   └── api/                 implementações *ApiServiceImpl (geradas via OpenAPI)
│   └── outbound/
│       ├── persistence/         entidades JPA + repositories (adapta domain <-> banco)
│       ├── blockchain/          cliente Web3j (poller, getLogs, timestamps)
│       ├── abi/                 decodificação dinâmica + AbiStoragePort (database/local/[blob])
│       ├── discovery/           cliente Blockscout
│       └── webhook/             dispatcher (fila, retry, log de entregas)
└── config/                     properties, beans, scheduling
```

Diferente do código base (que tem separação por pacote técnico — `abi/`, `ingest/`, `query/`, `repository/`, `web3/` — mas sem portas explícitas), aqui as portas de saída (`AbiStoragePort`, `BlockchainPort`, `WebhookPort`) precisam ser interfaces no `domain`/`application`, implementadas nos `adapters/outbound/*`.

## O que reaproveitar diretamente do código base

- **Decodificação dinâmica de ABI** (`abi/AbiEventCatalog.kt`, `EventDecoder.kt`, `EventDescriptor.kt`) — parse do `.abi`, `TypeReference.makeTypeReference`, `Event(name, typeRefs)`, `EventEncoder.encode` para `topic0`, decode via `Contract.staticExtractEventParameters`, formatação de `BigInteger`/`ByteArray`/`List`. É a implementação de referência da seção 4.9 do doc de produto. Muda só a origem (classpath estático → banco por `contract_address` + faixa de blocos, por causa do proxy upgradável).
- **`ingest/PollWindow.kt`** — aritmética pura da janela de blocos, sem dependências, reaproveitável tal qual.
- **Padrão de poller com auto-shrink** (`ingest/BlockchainPoller.kt::processRange`) — divide a janela ao meio em erro de RPC de range. Reaproveitar trocando `KeeperWeb3Service` por um client Web3j direto (sem Keycloak/interceptor de token).
- **Idempotência por `(tx_hash, log_index)`** com unique constraint + `existsByTxHashAndLogIndex`.
- **Padrão de reindexação assíncrona em lotes** (`ingest/RedecodeService.kt` + `RedecodeBatchProcessor.kt`, replicado em `BalanceBackfillService`/`TimestampBackfillService`): orquestrador com `AtomicBoolean` (uma execução por vez) + `@Async` + loop até não haver progresso + processor em bean separado com `@Transactional` próprio por lote (evita self-invocation). É a base da reindexação assíncrona citada no doc §4.3 — mas hoje o filtro é só `decoded=false` global; precisa parametrizar por `contract_address`/`topic0`/janela de blocos.
- **Reconstrução do `Log` a partir de `rawTopics`/`rawData` persistidos** para redecodificar sem tocar a rede (`RedecodeBatchProcessor::toLog`).
- **Padrão OpenAPI-first** (`build.gradle.kts::generateIndexerApi`) — `configOptions` (`useSpringBoot3→4`, `useTags`, `serviceInterface=true`/`serviceImplementation=false`, `documentationProvider=springdoc`, `modelMutable=true`) + task que remove `Application.kt`/`HomeController.kt` indesejados do gerado.
- **`query/EventQueryService.kt`** — `JpaSpecificationExecutor` dinâmica para filtros opcionais, boa base para a listagem de eventos da UI mínima (doc §6).

## O que precisa ser adaptado

- **Transporte com a rede**: remover `KeeperWeb3Service`/`ServiceTokenProvider` (Keycloak password+refresh); usar Web3j `HttpService` direto, sem interceptor de auth.
- **`AbiEventCatalog`**: virar tabela `contract_abis` (JSONB + `storage_path` + `valid_from_block`/`valid_to_block` por causa do proxy upgradável) atrás de uma porta `AbiStoragePort` (Database/LocalFile/[AzureBlob pós-aprovação]); cache em memória por `contract_address` (resolvendo qual ABI valia em cada bloco), populado no startup e invalidado on-change — não é mais um catálogo único global por `topic0`.
- **`RedecodeService`/`RedecodeBatchProcessor`**: parametrizar por `contract_address`, `topic0` e janela de blocos (hoje é só `decoded=false` global).
- **`BalanceProjectionService`**: hoje hard-coded para `Transfer`/`TransferSingle`/`TransferBatch` com campos fixos `from`/`to`/`value`/`id`. Reescrever para ler `behavior` (`CREDIT`/`DEBIT`/`TRANSFER`/`DELTA`) e `wallet_param`/`value_param` de uma configuração por usuário (doc §4.4) em vez de nomes fixos.
- **`ContractRegistrationService`**: hoje só re-rotula (`contractType`/`businessRef`) eventos raw já gravados. Precisa acionar o redecode de fato (decodificar, não só rotular) ao cadastrar uma ABI para um contrato com histórico.
- **`WebSecurityConfig`**: remover (sem auth na PoC); manter só CORS para o front consumir.

## O que precisa ser escrito do zero

- **`AbiStoragePort`** + `DatabaseAbiStorage` + `LocalFileAbiStorage` (+ `AzureBlobAbiStorage` pós-aprovação) — doc §4.8.
- **Cadastro de ABI** via upload de arquivo, colagem de texto JSON, discovery Blockscout — doc §4.1.
- **Cliente Blockscout** — listar contratos verificados, buscar ABI, buscar logs históricos (discovery + reindexação inicial) — doc §7.
- **Detecção automática de interfaces ERC20/721/1155** — templates built-in, comparação de `topic0` dos primeiros logs, níveis 1/2/3 de configuração — doc §4.5.
- **Modelo de contrato monitorado completo**: endereço + nome + descrição + versão + `network` (MAINNET/TESTNET) + status (ativo/pausado/arquivado) — hoje só existe `contractType`/`businessRef`/`active`.
- **Segregação MAINNET/TESTNET** por campo `network` em todas as entidades.
- **Configuração de monitoramento de saldo** como entidade editável via API (`contract_address`/`event_name`/`wallet_param`/`value_param`/`behavior`) — doc §4.4.
- **`asset_balance_snapshots`** + job noturno de consolidação (`@Scheduled` diário) — doc §4.4.
- **Webhooks** — módulo completo: config por usuário/contrato/tipo de evento, filtros opcionais, fila de envio, retry com backoff, log de tentativas, payload customizável — doc §4.6. **Não é sobre a conexão com a rede** (essa continua só polling) — é notificação de saída, disparada depois que um evento é decodificado e bate com a config do usuário.
- **Versionamento de ABI por faixa de blocos** (suporte a proxy upgradável) — não coberto pelo doc de produto original, decisão desta análise.

## Regras não negociáveis (do doc de produto §9)

- **OpenAPI first**: o `.yaml` é a fonte de verdade — nunca escrever controllers ou clientes HTTP manualmente. O generator já entrega controller + interface `*ApiService` + models; o único código handwritten do lado de entrada é a `*ApiServiceImpl` (fina, delega para `application`/`domain`/`adapters/outbound`) — ver `docs/hexagonal-architecture.md`.
- **Sem `web3j-gradle-plugin`**: decodificação dinâmica em runtime via API de baixo nível do Web3j (ver seção de reaproveitamento acima).
- **Sem BBChain Keeper**: Web3j direto.
- **Banco como índice central de ABIs**: PostgreSQL é sempre o índice, independente do storage escolhido.
- **Cache em memória de ABIs**: caminho crítico de decodificação nunca bate em banco ou disco.
- **Kotlin idiomático**: data classes, extension functions, sealed classes para resultados; sem `Any`/`nullable` desnecessário.
- **Reindexação assíncrona em lotes**: nunca síncrona, nunca em bloco único.
