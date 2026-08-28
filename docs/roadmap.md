# Evex — Roadmap de implementação

> **Como usar este documento**: antes de começar qualquer trabalho na sessão (você ou um dos agentes especializados em `.claude/agents/`), procure a primeira etapa com status 🔲/🟡 cujas dependências já estão ✅ — é dali que se continua. Ao terminar (mesmo parcialmente), atualize o **Status** e a **Notas de execução** da etapa. Decisões técnicas não triviais tomadas durante a etapa vão para [`decisions.md`](decisions.md), não aqui — este documento é sobre **onde estamos**, não **por que decidimos algo**.

Legenda: ✅ Concluído · 🟡 Em andamento · 🔲 A fazer · ⛔ Bloqueado

## Visão geral

| # | Etapa | Módulo | Agente | Status |
|---|---|---|---|---|
| E0 | Fundação do backend | backend | `kotlin-backend-dev` | ✅ Concluído |
| E1 | Contrato OpenAPI base + esqueleto hexagonal | backend | `hexagonal-architect` + `kotlin-backend-dev` | ✅ Concluído |
| E2 | Contrato monitorado (CRUD, network, versão, status) | backend | `kotlin-backend-dev` | ✅ Concluído |
| E3 | ABI dinâmica + storage strategies + cadastro (upload/texto) | backend | `blockchain-integration` | ✅ Concluído |
| E4 | Integração blockchain: poller + cursor + ingestão | backend | `blockchain-integration` | ✅ Concluído |
| E5 | Reindexação assíncrona (redecode) | backend | `blockchain-integration` + `kotlin-backend-dev` | ✅ Concluído |
| E6 | Discovery Blockscout + detecção automática ERC20/721/1155 | backend | `blockchain-integration` | ✅ Concluído |
| E7 | Saldos históricos (behaviors + snapshots diários) | backend | `kotlin-backend-dev` | ✅ Concluído |
| E8 | Webhooks (config + dispatcher + retry + log) | backend | `kotlin-backend-dev` | ✅ Concluído |
| E8.1 | Correções de backend + CORS (hardening pontual antes do frontend) | backend | `kotlin-backend-dev` | ✅ Concluído |
| E9 | Frontend: esqueleto + cliente gerado | frontend | `frontend-dev` | ✅ Concluído |
| E10 | Frontend: telas (contratos, eventos, saldos, webhooks) | frontend | `frontend-dev` | ✅ Concluído |
| E11 | Testes/hardening cross-cutting | backend+frontend | `reviewer` + todos | ✅ Concluído |
| E12 | Integração com Hardhat local + validação end-to-end | backend | `blockchain-integration` | ✅ Concluído (validado de ponta a ponta em 2026-08-28, ver DEC-059) |
| E13 | Frontend: migração pra MUI + telas de Contratos/Eventos mais didáticas | frontend | `frontend-dev` + `reviewer` | ✅ Concluído |

A cada etapa concluída, rodar `reviewer` antes de marcar ✅.

---

## E0 — Fundação do backend

**Depende de**: nada (ponto de partida).

**Objetivo**: projeto Kotlin/Spring Boot compilando e subindo, banco conectado, sem nenhuma feature de produto ainda.

**Escopo**:
- `build.gradle.kts` + `settings.gradle.kts` (Java 21, Spring Boot 4.1.1, Kotlin 2.4.10, Web3j 6.0.0, openapi-generator 7.24.0 — versões já validadas por spike).
- Gradle wrapper travado em **9.2.0** (Spring Boot 4.1.1 exige 8.14+/9.x).
- `application.yml` com datasource Postgres, `ddl-auto: update`, Flyway habilitado (sem migration ainda).
- `IndexerApplication.kt` (ou nome equivalente) mínimo, health check (`/actuator/health`) respondendo.
- Docker Compose ou instrução de Postgres local para dev (decidir e documentar).

**Docs de referência**: [`../backend/CLAUDE.md`](../backend/CLAUDE.md), [`../backend/docs/performance-and-ops-notes.md`](../backend/docs/performance-and-ops-notes.md) (config de batching/Flyway já validada).

**Critério de aceite**: `./gradlew build` verde; app sobe local com Postgres real; `GET /actuator/health` → 200.

**Notas de execução** (2026-08-21):
- Criado `backend/build.gradle.kts`, `settings.gradle.kts`, wrapper Gradle **9.2.0** (baixa o próprio Gradle, não depende do sdkman do host), `EvexIndexerApplication.kt`, `application.yml` (datasource Postgres via env vars com default local, `ddl-auto: update` + Flyway habilitado sem migrations ainda, batching JDBC conforme `performance-and-ops-notes.md`), `docker-compose.yml` (Postgres 16 para dev local), `.gitignore`.
- `./gradlew build` verde.
- **Não validado ponta a ponta**: este ambiente de execução (WSL) não tem Docker disponível (integração Docker Desktop desligada nesta distro) — não deu pra subir o Postgres e confirmar `/actuator/health` → 200 de fato. Validei o caminho até onde deu: subi a aplicação sem Postgres rodando e ela falhou exatamente com `Connection to localhost:5432 refused` (Hikari/Postgres driver) — confirma que toda a cadeia de config (datasource, JPA, Flyway, actuator) está corretamente ligada, só falta o banco de verdade.
- **Validado (2026-08-21, após usuário habilitar o Docker Desktop/WSL)**: `docker compose up -d` → Postgres 16 saudável; `./gradlew bootRun` conectou via Hikari, Hibernate reconheceu `PostgreSQLDialect`/versão 16.15, subiu em ~3s; `curl localhost:8089/actuator/health` → `{"status":"UP"}` / HTTP 200. Container do Postgres deixado rodando (`backend-postgres-1`) para a próxima etapa.

---

## E1 — Contrato OpenAPI base + esqueleto hexagonal

**Depende de**: E0.

**Objetivo**: primeira versão do `.yaml` (mínima, só a tag `Contracts`), pipeline `openapi-generator` funcionando, pastas `domain/application/adapters` com as interfaces de porta vazias definidas.

**Escopo**:
- `resources/swagger/` — spec **multi-arquivo granular** (`base/`, `paths/`, `base/components/{schemas,requests,responses,param/query,param/path,enum}`, `bundle/`), estrutura fechada em DEC-012. Conteúdo inicial: só `GET/POST /v1/contracts` + `GET /v1/contracts/{address}` (placeholder mínimo, sem ABI ainda) — mas exercitando todas as subpastas de `components/` pelo menos uma vez, pra provar o padrão granular funcionando (paginação, path param, query param, enum, erro).
- Task Gradle `bundleEvexApi` (Redocly CLI via `npx`) + `generateEvexApi`, nessa ordem (ver `openapi-first-workflow.md`), validando se o gotcha do `Application.kt`/`HomeController.kt` gerado ainda existe na v7.24.0.
- `springdoc-openapi-starter-webmvc-ui` adicionado ao `build.gradle.kts` (Swagger UI).
- Primeira `*ApiServiceImpl` (fina, delega pra um use case em `application/` com registro **em memória** — persistência real de contrato monitorado é escopo de E2, não duplicar aqui) provando o fluxo ponta a ponta.
- Interfaces de porta iniciais em `domain`/`application`: `AbiStoragePort`, `BlockchainPort` (assinaturas, sem implementação ainda).

**Docs de referência**: [`../backend/docs/hexagonal-architecture.md`](../backend/docs/hexagonal-architecture.md), [`../backend/docs/openapi-first-workflow.md`](../backend/docs/openapi-first-workflow.md).

**Critério de aceite**: `./gradlew build` bundla + gera o controller/model a partir da spec multi-arquivo; `*ApiServiceImpl` implementado sem controller manual em lugar nenhum; Swagger UI acessível; endpoints respondem via `curl`.

**Notas de execução** (2026-08-22):
- Spec granular criada em `backend/src/main/resources/swagger/` (`base/base.yaml` + `paths/{contracts,contract-detail}.yaml` + `base/components/{schemas,requests,responses,param/query,param/path,enum}/*.yaml`), estrutura fechada em DEC-012. `base.yaml` ficou **sem** seção `components:` manual — os arquivos de `paths/` referenciam os componentes direto por caminho relativo, e o Redocly deduplica/nomeia pelo arquivo físico; registrar de novo em `components:` gerava entradas duplicadas no bundle (tentativa revertida, ver DEC-012).
- Bundle via `npx @redocly/cli bundle` funcionando, wirado como task Gradle `bundleEvexApi` (`Exec`) → `generateEvexApi` (`dependsOn`) → `compileKotlin`/`processResources` (`dependsOn`). `./gradlew build` sozinho já bundla + gera + compila.
- Renomeado `adapters/in` → `adapters/inbound` e `adapters/out` → `adapters/outbound` em todo o projeto (código, docs, agentes) — `in` é palavra reservada em Kotlin, quebraria o pacote gerado pelo openapi-generator (DEC-014). Esse ajuste valia desde o E0, só foi percebido agora.
- Implementado: `domain/common/{Network,PageResult}.kt`, `domain/contract/{MonitoredContract,MonitoredContractPort,ContractNotFoundException}.kt`, `application/contract/MonitoredContractService.kt`, `adapters/outbound/persistence/InMemoryMonitoredContractAdapter.kt` (**temporário** — vira JPA em E2, mesma interface `MonitoredContractPort`), `adapters/inbound/api/{ContractsApiServiceImpl,ApiExceptionHandler}.kt`.
- `springdoc-openapi-starter-webmvc-ui` precisou ser **3.1.0**, não 2.8.15 como estava em `backend/CLAUDE.md` — 2.x é pra Spring Boot 3/Spring Framework 6; quebrava no boot (`ClassNotFoundException: WebMvcProperties`) com Spring Boot 4/Framework 7. Ver DEC-015.
- Gotcha do `Application.kt`/`HomeController.kt` gerado indesejado (herdado do código base) **não se repetiu** no plugin 7.24.0 com esta config — o `doLast` de limpeza foi mantido mesmo assim, é inofensivo se nunca disparar.
- Validados via `curl` os 9 cenários: criar (201), consultar existente (200), consultar inexistente formato válido (404 `ApiError`), path param malformado (400 `ApiError`), listar com filtro `network` (200), corpo inválido no POST (400 `ApiError`), `/actuator/health` (200), Swagger UI (200), `/v3/api-docs` (200).
- OpenAPI 3.1 gera um aviso do plugin ("3.1 support is still in beta") — funcionou sem erro para este spec, mas vale reavaliar se algum recurso mais avançado de 3.1 (ex.: `type` como array, `examples` plural) apresentar problema em etapas futuras.

**Atualização (2026-08-22, pendência resolvida antes de iniciar E2)**: uma edição pós-E1 em `build.gradle.kts` (adição de `resolveNpx()`, para resolver `npx` sem depender do PATH herdado — nvm só carregado em `~/.bashrc`, invisível a processos não-interativos) fez `bundleEvexApi`/`generateEvexApi` sumirem de `./gradlew tasks --all`. Causa raiz e fix documentados em `docs/decisions.md` DEC-017 (era um `*/` literal dentro do KDoc de `resolveNpx()` fechando o comentário cedo demais). `./gradlew build` validado verde de novo (bundle → generate → compile → jar). Wrapper Gradle confirmado em **9.2.0** (não 9.7.0 — reversão de um "Upgrade Gradle wrapper" indevido do IntelliJ, ver DEC-008).

---

## E2 — Contrato monitorado (CRUD, network, versão, status)

**Depende de**: E1.

**Objetivo**: modelo completo de "contrato monitorado" do doc de produto §4.1 (endereço, nome, descrição, versão, `network` MAINNET/TESTNET, status ativo/pausado/arquivado) com CRUD via API — **sem** ABI ainda (isso é a E3).

**Escopo**:
- Entidade `MonitoredContract` (domain + persistence) com os campos acima.
- Endpoints REST completos (spec + `*ApiServiceImpl` + `application` use case + repository).
- Campo `network` desde já em todas as entidades que forem criadas daqui pra frente (decisão travada, não deixar pra depois).

**Docs de referência**: `../base/indexer-product-draft.md` §4.1, [`decisions.md`](decisions.md) DEC-002/DEC-007.

**Critério de aceite**: cadastro, listagem, atualização de status e consulta por rede funcionando via Swagger UI; testes JUnit5+MockK+Testcontainers cobrindo o CRUD.

**Notas de execução** (2026-08-22):
- Domain: `ContractStatus` (novo enum `ACTIVE`/`PAUSED`/`ARCHIVED`, transição livre — decisão travada com o usuário), `ContractAlreadyExistsException` (novo), `MonitoredContract` estendido com `description`/`version`/`status`. `address`/`network` permanecem imutáveis após criação (chave natural) — também decisão travada.
- Application: `MonitoredContractService.register()` agora checa duplicidade via `findByAddress` antes de salvar (lança `ContractAlreadyExistsException`) e sempre cria com `status = ACTIVE`; `update()` e `updateStatus()` novos, ambos no padrão "load via `getByAddress` → `copy()` do campo mudado → `save()`" (sem novo método na porta, como previsto no plano).
- Persistência: primeira entidade JPA do projeto. `MonitoredContractEntity` implementa `Persistable<UUID>` (ver DEC-018 — `id` gerado em `application`, não pelo banco, quebraria o `isNew()` default). `MonitoredContractJpaRepository` (`JpaRepository` + `findByAddress` + `findByNetwork` paginado) e `JpaMonitoredContractAdapter` (decide insert/update via `existsById`, mapeia domínio↔entidade) substituem `InMemoryMonitoredContractAdapter` (removido). Schema criado via `ddl-auto: update` (DEC-006), sem migration Flyway nova.
- Spec OpenAPI: novo enum `contract-status`, schemas `update-contract-payload`/`update-contract-status-payload`, requests correspondentes, `paths/contract-status.yaml` (`PATCH /v1/contracts/{address}/status`). `contract.yaml`/`create-contract-payload.yaml` ganharam `description`/`version`(/`status` só na resposta). `contract-detail.yaml` ganhou `PUT` (mesmo Path Item Object do `GET`, regra DEC-012). `contracts.yaml` documenta `409` no `POST`. **Desvio do plano**: a descrição do campo `version` continha `(ex.: ...)` sem aspas YAML — o `ex.:` (dois pontos + espaço) é interpretado como início de novo mapeamento pelo parser, quebrando o bundle Redocly com "bad indentation of a mapping entry". Corrigido colocando a descrição inteira entre aspas duplas (mesmo padrão já usado em `api-error.yaml` para o mesmo tipo de texto) — não achado documentado antes, vale lembrar em specs futuras: qualquer descrição com `: ` literal dentro precisa de aspas.
- Adapters inbound: `ContractsApiServiceImpl` ganhou `updateMonitoredContract`/`updateContractStatus`, `toDto()` estendido; `ApiExceptionHandler` ganhou handler de `ContractAlreadyExistsException` → 409.
- **Desvio de dependências não previsto no plano**: Spring Boot 4.1.1 quebrou os testes `@DataJpaTest`/`@AutoConfigureMockMvc`/`@AutoConfigureTestDatabase` — essas anotações **não estão mais** em `spring-boot-test-autoconfigure` (esse jar caiu para ~28KB, só sobrou `jdbc`/`json` genérico). Boot 4 replicou para os test-slices a mesma modularização por tecnologia que já existe no autoconfigure principal (`spring-boot-webmvc`, `spring-boot-jpa`, ...): `AutoConfigureMockMvc`/`WebMvcTest` foram para `spring-boot-webmvc-test` (pacote `org.springframework.boot.webmvc.test.autoconfigure`), `DataJpaTest` para `spring-boot-data-jpa-test` (`org.springframework.boot.data.jpa.test.autoconfigure`), `AutoConfigureTestDatabase` para `spring-boot-jdbc-test` (`org.springframework.boot.jdbc.test.autoconfigure`). Adicionadas as 3 dependências `testImplementation` novas em `build.gradle.kts` (versão resolvida pelo BOM, não precisou pin manual). Não documentado em nenhum dos `CLAUDE.md`/docs até agora — registrado em `decisions.md` (DEC-019) para não redescobrir isso na próxima etapa que escrever `@WebMvcTest`/`@DataJpaTest`.
- Testes: `AbstractIntegrationTest` (Postgres via Testcontainers, singleton iniciado eager na companion object) + `MonitoredContractServiceTest` (7 testes unitários, MockK — **verde**) + `JpaMonitoredContractAdapterTest` (5 testes, `@DataJpaTest`+Testcontainers) + `ContractsApiTest` (8 testes, `@SpringBootTest`+MockMvc+Testcontainers, cobre os 8 cenários do plano incluindo 409/400/404).
- **Pendência do agente resolvida na sessão interativa**: o sandbox onde o agente `kotlin-backend-dev` rodou não tinha Docker disponível (mesma limitação de DEC-011, mas no ambiente do agente). Usuário abriu o Docker Desktop; `docker ps` confirmou o Postgres do `docker-compose.yml` (E0) de pé. Rodando `./gradlew test` de novo, os 5 testes de `JpaMonitoredContractAdapterTest` passaram, mas os 8 de `ContractsApiTest` falharam com `NoSuchBeanDefinitionException` — **bug real, não de ambiente**. Causa: `ContractsApiTest` injetava `@Autowired ObjectMapper` (Jackson 2, `com.fasterxml.jackson.databind`) pra montar corpo de requisição, mas o Spring Boot 4.1.1 auto-configura Jackson 3 (`tools.jackson.databind`) como bean — não existe bean Jackson 2 pra injetar (ver DEC-020, achado grande, vale a pena qualquer teste futuro que precise de `ObjectMapper` saber disso). Corrigido trocando a injeção por um `ObjectMapper()` local no teste (Jackson 2, mesma versão do resto do projeto, independente do que o Spring expõe como bean).
- **Validado de ponta a ponta** (2026-08-22, sessão interativa, Docker disponível): `./gradlew clean build` verde — bundle→generate→compile→test→jar, **20/20 testes passando** (7 unitários MockK + 5 JPA/Testcontainers + 8 API/Testcontainers via `MockMvc`, cobrindo 201/409/404/200/`PUT`/`PATCH`/400). Critério de aceite da etapa cumprido.
- **Revisão (`reviewer`)**: aprovada, sem violação de regra. Único achado (não-bloqueante): corrida entre a checagem de duplicidade em memória (`findByAddress` em `register()`) e a unique constraint do banco — duas requisições concorrentes com o mesmo `address` podiam colidir direto na constraint, vazando o formato de erro default do Spring em vez do `ApiError` do contrato. Corrigido na hora (usuário optou por não deixar pra E11): novo `@ExceptionHandler(DataIntegrityViolationException::class)` em `ApiExceptionHandler` → 409 `CONTRACT_ALREADY_EXISTS`, mesmo código do caminho comum. `./gradlew clean build` reconfirmado verde (20/20) depois do fix.

---

## E3 — ABI dinâmica + storage strategies + cadastro (upload/texto)

**Depende de**: E2.

**Objetivo**: núcleo de decodificação dinâmica de ABI (reaproveitado do código base) + as Strategies de storage (`Database`/`LocalFile`) + cadastro de ABI via upload de arquivo e colagem de texto. Discovery via Blockscout fica para E6.

**Escopo**:
- `contract_abis` com versionamento por faixa de blocos (`valid_from_block`/`valid_to_block`) — suporte a proxy upgradável.
- `AbiStoragePort` + `DatabaseAbiStorage` + `LocalFileAbiStorage` + `AbiStorageResolver`.
- Parsing/decode dinâmico (`Event`/`TypeReference`/`EventEncoder`/`Contract.staticExtractEventParameters`) — algoritmo replicado de `ingestion-pipeline-reference.md`/`abi-strategy-design.md`, escrito do zero.
- Cache em memória por `contract_address` resolvendo a ABI vigente por bloco.
- `POST /contracts/{address}/abi` com upload de arquivo, texto colado, e campo `storage` opcional.

**Docs de referência**: [`../backend/docs/abi-strategy-design.md`](../backend/docs/abi-strategy-design.md) (seções 1, 4, 5).

**Critério de aceite**: cadastrar ABI de um contrato de teste (Hardhat, quando disponível — ou fixture manual) e decodificar um log real dele; troca de `storage` via config sem mudança de código; cache não bate em banco/disco no caminho de decodificação (validar com teste ou log).

**Notas de execução** (2026-08-22, agente `blockchain-integration`):
- Domain (`domain/abi/`): `AbiSource`/`AbiStorageType` (enums), `ContractAbi`, `ContractAbiPort`, `AbiStoragePort`+`StorageLocation`, `RawLog`/`DecodedEvent`/`AbiDecodingPort`, `InvalidAbiContentException`, `AbiVersionOrderException`. `ContractNotFoundException` (já existente de E2) reaproveitada para o 404 do cadastro de ABI.
- **Desvio do plano**: `ContractAbi` ganhou um campo `storageLocation: StorageLocation` não previsto na lista de campos do plano original (que listava só metadados: `id`/`contractAddress`/`network`/`source`/`storageType`/`validFromBlock`/`validToBlock`/`createdAt`). Sem ele não haveria como `AbiStoragePort.retrieve()` (assinatura fechada em `abi-strategy-design.md` §1: `retrieve(location: StorageLocation): String`) saber de onde ler o conteúdo de volta em nenhum caminho — nem no warm-up do cache no boot, nem num futuro reprocessamento. Registrado como DEC-021.
- Adapters `adapters/outbound/abi/`: `AbiParser` (parse dinâmico do JSON de ABI → `EventDescriptor` com `Event`/`topic0` via `TypeReference.makeTypeReference`/`EventEncoder.encode`, réplica do algoritmo do código base — `ObjectMapper()` local, Jackson 2, não injetado, DEC-020), `DatabaseAbiStorage`/`LocalFileAbiStorage`/`HybridAbiStorage` (Strategy de storage), `AbiStorageResolver` (escolhe pela config `indexer.abi-storage.default` ou pelo `storage` do request), `AbiCache` (`ConcurrentHashMap<address, List<CachedAbiVersion>>`, warm-up via `@EventListener(ApplicationReadyEvent::class)`, `put()` incremental sem reler storage), `AbiDecodingAdapter` (só lê do `AbiCache`, nunca banco/disco — regra validada por teste MockK dedicado).
- **Desvio do plano**: `LocalFileAbiStorage` nomeia os arquivos só `{uuid}.json`, não `{contractAddress}-{uuid}.json` como o plano descrevia — a assinatura de `AbiStoragePort.store(content: String)` (fechada em `abi-strategy-design.md`) não recebe o endereço do contrato, não haveria como prefixar sem alargar a porta para todas as estratégias (inclusive `DatabaseAbiStorage`, que não precisaria do endereço).
- Persistência: `ContractAbiEntity` (`Persistable<UUID>`, mesmo padrão de `MonitoredContractEntity` — DEC-018), tabela `contract_abis` com `abi_content` mapeado como JSONB via `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6). `ContractAbiJpaRepository` (`findByContractAddressOrderByValidFromBlockAsc`, `findFirstByContractAddressAndValidToBlockIsNull`, `findAll`), `JpaContractAbiAdapter`.
- Application: `AbiRegistrationService.register()` segue a ordem do plano (contrato existe → parse da ABI → fecha versão aberta anterior, se houver, exigindo `validFromBlock` estritamente crescente → resolve storage e grava → persiste metadados → `AbiCache.put()` com os `EventDescriptor` já parseados no passo 2, sem reler); `listVersions()` com 404 se o contrato não existe.
- Spec OpenAPI: `paths/contract-abi.yaml` (`POST`/`GET` em `/v1/contracts/{address}/abi`), schemas `contract-abi.yaml`/`register-abi-payload.yaml`/`contract-abi-list.yaml` (array direto, sem paginação — lista curta por natureza, conforme o plano previa como opção), enums `abi-source.yaml`/`abi-storage-type.yaml`. `ContractsApiServiceImpl` ganhou `registerContractAbi`/`listContractAbiVersions` (mesma tag `Contracts`, mesmo arquivo — não é um novo recurso, é uma sub-rota de contrato). `ApiExceptionHandler` ganhou handlers de `InvalidAbiContentException`/`AbiVersionOrderException` → 400.
- Config: `indexer.abi-storage.{default,local-path}` em `application.yml`, `AbiStorageProperties` (`@Component @ConfigurationProperties`, sem `@EnableConfigurationProperties` separado — pego pelo component scan padrão da raiz do pacote).
- Testes (60 no total do projeto, todos verdes — 20 de E1/E2 + 40 novos de E3): `AbiParserTest` (7, unit — inclui `topic0` do Transfer ERC20 batendo com o valor bem conhecido `0xddf252ad...`), `AbiDecodingAdapterTest` (5, unit — decode simples, `topic0` desconhecido, endereço sem ABI, cenário de proxy upgradável com 2 versões por bloco, e o teste MockK que prova via `confirmVerified` que `ContractAbiPort`/`AbiStorageResolver` não são tocados depois que o cache está quente), `LocalFileAbiStorageTest` (4, unit, `@TempDir`), `HybridAbiStorageTest` (4, unit, MockK nos delegates), `AbiStorageResolverTest` (2, unit — prova a troca de storage só por config), `AbiRegistrationServiceTest` (7, MockK), `JpaContractAbiAdapterTest` (7, `@DataJpaTest`+Testcontainers), `ContractAbiApiTest` (6, `@SpringBootTest`+MockMvc+Testcontainers — 201 `UPLOAD_TEXT`, 200 listando 2 versões com fechamento automático de `validToBlock`, 404 contrato inexistente em POST e GET, 400 ABI malformada).
- **Bug pego pelos próprios testes** (não do código de produção, do fixture de teste): endereços de exemplo em `AbiDecodingAdapterTest` com 38 hex chars em vez de 40 (`0x1111...1a`, faltando um `1`) — como o teste usa `padStart(64, '0')` pra montar o topic indexado em vez de validar o tamanho, o log ficava sintaticamente válido mas decodificava um endereço deslocado em 1 byte (`0x0011...1a` em vez de `0x1111...1a`), pego pela asserção de igualdade, não por erro de execução. Corrigido usando os mesmos endereços de 40 chars já usados nos fixtures de E2 (`ContractsApiTest`).
- `./gradlew clean build` verde: bundle → generate → compile → **60/60 testes passando** → jar → build. Critério de aceite da etapa cumprido (decodificação validada com fixture manual — Hardhat ainda bloqueado por E12/DEC-010; troca de storage via config provada por `AbiStorageResolverTest`; "cache não bate em banco/disco" provado por teste MockK explícito).
- **Pendente para quando a etapa que schedula E5/E7/E8 começar**: `AbiDecodingPort` já está pronta para ser consumida (assinatura `decode(contractAddress, blockNumber, log): DecodedEvent?`), mas ainda não há nenhum chamador real — só os testes. Isso é esperado (E4 ainda não existe), só registrando que a porta está "pronta e não conectada".
- **Revisão (`reviewer`)**: aprovada com ressalvas não-bloqueantes. Confirmou: separação hexagonal (domain sem Web3j/framework — só `adapters/outbound/abi/` importa Web3j), `AbiDecodingAdapter` de fato só lê do `AbiCache` (nunca banco/disco), `ContractsApiServiceImpl` fina (sem regra de negócio), `Persistable<UUID>` seguindo DEC-018, e os dois desvios documentados em DEC-021 avaliados como aceitáveis. Achados não-bloqueantes: (1) comentários/KDoc em português no código novo — reconhecido como padrão já consolidado desde E1/E2, não regressão desta etapa, vale decisão explícita futura (atualizar `CLAUDE.md` pra refletir a prática real, ou item de hardening em E11 pra traduzir); (2) corrida entre cadastros concorrentes de ABI pro mesmo contrato sem guarda de banco (sem unique index parcial `WHERE valid_to_block IS NULL`) — registrado como DEC-022, pendência pra quando E5/E6/E11 tocar concorrência de cadastro; (3) `?: 0L` redundante em `ContractsApiServiceImpl.registerContractAbi` (o schema já tem `default: 0`) — cosmético, não corrigido.
- **Validação independente do build** (2026-08-22, sessão interativa, depois da revisão): o sandbox do `reviewer` não tinha Docker (mesmo padrão da DEC-011, distro nova — ver nota abaixo), então 25/60 testes falharam lá só por isso, não por código. Usuário habilitou a integração WSL do Docker Desktop pra esta distro (`Ubuntu 26.04` — distro diferente da que motivou a DEC-011 original); `./gradlew clean build` re-executado do zero nesta sessão confirmou de forma independente **60/60 testes passando**, `BUILD SUCCESSFUL`. Critério de aceite da etapa reconfirmado.

---

## E4 — Integração blockchain: poller + cursor + ingestão

**Depende de**: E3 (precisa do decoder para classificar decoded/raw).

**Objetivo**: poller rodando contra um nó Besu (ou Hardhat local), `eth_getLogs` sem filtro de endereço (rede inteira — decisão travada), cursor global, ingestão idempotente — **e** o endpoint de consulta desses eventos (a engrenagem sem visibilidade não serve de nada pra UI).

**Escopo**:
- `BlockchainPort`/cliente Web3j direto (`HttpService`, sem Keeper).
- `PollWindow` (algoritmo replicado de `ingestion-pipeline-reference.md` seção 1).
- Poller com auto-shrink em erro de RPC (seção 2 do mesmo doc).
- `blockchain_event` (raw + decoded unificado) com idempotência por `(tx_hash, log_index)`.
- Timestamp de bloco memoizado por lote.
- **`GET /v1/events`** (novo recurso `Events` na spec, mesmo padrão granular da E1/E2) — consulta paginada dos eventos indexados, com filtros básicos: `contractAddress`, `eventName`, `fromBlock`/`toBlock`, `fromDate`/`toDate` (reaproveitar o padrão `Specification` dinâmica do `EventQueryService` do código base, descrito em `backend/CLAUDE.md`). É o endpoint que alimenta a tela de eventos da UI mínima (doc §6) — sem ele o poller enche o banco mas ninguém consegue ver o resultado.

**Docs de referência**: [`../backend/docs/ingestion-pipeline-reference.md`](../backend/docs/ingestion-pipeline-reference.md) (seções 1–3), [`../backend/docs/performance-and-ops-notes.md`](../backend/docs/performance-and-ops-notes.md).

**Critério de aceite**: replicar os cenários de teste listados no doc de referência (janela normal, clamp por batchSize, auto-shrink simples e recursivo, erro de transporte não divide, cursor sobrevive a restart). `GET /v1/events` retornando eventos reais com os filtros funcionando via Swagger UI. Rodar contra Hardhat local quando E12 destravar.

**Notas de execução** (2026-08-22, agente `blockchain-integration`):
- Domain (`domain/blockchain/`): `BlockchainPort` (`currentBlockNumber`/`getLogs`/`getBlockTimestamp`), `BlockchainLog` (agnóstico de Web3j, mesmo princípio de `RawLog` da E3), `RpcRangeException`, `PollWindow` (função pura, algoritmo replicado de `ingestion-pipeline-reference.md` §1), `IngestionCursorPort`. `domain/event/`: `BlockchainEvent` (raw+decoded unificado), `EventSearchFilter`, `BlockchainEventPort`.
- Config: `BlockchainProperties` (`indexer.blockchain.*` — `rpcUrl`/`confirmations`/`batchSize`/`minBatchSize`/`pollIntervalMs`/`pollingEnabled`) e `IndexerProperties` (`indexer.network` — a config global da instância, decisão tomada com o usuário no planejamento: `BlockchainEvent.network` não deriva de `MonitoredContract` porque a ingestão é da rede inteira, DEC-001, a maioria dos logs é de endereço não cadastrado). Mesmo padrão `@Component`+`@ConfigurationProperties` de `AbiStorageProperties` (E3).
- Adapters `adapters/outbound/blockchain/`: `Web3jBlockchainClient` (Web3j `HttpService` direto, sem Keeper; `eth_getLogs` via `EthFilter` com `address = null` — omitido do JSON-RPC por causa de `Filter` ser `@JsonInclude(NON_NULL)`, não uma lista vazia, pra realmente significar "rede inteira" e não "nenhum endereço"; heurística de detecção de `RpcRangeException` documentada em detalhe na DEC-024 abaixo); `BlockchainPoller` (`@Scheduled(fixedDelayString = "${indexer.blockchain.poll-interval-ms}")`, `@ConditionalOnProperty` na classe — não no método `poll()` como o plano descrevia inicialmente, ver desvio abaixo — auto-shrink recursivo replicando `ingestion-pipeline-reference.md` §2); `IngestionBatchProcessor` (`@Transactional`, bean separado do poller pela mesma razão de self-invocation já documentada pro padrão de reindexação — idempotência por `existsByTxHashAndLogIndex`, timestamp memoizado por bloco distinto da janela, decode via `AbiDecodingPort` já pronta desde E3, cursor avança só depois do `saveAll`, na mesma transação).
- `@EnableScheduling` adicionado em `EvexIndexerApplication` (não existia antes — E4 é a primeira etapa com `@Scheduled`).
- Persistência: `BlockchainEventEntity` (`Persistable<UUID>`, DEC-018; `raw_topics`/`decoded_parameters` JSONB via `@JdbcTypeCode(SqlTypes.JSON)`, mesmo padrão de `ContractAbiEntity.abi_content` da E3, mas mapeados como `String` — serialização/desserialização manual via Jackson 2 local em `JpaBlockchainEventAdapter`, não mapeamento automático Hibernate de `Map`/`List` — ver DEC-024 abaixo). `BlockchainEventJpaRepository` (`JpaSpecificationExecutor`, `existsByTxHashAndLogIndex`), `JpaBlockchainEventAdapter` (`search` monta `Specification.allOf(listOfNotNull(...))` combinando só os filtros presentes; filtro de data aplica sobre `block_timestamp`, não `created_at`). `IngestionCursorEntity`/`IngestionCursorJpaRepository`/`JpaIngestionCursorAdapter` — linha única com `id` fixo (`FIXED_ID = 1`), **sem** `Persistable` (diferente de `BlockchainEventEntity`/`ContractAbiEntity`/`MonitoredContractEntity`) porque `save()` via `EntityManager.merge()` já faz upsert corretamente mesmo sem `Persistable` quando o `id` é uma constante conhecida — comportamento confirmado pelos testes (`JpaIngestionCursorAdapterTest`).
- Application: `EventQueryService.search()` — repassa pro `BlockchainEventPort`, usado pela nova `EventsApiServiceImpl`.
- Spec OpenAPI: nova tag `Events`, `paths/events.yaml` (`GET /v1/events`), schemas `event.yaml`/`event-page.yaml`, response `event-page-response.yaml`, 6 novos query params (`contractAddress`, `eventName`, `fromBlock`, `toBlock`, `fromDate`, `toDate` — reaproveitando `page`/`size` já existentes de E1/E2). `EventsApiServiceImpl` (novo arquivo, mesmo padrão fino de `ContractsApiServiceImpl`).
- Config: `indexer.network`/`indexer.blockchain.*` em `application.yml`, todos com defaults sensatos via env var (mesmo padrão de `indexer.abi-storage.*` da E3).
- Testes (104 no total do projeto — 60 de E1-E3 + 44 novos): `PollWindowTest` (7, unit puro, os 4 cenários do doc + 3 variações de borda), `BlockchainPollerTest` (6, MockK — happy path, `PollWindow` retornando `null`, auto-shrink 1 nível, auto-shrink recursivo 2 níveis, janela de 1 bloco não encolhe abaixo do piso, erro de transporte não dispara shrink), `IngestionBatchProcessorTest` (9, MockK — idempotência, timestamp memoizado por bloco distinto, decode conhecido/desconhecido, cursor avança só depois do `saveAll`, cursor avança mesmo com janela vazia ou 100% duplicada, `network` vem de `IndexerProperties`), `Web3jBlockchainClientTest` (5, unit — mapeamento `Log→BlockchainLog` e detecção de erro de range, sem HTTP real, métodos `isRangeError`/`toBlockchainLog` marcados `internal` de propósito pra serem testáveis isoladamente), `JpaBlockchainEventAdapterTest` (8, `@DataJpaTest`+Testcontainers — `saveAll`/`existsByTxHashAndLogIndex`, round-trip JSONB, cada filtro isolado + combinado), `JpaIngestionCursorAdapterTest` (3, `@DataJpaTest`+Testcontainers — cursor sobrevive a "restart" via `entityManager.flush()+clear()` entre escrita e releitura por uma instância nova do adapter), `EventsApiTest` (8, `@SpringBootTest`+MockMvc+Testcontainers — cada filtro + paginação + evento raw sem `eventName`/`decodedParameters` no JSON).
- **Desvio do plano — bug real pego durante a implementação, não só de ambiente**: um novo `src/test/resources/application.yml` (criado pra setar `indexer.blockchain.polling-enabled: false` nos testes, exatamente como o plano descrevia) fez **43 dos 104 testes falharem** com `relation "X" does not exist"` (mesmo pras entidades antigas, `monitored_contracts`/`contract_abis`) e os testes `@SpringBootTest` falharem já no carregamento do contexto. Causa raiz: um `application.yml` em `src/test/resources` **substitui** (não mescla com) o de `src/main/resources` no classpath efetivo — `build/resources/test` aparece antes de `build/resources/main` na ordem de resolução do Gradle, então o `getResource("application.yml")` do Spring Boot resolve só pro arquivo de teste, e `ddl-auto: update`/todo o resto de `application.yml` principal simplesmente some pros testes (sem erro, sem warning — silencioso). `create-drop` mascarava o sintoma (criava e derrubava tabelas do zero, dando a falsa impressão de estar tudo bem) até eu isolar via bissecção comparando `update` vs. `create-drop`. Fix: removido `src/test/resources/application.yml`; `polling-enabled: false` registrado via `@DynamicPropertySource` em `AbstractIntegrationTest` (mesmo mecanismo já usado pro datasource do Testcontainers) — aplica a todo teste que estende essa classe base, sem risco de sombrear o `application.yml` principal. Documentado em detalhe na DEC-024.
- **Desvio do plano — bug de fixture, não de produção**: endereços de exemplo novos (`0x1111...1a`) com 38 hex chars em vez de 40 — mesma classe de bug já documentada na E3 (`AbiDecodingAdapterTest`), desta vez pego pela validação `pattern` do OpenAPI (`ConstraintViolationException` → 400) em vez de por asserção de igualdade. Corrigido usando os mesmos endereços de 40 chars já padronizados nos fixtures de E2/E3.
- **Desvio do plano — ajuste de implementação, não de comportamento**: `@ConditionalOnProperty` foi anotado na **classe** `BlockchainPoller`, não no método `poll()` como o texto do plano sugeria — condicionais do Spring Boot só se aplicam a definições de bean inteiras (`@Component`/`@Configuration`/`@Bean`), não a métodos arbitrários de uma classe já instanciada. Efeito prático é o mesmo (o poller inteiro não roda quando `polling-enabled=false`).
- `./gradlew clean build` verde, reconfirmado 2x seguidas (incluindo `--rerun`, sem cache mascarando falha): bundle → generate → compile → **104/104 testes passando** → jar → build. Validado manualmente também via `bootRun` contra o Postgres real do `docker-compose.yml` (`EVEX_POLLING_ENABLED=false`, `EVEX_RPC_URL` apontando pra uma porta inválida de propósito, provando que o boot não depende de RPC disponível): `GET /actuator/health` → 200, `GET /v1/events` sem filtro → `200` com página vazia, `GET /v1/events` com todos os filtros preenchidos → `200`, endereço malformado em `contractAddress` → `400` `VALIDATION_ERROR`, Swagger UI → `200`.
- **Pendência explícita (não faz parte do critério desta etapa)**: nunca rodado contra Besu/Hardhat real (E12 ainda bloqueado, DEC-010) — `Web3jBlockchainClient` só validado estruturalmente (compila, mapeia dados fabricados em memória) e a heurística de `isRangeError` é best-effort, não validada contra mensagens de erro reais de um nó. Revisar assim que E12 destravar.
- **Revisão (`reviewer`)**: aprovada com ressalvas não-bloqueantes (2026-08-22). Confirmado: separação hexagonal íntegra (`domain/blockchain/`/`domain/event/` sem Web3j/Spring/JPA — só `adapters/outbound/blockchain/` importa Web3j), `PollWindow` e o auto-shrink recursivo de `BlockchainPoller` batem exatamente com os pseudocódigos de `ingestion-pipeline-reference.md` §1–2 (erro de range dispara shrink recursivo até `minBatchSize`, erro de transporte não dispara shrink — validado por teste dedicado com `IOException`), cursor avança só na mesma transação da ingestão (depois do `saveAll`, mesmo `@Transactional` de `IngestionBatchProcessor`, testado inclusive pra janela vazia/100% duplicada), idempotência por `existsByTxHashAndLogIndex` checada log a log antes de decodificar, timestamp de bloco memoizado por bloco distinto (testado), `AbiDecodingPort.decode()` de fato consumida (nunca reimplementada), `EventsApiServiceImpl` fina delegando pro `EventQueryService`, `Persistable<UUID>` em `BlockchainEventEntity` seguindo DEC-018. `IngestionCursorEntity` de linha única sem `Persistable` avaliado como seguro: `merge()` faz upsert corretamente com `id` fixo (confirmado por teste), e não há risco de 2 execuções concorrentes do poller no mesmo processo — `fixedDelay` do Spring garante que a próxima invocação do mesmo `@Scheduled` só começa depois da anterior terminar, independente do tamanho do pool do `TaskScheduler`. Os dois achados da DEC-024 revisados: heurística de `isRangeError` documentada como best-effort (pendência explícita pra E12, aceitável), e o fix do shadowing de `application.yml` aplicado corretamente (arquivo não existe mais no repo, override via `@DynamicPropertySource` em `AbstractIntegrationTest`). Cobertura de teste cobre todos os cenários de `PollWindow`/auto-shrink listados no doc de referência e todos os filtros de `GET /v1/events` (incluindo paginação e evento raw sem `eventName`/`decodedParameters`). `./gradlew clean build` reconfirmado verde de forma independente (104/104).
Achados corrigidos na hora pelo `reviewer` (ver DEC-025): (1) 3 comentários (`BlockchainPoller.kt`, `BlockchainProperties.kt`, `application.yml`) citavam `src/test/resources/application.yml` como se ainda existisse — sobreviveram ao fix real da DEC-024 (arquivo removido, override movido pra `@DynamicPropertySource`), reescritos para referenciar o mecanismo real; (2) `IngestionBatchProcessor` gravava `contractAddress` sem normalizar pra lowercase — inconsistente com o padrão do resto do projeto (E2/E3 sempre normalizam endereço antes de persistir/comparar), risco de filtro `GET /v1/events?contractAddress=` não bater silenciosamente se um nó devolver endereço checksummed; corrigido normalizando na origem (`IngestionBatchProcessor`). `./gradlew clean build` reconfirmado verde (104/104) depois dos dois fixes.
Sem achados bloqueantes. Etapa pronta para ser marcada ✅ pela sessão principal.
- **Validação independente do build** (2026-08-22, sessão interativa, depois da revisão): `./gradlew clean build` re-executado do zero nesta sessão (Docker já funcionando, DEC-023) confirma de forma independente **104/104 testes passando**, `BUILD SUCCESSFUL`. Etapa marcada ✅.

---

## E5 — Reindexação assíncrona (redecode)

**Depende de**: E4.

**Objetivo**: reprocessar eventos raw quando uma ABI nova é cadastrada, parametrizável por `contract_address`, `topic0` ou janela de blocos (doc §4.3) — adaptando o padrão orquestrador+batch-processor do código base.

**Escopo**:
- Orquestrador assíncrono em lotes (padrão `AtomicBoolean` + `@Async` + batch processor em bean separado — seção 4 de `ingestion-pipeline-reference.md`).
- Filtro parametrizado (não é mais só "globalmente pendente").
- Endpoint admin para disparar e consultar status.
- Ligar ao cadastro de ABI da E3: cadastrar ABI para contrato com histórico deve acionar o redecode automaticamente (não só re-rotular).

**Docs de referência**: [`../backend/docs/ingestion-pipeline-reference.md`](../backend/docs/ingestion-pipeline-reference.md) (seções 4–5, inclui cenários de teste).

**Critério de aceite**: cadastrar ABI depois de já ter eventos raw do contrato → reindexação assíncrona decodifica o histórico; reindexação por `topic0` afeta múltiplos contratos; reindexação por janela de blocos limita corretamente.

**Notas de execução** (2026-08-22, agente `blockchain-integration`):
- Domain (`domain/redecode/`): `RedecodeFilter` (`contractAddress`/`topic0`/`fromBlock`/`toBlock`, todos opcionais — filtro vazio é caso válido, redecodifica tudo que está pendente globalmente), `RedecodeStatus` (snapshot `running`/`processedCount`/`decodedCount`/`remainingCount`). `BlockchainEventPort` (E4) ganhou 3 métodos novos: `findPendingCandidates(filter, limit)` (ordenado `blockNumber ASC, logIndex ASC, id ASC` — ordem estável entre lotes sucessivos), `countPending(filter)`, `updateAll(events)`.
- **Achado #1 do plano confirmado na implementação**: `JpaBlockchainEventAdapter.saveAll()` hardcodava `new = true` em `toEntity()` — quebraria o redecode (linhas vindas de `findPendingCandidates` já têm PK no banco; `saveAll` nelas tentaria `INSERT` sobre PK existente). Fix aplicado exatamente como o plano previa: `toEntity(isNew: Boolean)` — `saveAll` chama com `true`, `updateAll` (novo) com `false`. `updateAll` faz `entityManager.merge()` (via `Persistable.isNew()=false`), confirmado por teste (`updateAll updates existing rows in place, does not duplicate the primary key`).
- Application (`application/redecode/`, 3 beans conforme o plano — achado #2 sobre self-invocation de `@Async`): `RedecodeBatchProcessor` (`@Component`, `@Transactional fun processBatch(filter, batchSize)` — busca candidatos, tenta decodificar via `AbiDecodingPort.decode()` já existente desde E3/E4 (nenhuma decodificação reimplementada), só as linhas que mudaram vão pro `updateAll`); `RedecodeRunner` (`@Component`, `@Async fun run(filter, orchestrator)` — loop até `decodedCount == 0`, não até "sem mais linhas", `finally { orchestrator.onFinished() }`); `RedecodeOrchestrator` (`@Service`, dono do `AtomicBoolean running` + `AtomicLong` contadores + `@Volatile lastFilter`, `tryStart`/`status`/`onBatchProcessed`/`onFinished`) — dispara `runner.run(filter, this)` passando a própria instância como parâmetro (não injetada de volta no `RedecodeRunner`, evita dependência circular de bean).
- Ligação com E3: `AbiRegistrationService.register()` ganhou `RedecodeOrchestrator` no construtor, chama `tryStart(RedecodeFilter(contractAddress = normalizedAddress))` incondicionalmente depois do `abiCache.put(...)` e antes do `return saved` — não bloqueia a resposta HTTP (`tryStart` só dispara o `@Async` e retorna).
- Config: `RedecodeProperties` (`indexer.redecode.batch-size`, default 100, mesmo padrão `@Component`+`@ConfigurationProperties` de `BlockchainProperties`/`AbiStorageProperties`). `EvexIndexerApplication` ganhou `@EnableAsync` (primeira etapa a usar `@Async` — convive com `@EnableScheduling` de E4 sem conflito).
- Spec OpenAPI: nova tag `Redecode`, `paths/redecode.yaml` (`POST /v1/redecode`, corpo opcional `redecode-filter-payload.yaml`, resposta `202` com `redecode-status.yaml`), `paths/redecode-status.yaml` (`GET /v1/redecode/status`, `200` com o mesmo schema). Corpo opcional (`requestBody.required: false`) gerou parâmetro nullable no service interface (`triggerRedecode(redecodeFilterPayload: RedecodeFilterPayload?)`), confirmado inspecionando o código gerado antes de escrever o `*ApiServiceImpl`. `RedecodeApiServiceImpl` (novo, mesmo padrão fino de `EventsApiServiceImpl`) delega direto pro `RedecodeOrchestrator` — sem serviço de aplicação intermediário, o orquestrador já É o caso de uso.
- **Desvio do plano — achado real de teste, não de produção**: o teste de ponta a ponta do critério de aceite (`RedecodeAcceptanceTest`) precisa de `@Async` funcionando de verdade, o que **exclui `@Transactional`** na classe de teste (a thread do runner assíncrono usa uma conexão/transação separada da conexão do teste; se o teste estivesse `@Transactional` — como todo o resto dos `*ApiTest` desde E2 — a transação da inserção dos dados de setup nunca teria sido commitada, e o `RedecodeRunner` rodando em outra thread/conexão nunca veria esses dados). Isso significa que os dados desta classe de teste **ficam commitados de verdade** no container Postgres compartilhado (`AbstractIntegrationTest`, container singleton reaproveitado por toda a suíte, JVM inteira) — diferente de todo o resto da suíte desde E1, que sempre usa rollback automático (`@Transactional` explícito ou implícito via `@DataJpaTest`). Isso causou contaminação cross-teste real na primeira rodada: (1) endereços fixos reaproveitados em toda a suíte (`0x1111...1a` etc.) colidiam com unique constraint de outros testes rodando depois no mesmo container; (2) mesmo trocando para endereços aleatórios, os eventos/contratos ficavam permanentemente na tabela e apareciam em queries de outros testes sem filtro de endereço restritivo o bastante (ex.: `JpaBlockchainEventAdapterTest > search filters by fromDate and toDate`, que filtra só por data, sem endereço). Fix: `RedecodeAcceptanceTest` usa endereços/tx hashes gerados aleatoriamente (`randomAddress()`/`randomTxHash()`, nunca os fixtures fixos do resto da suíte) **e** um `@AfterEach` que deleta explicitamente (via os `JpaRepository` injetados direto, não a porta de domínio — não há método de delete na porta) tudo que o teste criou, rastreado por endereço. Documentado em detalhe em `docs/decisions.md` (DEC-026) — primeira classe de teste do projeto que não pode usar o padrão de rollback automático estabelecido desde E1.
- Testes (130 no total do projeto — 104 de E1-E4 + 26 novos): `RedecodeBatchProcessorTest` (4, unit MockK — decodifica `topic0` conhecido e manda pro `updateAll`, mantém `decoded=false` e não chama `updateAll` quando `topic0` é desconhecido, lote vazio, `updateAll` só com as linhas que mudaram), `RedecodeOrchestratorTest` (6, unit MockK no `RedecodeRunner` — `tryStart` true/false conforme `running`, libera depois de `onFinished`, `status()` reflete contadores, `remainingCount` vem de `countPending` com o filtro certo), `JpaBlockchainEventAdapterTest` (+7, `@DataJpaTest`+Testcontainers — `findPendingCandidates` por cada filtro isolado + combinado + ordem estável entre chamadas sucessivas, `countPending`, `updateAll` não duplica PK), `AbiRegistrationServiceTest` (extensão — `tryStart` verificado depois de `register()` bem-sucedido, `verify(exactly = 0)` nos caminhos de erro), `RedecodeApiTest` (6, `@SpringBootTest`+MockMvc+Testcontainers — `POST` com corpo vazio/sem corpo/cada filtro isolado, `GET /status`), `RedecodeAcceptanceTest` (3, `@SpringBootTest`+MockMvc+Testcontainers+Awaitility, `@Async` real — os 3 cenários do critério de aceite: histórico decodificado ao cadastrar ABI, `topic0` afetando múltiplos contratos, janela de blocos limitando corretamente).
- `./gradlew clean build` verde, reconfirmado 3x seguidas (incluindo `--rerun`, sem cache mascarando falha nem flakiness do componente assíncrono): bundle → generate → compile → **130/130 testes passando** → jar → build.
- **Pendência explícita (mesma classe da E4)**: nunca rodado contra Besu/Hardhat real (E12 ainda bloqueado, DEC-010) — o redecode não toca a rede (reconstrói `RawLog` a partir de `rawTopics`/`rawData` já persistidos), então essa pendência é menos crítica aqui do que foi em E4, mas a decodificação em si (`AbiDecodingPort`) segue com a mesma limitação já registrada.
- **Revisão (`reviewer`)**: aprovada com 2 achados corrigidos na hora (não-bloqueantes depois do fix, mas eram bloqueantes antes — deixariam a suíte flaky). Confirmado: design de 3 beans (`RedecodeOrchestrator`/`RedecodeRunner`/`RedecodeBatchProcessor`) evita self-invocation de `@Async` corretamente (`tryStart` chama `runner.run()` via bean injetado, passa pelo proxy do Spring); fix do `Persistable.isNew()` (`toEntity(isNew: Boolean)`, `saveAll`→`true`/`updateAll`→`false`) confirmado fazendo `UPDATE` de fato, sem duplicar PK (`JpaBlockchainEventAdapterTest > updateAll updates existing rows in place`); `AtomicBoolean.compareAndSet` garante 1 execução por vez; gatilho automático em `AbiRegistrationService.register()` chamado depois de `abiCache.put(...)`, não bloqueia a resposta HTTP, filtro só por `contractAddress`; separação hexagonal íntegra (`domain/redecode/` sem import de framework/Web3j/JPA); `RedecodeApiServiceImpl` fina, delega direto pro orquestrador; os 3 cenários do critério de aceite cobertos por `RedecodeAcceptanceTest`; idioma do código em inglês, sem auth, `network` presente (herdado de `BlockchainEvent`, E4), reindexação sempre assíncrona em lotes.
  Achados corrigidos na hora (ver DEC-027 para detalhe completo): (1) **flakiness real** em `RedecodeAcceptanceTest` — `./gradlew clean build --rerun` numa 2ª rodada (JIT/caches diferentes da 1ª) falhou por timeout do Awaitility: `registerAbi()` dispara uma reindexação automática (`tryStart`) que, se ainda em andamento, faz o `tryStart` seguinte (outro `registerAbi`, ou o `POST /v1/redecode` manual do próprio teste) ser descartado silenciosamente (`RedecodeOrchestrator` é "1 execução por vez, não enfileira" por design, `ingestion-pipeline-reference.md` §4) — corrigido com um helper `awaitRedecodeIdle()` chamado depois de todo `registerAbi()` nos 3 testes, esperando `running == false` antes do próximo `tryStart`; (2) **gap de cobertura** do critério de parada do loop (`decodedCount == 0`, não `processedCount == 0`/lista vazia — `ingestion-pipeline-reference.md` §4) — nenhum teste existente distinguia os dois critérios (todos usam no máximo 1 lote); criado `RedecodeRunnerTest` novo com o cenário de 2 lotes (1º com mistura decodificável/permanentemente-não-decodificável, 2º só com os remanescentes) provando que o loop para no critério certo.
  `./gradlew clean build` reconfirmado verde depois dos fixes, e mais 3 rodadas de `./gradlew clean test --rerun` (todas verdes) — **133/133 testes passando** (130 relatados pelo agente + 3 novos de `RedecodeRunnerTest`). Etapa pronta para ser marcada ✅ pela sessão principal.
- **Validação independente do build** (2026-08-22, sessão interativa, depois da revisão): `./gradlew clean build` re-executado do zero nesta sessão confirma de forma independente **133/133 testes passando**, `BUILD SUCCESSFUL`. Etapa marcada ✅.

---

## E6 — Discovery Blockscout + detecção automática ERC20/721/1155

**Depende de**: E3.

**Objetivo**: cliente Blockscout (doc §7) + templates built-in de interface padrão com detecção automática (doc §4.5).

**Escopo**:
- Cliente Blockscout (`RestClient`): listar contratos verificados, buscar ABI, buscar logs históricos.
- `InterfaceTemplate` (ERC20/721/1155) + `InterfaceDetector` (comparação de `topic0`s) — Strategy pattern conforme `abi-strategy-design.md` seção 3.
- Fluxo de cadastro sugerindo nível 1/2/3 de configuração.

**Docs de referência**: [`../backend/docs/abi-strategy-design.md`](../backend/docs/abi-strategy-design.md) (seções 3–4).

**Critério de aceite**: cadastrar um contrato ERC20 sem enviar ABI → sistema detecta e sugere o template automaticamente; discovery lista contratos verificados de um Blockscout de teste.

**Notas de execução** (2026-08-23, agente `blockchain-integration`):
- Domain (`domain/abi/`): `InterfaceTemplate` (`name`/`eventTopic0s`/`builtinAbi`, tipo puro), `DetectionLevel` (`FULL`/`PARTIAL`/`NONE`), `InterfaceMatch` (`templateName`/`level`/`matchedTopic0s`/`missingTopic0s`), `InterfaceDetector` (função pura `detect(observed, templates)`, mesmo espírito de `PollWindow`), `NoFullInterfaceMatchException` (400) e `BlockscoutAbiNotFoundException` (404, novo — não estava na lista original de exceções do plano, mas precisa de um tipo próprio pra diferenciar "Blockscout respondeu mas não tem ABI verificada" de `BlockscoutUnavailableException`, 503, "Blockscout nem está configurado/acessível"). `domain/discovery/` (pacote novo): `BlockscoutPort`, `BlockscoutContractSummary`, `BlockscoutUnavailableException`. `BlockchainEventPort` (E4) ganhou `findDistinctTopic0s(contractAddress)`.
- **Achado do plano confirmado na implementação**: `topic0` de `Transfer(address,address,uint256)` é o mesmo para ERC20 e ERC721 (não inclui `indexed`) — `InterfaceDetector.detect()` ordena por `DetectionLevel` (FULL primeiro, via ordem natural do enum) e, dentro do mesmo nível, por `eventTopic0s.size` decrescente. Testado explicitamente em `InterfaceDetectorTest` com o cenário dos 3 eventos ERC721 resolvendo como ERC721 (não o ERC20 ambíguo).
- **Desvio do plano**: `InterfaceMatch` não carrega uma referência ao `InterfaceTemplate` (só `templateName: String`, exatamente como o plano especificou nos campos). O texto do plano descrevia o registro `AUTO_DETECTED` como "usa `match.template.builtinAbi`" — como o domínio não carrega esse ponteiro (por design, pra não acoplar `InterfaceMatch` a `InterfaceTemplate`/`BuiltinInterfaceTemplates`), `AbiRegistrationService.resolveAbiContent()` resolve o `builtinAbi` de volta via `BuiltinInterfaceTemplates.findByName(match.templateName)` (novo método utilitário no adapter, não estava no plano original, mas é a forma natural de fechar esse gap sem alargar `InterfaceMatch`).
- Adapters `adapters/outbound/abi/`: `BuiltinInterfaceTemplates` (`@Component`, monta os 3 `InterfaceTemplate` no construtor reaproveitando `AbiParser` já existente da E3 — cada `builtinAbi` é uma ABI JSON mínima escrita à mão, `topic0` vem do `EventDescriptor.topic0` já calculado, nenhuma lógica nova de Web3j). Valores de `topic0` dos 5 eventos padrão (Transfer/Approval/ApprovalForAll/TransferSingle/TransferBatch) **confirmados via execução real de `org.web3j.abi.EventEncoder.encode`** (não hardcoded de memória) durante o planejamento — usados tanto no código de produção quanto nos testes.
- Adapters `adapters/outbound/discovery/` (pacote novo, previsto desde `backend/CLAUDE.md`): `BlockscoutClient : BlockscoutPort` — Spring `RestClient` (bean `RestClient.Builder` do módulo `spring-boot-restclient`, ver DEC-028 abaixo), os 3 endpoints exatos do doc §7 (`GET /api/v2/smart-contracts?filter=verified`, `GET /api/v2/smart-contracts/{address}`, `GET /api/v2/addresses/{address}/logs`). `base-url` vazio → `BlockscoutUnavailableException` sem tentar HTTP (`RestClient` construído `lazy`, só na 1ª chamada). `JsonNode`/`ObjectMapper` local (Jackson 2, DEC-020) pra navegação dinâmica, mesmo padrão de `AbiParser` — precisou forçar explicitamente o conversor Jackson 2 no builder (ver DEC-029 abaixo, achado real de teste).
- Application: `InterfaceDetectionService.suggest(address)` — resolve `topic0`s observados preferindo `BlockchainEventPort.findDistinctTopic0s` (DEC-001), cai pro Blockscout só se vazio; **`BlockscoutUnavailableException` do fallback é engolida** (não propagada) — ver DEC-030. `BlockscoutDiscoveryService` (thin, delega pro `BlockscoutPort`). `AbiRegistrationService.register()` estendido: `abi` virou `String?`; novo método privado `resolveAbiContent(address, abi, source)` resolve o conteúdo conforme a origem — implementado como `when` (não uma hierarquia de 4 classes `AbiIngestionStrategy` separada, ver desvio abaixo) — chamado **antes** do `abiParser.parse(...)`, preservando a regra "ABI inválida falha antes de tocar storage" também pras novas origens.
- **Desvio do plano**: a "Strategy de origem do cadastro" (`AbiIngestionStrategy`, `abi-strategy-design.md` §4) foi implementada como um `when` dentro de `AbiRegistrationService.resolveAbiContent()`, não como 4 classes/beans `UploadFileStrategy`/`UploadTextStrategy`/`BlockscoutStrategy`/`AutoDetectedStrategy` injetados via `Map<AbiSource, AbiIngestionStrategy>` (padrão usado por `AbiStorageResolver`/`AbiStoragePort` na E3). Motivo: os 4 ramos não têm estado próprio nem variação de configuração em runtime (diferente de `AbiStoragePort`, que troca por `application.yml`) — são 4 formas fixas de resolver uma `String`, então uma hierarquia de classes só pra satisfazer o padrão Strategy à risca seria overhead sem ganho real nesta etapa. Registrado como decisão consciente, não descuido.
- Persistência: `BlockchainEventJpaRepository` ganhou `@Query` derivada (`findDistinctTopic0sByContractAddress`, `SELECT DISTINCT ... WHERE contract_address = ? AND topic0 IS NOT NULL`); `JpaBlockchainEventAdapter.findDistinctTopic0s` normaliza o endereço pra lowercase antes de delegar (mesmo padrão do resto da classe).
- Config: `BlockscoutProperties` (`indexer.blockscout.base-url`, default vazio via `EVEX_BLOCKSCOUT_URL`), mesmo padrão `@Component`+`@ConfigurationProperties` das demais. **Dependência nova em `build.gradle.kts`**: `org.springframework.boot:spring-boot-restclient` — ver DEC-028.
- Spec OpenAPI: nova tag `Discovery`; `paths/blockscout-contracts.yaml` (`GET /v1/discovery/blockscout/contracts`, `200`/`503`); `paths/contract-abi-suggestions.yaml` (`GET /v1/contracts/{address}/abi/suggestions`, `200`/`404`, tag `Contracts` — sub-rota de contrato, mesmo critério já usado em `contract-abi.yaml` na E3); schemas novos `blockscout-contract(-list)`, `interface-suggestion(-list)`, enum `detection-level` (só `FULL`/`PARTIAL` — `NONE` nunca chega na API, filtrado em `InterfaceDetectionService.suggest()`); `register-abi-payload.yaml`: `abi` saiu de `required`, ganhou `nullable: true` e descrição explicando quando é obrigatório/ignorado. `DiscoveryApiServiceImpl` (novo, thin) e `ContractsApiServiceImpl.suggestInterface` (novo método — chama `monitoredContractService.getByAddress()` só pelo efeito colateral do 404, já que `InterfaceDetectionService.suggest()` não sabe nem precisa saber se o endereço é um `MonitoredContract`). `ApiExceptionHandler` ganhou os 3 handlers (`BlockscoutAbiNotFoundException`→404, `NoFullInterfaceMatchException`→400, `BlockscoutUnavailableException`→503).
- Testes (171 no total do projeto — 133 de E1-E5 + 38 novos): `InterfaceDetectorTest` (7, unit puro — match total/parcial/nenhum, o cenário do achado de ambiguidade ERC20/721, ERC1155 sem ambiguidade, ordenação), `BuiltinInterfaceTemplatesTest` (6, unit — `topic0` de cada template batendo com os valores confirmados via `EventEncoder`, round-trip via `AbiParser`), `InterfaceDetectionServiceTest` (4, MockK — prioridade `BlockchainEventPort`/fallback Blockscout/filtro de `NONE`/engolir `BlockscoutUnavailableException`), `BlockscoutClientTest` (6, `MockRestServiceServer` — os 3 endpoints, `base-url` vazio sem tentar HTTP, 404→null em `getAbi`, 5xx→`BlockscoutUnavailableException`), `AbiRegistrationServiceTest` (+9 — `UPLOAD_TEXT`/`UPLOAD_FILE` sem `abi`→400, `BLOCKSCOUT` registra ignorando o `abi` do request, `BLOCKSCOUT` sem ABI verificada→404, `AUTO_DETECTED` com `FULL`→registra, `AUTO_DETECTED` só `PARTIAL`/sem match→400 sem registrar), `JpaBlockchainEventAdapterTest` (+2 — dedup de `topic0`s, contrato sem eventos), `DiscoveryApiTest` (1, `@SpringBootTest`+MockMvc+Testcontainers — 503 sem Blockscout configurado), `ContractAbiSuggestionsApiTest` (4, idem — 404 contrato inexistente, `FULL` ERC20 com Transfer+Approval semeados, `PARTIAL` sem nenhum `FULL`, lista vazia sem dado nenhum e Blockscout indisponível), `AutoDetectedAbiAcceptanceTest` (2, `@SpringBootTest`+MockMvc+Testcontainers+Awaitility, `@Async` real, mesmo padrão não-transacional + limpeza explícita de `RedecodeAcceptanceTest`/DEC-026 — critério de aceite da etapa: `POST .../abi` com `source=AUTO_DETECTED` registra o `builtinAbi` do ERC20 e decodifica o histórico raw já ingerido; `PARTIAL` isolado não registra nada).
- `./gradlew clean build` verde, reconfirmado 3x seguidas (incluindo `--rerun`, sem cache mascarando falha nem flakiness do componente assíncrono): bundle → generate → compile → **171/171 testes passando** → jar → build.
- **Pendência explícita (mesma classe de E4/E5/E12/DEC-010)**: nunca rodado contra um Blockscout real — `BlockscoutClient` validado estruturalmente (parsing/mapeamento) via `MockRestServiceServer`, formato de resposta é best-effort da API pública v2 documentada do Blockscout (endpoints exatos do doc §7), a confirmar quando houver uma instância real disponível. Segundo ponto do critério de aceite da etapa ("discovery lista contratos verificados de um Blockscout de teste") cumprido nesse limite — mesmo padrão já aceito em E3/E4.
- **Pendência explícita nova**: `docs/decisions.md` DEC-022 (corrida entre cadastros concorrentes de ABI pro mesmo endereço, sem unique index parcial `WHERE valid_to_block IS NULL`) segue sem fix — E6 tocou `AbiRegistrationService.register()` de novo (3 novos ramos de `source`) sem aumentar a frequência esperada de cadastro concorrente na PoC; revisitar junto com E7/E8/E11 como já estava registrado.
- **Revisão (`reviewer`)**: aprovada com ressalvas não-bloqueantes (2026-08-23). Confirmado: (1) desempate de ambiguidade ERC20/ERC721 do `InterfaceDetector` correto — ordena `FULL` primeiro e, dentro do mesmo nível, por `eventTopic0s.size` decrescente; `InterfaceDetectorTest > a contract emitting all 3 ERC721 events resolves as ERC721, not the ambiguous ERC20 subset match` cobre exatamente o cenário pedido; (2) `AbiSource.AUTO_DETECTED` só registra em match `FULL` — `resolveAbiContent()` lança `NoFullInterfaceMatchException` tanto pra `PARTIAL` quanto pra `NONE` (`?.takeIf { it.level == DetectionLevel.FULL }`), sem nenhum caminho que aplique um template parcial silenciosamente; coberto por `AbiRegistrationServiceTest` (unit) e `AutoDetectedAbiAcceptanceTest` (fim a fim, 400 sem registrar nada); (3) `BlockscoutClient` nunca derruba o resto do sistema: `base-url` vazio nunca tenta HTTP (`RestClient` construído `lazy`), toda falha de rede/HTTP vira `BlockscoutUnavailableException` (nunca uma exceção crua do `RestClientException` propagada); DEC-030 avaliada como o comportamento certo (Blockscout é *fallback* silencioso só em `InterfaceDetectionService.suggest()`, continua propagando 503 nos 2 usos onde é fonte primária pedida explicitamente — `AbiSource.BLOCKSCOUT` e `GET /v1/discovery/blockscout/contracts`) — único ponto de atenção (não-bloqueante): o log do fallback engolido é `DEBUG`, nível tipicamente desligado em produção; um operador que esqueceu de configurar `indexer.blockscout.base-url` não teria sinal nenhum de que o fallback está sempre vazio. Sugestão não-bloqueante: subir pra `WARN` (ou `INFO`) se isso vier a incomodar na prática; (4) DEC-029 (forçar `MappingJackson2HttpMessageConverter`) bem localizada — `grep` por `tools.jackson` em `src/main`/`src/test` só encontra menções em comentário (explicando o porquê), nenhum import real de Jackson 3 vazou pra fora de `BlockscoutClient`; consistente com DEC-020; (5) `abi` opcional em `register-abi-payload.yaml` não regrediu nada de E3 — `UPLOAD_FILE`/`UPLOAD_TEXT` sem `abi` (`null`/branco) seguem rejeitados com 400 `INVALID_ABI_CONTENT` via `resolveAbiContent()`, testado em `AbiRegistrationServiceTest`; (6) `BuiltinInterfaceTemplates` reaproveita `AbiParser`/`EventEncoder` já existentes da E3 pra computar `topic0` — nenhuma lógica nova de Web3j; (7) separação hexagonal íntegra — `domain/abi/`/`domain/discovery/` sem import de Web3j/JPA/Spring (`RawLog.kt` só *menciona* Web3j em comentário, não importa); (8) `DiscoveryApiServiceImpl`/`ContractsApiServiceImpl.suggestInterface` finos, sem regra de negócio — só mapeiam DTO e delegam; (9) desvio do `AbiIngestionStrategy` como `when` (não 4 classes Strategy) avaliado como aceitável nesta etapa — formalizado em `docs/decisions.md` DEC-031 (não tinha entrada própria, só a justificativa em prosa nesta seção); ponto de atenção registrado ali: revisitar se um 5º `AbiSource` aparecer ou se o `when` crescer além da resolução de conteúdo; (10) critério de aceite cumprido no limite já aceito desde E3/E4 (sem Blockscout real disponível — discovery/detecção validados via `MockRestServiceServer`/fixtures); (11) nenhuma violação de regra geral — idioma do código em inglês (só comentários em português, prática já consolidada desde E1/E2), sem auth, `network` presente em `ContractAbi` (herdado do `MonitoredContract`), `BlockscoutContractSummary`/`InterfaceMatch` corretamente sem `network` por serem DTOs transientes de terceiro, não entidades persistidas. Endereços de fixture novos (`ContractAbiSuggestionsApiTest`/`AutoDetectedAbiAcceptanceTest`) conferidos com 40 hex chars — sem repetir o bug de E3/E4.
  `./gradlew clean build --rerun` (2x) + `./gradlew clean test --rerun` (1x) reconfirmados verdes de forma independente nesta revisão, sem sinal de flakiness: **171/171 testes passando**, `BUILD SUCCESSFUL` em todas as rodadas.
  Nenhum achado bloqueante. Achado não-bloqueante já endereçado nesta revisão: DEC-031 criada em `docs/decisions.md` formalizando o desvio do item (9). Achados não-bloqueantes remanescentes (não corrigidos, ficam para quando fizer sentido revisitar): nível de log `DEBUG` do fallback engolido em DEC-030 (item acima); DEC-022 (corrida de cadastro concorrente de ABI) segue como pendência já rastreada, sem mudança de risco nesta etapa. Etapa pronta para ser marcada ✅ pela sessão principal.
- **Validação independente do build** (2026-08-23, sessão interativa, depois da revisão): `./gradlew clean build` re-executado do zero nesta sessão confirma de forma independente **171/171 testes passando**, `BUILD SUCCESSFUL`. Etapa marcada ✅.

---

## E7 — Saldos históricos (behaviors + snapshots diários)

**Depende de**: E5 (usa o mesmo pipeline decodificado).

**Objetivo**: configuração de monitoramento de saldo pelo usuário (doc §4.4) com os 4 behaviors, e job noturno de snapshot.

**Escopo**:
- `BalanceMonitorConfig` (entidade editável via API).
- `BalanceBehaviorStrategy` (`Credit`/`Debit`/`Transfer`/`Delta`) — Strategy pattern conforme `abi-strategy-design.md` seção 2.
- `asset_balances` (atual) + `asset_balance_snapshots` (diário) + job `@Scheduled`.

**Docs de referência**: [`../backend/docs/abi-strategy-design.md`](../backend/docs/abi-strategy-design.md) (seção 2).

**Critério de aceite**: os 4 behaviors calculando corretamente em cenários de teste dedicados; snapshot noturno gerando linha por `snapshot_date`; consulta de saldo numa data específica funcionando.

**Notas de execução** (2026-08-23, agente `kotlin-backend-dev`):
- Domain (`domain/balance/`): `BalanceBehavior` (enum `CREDIT`/`DEBIT`/`TRANSFER`/`DELTA`), `BalanceMonitorConfig`, `InvalidBalanceMonitorConfigException`/`BalanceMonitorConfigNotFoundException`, `BalanceMonitorConfigPort`, `BalanceDelta`, `BalanceBehaviorStrategy` (interface — implementações ficam em `application/balance/`, ver DEC-032), `DecodedNumbers.kt` (`Any?.toBalanceAmount(): BigInteger`, achado #1 do plano), `BalanceEventReading.kt` (`ZERO_ADDRESS` + `readWalletAddress`/`readTokenId`, helpers puros compartilhados pelas 4 strategies), `AssetBalance`/`AssetBalanceSnapshot`/`AssetBalancePort`.
- Application (`application/balance/`): `CreditBehavior`/`DebitBehavior`/`TransferBehavior`/`DeltaBehavior` (`@Component`, implementações de `BalanceBehaviorStrategy` — nunca lançam exceção por parâmetro de config ausente/incompatível, retornam lista vazia, ver DEC-034; `TransferBehavior` pula o endereço zero de qualquer lado, mint/burn); `BalanceProjectionService` (`@Service`, sem `@Transactional` próprio — roda dentro da transação já aberta por quem chama, exatamente como o plano previa); `BalanceMonitorConfigService` (CRUD + validação de campos por `behavior`); `BalanceSnapshotJob` (`@Component`, `@Scheduled(cron = "${indexer.balance.snapshot-cron}")`, guarda `cursor.current() < 0` → pula o snapshot se o poller nunca ingeriu nada).
- Pontos de integração (extensão de E4/E5, não módulo novo): `IngestionBatchProcessor.ingestRange()` ganhou `private val balanceProjectionService: BalanceProjectionService` no construtor, chama `projectBatch(toSave)` logo depois de `events.saveAll(toSave)` (só quando `toSave` não está vazio); `RedecodeBatchProcessor.processBatch()` idem, chama `projectBatch(changed)` logo depois de `events.updateAll(changed)`. Ambos os testes existentes (`IngestionBatchProcessorTest`/`RedecodeBatchProcessorTest`) atualizados com o novo parâmetro mockado (`relaxed = true`) + testes novos verificando a chamada.
- Persistência: `BalanceMonitorConfigEntity` (`Persistable<UUID>`, mesmo padrão DEC-018 de `MonitoredContractEntity`/`ContractAbiEntity`) + `BalanceMonitorConfigJpaRepository` + `JpaBalanceMonitorConfigAdapter`. `AssetBalanceEntity`/`AssetBalanceSnapshotEntity` — **sem** `Persistable<UUID>` (desvio deliberado do padrão DEC-018, conforme o plano): toda escrita via `@Modifying @Query` nativa (`ON CONFLICT`) em `AssetBalanceJpaRepository` (`upsertDelta`/`snapshotAll`), nunca `repository.save()`; `token_id` mapeado como `String` **não-nulo** (sentinela `''`, achado #2 do plano — `NULL` nunca colide consigo mesmo em `UNIQUE`/`ON CONFLICT`), conversão `domain (String?) ↔ coluna (String, '' = null)` só em `JpaAssetBalanceAdapter`. `balance` mapeado como `numeric(78,0)` explícito (`columnDefinition`) — suficiente pra qualquer `uint256`/`int256` (até 78 dígitos decimais), o mapeamento automático padrão do Hibernate pra `BigInteger` não garante precisão suficiente sem isso. `JpaAssetBalanceAdapter.applyDeltas` mescla a lista por chave `(wallet, contract, tokenId, network)` em memória (`groupBy` + soma `BigInteger`) antes de 1 upsert por chave já mesclada.
- **Achado real de teste, não previsto no plano** (ver DEC-033): `@Modifying` nativa com `ON CONFLICT` não invalida o 1º-level cache do Hibernate — uma leitura via `Specification`/JPQL que já carregou a mesma linha na mesma transação continua devolvendo o objeto cacheado desatualizado depois do `UPDATE` nativo. Fix: `@Modifying(clearAutomatically = true)` nos 2 métodos nativos de `AssetBalanceJpaRepository`. Pego pelo teste de integração `JpaAssetBalanceAdapterTest > snapshotAll ... idempotente pro mesmo dia`, que roda o job 2x na mesma transação de teste.
- Config: `BalanceProperties` (`indexer.balance.snapshot-cron`, default `0 0 0 * * *`/meia-noite) — mesmo padrão `@Component`+`@ConfigurationProperties` de `RedecodeProperties`/`BlockscoutProperties`. `application.yml` ganhou a seção `indexer.balance`.
- Spec OpenAPI: 2 tags novas (`BalanceMonitors`, `Balances`); `paths/balance-monitors.yaml` (`POST`/`GET`), `paths/balance-monitor-detail.yaml` (`GET`/`PUT`), `paths/balance-monitor-status.yaml` (`PATCH`), `paths/balances.yaml` (`GET`, saldo atual), `paths/balances-history.yaml` (`GET`, saldo numa data — `date` obrigatório). Schemas novos: `balance-monitor-config(-page)`, `create/update-balance-monitor-payload`, `update-balance-monitor-status-payload`, `balance-behavior` (enum), `asset-balance(-page)`, `asset-balance-snapshot(-page)`. **Campo `balance` é `string`, não `integer`, em `AssetBalance`/`AssetBalanceSnapshot`** — `uint256`/`int256` excedem a precisão segura de `number` em JSON; conversão `BigInteger.toString()`/`toDto()` só na borda (`BalancesApiServiceImpl`). `BalanceMonitorsApiServiceImpl`/`BalancesApiServiceImpl` (novos, finos) delegam pra `BalanceMonitorConfigService`/`AssetBalancePort` direto (sem serviço de aplicação intermediário pra consulta pura, mesmo padrão de `RedecodeApiServiceImpl`). `ApiExceptionHandler` ganhou `InvalidBalanceMonitorConfigException` → 400 `INVALID_BALANCE_MONITOR_CONFIG`, `BalanceMonitorConfigNotFoundException` → 404 `BALANCE_MONITOR_CONFIG_NOT_FOUND`.
- Testes (228 no total do projeto — 171 de E1-E6 + 57 novos): `BalanceBehaviorStrategyTest` (10, unit puro — os 4 behaviors, `toBalanceAmount()` com `Int`/`Long`/`BigInteger`/`String`/tipo inválido, `TransferBehavior` pulando endereço zero dos 2 lados, config mal formada → lista vazia), `BalanceProjectionServiceTest` (4, MockK — sem config/evento não-decodificado não gera delta, config ativa gera delta certo, 2 eventos do mesmo lote → 1 `applyDeltas` com os 2 deltas), `BalanceMonitorConfigServiceTest` (9, MockK — validação por `behavior`, CRUD básico), `JpaAssetBalanceAdapterTest` (7, `@DataJpaTest`+Testcontainers — acumulação entre chamadas, **teste específico do achado #2** provando que 2 deltas ERC20 com `tokenId=null` em chamadas separadas viram 1 linha só, mesclagem dentro de 1 chamada, lista vazia é no-op, cada filtro de `search`, `snapshotAll` idempotente pro mesmo dia + filtro por data em `searchHistory`), `JpaBalanceMonitorConfigAdapterTest` (6, `@DataJpaTest`+Testcontainers), `BalanceMonitorsApiTest` (12, `@SpringBootTest`+MockMvc+Testcontainers — CRUD completo, 400 de validação por `behavior`, 404), `BalancesApiTest` (8, idem — cada filtro, `balance` como string decimal no JSON, 400 sem `date` no history, 200 vazio pra data sem snapshot), `BalanceProjectionAcceptanceTest` (1, `@SpringBootTest`+MockMvc+Testcontainers+Awaitility, `@Async` real — o critério de aceite fim a fim: cadastrar config `TRANSFER`+ABI ERC20, semear evento raw de mint, `registerAbi()` decodifica via redecode automático de E5 → saldo correto em `GET /v1/balances` → `BalanceSnapshotJob.run()` chamado direto (não espera o cron) → linha nova em `GET /v1/balances/history?date=hoje`).
- **Achado real de teste, não previsto no plano** — contaminação de estado global entre testes: `BalanceProjectionAcceptanceTest` precisa do cursor de ingestão (`IngestionCursorPort`) avançado pra `BalanceSnapshotJob` não pular o snapshot (guarda `cursor.current() < 0`) — mas diferente da limpeza por endereço já estabelecida em `RedecodeAcceptanceTest`/DEC-026, o cursor é uma **linha global única** (DEC-002, não escopada por endereço). Avançar o cursor sem desfazer depois deixaria `JpaIngestionCursorAdapterTest > current returns -1 when nothing has been processed yet` quebrado pra sempre no container Postgres singleton compartilhado (mesma classe de bug pega no primeiro `./gradlew clean build` desta etapa). Fix: `@AfterEach` desta classe também deleta a linha `ingestion_cursor` (`id` fixo), restaurando o estado "nada processado ainda" que existia antes do teste rodar.
- `./gradlew clean build` verde, reconfirmado 3x seguidas (`clean build`, `clean build --rerun`, `clean test --rerun`), sem sinal de flakiness: bundle → generate → compile → **228/228 testes passando** → jar → build.
- **Desvio de escopo explícito, conforme combinado no plano**: eventos com parâmetro array (`TransferBatch` do ERC1155 — múltiplos deltas por evento) ficam **fora** desta etapa — `BalanceBehaviorStrategy` só lida com parâmetros escalares. `TransferSingle`/`Transfer`/eventos escalares (ERC20 e ERC1155 uma unidade por vez) cobrem o critério de aceite. Registrado como item futuro, não lacuna desta etapa.
- **Pendência explícita (mesma classe de E4/E5/E6)**: nunca validado contra Besu/Hardhat real (E12 ainda bloqueado, DEC-010) — a projeção de saldo em si não toca a rede (opera sobre `BlockchainEvent.decodedParameters` já persistido), então essa pendência é mínima aqui.
- **Pendências de design registradas em `docs/decisions.md`**: DEC-032 (implementações de `BalanceBehaviorStrategy` em `application/balance/`, não `adapters/outbound/abi/` como o texto literal de `hexagonal-architecture.md` sugeriria — Strategy sem I/O nenhum, mas precisa de `@Component` pro wiring por `Map`), DEC-033 (achado real do `clearAutomatically = true`), DEC-034 (config de saldo mal formada falha silenciosamente, por design, pra não derrubar a transação de ingestão/redecode inteira).
- **Critério de aceite da etapa cumprido**: os 4 behaviors calculando corretamente em cenários dedicados (`BalanceBehaviorStrategyTest`); snapshot noturno gerando linha por `snapshot_date`, idempotente (`JpaAssetBalanceAdapterTest`/`BalanceProjectionAcceptanceTest`); consulta de saldo numa data específica funcionando (`GET /v1/balances/history`, `BalancesApiTest`/`BalanceProjectionAcceptanceTest`).
- **Revisão (`reviewer`)**: 1 achado bloqueante corrigido na hora (ver DEC-035 para detalhe completo), sem outros achados bloqueantes. Confirmado: achado #1 do plano (`toBalanceAmount()` tolerante a `Int`/`Long`/`BigInteger`/`String`, testado explicitamente); achado #2 do plano (`token_id` sentinela `''` isolado em `JpaAssetBalanceAdapter`, prova de 2 deltas ERC20 `tokenId=null` em chamadas separadas → 1 linha só, `JpaAssetBalanceAdapterTest`); DEC-033 (`clearAutomatically = true`) aplicado nos 2 métodos nativos necessários (`upsertDelta`/`snapshotAll`), sem caminho de leitura stale restante; integridade transacional com E4/E5 correta — `BalanceProjectionService` sem `@Transactional` próprio, roda dentro da transação de `IngestionBatchProcessor.ingestRange()`/`RedecodeBatchProcessor.processBatch()`, nenhum saldo persistido fora da atomicidade já estabelecida; `TransferBehavior` pula o endereço zero dos 2 lados (mint/burn testados), endereços já normalizados lowercase pelo resto do pipeline; separação hexagonal íntegra (`domain/balance/` sem import de framework/JPA, confirmado por grep); DEC-032 (Strategies sem I/O em `application/balance/`, não `adapters/outbound/abi/`) avaliada como razoável — o wiring por `Map<BalanceBehavior, BalanceBehaviorStrategy>` exige bean Spring, e não há I/O real que justificasse um pacote `adapters/outbound/`; `BalanceMonitorsApiServiceImpl`/`BalancesApiServiceImpl` finas, sem regra de negócio; validação de `BalanceMonitorConfig` por `behavior` cobre os 2 sentidos (campo faltando e campo presente indevidamente) para `TRANSFER` e `CREDIT` (mesmo código de validação vale por igual para `DEBIT`/`DELTA`, não testados individualmente — não-bloqueante); critério de aceite cumprido (4 behaviors, snapshot por `snapshot_date` idempotente, consulta por data).
  **Achado bloqueante corrigido (DEC-035)**: DEC-034 documentava "`BalanceBehaviorStrategy` nunca lança exceção por config malformada" mas isso só valia pra parâmetro **ausente** — `toBalanceAmount()` lança `IllegalArgumentException`/`NumberFormatException` pra parâmetro **presente com tipo incompatível** (prova: o próprio teste do projeto, `BalanceBehaviorStrategyTest > toBalanceAmount throws for an unsupported type`), e nada capturava isso entre a Strategy e `BalanceProjectionService.projectBatch()`. Como `projectBatch()` roda dentro da mesma transação de ingestão/redecode, essa exceção não capturada fazia rollback do lote inteiro por causa de 1 config mal configurada — e como o cursor global (DEC-002) só avança na mesma transação, o efeito prático seria a ingestão **travar permanentemente** na mesma janela de blocos a cada ciclo do poller (o `catch (ex: Exception)` de `BlockchainPoller.poll()` evita o crash da aplicação, mas não recupera o progresso). Fix: `try/catch` centralizado em `BalanceProjectionService.projectBatch()` ao redor de `strategy.apply(...)`, log `WARN` com `config.id`/`contractAddress`/`eventName`/`txHash`/`logIndex` (endereça também a lacuna de visibilidade que a própria DEC-034 já reconhecia), delta tratado como vazio pra aquela config/evento, sem afetar o resto do lote. Teste novo (`BalanceProjectionServiceTest`) prova que um evento com parâmetro de tipo incompatível não impede o delta de outro evento do mesmo lote.
  `./gradlew clean build` reconfirmado verde 2x (incluindo `--rerun`, sem cache mascarando falha) depois do fix: **229/229 testes passando** (228 relatados pelo agente + 1 novo). Etapa pronta para ser marcada ✅ pela sessão principal.
- **Validação independente do build** (2026-08-23, sessão interativa, depois da revisão): `./gradlew clean build` re-executado do zero nesta sessão confirma de forma independente **229/229 testes passando**, `BUILD SUCCESSFUL`. Etapa marcada ✅.

---

## E8 — Webhooks (config + dispatcher + retry + log)

**Depende de**: E4 (dispara a partir de eventos decodificados na ingestão/redecode).

**Objetivo**: módulo completo do zero (doc §4.6) — não existe nada equivalente no código base.

**Escopo**:
- Config por usuário/contrato/tipo de evento + filtros opcionais (ex.: `value > X`).
- `WebhookDispatchPort` + implementação HTTP com fila, retry com backoff, log de tentativas/status de entrega.
- Payload customizável por webhook.
- Disparo integrado ao pipeline de ingestão/redecode (não pode ser síncrono bloqueando a ingestão).

**Docs de referência**: `../base/indexer-product-draft.md` §4.6.

**Critério de aceite**: configurar um webhook, gerar um evento que bate no filtro, ver o `POST` chegar (endpoint de teste), simular falha e ver o retry com backoff + log de tentativas.

**Notas de execução** (2026-08-23, agente `kotlin-backend-dev`):
- Plano completo aprovado com o usuário antes de implementar (payload customizável via `payloadTemplate` com placeholders `{{campo}}`/`{{decodedParameters.nome}}`, envelope JSON padrão quando ausente; fluxo de ponta a ponta explícito: evento salvo → `WebhookMatchingService.matchAndEnqueue()` na mesma transação → `WebhookDelivery` `PENDING` → `WebhookDispatchJob` `@Scheduled` dispara o `POST` de verdade fora da transação).
- Domain (`domain/common/`): `DecodedValueConversion.kt` — `fun Any?.toBigIntegerOrNull(): BigInteger?` promovido de `domain/balance/DecodedNumbers.kt::toBalanceAmount()` (E7), tolerante a `Int`/`Long`/`BigInteger`/`String` numérica, `null` (nunca lança) pra qualquer outro caso. `DecodedNumbers.kt::toBalanceAmount()` virou wrapper fino (`toBigIntegerOrNull() ?: throw IllegalArgumentException(...)`), mesmo contrato/exceção de antes — `BalanceBehaviorStrategyTest` (E7) passa sem alteração.
- **Achado real de teste, não previsto no plano** (DEC-036): o ramo `is String -> this.toBigIntegerOrNull()` da função acima causava `StackOverflowError` — chamada não qualificada dentro do próprio corpo resolve pra ela mesma (declaração do mesmo pacote tem prioridade sobre o import implícito de `kotlin.text.toBigIntegerOrNull()`, independente de `String` ser um receiver mais específico). Fix: `try { BigInteger(this) } catch (ex: NumberFormatException) { null }` direto, sem chamada de nome ambíguo. Pego pelos próprios testes na primeira rodada (`DecodedValueConversionTest`/`WebhookFilterConditionTest`/`BalanceBehaviorStrategyTest`/`WebhookMatchingServiceTest` todos falhavam com `StackOverflowError` antes do fix).
- Domain (`domain/webhook/`, pacote novo): `FilterOperator` (enum `GT`/`GTE`/`LT`/`LTE`/`EQ`/`NEQ`), `WebhookFilterCondition` (+ função pura `matches(parameters): Boolean` — GT/GTE/LT/LTE sempre numérico via `toBigIntegerOrNull()`, EQ/NEQ tenta numérico e cai pra comparação de `String`, nunca lança), `WebhookConfig`, `WebhookConfigPort`, `WebhookDelivery`/`WebhookDeliveryStatus`/`WebhookDeliveryAttempt`/`WebhookDeliveryPort`, `WebhookDispatchPort`+`WebhookAttemptResult`, `InvalidWebhookConfigException`/`WebhookConfigNotFoundException`. Nenhum import de Spring/JPA/Web3j — confirmado via grep antes de fechar a etapa.
- Application (`application/webhook/`): `WebhookMatchingService` (`@Service`, sem `@Transactional` próprio — roda dentro da transação de quem chama; `try/catch` por combinação evento×config, mesma cautela de DEC-035/E7 desde o início, log `WARN` com `config.id`/`event.txHash`/`event.logIndex`); `WebhookConfigService` (CRUD + valida `payloadTemplate` via `WebhookPayloadRenderer.validateTemplate()` no cadastro/edição); `WebhookDeliveryRecorder` (`@Component` separado, `@Transactional`, mesma razão de self-invocation já documentada pra `IngestionBatchProcessor`/E4 e `RedecodeBatchProcessor`/E5 — grava `WebhookDeliveryAttempt`, decide `DELIVERED`/`PENDING`+backoff/`FAILED` conforme `attemptCount` vs. `maxAttempts`); `WebhookDispatchJob` (`@Component`, `@Scheduled(fixedDelayString = "${indexer.webhook.dispatch-interval-ms}")`, sem guarda de concorrência própria — mesma garantia natural do `@Scheduled` já estabelecida em `BlockchainPoller`/`BalanceSnapshotJob`).
- Pontos de integração (extensão de E4/E5, mesmo padrão de E7): `IngestionBatchProcessor.ingestRange()` e `RedecodeBatchProcessor.processBatch()` ganharam `private val webhookMatchingService: WebhookMatchingService` no construtor (3º colaborador desses 2 arquivos agora — poller/redecode em si, saldo, webhook), chamado logo depois de `balanceProjectionService.projectBatch(...)`, mesma transação, nenhuma mudança na estrutura existente.
- Adapters `adapters/outbound/webhook/` (pacote novo): `WebhookPayloadRenderer` (`@Component`, `ObjectMapper()` Jackson 2 local — `renderDefault`/`renderTemplate`/`validateTemplate`; placeholders ocupam a posição **inteira** do valor JSON no template, sem aspas pré-existentes — o renderer decide sozinho se precisa aspas, chave desconhecida vira `null` bare); `WebhookHttpClient : WebhookDispatchPort` (Spring `RestClient`, `SimpleClientHttpRequestFactory` com `connect`/`readTimeout` = `indexer.webhook.request-timeout-ms`, qualquer exceção de rede/timeout/4xx/5xx vira `WebhookAttemptResult(success=false, ...)`, nunca propaga exceção crua).
- **Achado real de teste, não previsto no plano** (DEC-038): `WebhookHttpClientTest` não pôde seguir o mesmo mecanismo de `BlockscoutClientTest` (`MockRestServiceServer.bindTo(builder)`, E6) — `WebhookHttpClient` sobrescreve o `requestFactory` do `RestClient.Builder` injetado (pro timeout configurável), e essa sobrescrita sempre acontece depois de qualquer `bindTo()` do teste (só resolvida na 1ª chamada de `send()`, via `by lazy`), quebrando o mock de qualquer jeito. Testado em vez disso contra um servidor HTTP local real efêmero (`com.sun.net.httpserver.HttpServer`, JDK padrão, sem dependência nova) pros cenários 200/500, e `http://localhost:1` (sem listener) pro cenário de conexão recusada.
- Persistência (`adapters/outbound/persistence/`): `WebhookConfigEntity` (`Persistable<UUID>`, DEC-018, `filters` JSONB via `@JdbcTypeCode(SqlTypes.JSON)` mapeado como `String`), `WebhookDeliveryEntity` (`Persistable<UUID>`, atualizada in-place via `repository.save()` normal — sem desvio do padrão `Persistable`, diferente de `AssetBalanceEntity`/E7, aqui não há upsert por chave natural), `WebhookDeliveryAttemptEntity` (`Persistable<UUID>`, sempre `new=true`, só insert). `WebhookConfigJpaRepository`/`WebhookDeliveryJpaRepository` (+ `findDue` via `@Query` JPQL parametrizada por status/`nextAttemptAt`)/`WebhookDeliveryAttemptJpaRepository`. `JpaWebhookConfigAdapter`/`JpaWebhookDeliveryAdapter` seguindo os padrões já estabelecidos (`Specification` dinâmica pra `search`).
- **Achado real de teste, não previsto no plano** (DEC-037): `JpaWebhookConfigAdapter` precisou de `jacksonObjectMapper()` (registra `KotlinModule`, biblioteca já dependência do projeto desde E0) em vez de `ObjectMapper()` puro (padrão usado até agora por `AbiParser`/`JpaBlockchainEventAdapter`/`BlockscoutClient`) — `filters: List<WebhookFilterCondition>` é a primeira vez que o projeto desserializa uma **data class Kotlin própria** via Jackson 2 local (os outros `ObjectMapper()` só lidam com `Map`/`List`/`JsonNode` genéricos, que não precisam do módulo); sem isso, `InvalidDefinitionException` ("no Creators, like default constructor, exist") em todo round-trip de `filters`.
- Config: `WebhookProperties` (`indexer.webhook.{dispatch-interval-ms,dispatch-batch-size,max-attempts,base-delay-seconds,max-delay-seconds,request-timeout-ms}`, mesmo padrão `@Component`+`@ConfigurationProperties` de `BalanceProperties`/`RedecodeProperties`). `application.yml` ganhou a seção `indexer.webhook`.
- Spec OpenAPI: nova tag `Webhooks`; `paths/webhooks.yaml` (`POST`/`GET /v1/webhooks`), `paths/webhook-detail.yaml` (`GET`/`PUT /v1/webhooks/{id}`), `paths/webhook-status.yaml` (`PATCH /v1/webhooks/{id}/status`), `paths/webhook-deliveries.yaml` (`GET /v1/webhooks/{id}/deliveries`, com `attempts` aninhado por entrega). Schemas novos: `webhook-config(-page)`, `create/update-webhook-payload`, `update-webhook-status-payload`, `filter-condition`, `filter-operator` (enum), `webhook-delivery(-page)`, `webhook-delivery-status` (enum), `webhook-delivery-attempt`. `WebhooksApiServiceImpl` (novo, fino) delega pra `WebhookConfigService`/`WebhookDeliveryPort` direto (leitura pura de entregas não precisa de serviço de aplicação extra, mesmo padrão de `BalancesApiServiceImpl`). `ApiExceptionHandler` ganhou `InvalidWebhookConfigException`→400 `INVALID_WEBHOOK_CONFIG`, `WebhookConfigNotFoundException`→404 `WEBHOOK_CONFIG_NOT_FOUND`.
- **Desvio do plano — achado de compilação, não de design**: campo `url` (`format: uri` no spec) gera `java.net.URI` no DTO gerado, não `String` como o domínio (`WebhookConfig.url: String`) — `WebhooksApiServiceImpl` converte explicitamente (`.toString()`/`URI.create(...)`) nas 2 direções, mesmo padrão de conversão de borda já usado pra `balance: String` (E7)/enums.
- Testes (298 no total do projeto — 229 de E1-E7 + 69 novos): `DecodedValueConversionTest` (7, unit puro), `WebhookFilterConditionTest` (11, unit puro — os 6 operadores, fallback de string em EQ/NEQ, não lança pra parâmetro ausente/tipo incompatível), `WebhookPayloadRendererTest` (8, unit — envelope padrão, placeholders top-level e `decodedParameters.*`, placeholder desconhecido vira `null` bare, `validateTemplate` aceita/rejeita), `WebhookMatchingServiceTest` (6, MockK — sem config não enfileira, sem filtro sempre enfileira, com filtro só quando bate, evento não decodificado ignorado, erro numa config não impede as outras do mesmo lote), `WebhookDeliveryRecorderTest` (4, MockK — sucesso→`DELIVERED`, falha abaixo do teto→`PENDING`+backoff calculado, backoff dobra e satura em `maxDelaySeconds`, falha esgotando `maxAttempts`→`FAILED`), `WebhookDispatchJobTest` (4, MockK — só entregas devidas, delega resultado pro recorder, pula config inexistente sem lançar, nada devido não faz nada), `WebhookHttpClientTest` (3, servidor HTTP local real — 200/500/conexão recusada), `JpaWebhookConfigAdapterTest` (7, `@DataJpaTest`+Testcontainers — round-trip de `filters` via JSONB, `findActive`, `findAll`), `JpaWebhookDeliveryAdapterTest` (7, idem — `findDue` respeitando `nextAttemptAt`/status, `recordAttempt`+`findAttempts` ordenado, `search`), `WebhooksApiTest` (12, `@SpringBootTest`+MockMvc+Testcontainers — CRUD completo, 400 template malformado, 404, entregas), `WebhookAcceptanceTest` (1, `@SpringBootTest`+MockMvc+Testcontainers+Awaitility, `@Async` real — critério de aceite fim a fim: webhook configurado → evento decodificado via redecode automático de E5 → entrega `PENDING` → `dispatch()` com mock de sucesso → `DELIVERED` com 1 tentativa; cenário de falha: mock de erro 3x seguidas, manipulando `nextAttemptAt` direto no banco entre as chamadas pra não esperar o backoff real → `attemptCount=3`, `PENDING`, log de 3 tentativas visível via API).
- **Desvio do plano — mecanismo de teste, não de design** (DEC-039): `WebhookAcceptanceTest` sobrescreve `WebhookDispatchPort` via `@TestConfiguration` aninhada (`@Bean @Primary fun webhookDispatchPort() = mockk(relaxed = true)`, detectada automaticamente pelo `@SpringBootTest`) em vez de `@MockkBean` — essa anotação vem de `com.ninja-squad:springmockk`, nunca declarada no projeto (só `io.mockk:mockk` puro, DEC-009); efeito idêntico, sem dependência nova.
- `IngestionBatchProcessorTest`/`RedecodeBatchProcessorTest` (E4/E5) atualizados com o novo colaborador `WebhookMatchingService` (mock `relaxed = true`) + 1 nova asserção cada confirmando a chamada.
- `./gradlew clean build`/`./gradlew clean test --rerun` (3x seguidas, incluindo `--rerun`, sem cache mascarando falha nem flakiness do componente assíncrono): bundle → generate → compile → **298/298 testes passando** → jar → build, em todas as rodadas.
- **Pendência explícita (mesma classe de E4/E5/E6/E7)**: nunca disparado contra um endpoint HTTP de terceiro real fora de teste — validado via `WebhookHttpClientTest` (servidor HTTP local efêmero) e o teste de aceitação (mock de `WebhookDispatchPort`), mesmo limite já aceito desde E3 pra qualquer integração de rede externa (Besu/Blockscout/agora webhooks).
- **Pendência explícita nova**: `docs/decisions.md` DEC-022 (corrida de cadastro concorrente de ABI) segue sem fix — E8 não tocou `AbiRegistrationService`, sem mudança de risco.
- Decisões técnicas registradas em `docs/decisions.md`: DEC-036 (recursão infinita por colisão de nome com `kotlin.text`), DEC-037 (`jacksonObjectMapper()` pra desserializar data class própria), DEC-038 (`WebhookHttpClient` não testável via `MockRestServiceServer`, servidor local real em vez disso), DEC-039 (`@TestConfiguration`+`@Primary` em vez de `@MockkBean`/springmockk).
- **Não marcada como concluída** — aguardando revisão do `reviewer` antes da sessão principal marcar ✅, conforme instrução do agente.

**Revisão (`reviewer`, 2026-08-23)**: aprovada com 1 achado bloqueante (corrigido na hora) e ressalvas não-bloqueantes. Confirmado: a lição da DEC-035 aplicada desde o início em `WebhookMatchingService.matchAndEnqueue()` (try/catch por combinação evento×config, log `WARN`, nunca deixa exceção escapar pra dentro da transação de `IngestionBatchProcessor`/`RedecodeBatchProcessor` — teste dedicado provando isolamento); DEC-036 (fix de `toBigIntegerOrNull()`) correto, sem outro ponto de sombra de nome equivalente no projeto; separação HTTP/transação em `WebhookDispatchJob.dispatch()` correta (`send()` fora de transação, `recorder.recordAttempt()` `@Transactional` em bean separado, chamado depois); fórmula de backoff exponencial com teto exatamente `min(baseDelaySeconds * 2^(attemptCount-1), maxDelaySeconds)`; DEC-037 (`jacksonObjectMapper()`) correto, com teste de round-trip dedicado de `filters`; DEC-039 (`@TestConfiguration`+`@Primary`) mocka `WebhookDispatchPort` corretamente, cobre sucesso e falha/retry/backoff sem rede real; `WebhookPayloadRenderer` cobre envelope padrão/placeholders/placeholder desconhecido→`null` bare/`validateTemplate` no cadastro; fluxo ponta a ponta sem passo manual escondido; `domain/webhook`/`domain/common` sem import de framework; `WebhooksApiServiceImpl` fina; nenhuma violação de idioma de código/auth/campo `network` (exceto ressalva abaixo)/disparo síncrono.

**Achado bloqueante corrigido na hora** (ver DEC-040): `WebhookHttpClient` propagava exceção crua (`IllegalArgumentException`/`InvalidUrlException`, não `RestClientException`) para uma `url` sintaticamente aceita como `java.net.URI` no cadastro (ex.: `"not-a-url"`, URI relativa válida) mas inválida pra uma requisição HTTP real — contradizia o item 5 do escopo de revisão e o próprio contrato documentado da classe. Efeito: `WebhookDispatchJob.dispatch()` sem `try/catch` ao redor de `send()` abortava o ciclo `@Scheduled` inteiro (não só a config quebrada) a cada execução, indefinidamente, pra qualquer entrega devida do mesmo lote. Fix: `WebhookConfigService.validateUrl()` novo (rejeita no cadastro/edição, 400 `INVALID_WEBHOOK_CONFIG`) + `catch (ex: IllegalArgumentException)` adicional em `WebhookHttpClient.send()` (rede de segurança). Também corrigido um gap de cobertura de teste (`WebhookDeliveryRecorderTest` — nenhum teste exercitava de fato o `minOf()` truncando o backoff; teste novo adicionado com `attemptNumber=6` excedendo `maxDelaySeconds`). `./gradlew clean build`/`./gradlew clean test --rerun` (3x seguidas) verdes depois do fix — **302/302 testes passando** (298 do agente + 4 novos desta revisão).

**Ressalvas não-bloqueantes**: (1) `WebhookDelivery`/`WebhookDeliveryAttempt` não carregam `network` diretamente (só acessível via join com `webhook_config_id`/`webhookConfigId` → `WebhookConfig.network`) — desvio pontual da regra "campo `network` em todas as entidades", mas sem risco real de vazamento cross-network (nenhuma query dessas tabelas ocorre sem passar pelo `webhookConfigId`/config pai); mesma classe de exceção já aceita implicitamente por `IngestionCursorEntity` (E4, global por design). Não corrigido — não há query hoje que precise filtrar essas tabelas por `network` diretamente. (2) `WebhookDispatchJob.dispatch()` ainda não isola exceções por item individual dentro do loop (diferente de `WebhookMatchingService.matchAndEnqueue`) — uma falha futura do `WebhookDeliveryRecorder` (grava no banco, `@Transactional`) pra 1 entrega ainda abortaria o resto do lote daquele ciclo; não corrigido por falta de caso reproduzível conhecido (diferente do achado da `url`, que foi determinístico) — pendência pra E11 se aparecer um caso real. (3) Comentários/KDoc em português — padrão já consolidado desde E1/E2, não regressão desta etapa (mesma ressalva não-bloqueante repetida desde a revisão da E3).

Etapa pronta para ser marcada ✅ pela sessão principal.

**Validação independente do build** (2026-08-23, sessão interativa, depois da revisão): `./gradlew clean build` re-executado do zero nesta sessão confirma de forma independente **302/302 testes passando**, `BUILD SUCCESSFUL`. Etapa marcada ✅.

---

## E8.1 — Correções de backend + CORS (hardening pontual antes do frontend)

**Depende de**: E8.

**Objetivo**: etapa inserida a pedido do usuário, fora do roadmap original — antes de começar o
frontend (E9), resolver as pendências não-bloqueantes acumuladas nas revisões de E2-E8 e configurar
CORS no backend via Spring Security (não um `WebMvcConfigurer` solto — decisão tomada com o usuário
nesta sessão, alinhada com o que `backend/CLAUDE.md` já antecipava: "`WebSecurityConfig`: remover
[JWT/Keycloak do código base]; manter só CORS").

**Escopo**:
1. **CORS via Spring Security** — `spring-boot-starter-security` novo, `SecurityConfig`
   (`CorsConfigurationSource` a partir de `CorsProperties.allowedOrigins`, default
   `http://localhost:5173`) + `SecurityFilterChain` **100% permissivo** (`anyRequest().permitAll()`,
   CSRF desabilitado) — CORS é a única coisa que o filtro de fato faz, sem contradizer "sem
   autenticação/autorização na PoC" do `CLAUDE.md` raiz.
2. **DEC-022** (corrida de cadastro concorrente de ABI) — 1ª migration Flyway real do projeto:
   índice único parcial `contract_abis(contract_address) WHERE valid_to_block IS NULL` +
   `baseline-on-migrate`/`baseline-version` (`performance-and-ops-notes.md`) + handler de
   `DataIntegrityViolationException` → 409 no fluxo de cadastro de ABI.
3. **DEC-030** — nível de log do fallback engolido do Blockscout em `InterfaceDetectionService.suggest()`:
   `DEBUG` → `WARN`.
4. **`WebhookDispatchJob`** — isolar exceção por entrega dentro do loop de `dispatch()` (mesma lição
   de DEC-035/DEC-040, aplicada proativamente antes de um caso real).
5. **`network`** em `WebhookDelivery`/`WebhookDeliveryAttempt` — hoje só acessível via
   `webhookConfigId` → `WebhookConfig.network`; adiciona a coluna direto (via `ddl-auto`, sem
   migration).
6. **Cobertura de teste** — `BalanceMonitorConfigServiceTest`: `DEBIT`/`DELTA` testados
   individualmente (hoje só cobertos indiretamente via `CREDIT`).

Fora de escopo deliberadamente: tradução de comentários/KDoc pra inglês (item explícito de E11, não
regressão) e qualquer feature de produto nova.

**Docs de referência**: `docs/decisions.md` DEC-022/DEC-030/DEC-035/DEC-040 (o problema em cada
item), `backend/docs/performance-and-ops-notes.md` (baseline do Flyway), `backend/CLAUDE.md`
("O que precisa ser adaptado" — `WebSecurityConfig`).

**Critério de aceite**: `./gradlew clean build` verde com todos os testes novos; CORS funcionando de
verdade (origem configurada recebe `Access-Control-Allow-Origin`, `/actuator/health`/Swagger UI/
`/v1/**` continuam abertos sem exigir login depois do Spring Security novo); os demais 5 itens sem
retrabalho necessário quando E9/E10 começarem a bater nesse código.

**Notas de execução** (2026-08-23, agente `kotlin-backend-dev`):
- **Item 1 (CORS via Spring Security)**: `spring-boot-starter-security` novo em `build.gradle.kts`.
  `config/CorsProperties.kt` (`indexer.cors.allowed-origins`, default `http://localhost:5173`) +
  `config/SecurityConfig.kt` (`@Configuration @EnableWebSecurity`, `corsConfigurationSource` registrado
  só pras rotas sob `/v1` via `UrlBasedCorsConfigurationSource`, `securityFilterChain` com
  `.cors {...}` + `.csrf { it.disable() }` + `.authorizeHttpRequests { it.anyRequest().permitAll() }`).
  `application.yml` ganhou `indexer.cors.allowed-origins`.
- **Achado real de teste, não previsto no plano** (DEC-043, item 1): injetar `CorsConfigurationSource`
  por tipo puro em `securityFilterChain()` quebrou **80 de 316 testes** (todo `@SpringBootTest` do
  projeto, não só os novos) com `NoUniqueBeanDefinitionException` — o Spring MVC já registra
  `mvcHandlerMappingIntrospector` como um bean que também implementa `CorsConfigurationSource` (usado
  pra `@CrossOrigin`), ambíguo com o bean de `SecurityConfig`. Fix: `@Qualifier("corsConfigurationSource")`
  explícito no parâmetro.
- **Achado real, não previsto no plano** (DEC-043, item 2): mesmo com `securityFilterChain` definido,
  `spring-boot-starter-security` ainda disparava `UserDetailsServiceAutoConfiguration` (autoconfig
  diferente da que decide o filtro default), logando "Using generated security password..." a cada
  boot — sem efeito funcional (`permitAll()` nunca pede essa credencial), mas exatamente o tipo de
  ruído que o "ponto de atenção crítico" do plano pedia pra vigiar. Fix:
  `@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])` em
  `EvexIndexerApplication`. Validado via `bootRun` real contra o Postgres do `docker-compose.yml`: log
  limpo, sem o warning.
- Testes novos: `SecurityConfigTest` (4 — `/actuator/health`/`/v3/api-docs`/`/swagger-ui/index.html`/
  `GET /v1/contracts` todos 200 sem login) e `CorsConfigTest` (4 — origem configurada recebe
  `Access-Control-Allow-Origin`; origem não-configurada é rejeitada com 403, sem o header, confirmando
  que `DefaultCorsProcessor` rejeita requisição real — não só preflight — de origem não permitida;
  preflight `OPTIONS` com `Access-Control-Request-Method` funciona; requisição sem header `Origin`
  nunca é bloqueada pelo filtro CORS). Validado também via `curl` manual (`-H "Origin: ..." -X OPTIONS`)
  contra o `bootRun` real — ver confirmação explícita no relatório final da etapa.
- **Item 2 (DEC-022, 1ª migration Flyway real)**: `src/main/resources/db/migration/
  V1__contract_abis_unique_open_version.sql` (índice único parcial `contract_abis_unique_open_version`
  `ON contract_abis (contract_address) WHERE valid_to_block IS NULL`). `application.yml` ganhou
  `spring.flyway.baseline-on-migrate: true`/`baseline-version: 0`. Domain: `AbiVersionConflictException`
  (novo, `domain/abi/`) — `AbiRegistrationService.register()` captura `DataIntegrityViolationException`
  especificamente ao redor de `contractAbis.save(newAbi)` e traduz pra essa exceção; `ApiExceptionHandler`
  ganhou handler dedicado (`ABI_VERSION_CONFLICT`, 409) — **não** reaproveita o
  `@ExceptionHandler(DataIntegrityViolationException::class)` já existente (E2, `CONTRACT_ALREADY_EXISTS`)
  porque Spring não permite 2 handlers pro mesmo tipo de exceção no mesmo `@RestControllerAdvice` — ver
  DEC-042.
- **Achado real, não previsto no plano** (DEC-041, o mais substancial da etapa): investigado por que
  `spring.flyway.enabled: true` (em `application.yml` desde E0) nunca de fato criou
  `flyway_schema_history` em nenhum ambiente até agora — `spring-boot-autoconfigure-4.1.1.jar` não
  contém **nenhuma** classe Flyway (mesma modularização por tecnologia de DEC-019/020/028);
  `org.springframework.boot:spring-boot-flyway` (pacote `org.springframework.boot.flyway.autoconfigure`)
  nunca tinha sido declarado — só o driver `flyway-database-postgresql`, sem a integração Spring Boot.
  Adicionada a dependência — o que reintroduziu o problema real que motivou a investigação: a ordem
  padrão do Boot roda Flyway **antes** do Hibernate criar o schema (`ddl-auto: update`, DEC-006),
  então a migration V1 (que referencia `contract_abis`) falharia sempre que o banco começa vazio —
  **todo** teste de integração via Testcontainers, e qualquer 1º boot de ambiente novo. Fix:
  `config/PostSchemaFlywayMigrator.kt` (novo) — `FlywayMigrationStrategy` no-op (substitui o
  `.migrate()` automático, cedo demais) + `@Component` que dispara `flyway.migrate()` de verdade em
  `ApplicationReadyEvent` (depois do schema do Hibernate já existir). Validado via `bootRun` contra o
  Postgres real de dev (log: `EntityManagerFactory` inicializado → `AbiCache` warm-up → só depois
  `FlywayExecutor`/baseline v0/migração pra v1 com sucesso) e `psql \d contract_abis` confirmando o
  índice único parcial presente de fato. Detalhe completo em DEC-041 — inclui por que `@DataJpaTest`
  nunca roda essa migration (test slice não inclui `FlywayAutoConfiguration` por padrão), motivo pelo
  qual o teste de regressão da constraint foi colocado num `@SpringBootTest` (`ContractAbiApiTest`),
  não num `@DataJpaTest`.
- Testes novos: `AbiRegistrationServiceTest` (1, MockK — `contractAbis.save` lançando
  `DataIntegrityViolationException` é traduzido pra `AbiVersionConflictException`); `ContractAbiApiTest`
  (1, `@SpringBootTest` — insere diretamente uma 2ª linha aberta via `ContractAbiJpaRepository`,
  bypassando `AbiRegistrationService`, simulando deterministicamente a corrida sem depender de timing
  real de threads; `saveAndFlush` propaga `DataIntegrityViolationException` de verdade contra o
  Postgres real, provando que a migration/constraint funciona).
- **Item 3 (DEC-030)**: `InterfaceDetectionService.suggest()` — nível de log do fallback engolido do
  Blockscout, `DEBUG` → `WARN`. Sem asserção nova necessária (mudança de nível de log, não de
  comportamento) — `InterfaceDetectionServiceTest` continua verde sem alteração.
- **Item 4**: `WebhookDispatchJob.dispatch()` — `try/catch` por entrega dentro do loop (extraído pro
  método privado `dispatchSafely()`), log `WARN` com `delivery.id`, nunca aborta o ciclo `@Scheduled`
  inteiro. Teste novo em `WebhookDispatchJobTest` (1 — 3 entregas no lote, a do meio falha ao gravar
  via `recorder.recordAttempt` mockado lançando, as outras 2 são processadas normalmente).
- **Item 5 (`network` em `WebhookDelivery`/`WebhookDeliveryAttempt`)**: campo `network: Network`
  adicionado nas 2 data classes de domínio (denormalizado de `WebhookConfig.network` no `enqueue`,
  `WebhookMatchingService`; propagado de `WebhookDelivery.network` pra `WebhookDeliveryAttempt.network`
  em `WebhookDeliveryRecorder.recordAttempt`), `WebhookDeliveryEntity`/`WebhookDeliveryAttemptEntity`
  (coluna `network` via `@Enumerated(EnumType.STRING)`, criada via `ddl-auto: update` — sem migration,
  conforme o plano) e `JpaWebhookDeliveryAdapter` (mapeamento nas 4 funções `toEntity`/`toDomain`).
  Todos os call sites de teste que construíam `WebhookDelivery`/`WebhookDeliveryAttempt` diretamente
  (`WebhookDispatchJobTest`, `WebhookDeliveryRecorderTest`, `JpaWebhookDeliveryAdapterTest`) atualizados.
  Teste novo em `JpaWebhookDeliveryAdapterTest` (1 — round-trip de `network` nas duas tabelas).
- **Item 6 (`BalanceMonitorConfigServiceTest`)**: 2 testes novos (`register with a valid DEBIT config
  saves it...`/`... DELTA config saves it...`), espelhando o já existente de `CREDIT` — antes só
  cobertos indiretamente (mesmo branch de validação de `CREDIT`, nunca exercitado isoladamente).
- **Desvio de compilação, não de design, pego 2x pelo compilador** (mesma classe de bug de DEC-017,
  reincidente): 2 comentários KDoc novos continham o literal `/v1/**` — o `/*` embutido abre um
  comentário de bloco *aninhado* em Kotlin (comentários de bloco Kotlin são aninháveis, diferente de
  Java/C), consumindo o `*/` de fechamento real ("Unclosed comment", sem apontar a linha exata da
  causa). Corrigido em `config/CorsProperties.kt`/`config/CorsConfigTest.kt` reescrevendo sem o padrão
  literal dentro do comentário (descrito em palavras, ou movido pra dentro de crases só em código de
  verdade, nunca em prosa de comentário) — regra da DEC-017 generalizada aqui pra qualquer sequência
  `/*` (não só `*/`) embutida em comentário de bloco.
- **Desvio de compilação, não de design**: 2 nomes de teste continham `(E8.1)` — `.` (ponto) é
  caractere ilegal em nome de método JVM mesmo entre crases (identificador Kotlin com espaços via
  crase ainda vira nome de método real, sujeito às mesmas restrições da JVM). Renomeado pra `(E8-1)`.
- `./gradlew clean build`/`./gradlew clean build --rerun` (2x seguidas): bundle → generate → compile →
  **316/316 testes passando** → jar → build, em todas as rodadas. Validado também via `bootRun` real
  contra o Postgres do `docker-compose.yml` (E0): `GET /actuator/health` → 200, `GET /swagger-ui/
  index.html` → 200, `GET /v1/contracts` → 200 sem login em nenhum dos três; `curl -H "Origin:
  http://localhost:5173"` em `GET /v1/contracts` → `Access-Control-Allow-Origin` presente; `curl -X
  OPTIONS -H "Origin: ..." -H "Access-Control-Request-Method: POST"` → 200 com
  `Access-Control-Allow-Methods` listando `POST`; `curl -H "Origin: http://evil.test"` → 403, sem o
  header. `psql \d contract_abis` confirmou o índice `contract_abis_unique_open_version` presente no
  banco real depois do boot.
- Decisões técnicas registradas em `docs/decisions.md`: DEC-041 (Flyway nunca rodou de fato antes
  desta etapa — `spring-boot-flyway` ausente — + ordenação Flyway↔JPA invertida via
  `FlywayMigrationStrategy` no-op/`ApplicationReadyEvent`), DEC-042 (`AbiVersionConflictException`
  dedicada, não reaproveita o handler de `DataIntegrityViolationException` já existente), DEC-043
  (`@Qualifier` pro `CorsConfigurationSource` ambíguo com `mvcHandlerMappingIntrospector`;
  `UserDetailsServiceAutoConfiguration` excluída).
- **Não marcada como concluída** — aguardando revisão do `reviewer` antes da sessão principal marcar
  ✅, conforme instrução do agente.
- **Revisão (`reviewer`, 2026-08-23)**: aprovada com ressalvas não-bloqueantes, validada com
  verificação real (não só leitura de código) — `./gradlew clean build`/`--rerun` verdes 2x
  (316/316), `bootRun` real contra o Postgres do `docker-compose.yml` (`/actuator/health`/Swagger
  UI/`/v3/api-docs`/`GET /v1/contracts` → 200 sem login), CORS real via `curl` (origem configurada
  recebe `Access-Control-Allow-Origin`, `http://evil.test` → 403 sem o header, preflight `OPTIONS`
  → 200 com `Access-Control-Allow-Methods`), `psql \d contract_abis` confirmando o índice único
  parcial no banco, `select * from flyway_schema_history` confirmando baseline v0 + V1 aplicada com
  sucesso, ausência do warning "Using generated security password" no log de boot. Todos os 8 itens
  do escopo (CORS/Security, DEC-041, DEC-022/DEC-042, DEC-030, `WebhookDispatchJob`, `network` em
  `WebhookDelivery`/`Attempt`, cobertura `BalanceMonitorConfigServiceTest`, DEC-043) confirmados
  corretos, sem gambiarra escondendo sintoma.
  **Achado novo desta revisão (DEC-044, não-bloqueante)**: medindo o log de boot real, existe uma
  janela de ~110ms entre o Tomcat começar a aceitar tráfego TCP real (`WebServerStartStopLifecycle`,
  dispara em `finishRefresh()`) e o Flyway/`AbiCache` terminarem de rodar (ambos em
  `@EventListener(ApplicationReadyEvent::class)`, que só dispara **depois** do socket já estar
  aberto) — em teoria uma requisição real pra `/v1/**` poderia chegar nesse intervalo sem o índice
  da DEC-022 necessariamente existir ainda. Aceito como dívida técnica de baixo risco pra PoC
  (cadastro de ABI é operação administrativa de baixa frequência, sem orquestrador de deploy
  direcionando tráfego real na frente do boot) — fix já esboçado pra quando E11 tocar isso: trocar
  `PostSchemaFlywayMigrator` de `ApplicationReadyEvent` pra `SmartInitializingSingleton.afterSingletonsInstantiated()`,
  que roda antes do socket abrir.
  Ressalva secundária: cobertura de teste do DEC-022 é a combinação de um teste unit (tradução da
  exceção) + um teste de integração (constraint real do banco), não um único teste HTTP ponta-a-ponta
  emendando os dois — avaliado como aceitável, mesmo padrão já usado no projeto pra corridas difíceis
  de reproduzir com timing real (E5/DEC-026).
- **Validação independente do build** (2026-08-23, sessão interativa, depois da revisão): `./gradlew clean build` re-executado do zero nesta sessão confirma de forma independente **316/316 testes passando**, `BUILD SUCCESSFUL`. Etapa marcada ✅.

---

## E9 — Frontend: esqueleto + cliente gerado

**Depende de**: E2 (precisa de pelo menos 1 recurso real na API para gerar cliente).

**Objetivo**: projeto Vite/React/TS rodando, cliente `typescript-axios` gerado a partir do mesmo `.yaml` do backend, roteamento base.

**Escopo**:
- `npm create vite@latest` (React 19 + TS strict).
- Tailwind v4, React Router v7, TanStack Query v5 configurados.
- Geração do cliente via `openapi-generator` (`typescript-axios`) apontando pro mesmo `.yaml`.

**Docs de referência**: [`../frontend/CLAUDE.md`](../frontend/CLAUDE.md).

**Critério de aceite**: app sobe (`npm run dev`), 1 chamada real à API do backend via TanStack Query funcionando (ex.: listar contratos).

**Notas de execução** (2026-08-24, agente `frontend-dev`):
- Scaffold: `npm create vite@latest vite-scaffold -- --template react-ts --eslint` numa pasta temporária
  (fora do repo), mesclado manualmente para dentro de `frontend/` preservando o `CLAUDE.md` já
  existente (o comando recusaria/pediria confirmação num diretório não-vazio). Flag `--eslint`
  necessária — o template atual do `create-vite` (v9.1.2, Vite 8, TS ~6.0.2) usa `oxlint` por
  padrão, sem `eslint.config.js`; sem a flag, `@typescript-eslint/no-explicit-any` (regra do
  `frontend/CLAUDE.md`) não estaria ativo. 3 divergências do template real vs. o assumido pelo
  plano, todas corrigidas e detalhadas em `docs/decisions.md` DEC-045: `--eslint` explícito,
  `"strict": true` ausente por padrão em `tsconfig.app.json` (setado explicitamente), e
  `erasableSyntaxOnly: true` (novidade do template) incompatível com TS parameter properties do
  cliente gerado (`typescript-axios`/`src/api/base.ts`) — removido do `tsconfig.app.json`.
- Tailwind v4: `@tailwindcss/vite` como plugin do `vite.config.ts` + `@import "tailwindcss";` em
  `src/index.css` — sem `tailwind.config.js`/`postcss.config.js`, conforme o plano.
- React Router v7: pacote `react-router` (não `react-router-dom`). `src/routes/RootLayout.tsx` (1
  layout raiz, `<Outlet />`) + `src/routes/router.tsx` (`createBrowserRouter`, 1 rota `/` → prova de
  conceito de contratos) — roteamento completo das 4 telas é escopo da E10.
- Bundle + cliente gerado: scripts `bundle:api` (`redocly bundle
  ../backend/src/main/resources/swagger/base/base.yaml -o openapi/evex-api.bundled.yaml`, mesma
  ferramenta do backend/DEC-012, aqui rodada pelo frontend por conta própria — decisão já tomada com
  o usuário antes de começar) e `generate:api` (`bundle:api` + `openapi-generator-cli generate -g
  typescript-axios -o src/api`). Flags do gerador ajustadas por tentativa/inspeção real do output
  (não estavam fechadas no plano): sem `npmName` (evita gerar `package.json`/`tsconfig.json`/
  `README.md` de publicação — confirmado que sem esse parâmetro o gerador não os escreve, "Required
  to generate a full package"); `--global-property=apiTests=false,apiDocs=false,modelTests=false,
  modelDocs=false` (suprime a pasta `docs/*.md`); e um `rm -rf` final dos arquivos de scaffold que
  ainda sobram mesmo assim (`git_push.sh`, `.gitignore`, `.npmignore`, `.openapi-generator-ignore`,
  `.openapi-generator/`) — resultado limpo: só `api.ts`/`base.ts`/`common.ts`/`configuration.ts`/
  `index.ts` (gerados) + `config.ts` (handwritten). `predev`/`prebuild` encadeiam `generate:api` antes
  de `dev`/`build`, confirmado funcionando (`npm run dev` gera o cliente automaticamente antes de
  subir o Vite).
- `src/api/config.ts` (handwritten) — exporta `apiConfiguration = new Configuration({ basePath:
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080' })`. `.gitignore` de `src/api/`
  precisou de exceção explícita (`src/api/*` + `!src/api/config.ts`) para não perder esse arquivo do
  controle de versão junto com o resto do diretório gerado — detalhado em `docs/decisions.md`
  DEC-046.
- TanStack Query v5: `QueryClientProvider`/`QueryClient` em `src/main.tsx` (envolvendo o
  `RouterProvider`). `src/features/contracts/useContracts.ts` — hook `useQuery` chamando
  `ContractsApi.listMonitoredContracts()` do cliente gerado, sem `useEffect`.
  `src/features/contracts/ContractsPage.tsx` — renderiza a lista (`loading`/`error`/vazio/conteúdo),
  sem estilização/UX real (Tailwind só o mínimo para o texto não ficar colado — a tela de verdade é
  escopo da E10).
- `vite.config.ts`: plugin React + plugin Tailwind v4 + `server.proxy` (`/v1`, `/actuator` →
  `localhost:8080`) documentado no próprio arquivo como conveniência **opcional**, não crítica (CORS
  da E8.1 já resolve o caso principal) — o cliente gerado aponta direto para `localhost:8080` via
  `VITE_API_BASE_URL`, não usa o proxy por padrão.
- `.env.example` (`VITE_API_BASE_URL=http://localhost:8080`). `.gitignore` do frontend:
  `node_modules/`, `dist/`, `openapi/`, `src/api/*` (exceto `config.ts`), `.env`/`.env.local`.
- `openapitools.json` (criado automaticamente pelo `@openapitools/openapi-generator-cli` ao rodar
  pela primeira vez, pinando a versão do JAR do gerador em `7.24.0` — mesma versão que o backend usa,
  DEC-008) mantido e versionado — não é um artefato de execução, é config de pin de versão,
  equivalente em espírito ao `gradle/wrapper` do backend.
- **Verificação executada nesta sessão** (Docker Desktop disponível, Postgres já rodando de sessão
  anterior): `docker compose up -d` (container já `healthy`) + `./gradlew bootRun` em `backend/`
  (porta 8080) → `GET /actuator/health` 200. Contrato de teste criado via `curl POST
  /v1/contracts` (`0x1234567890abcdef1234567890abcdef12345678`, "Token de Teste E9", `TESTNET`) →
  201. `cd frontend && npm install && npm run dev` (via `predev`, gera o cliente automaticamente) →
  Vite sobe em `http://localhost:5173` sem erro.
  CORS real validado via `curl` simulando o browser: preflight `OPTIONS` com `Origin:
  http://localhost:5173` → 200 com `Access-Control-Allow-Origin: http://localhost:5173`
  /`Access-Control-Allow-Methods`; `GET /v1/contracts` real com o mesmo header `Origin` → 200 com
  `Access-Control-Allow-Origin` presente e o corpo JSON esperado.
  Prova visual real (não só `curl`): Chromium via Playwright (`npx playwright install chromium`,
  headless, sem dependência nova no projeto — só usado para esta validação pontual, fora do
  `package.json`) navegou até `http://localhost:5173/`, capturado (a) a resposta de rede real
  `GET http://localhost:8080/v1/contracts` → `200` (cross-origin, porta 5173 → porta 8080, sem
  proxy), (b) o texto renderizado na página incluindo o endereço/nome/rede/status do contrato
  cadastrado via `curl`, (c) nenhum erro de console (só logs informativos do Vite HMR/React
  DevTools), (d) screenshot confirmando visualmente "Contratos monitorados" com o card do contrato
  — fecha o ciclo Vite→React Router→TanStack Query→cliente gerado→CORS→backend→Postgres descrito no
  critério de aceite, de ponta a ponta e cross-origin de verdade.
  `npm run build` (`tsc -b && vite build`, inclui `generate:api` via `prebuild`) e `npm run lint`
  (ESLint) confirmados limpos, sem erro/warning, em execução isolada (sem o backend rodando) e depois
  de novo com o backend rodando — mesmo resultado.
- Processos de dev (Vite, `bootRun`) encerrados ao final da verificação; container Postgres deixado
  rodando (estado pré-existente desta sessão, não iniciado por esta etapa).
- **Pendência explícita para a E10**: nenhuma tela real ainda (só a prova de conceito de contratos,
  sem paginação/filtros/cadastro/UX) — as 4 telas da UI mínima (contratos, eventos, saldos,
  webhooks) são o escopo completo da próxima etapa.
- **Não marcada como concluída** — aguardando revisão do `reviewer` antes da sessão principal marcar
  ✅, conforme instrução do agente.
- **Revisão (`reviewer`, 2026-08-24)**: aprovada, sem achado bloqueante. Validado de forma
  independente (não só relato do agente): `npm run build` e `npm run lint` limpos nesta sessão;
  backend (`./gradlew bootRun`) + frontend (`npm run dev`) subidos de fato, contrato de teste criado
  via `curl`, CORS cross-origin confirmado via `curl -H "Origin: http://localhost:5173"` batendo em
  `localhost:8080` (preflight `OPTIONS` e `GET` reais, `Access-Control-Allow-Origin` presente nos
  dois); `.gitignore` de `src/api/` (`src/api/*` + `!src/api/config.ts`, DEC-046) testado num repo
  Git descartável — confirma que só `config.ts` fica rastreado e o resto do diretório gerado
  continua ignorado; nenhum `useEffect`/`fetch`/`axios` direto/`any` explícito em código handwritten;
  `@typescript-eslint/no-explicit-any` confirmado como `error` (não `warn`) na config ativa;
  `"strict": true` de fato presente e efetivo em `tsconfig.app.json`; Tailwind v4 real (plugin do
  Vite + `@import "tailwindcss"`, sem `tailwind.config.js`/`postcss.config.js` residual); estrutura
  de pastas batendo com `frontend/CLAUDE.md`. Achado único, não-bloqueante, cosmético: `index.html`
  declara `lang="en"` enquanto todo o texto visível da UI é português (`frontend/CLAUDE.md`: "idioma
  da UI/documentação: português") — não corrigido pelo `reviewer` por ser trivial e não travar a
  etapa, mas vale ajustar para `lang="pt-BR"` na E10 quando as telas de verdade forem escritas.
  Remoção de `erasableSyntaxOnly` do `tsconfig.app.json` (DEC-046) avaliada como correção pontual e
  localizada — a flag restringe a um subconjunto de sintaxe TS "type-strippable" sem transformação
  (relevante para runtimes que fazem só remoção de tipos, ex.: Node nativo), não é uma checagem de
  tipo/soundness; como o projeto sempre passa pelo `esbuild`/Vite para build real, removê-la não abre
  brecha em nenhuma verificação que `strict: true` (mantida) já não cubra.
- **Validação independente** (2026-08-24, sessão interativa, depois da revisão): `npm run build`
  (`tsc -b && vite build`, inclui `predev`/`generate:api` reexecutando bundle+geração do zero) e
  `npm run lint` re-executados nesta sessão, ambos limpos (build gerou `dist/` sem erro; lint sem
  nenhuma saída = zero violações). Etapa marcada ✅.

---

## E10 — Frontend: telas (contratos, eventos, saldos, webhooks)

**Depende de**: E9 + os endpoints backend correspondentes (E2/E3 para contratos, E4/E5 para eventos, E7 para saldos, E8 para webhooks — pode ser feito em paralelo, tela por tela, conforme o backend destrava cada recurso).

**Escopo**: as 4 telas da UI mínima da PoC (doc §6).

**Docs de referência**: [`../frontend/CLAUDE.md`](../frontend/CLAUDE.md), `../base/indexer-product-draft.md` §6.

**Critério de aceite**: as 4 telas navegáveis e funcionais contra o backend real (sem mocks).

**Notas de execução** (2026-08-24, agente `frontend-dev`):
- Escopo implementado à risca conforme o plano aprovado na sessão de planejamento (incluindo as 4
  decisões de escopo explícitas: sem lib de formulário nova, ABI dentro da tela de contratos,
  config de saldo dentro da tela de saldos, discovery Blockscout como atalho opcional).
- `components/` novos: `Pagination.tsx` (page/size/anterior/próximo/total), `ApiErrorAlert.tsx`
  (extrai `error.response?.data.message` de erros do axios via `isAxiosError<ApiError>`, fallback
  genérico), `NetworkSelect.tsx` (`<select>` MAINNET/TESTNET, com/sem opção "Todas").
- `routes/RootLayout.tsx` — barra de navegação com os 4 links (`NavLink`, destaque de rota ativa).
  `routes/router.tsx` — `/` redireciona pra `/contracts`; rotas novas: `/contracts`,
  `/contracts/new`, `/contracts/:address`, `/events`, `/balances`, `/webhooks`, `/webhooks/:id`.
- `features/contracts/`: `useContracts.ts` (estendido da E9 — `useContracts`/`useContract`/
  `useCreateContract`/`useUpdateContract`/`useUpdateContractStatus`/`useContractAbiVersions`/
  `useRegisterAbi`/`useInterfaceSuggestions`/`useVerifiedContracts`), `ContractsListPage.tsx`
  (substitui a `ContractsPage.tsx` da E9, removida), `ContractFormPage.tsx` (form + atalho de
  importação do Blockscout), `ContractDetailPage.tsx` (dados + status + delega a edição pra um
  `ContractEditForm` interno), `AbiSection.tsx` (lista de versões + form com 4 origens: colar
  texto/arquivo/Blockscout/detecção automática).
- `features/events/`: `useEvents.ts` (`useEvents(filters, page, size)`), `EventsPage.tsx` (form de
  filtro + tabela paginada com linha expansível pra `decodedParameters`, tx hash truncado com
  `title` mostrando o valor completo, "raw" quando `decoded=false`).
- `features/balances/`: `useBalances.ts` (`useBalances`/`useBalanceHistory`),
  `useBalanceMonitors.ts` (`useBalanceMonitors`/`useCreateBalanceMonitor`/
  `useUpdateBalanceMonitor`/`useUpdateBalanceMonitorStatus`), `BalancesPage.tsx` (compõe as duas
  seções abaixo), `BalanceQuerySection.tsx` (toggle saldo atual/numa data, tabela com `balance`
  exibido como veio da API — string decimal, nunca `Number()`/`parseFloat`, conforme a regra do
  plano sobre precisão de uint256/int256), `BalanceMonitorSection.tsx` (tabela + form com campos
  condicionais por `behavior`: TRANSFER mostra `fromParam`/`toParam`, CREDIT/DEBIT/DELTA mostra
  `walletParam`).
- `features/webhooks/`: `useWebhooks.ts` (`useWebhooks`/`useWebhook`/`useCreateWebhook`/
  `useUpdateWebhook`/`useUpdateWebhookStatus`/`useWebhookDeliveries`), `WebhookForm.tsx` (form
  compartilhado entre cadastro e edição — `filters` como lista dinâmica de
  `{paramName, operator, value}` com adicionar/remover linha, `payloadTemplate` opcional com
  placeholder de exemplo), `WebhooksListPage.tsx` (lista + form de criação), `WebhookDetailPage.tsx`
  (config + toggle de status + edição + tabela de entregas com linha expansível mostrando
  `attempts[]`).
- `index.html`: `lang="en"` → `lang="pt-BR"` — item cosmético não-bloqueante apontado na revisão da
  E9 (idioma da UI é português), corrigido nesta etapa já que o arquivo estava sendo tocado de novo.
- **Desvio do plano, achado pelo próprio lint**: a primeira versão de `ContractDetailPage.tsx`
  inicializava o form de edição (`name`/`description`/`version`) via `useState('')` + `useEffect`
  sincronizando com `contract.data` assim que a query resolvia — padrão comum, mas
  `eslint-plugin-react-hooks` (regra `react-hooks/set-state-in-effect`, ativa por padrão no
  `reactHooks.configs.flat.recommended` já configurado desde a E9) barra isso como **erro**, não
  warning (cascading renders). Refatorado para extrair um componente interno `ContractEditForm`
  que só é montado depois que `contract.data` já existe (render condicional), inicializando o
  `useState` a partir das props direto (sem efeito) — com `key={contract.data.address}` no ponto de
  uso, pra garantir remount correto (reinicializar o form) se o usuário navegar para outro endereço
  de contrato sem sair da tela de detalhe. Sem esse `key`, o `useState` não seria resincronizado ao
  trocar de contrato (React reaproveitaria a instância do componente). Não achado documentado antes
  em nenhum `CLAUDE.md`/doc — vale registrar como padrão do projeto para qualquer form futuro que
  precise inicializar estado local a partir de dado assíncrono de uma query.
- **Verificação executada nesta sessão** (Docker Desktop disponível, Postgres já rodando de sessão
  anterior com dados residuais de E9/revisões passadas): `docker compose up -d` (container já
  `healthy`) + `./gradlew bootRun` em `backend/` → `/actuator/health` 200. `npm run dev` em
  `frontend/` (via `predev`, regenera o cliente) → Vite em `http://localhost:5173`.
  `npm run build` (`tsc -b && vite build`) e `npm run lint` (ESLint) confirmados limpos, zero erros
  e zero warnings, com e sem o backend rodando.
  Prova real de ponta a ponta via Chromium/Playwright (reaproveitando a instalação já feita na E9,
  headless, fora do `package.json` do projeto — só uso pontual de validação, mesmo padrão da E9):
  navegação real pelas 4 telas com interação de formulário de verdade, confirmando requisições de
  rede reais cross-origin (`localhost:5173` → `localhost:8080`, sem proxy) e o resultado
  renderizado na tela:
  - **Contratos**: listagem (`GET /v1/contracts`) mostrando os 2 contratos residuais de E9;
    filtro por rede (`network=MAINNET` → vazio; `network=TESTNET` → mostra os residuais) confirmado
    funcionando; cadastro de um novo contrato (`POST /v1/contracts` → 201) navegando pro detalhe;
    seção de importação do Blockscout tratando o 503 (`GET /v1/discovery/blockscout/contracts`)
    como aviso "não disponível", não erro — mesmo comportamento esperado desde E6/DEC-010 (nenhuma
    instância Blockscout real configurada nesta PoC); edição de nome/descrição/versão
    (`PUT /v1/contracts/{address}` → 200); toggle de status ACTIVE→PAUSED
    (`PATCH .../status` → 200, refletido na tela e nos botões desabilitados corretamente).
  - **ABI** (dentro do detalhe do contrato): cadastro via "colar texto" com uma ABI ERC20 mínima
    (evento `Transfer`) → `POST .../abi` → 201, nova versão aparecendo na tabela
    (`validFromBlock=0`/`vigente`/`UPLOAD_TEXT`/`DATABASE`); cadastro via "enviar arquivo" (`.abi`
    real lido via `File.text()` no browser, sem multipart, conforme decisão do plano) → 201,
    `source=UPLOAD_FILE` refletido corretamente; "detectar automaticamente" → botão chama
    `GET .../abi/suggestions`, mostra "Nenhuma interface padrão reconhecida" (esperado: sem eventos
    reais ingeridos — E12/DEC-010 bloqueado — e sem Blockscout disponível como fallback, não há
    `topic0` pra comparar contra os templates built-in) e desabilita corretamente o botão de
    confirmação (regra "só habilita quando há sugestão FULL").
  - **Eventos**: tela renderiza filtros e tabela vazia ("Nenhum evento encontrado") — esperado, sem
    poller real rodando contra rede nenhuma (log real do `bootRun` confirma
    `Connection refused`/`ConnectException` nas tentativas de `eth_getLogs` — mesma pendência de
    E12/DEC-010, o Hardhat local ainda não foi importado). Não deu pra validar visualmente linha
    expansível de `decodedParameters`/evento raw vs. decodificado com dado real por esse motivo —
    validado só via leitura de código + tipos (schema `Event` do cliente gerado).
  - **Saldos**: seção de consulta renderiza filtros (atual/numa data) e "Nenhum saldo encontrado"
    (esperado, sem saldo projetado por falta de eventos reais); seção de configuração — cadastro de
    um `BalanceMonitorConfig` com `behavior=TRANSFER` (`POST /v1/balance-monitors` → 201) mostrando
    corretamente os campos condicionais `fromParam`/`toParam` (não `walletParam`), refletido na
    tabela.
  - **Webhooks**: cadastro (`POST /v1/webhooks` → 201) aparecendo na lista; navegação pro detalhe
    (`GET /v1/webhooks/{id}`); edição via o mesmo `WebhookForm` reaproveitado; toggle de status;
    seção de entregas mostrando "Nenhuma entrega registrada ainda" (esperado — sem eventos reais
    decodificados batendo no filtro do webhook, não há entrega enfileirada; mesma pendência de
    E12/DEC-010). Não deu pra validar visualmente a linha expansível de `attempts[]` com dado real
    por esse motivo — validado só via leitura de código + tipos.
  - Nenhum erro de console inesperado durante toda a navegação (só o 503 esperado do Blockscout,
    tratado pela própria tela); nenhuma chamada HTTP manual fora do cliente gerado; nenhum
    `useEffect` disparando chamada de API em nenhuma tela.
  Processos de dev (Vite, `bootRun`) encerrados ao final da verificação; container Postgres deixado
  rodando (estado pré-existente desta sessão, não iniciado por esta etapa).
- **Pendência explícita, não desta etapa**: fluxo completo "evento real → saldo projetado → webhook
  entregue" não pôde ser validado ponta a ponta com dado de blockchain real — depende de E12
  (import do Hardhat local, hoje `⛔ Bloqueado`, DEC-010). As telas de Eventos/Saldos/Webhooks estão
  prontas pra exibir esse dado assim que ele existir (schemas e queries já corretos, validado via
  cadastro manual de config que aceita o formato esperado); só não há como provar visualmente a
  renderização de uma linha de evento decodificado/saldo projetado/entrega de webhook sem rede real
  ou sem inserir fixture manual no banco (fora do escopo desta etapa de frontend).
- **Não marcada como concluída** — aguardando revisão do `reviewer` antes da sessão principal marcar
  ✅, conforme instrução do agente.
- **Revisão (`reviewer`, 2026-08-24)**: aprovada, sem achado bloqueante. Checklist de início de
  sessão seguido (roadmap E9/E10 completo, `decisions.md` até DEC-047, `CLAUDE.md` raiz,
  `frontend/CLAUDE.md`) antes de revisar. Validado de forma independente (não só relato do agente):
  `npm run build` e `npm run lint` limpos nesta sessão; `grep` confirma zero `fetch(`/`axios.` fora
  de `src/api/`, zero `any` explícito, zero `Number()`/`parseFloat()`/`parseInt()` em cima de
  `balance` (exibido sempre como veio da API); `AbiSection.tsx` confirmado desabilitando o submit de
  `source=AUTO_DETECTED` até haver sugestão `level=FULL` (mesma regra do backend,
  `AbiRegistrationService`); `ContractFormPage.tsx`/`useVerifiedContracts` confirmados tratando o 503
  de `GET /v1/discovery/blockscout/contracts` via estado `unavailable` (não `isError`) — mensagem de
  aviso, não erro vermelho; `BalanceMonitorSection.tsx` confirmado replicando a regra condicional de
  campos por `behavior` (TRANSFER → `fromParam`/`toParam`, CREDIT/DEBIT/DELTA → `walletParam`);
  `index.html` com `lang="pt-BR"` confirmado (fecha o achado não-bloqueante da revisão da E9);
  `RootLayout`/`router.tsx` confirmados com as 4 rotas principais + sub-rotas
  (`/contracts/new`, `/contracts/:address`, `/webhooks/:id`) e nav bar linkando as 4.
  Backend (`./gradlew bootRun`) + frontend (`npm run dev`) subidos de fato nesta sessão (Postgres já
  `healthy`, estado pré-existente) — navegação real via Chromium/Playwright (mesmo padrão de
  E8.1/E9): as 4 telas renderizando, cadastro de contrato (`POST /v1/contracts` → 201) navegando pro
  detalhe, seção de ABI com detecção automática mostrando corretamente "Nenhuma interface padrão
  reconhecida" e submit desabilitado, filtros condicionais do form de saldo alternando
  `fromParam`/`toParam` ⇄ `walletParam` ao trocar `behavior` no `<select>`, discovery Blockscout
  mostrando o aviso "não disponível" (confirmado via `curl` que o endpoint responde 503 de fato), e
  as 2 webhooks de teste cadastradas via `curl` aparecendo na listagem. Único erro de console
  observado durante a navegação foi o próprio 503 do Blockscout logado pelo Chrome (comportamento
  padrão do browser para qualquer resposta não-2xx, não uma falha da aplicação) — sem nenhum outro
  erro/warning inesperado.
  **Investigação adicional sobre o padrão da DEC-047** (`ContractEditForm`, componente filho montado
  condicionalmente + `key` pra reinicializar o form ao trocar de contrato): verificado se o mesmo
  padrão foi aplicado de forma consistente em `WebhookDetailPage.tsx`, que tem uma situação análoga
  (`<WebhookForm initialValues={webhook.data} .../>`, `useState` inicializado a partir de `props` sem
  efeito) mas **sem** `key={webhook.data.id}` explícito — hipótese de que, ao navegar entre duas
  páginas de detalhe de webhook já cacheadas pelo TanStack Query (`goBack`/`goForward` do browser,
  sem passar pela listagem), o form pudesse reter valores do webhook anterior por falta de remount
  explícito. Reproduzido com um teste dirigido (Playwright + instrumentação temporária de
  `data-mount-id` via `useRef`, revertida depois): **não é um bug na prática** — o componente
  remonta em toda navegação entre webhooks distintos mesmo sem `key` explícito (confirmado por um
  `mountId` novo a cada visita), porque a própria condicional `{webhook.data && (...)}` já força
  unmount/remount do `WebhookForm` nesse fluxo. Não bloqueante, mas registrado como observação de
  robustez: o padrão fica implícito (depende de `webhook.data` de fato passar por `undefined`
  durante a troca de `id`, o que hoje acontece mas não é garantido por nenhum contrato explícito) em
  vez de explícito como em `ContractEditForm` — recomendação não-bloqueante para hardening futuro:
  adicionar `key={webhook.data.id}` em `<WebhookForm>` dentro de `WebhookDetailPage.tsx`, deixando o
  comportamento correto por construção (mesmo padrão documentado em DEC-047) em vez de por
  coincidência de implementação do React Router/TanStack Query.
  Estrutura de pastas batendo com `frontend/CLAUDE.md`; sem CSS modules/styled-components; sem
  `console.log`/`debugger` esquecido; nenhum `useEffect` fazendo fetch (o único `useEffect` que o
  `grep` inicial acusou em `ContractDetailPage.tsx` era um falso positivo — a palavra aparece só num
  comentário explicando por que **não** foi usado `useEffect`, não uma chamada real).
  Processos de dev (Vite, `bootRun`) encerrados ao final da verificação; container Postgres deixado
  rodando (estado pré-existente).
- **Validação independente** (2026-08-24, sessão interativa, depois da revisão): `npm run build`
  (`tsc -b && vite build`) e `npm run lint` re-executados nesta sessão, ambos limpos. Etapa marcada ✅.

---

## E11 — Testes/hardening cross-cutting

**Depende de**: todas as etapas de backend/frontend relevantes já implementadas.

**Objetivo**: fechar a cobertura de teste (JUnit5+MockK+Testcontainers no backend), revisar tudo contra as regras dos `CLAUDE.md` via `reviewer`.

**Docs de referência**: todos os `backend/docs/*.md` (cenários de teste já listados em `ingestion-pipeline-reference.md`).

**Notas de execução — itens 1-6** (2026-08-24, agente `kotlin-backend-dev`; item 7, revisão cross-cutting completa via `reviewer`, ainda pendente — etapa **não** marcada ✅ por instrução explícita):

- **Item 1 — idioma dos comentários (DEC-048)**: `CLAUDE.md` raiz atualizado — "Regras gerais" agora
  distingue explicitamente idioma de identificadores (inglês) do idioma de comentários/KDoc inline
  (português, mesmo idioma da documentação solta). Formaliza a prática já consolidada desde E1/E2 (o
  `reviewer` já tinha apontado essa divergência entre regra escrita e prática real como achado
  não-bloqueante na revisão da E3). Nenhum comentário existente foi traduzido — decisão tomada com o
  usuário antes da execução (custo/risco de traduzir ~50 arquivos já revisados, sem ganho real).
- **Item 2 — `WebhookForm` `key` explícito**: `frontend/src/features/webhooks/WebhookDetailPage.tsx`
  ganhou `key={webhook.data.id}` em `<WebhookForm>`, mesmo padrão já usado em `ContractEditForm`
  (`key={contract.data.address}`, DEC-047). Achado não-bloqueante da revisão da E10 (`reviewer` já
  tinha confirmado que não era bug hoje — a condicional `{webhook.data && (...)}` já força
  unmount/remount — só recomendação de tornar o comportamento correto por construção, não por
  coincidência de implementação).
- **Item 3 — `WARN` em resultado vazio inesperado (resíduo de DEC-034)**: `BalanceProjectionService
  .applySafely()` agora loga `WARN` também quando uma config **ativa** (já bateu
  `contractAddress`+`eventName`) processa um evento e a `BalanceBehaviorStrategy` correspondente
  devolve lista vazia — antes desta etapa, só o caso de exceção (DEC-035, parâmetro presente mas de
  tipo incompatível) logava; o caso de parâmetro **ausente** (`?: return emptyList()` nas 4
  implementações) continuava 100% silencioso. Mesmos campos de contexto já usados em DEC-035
  (`config.id`/`contractAddress`/`eventName`/`txHash`/`logIndex`).
- **Item 4 — rate-limit/dedupe do `WARN` (resíduo de DEC-035)**: nova classe
  `application/common/WarnLogDeduper.kt` (plain Kotlin, sem Spring) — `ConcurrentHashMap<UUID,
  Instant>` local, atualizado só quando de fato manda logar (não a cada chamada — cuidado documentado
  em DEC-049, sem isso um fluxo contínuo de eventos nunca deixaria o intervalo "vencer"). Nova
  property `indexer.warn-log-dedupe-interval` (`IndexerProperties.warnLogDedupeInterval`, default
  5min) — instanciado internamente por `BalanceProjectionService` e `WebhookMatchingService` (um
  `WarnLogDeduper` por classe, rate-limitando por `config.id`). Ver design completo em DEC-049.
- **Item 5 — fecha DEC-044 (janela Tomcat×Flyway/AbiCache)**: `config/PostSchemaFlywayMigrator.kt` —
  `PostSchemaFlywayMigrator` migrado de `@EventListener(ApplicationReadyEvent::class)` pra
  `SmartInitializingSingleton.afterSingletonsInstantiated()` (fix já esboçado na própria DEC-044, só
  não aplicado antes por prudência). Validado com `bootRun` real: `FlywayExecutor`/`DbMigrate`
  completam antes do `Tomcat started` (antes do fix era o oposto, ~110ms de janela real medidos na
  DEC-044). Só `PostSchemaFlywayMigrator` foi tocado — o warm-up do `AbiCache` continua em
  `ApplicationReadyEvent`, fora do escopo explícito desta etapa (pendência registrada em DEC-050 pra
  quem quiser fechar por completo no futuro). Ver detalhes/alternativas em DEC-050.
- **Item 6 — auditoria de cobertura contra `ingestion-pipeline-reference.md`**: conferência
  cenário-a-cenário (não escrita de teste do zero) — resultado: cobertura **confirmada completa**
  pras seções §1 (`PollWindow`), §2 (auto-shrink), §3 (idempotência) e §5 (garantia de delta único,
  propriedade estrutural). §4 (reindexação em lotes): critério de parada coberto explicitamente
  (`RedecodeRunnerTest`); "evento sem `block_timestamp` fica adiado" não se aplica à arquitetura do
  Evex (timestamp sempre síncrono na ingestão, E4 — diferente do código base Anbima com 3 backfills
  separados); **1 gap real encontrado e fechado**: "paginação processa em lotes menores que o total
  pendente sem duplicar/pular linhas" só tinha cobertura indireta (loop mockado em
  `RedecodeRunnerTest` + ordenação isolada em `JpaBlockchainEventAdapterTest`), nunca as duas coisas
  juntas contra Postgres real — novo teste em `RedecodeAcceptanceTest`
  (`redecode processes more pending rows than a single batch, decoding all of them exactly once`, 5
  linhas pendentes/`batchSize=2`, loop real via `RedecodeBatchProcessor.processBatch` chamado
  diretamente, prova >1 lote disparado e soma exata sem duplicar/pular). Detalhe completo da auditoria
  em DEC-051.
- **Testes novos**: `WarnLogDeduperTest` (5, unit puro — `now` injetável, sem `Thread.sleep`),
  `BalanceProjectionServiceTest` (+4 — `WARN` em resultado vazio, dedupe do `WARN` de resultado vazio,
  dedupe do `WARN` de exceção, log de novo após o intervalo expirar via `IndexerProperties` com
  intervalo curtíssimo), `WebhookMatchingServiceTest` (+1 — dedupe do `WARN` existente),
  `RedecodeAcceptanceTest` (+1 — paginação multi-lote real, item 6). Novo helper de teste
  `support/LogCaptor.kt` (`ListAppender` do logback-classic, já transitivo via
  `spring-boot-starter-logging` — sem lib nova) pra asserção direta sobre linhas de log `WARN`
  emitidas/suprimidas, em vez de inferir só pelo efeito colateral (`applyDeltas`/`enqueue` não
  chamado).
- **`./gradlew clean build`**: verde — bundle→generate→compile→test→jar→build, **327/327 testes
  passando** (316 herdados de E8.1 + 11 novos). `npm run build`/`npm run lint` (frontend): ambos
  limpos.
- **Validação adicional do item 5 nesta sessão** (Docker Desktop disponível, Postgres do
  `docker-compose.yml` já `healthy`): `bootRun` real, log com timestamp em ms — `FlywayExecutor:
  Database: ...` às `14:13:53.470`, `DbMigrate: Schema "public" is up to date` às `14:13:53.529`,
  `Tomcat started on port 8099` só às `14:13:53.578` (Flyway termina **antes** do Tomcat abrir o
  socket — ordem invertida em relação à medição original da DEC-044, onde Tomcat abria ~110ms antes).
- **Sem desvios do plano aprovado** — os 6 itens foram implementados exatamente como desenhados na
  sessão de planejamento; nenhuma surpresa de causa raiz a documentar além do que já está detalhado em
  DEC-048/DEC-049/DEC-050/DEC-051.
- **Pendência explícita**: item 7 (revisão cross-cutting completa via `reviewer`) ainda não
  executado — etapa permanece 🔲/não marcada ✅ até isso acontecer, por instrução explícita do escopo
  desta tarefa.
- **Item 7 — revisão cross-cutting completa (`reviewer`, 2026-08-24)**: **aprovada sem ressalvas** —
  a última revisão do roadmap disponível, sem nenhum achado bloqueante, não-bloqueante ou pendência
  nova. `./gradlew clean build` (327/327) e `npm run build`/`npm run lint` (frontend) confirmados
  verdes pelo próprio `reviewer`; `bootRun` real confirmou de forma independente a ordem de boot do
  item 5 (Flyway termina antes do Tomcat abrir o socket). Os 4 pontos de auditoria do escopo:
  (1) campo `network` — auditadas as 10 entidades JPA do projeto, todas com `network` exceto
  `IngestionCursorEntity` (exceção documentada, cursor global); achado incidental: a lista de
  exceções do próprio enunciado da revisão estava desatualizada (`WebhookDelivery`/
  `WebhookDeliveryAttempt` já têm `network` desde E8.1, não são mais exceção) — a favor do projeto,
  não um problema; (2) `Persistable<UUID>` (DEC-018) — aplicado consistentemente em todas as
  entidades com `id` gerado em `application`, as 2 exceções documentadas (`AssetBalanceEntity`/
  `AssetBalanceSnapshotEntity`, upsert nativo) e `IngestionCursorEntity` continuam corretas, nenhuma
  divergência não documentada; (3) separação hexagonal — `grep` nos 9 pacotes `domain/*` por
  `org.springframework`/`jakarta.persistence`/`org.web3j`/`org.hibernate`/`com.fasterxml.jackson`:
  zero ocorrências em todo o projeto; (4) frontend sem HTTP manual — `grep` por
  `fetch(`/`axios.`/`XMLHttpRequest` em todo `frontend/src`: única ocorrência é o arquivo gerado
  (`src/api/common.ts`), zero em `features`/`components`/`routes`. Confirmação dos itens 1-6
  (implementados pelo `kotlin-backend-dev` nesta sessão): todos corretos por leitura direta do
  código — `CLAUDE.md` atualizado, `key` do `WebhookForm`, log `WARN` de `BalanceProjectionService`
  cobrindo os 2 casos (parâmetro ausente e tipo incompatível), `WarnLogDeduper` com
  `ConcurrentHashMap.compute()` atômico, `PostSchemaFlywayMigrator` via `SmartInitializingSingleton`
  validado com boot real, teste novo de `RedecodeAcceptanceTest` exercitando paginação multi-lote
  real (5 linhas, `batchSize=2`, sem duplicar/pular). Sweep adicional (além do escopo formal):
  nenhum `@RestController` manual, as 7 `*ApiServiceImpl` finas, nenhum `TODO`/`FIXME` esquecido.
- **Validação independente do build** (2026-08-24, sessão interativa, depois da revisão):
  `./gradlew clean build` e `npm run build`/`npm run lint` re-executados do zero nesta sessão,
  confirmando de forma independente **327/327 testes passando** e frontend limpo. Etapa marcada ✅.

---

## E12 — Integração com Hardhat local + validação end-to-end

**Depende de**: criação do ambiente `local-env/` dentro deste repositório (Hardhat via scaffold-eth-2 + Blockscout + contratos de teste) — **não depende mais de import externo** (ver DEC-052, que revê a premissa da DEC-010: o usuário decidiu não seguir com o repo Hardhat da empresa, optando por um ambiente público, melhor para apresentar o projeto).

**Objetivo**: rodar o pipeline completo (poller → decode → reindexação → saldos → webhooks) contra contratos reais de teste, não só fixtures/mocks.

**Escopo**:
- Montar `local-env/` (estrutura, contratos de teste, docker-compose do Blockscout) — ver [`../local-env/CLAUDE.md`](../local-env/CLAUDE.md).
- Rodar o nó Hardhat (via scaffold-eth-2 clonado à parte) + deploy dos contratos de teste.
- Apontar o backend (`indexer.blockchain.rpc-url`) para o nó local e validar o poller/decodificação contra transações reais.
- Validar discovery via Blockscout (E6) contra os contratos de teste deployados.

**Notas de execução** (2026-08-27):
- Etapa desbloqueada nesta sessão: decisão de montar ambiente próprio em vez de importar repo externo (DEC-052). Trabalho dividido em etapas com aprovação manual entre elas:
  - **Etapa 1** (estrutura + docs base do módulo `local-env/`) — concluída.
  - **Etapa 2** (clone do scaffold-eth-2 + `yarn install` via Corepack + análise da estrutura real: deploy via `rocketh`, `deployedContracts.ts` auto-gerado, chainId 31337, UI de debug em `/debug`) — concluída.
  - **Etapa 3** (plano concreto + implementação: os 4 contratos de teste em `local-env/contracts/` — `TestERC20`/`TestERC721`/`TestERC1155`/`TestEventEmitter`, script de deploy `rocketh`, script de seed `local-env/seed/register-contracts.ts` chamando a API do Evex, `local-env/blockscout/README.md` documentando o Blockscout oficial via `hardhat-network.yml` — DEC-053 corrigiu a suposição inicial da DEC-052 sobre como desabilitar trace interno) — concluída.
  - **Etapa 4** (execução real de ponta a ponta) — **núcleo validado**: `yarn chain` + deploy dos 4 contratos + backend do Evex (Postgres real) + seed via API confirmaram poller→decode→redecode→API funcionando com dados reais (`Transfer` e `WidgetCreated` decodificados corretamente em `GET /v1/events`, ver DEC-054). **Blockscout com pendência**: `hardhat-network.yml` sobe mas o container `backend` dele crash-loopa (erro de SSL na conexão com o Postgres interno do Blockscout — DEC-054), discovery real (E6) segue não validado contra Blockscout de verdade. Todos os containers/processos desta rodada foram encerrados ao final da sessão. Passo a passo pra rodar manualmente está em `local-env/README.md`.
  - **Etapa 5** (investigação do crash-loop, a pedido do usuário) — causa raiz encontrada em DEC-055: bug do Postgrex/`db_connection` empacotado na imagem publicada (`9.0.2`, travada há ~1 ano).
  - **Etapa 6** (resolução real, DEC-056) — usuário questionou a conclusão da DEC-055; compilar o Blockscout do source (`11.2.8`, HEAD) **resolveu o bug de fato** (migração limpa, sem SSL). Um segundo bug (permissão em volume `dets/`/`logs/`, dono `root` vs. processo não-root) também corrigido. **Discovery E6 validado contra Blockscout real** (não stub): fallback de detecção de interface via `GET /api/v2/addresses/{address}/logs` testado de ponta a ponta com um `safeMint` real no `TestERC721`, resultado `ERC721`/`ERC20` `PARTIAL` correto. Pendência isolada e não-bloqueante: verificação de contrato (`rocketh-verify blockscout`) incompatível com a API v2 real (desencontro entre ferramentas de terceiros, não bug do Blockscout) — `listVerifiedContracts`/`getAbi` origem `BLOCKSCOUT` seguem não validados contra instância real.
  - Receita completa de build+run em `local-env/blockscout/README.md`; stub HTTP (`local-env/blockscout-stub/`) mantido como alternativa mais leve, documentado mas não mais necessário como Plano A.
  - **Etapa 7** (DEC-057) — verificação de contrato fechada via chamada direta à API v2 real (`local-env/blockscout/verify-contracts.ts`, sem depender do `rocketh-verify`). Isso revelou e corrigiu **2 bugs reais no `BlockscoutClient.kt`** (não só do ambiente de teste): `filter=verified` inválido no endpoint de listagem, e `address` sendo um objeto aninhado (`{hash, name}`) em vez de string plana. As **3 integrações da E6 estão validadas contra Blockscout real**: discovery (`GET /v1/discovery/blockscout/contracts`), ABI via Blockscout (`source=BLOCKSCOUT`), e fallback de detecção de interface via logs. `./gradlew clean build` reconfirmado verde.
  - Etapa ✅ pros fins do `local-env/` e da integração Blockscout (E6) — todas as pendências anteriores fechadas. **E12 como um todo** (rodar o pipeline completo contra Hardhat local de forma permanente/CI) segue como trabalho contínuo de qualquer nova etapa futura que precisar validar contra rede real, não uma etapa fechada de uma vez só.
  - **Etapa 8** (DEC-058, consolidação operacional) — `local-env/docker-compose.yml` (Blockscout + Postgres do Evex, num arquivo só) + `local-env/scripts/up.sh`/`down.sh` (copia contratos, deploya, sobe docker, sobe os 2 frontends em background). Backend do Evex e frontends ficam de fora do compose de propósito (debug na IDE; frontends rodam como processo de host pra não quebrar binários nativos do `node_modules` do scaffold-eth-2 numa imagem Alpine). Dia a dia passa de ~6-7 terminais manuais pra 2 (`yarn chain` + `./scripts/up.sh`). Validado rodando de ponta a ponta nesta sessão.

**Notas de execução** (2026-08-28):
  - **Etapa 9** (DEC-059, otimização — usuário reportou o `up.sh` pesado demais no dia a dia) — dois cortes:
    1. `local-env/seed/emit-events.ts` (novo) dispara `mint`/`safeMint`/`mintBatch`/`createWidget`/`changeStatus`/`processBatch` direto via ethers (mesma conta deployer padrão do Hardhat), lido de `deployedContracts.ts` como `register-contracts.ts` já fazia. Substitui a UI `/debug` do scaffold-eth-2 (Next.js) como caminho padrão de disparo de eventos — `up.sh` não sobe mais esse frontend, só o do Evex.
    2. Blockscout (Elixir + Postgres próprio + Redis, a peça mais pesada do ambiente) virou opt-in: `profiles: ["blockscout"]` nos 5 serviços dele em `docker-compose.yml`, `up.sh` sobe só `evex-postgres` por padrão. `scripts/blockscout-up.sh`/`blockscout-down.sh` (novos) sobem/derrubam só o Blockscout sob demanda — decisão do usuário de manter esse gerenciamento separado do `up.sh`/`down.sh` ("deixei o blockscout como um script que pode ser executado depois que já estiverem de pé, aí subo e derrubo conforme a necessidade").
  - `local-env/README.md`/`CLAUDE.md` atualizados pro fluxo novo. **Validado de ponta a ponta nesta mesma sessão** (Docker Desktop ficou disponível no meio do trabalho — antes só validação manual do YAML): `docker compose config` (com e sem `--profile blockscout`) limpo; `up.sh` completo rodado 2x (deploy idempotente reusando endereços na 2ª vez, `emit-events.ts` com os 7 tx confirmados on-chain, só `evex-postgres` no Docker, frontend do Evex `200` em `:5173`); `down.sh` derrubando tudo de novo; `blockscout-up.sh` subindo os 4 containers do Blockscout com a API real respondendo `200` de primeira; `blockscout-down.sh` confirmado não tocando `evex-postgres`. **2 bugs reais achados e corrigidos** durante essa validação (detalhados na DEC-059): frontend ficava órfão depois do `down.sh` (fix: `setsid` + matar o grupo de processos) e `blockscout-down.sh` derrubava `evex-postgres` junto por engano (`docker compose down` ignora profile — fix: listar os serviços do Blockscout explicitamente). Etapa 9 fechada — ambos os scripts (`up.sh`/`down.sh` e `blockscout-up.sh`/`blockscout-down.sh`) testados de ponta a ponta, não só revisados.
  - **Etapa 10** (DEC-061, achado do usuário usando o ambiente depois da Etapa 9) — usuário reportou log recorrente (`debug_traceTransaction` / `Unable to find a block containing transaction ...`) no terminal do `yarn chain`. Causa raiz: Postgres do Blockscout é bind mount em disco (sobrevive a restart do Hardhat, que é in-memory) — indexer ficava preso reprocessando trace de transações de uma chain antiga que não existe mais. Fix: `blockscout-up.sh` agora limpa `blockscout-db-data/`/`redis-data/` antes de subir (container `alpine` root descartável). Reproduzido e confirmado: 0 ocorrências do erro depois do fix (antes, dezenas por minuto).

---

## E13 — Frontend: migração pra MUI + telas de Contratos/Eventos mais didáticas

**Depende de**: E10 (telas já existentes, em Tailwind).

**Objetivo**: trocar a estilização de Tailwind por MUI (decisão consciente do usuário, revisa a regra "Tailwind, sem CSS-in-JS" — ver DEC-060) nas 4 telas existentes, e usar essa migração pra deixar a tela de Contratos mais didática/segregada com modais, e a tela de Eventos com os parâmetros decodificados apresentados de forma legível (não mais `JSON.stringify` cru).

**Escopo**:
- Instalar MUI (`@mui/material` + `@emotion/react`/`@emotion/styled`, versão compatível com React 19) e remover Tailwind (`tailwindcss`, config, todas as classes `className="..."` utilitárias) do projeto.
- **Contratos** (`ContractsListPage`/`ContractDetailPage`/`ContractFormPage`/`AbiSection`): reorganizar em seções mais claras, usar `Dialog` (modal) do MUI para ações pontuais (cadastrar contrato, cadastrar nova versão de ABI, ver sugestões de detecção automática) em vez de tudo inline na página. Incluir estado vazio explicativo pro discovery via Blockscout (deixar claro que só lista contrato **verificado** no Blockscout — achado da sessão 2026-08-28, ver DEC-059/README `local-env`).
- **Eventos** (`EventsPage`): trocar o `<pre>{JSON.stringify(evt.decodedParameters, null, 2)}</pre>` por uma apresentação formatada por tipo de parâmetro (endereço truncado/copiável, número com separador de milhar, booleano como chip/ícone, string/array direto) — decisão de layout fica com o `frontend-dev`.
- **Saldos** e **Webhooks**: migração 1:1 de Tailwind pra MUI (sem redesign funcional pedido, só troca de estilização) — mesma rodada, pra não deixar 2 sistemas de estilo coexistindo no projeto.

**Notas de execução** (2026-08-28): etapa aberta nesta sessão, a partir de feedback do usuário testando o `local-env/` (tela de contratos "pouco didática", discovery do Blockscout sem feedback claro do porquê estar vazio, decode de eventos "cru" na lista). Delegada ao `frontend-dev`.

**Notas de execução** (2026-08-28, agente `frontend-dev`):
- Dependências: `@mui/material@9.4.0` + `@mui/icons-material@9.4.0` + `@emotion/react@11.14.0` + `@emotion/styled@11.14.1` instaladas (`@mui/material` declara suporte oficial a React 19 no peerDependencies desde a v9); `tailwindcss`/`@tailwindcss/vite` removidos de `package.json`, `vite.config.ts` (plugin) e `index.css` (`@import "tailwindcss"` trocado por um reset mínimo — `CssBaseline` do MUI cobre o resto). `main.tsx` ganhou `ThemeProvider`/`CssBaseline` em torno do `QueryClientProvider`; tema único em `src/theme.ts` (paleta neutra, sem customização elaborada — é uma PoC). Nenhuma classe `className="..."` utilitária restante em `src/` (confirmado via grep) — as 17 `.tsx` pré-existentes + os 5 arquivos novos desta etapa (`ContractFormDialog`, `AbiRegisterDialog`, `AbiSuggestionsDialog`, `DecodedParamValue`, `CopyableHash`, `theme.ts`) saíram 100% em componentes MUI.
- **Achado técnico não previsto**: MUI v9 removeu os "system props" atalho (`alignItems`/`justifyContent`/`flexWrap`/`gap` em `Stack`; `fontWeight`/`fontFamily`/`fontSize`/`display` em `Typography`) que existiam em v5/v6 — só sobrou `sx` pra isso (confirmado inspecionando `Stack.d.ts`/`Typography.d.ts` em `node_modules`, não documentado no changelog consultado antes de começar). Todo uso desses props precisou migrar pra dentro de `sx={{ ... }}` — sem isso `tsc -b` falhava com ~20 erros `TS2769` espalhados pelas 4 telas. Registrado como DEC-062 pra não redescobrir isso numa manutenção futura.
- **Contratos** (maior redesign, ver DEC-060): `ContractFormPage.tsx` (rota `/contracts/new`) removida — cadastro de contrato virou `ContractFormDialog` (novo), aberto via botão "Novo contrato" em `ContractsListPage`, com a lista de importação do Blockscout lado a lado dentro do próprio modal (`Grid` 2 colunas). `AbiSection` ganhou 2 modais novos: `AbiRegisterDialog` (as mesmas 4 origens de antes — colar texto/arquivo/Blockscout/detecção automática — agora em `Dialog`) e `AbiSuggestionsDialog` (preview somente-leitura das sugestões de detecção automática, dissociado do fluxo de cadastro — usuário pode "espiar" sem se comprometer a cadastrar). `ContractDetailPage` reorganizada em `Card`s separados (status via `ToggleButtonGroup`, dados do contrato, ABI). Estado vazio do discovery Blockscout (0 contratos verificados, não "unavailable") agora é um `Alert severity="info"` explicando que o discovery só lista contrato **verificado** (não é erro) e apontando `local-env/blockscout/verify-contracts.ts` como próximo passo em ambiente local (texto lido de `local-env/README.md` §5.1, não inventado).
- **Eventos**: `decodedParameters` no schema OpenAPI é `type: object, additionalProperties: true` — **sem tipo por parâmetro** (vira `{ [key: string]: any }` no cliente gerado, confirmado em `src/api/api.ts`). Diferente do que a descrição da etapa presumia ("o schema... já carrega isso"), não há metadado de tipo por campo vindo do backend — a formatação em `DecodedParamValue` (novo) decide o tipo de exibição pelo **tipo em runtime** do valor já decodificado (buscado em `AbiDecodingAdapter.formatValue` no backend: `BigInteger`→número, `String`→string/endereço, `Boolean`→boolean, `ByteArray`→hex string, `List`→array), não por um schema explícito. Regras aplicadas: endereço (`0x` + 40 hex) e hex genérico longo → `CopyableHash` (truncado + tooltip + copiar, componente novo reaproveitado também na tx hash da própria tabela e no endereço de contrato); string só de dígitos → agrupamento de milhar **sem** passar por `Number`/`parseInt` (uint256/int256 excedem `Number.MAX_SAFE_INTEGER`, mesmo cuidado já em `BalanceQuerySection.balance`); `number` nativo → `Intl.NumberFormat('pt-BR')`; `boolean` → `Chip` Sim/Não; array → recursivo. Parâmetros expandidos agora em tabela (`Parâmetro`/`Valor`) dentro de `Collapse`, não mais `<pre>{JSON.stringify(...)}</pre>`.
- **Achado sinalizado, não corrigido (fora do escopo desta etapa)**: `decodedParameters` é serializado pelo Jackson padrão do backend (sem customização de `BigInteger`, confirmado por `grep` em `backend/src/main/kotlin` — nenhum serializer/config customizado) — valores `BigInteger` grandes (ex.: `uint256` de token com 18 casas decimais) viram literal numérico JSON, e o `JSON.parse` do axios no frontend os transforma em `number` do JS **com perda de precisão silenciosa** acima de `Number.MAX_SAFE_INTEGER` (2^53). Isso já valia antes desta etapa (não é regressão) — a formatação nova só torna o número mais legível, não resolve a perda de precisão upstream. Não corrigido aqui por ser decisão de contrato/serialização do backend (fora do escopo deste agente) — sinalizado para avaliação futura (ex.: serializar `decodedParameters` numéricos grandes como string, análogo ao que `AssetBalance.balance` já faz).
- **Saldos/Webhooks**: migração 1:1 conforme pedido — `Table`/`TextField`/`Select`/`Button`/`Alert`/`Chip`/`Pagination` (MUI) no lugar das tags HTML soltas com `className`, sem mudança de comportamento/campos.
- Componentes compartilhados: `NetworkSelect` (agora `Select` MUI), `Pagination` (agora usa `Pagination` do MUI internamente, numerado, mantendo a interface de props 0-indexed), `ApiErrorAlert` (`Alert severity="error"`), `CopyableHash` (novo).
- Validação: `npx tsc -b` limpo, `npm run build` (bundle Redocly → generate typescript-axios → `tsc -b` → `vite build`) verde do zero, `npm run lint` (ESLint, `@typescript-eslint/no-explicit-any` ativo) sem erros/warnings. Sem `useEffect` para chamada de API introduzido (grep confirma só menções em comentário). Sem framework de teste e2e/headless configurado no projeto (mesma limitação de E9-E12) — validação visual manual não executada nesta sessão, só build+lint+typecheck.
- **Pendência não-bloqueante**: não rodei `reviewer` — este agente (`frontend-dev`) não tem acesso a invocar outro subagente na sessão em que foi executado (sem ferramenta de subagente disponível no toolset). Fica como próximo passo da sessão principal antes de marcar E13 ✅ definitivamente.

**Revisão (`reviewer`, 2026-08-28)**: aprovada, sem achados bloqueantes. Confirmado item a item:
1. Tailwind removido por completo — `grep -rn "className="` e `grep -i tailwind package.json` em `frontend/src`/`package.json` sem nenhum resultado; nenhum `tailwind.config`/`postcss.config` residual; `vite.config.ts` sem o plugin; `index.css` reduzido a um reset mínimo (comentário explica que `CssBaseline` cobre o resto).
2. Modais fazem sentido como UX, não são só "form dentro de Dialog": `ContractFormDialog` traz o atalho de importação Blockscout lado a lado (2 colunas) no mesmo modal, preenchendo o form ao clicar num item da lista; `AbiRegisterDialog` mantém as 4 origens com validação de `canSubmit` por origem (ex.: exige sugestão `FULL` pra habilitar "Detectar automaticamente"); `AbiSuggestionsDialog` é deliberadamente separado do fluxo de cadastro pra permitir "espiar" sugestões sem compromisso — motivação clara e coerente com o pedido da etapa.
3. Estado vazio do discovery Blockscout explica de fato o "porquê": `Alert severity="info"` em `ContractFormDialog.tsx` diz explicitamente que o discovery só lista contrato **verificado** (não é erro) e aponta o passo seguinte em ambiente local (`local-env/blockscout/verify-contracts.ts`, texto verificado contra `local-env/README.md`, não inventado) — resolve o problema de UX que abriu a etapa.
4. `DecodedParamValue`: robusta para `BigInteger`/`Boolean`/`ByteArray`/`List` (os tipos que `AbiDecodingAdapter.formatValue` de fato produz, confirmado lendo o arquivo no backend) — endereço/hex longo, boolean-chip, array recursivo e fallback `JSON.stringify` todos corretos. Achado não-bloqueante (registrado em DEC-063): o ramo `DIGITS_PATTERN` (string só de dígitos → agrupamento de milhar) documenta proteger contra estouro de `Number.MAX_SAFE_INTEGER` de `uint256`/`int256`, mas na prática nunca recebe esses valores como string — `BigInteger` é serializado pelo Jackson do backend como literal numérico JSON (não string) e o `JSON.parse` do axios já converte pra `number` do JS **antes** deste componente rodar (a perda de precisão, quando ocorre, já aconteceu). O ramo hoje só é alcançável por um parâmetro Solidity `string`/`bytes` cujo valor decodificado seja só dígitos (ex.: um ID/código de referência armazenado como `string`), caso em que aplicar separador de milhar reformata algo que não é semanticamente um número. Não gera crash, é `TypeScript`-são, mas é uma formatação enganosa num cenário plausível de ABI real — ver DEC-063 para detalhe e sugestão de encaminhamento.
5. Regras não-negociáveis do projeto: sem violação. `grep` confirma zero `useEffect` fazendo fetch (as 2 únicas ocorrências da palavra são comentários explicando a ausência), zero chamada HTTP manual (`axios.get/post/...`/`fetch(` só dentro de `src/api/` gerado), `any` explícito só dentro de `src/api/` (cliente gerado, não editado à mão — `DecodedParamValue` usa `unknown`, não `any`), identificadores em inglês/comentários em português mantidos, cliente gerado (`ContractsApi`/`DiscoveryApi`/etc.) é a única via de acesso à API em todos os hooks novos (`useContracts.ts`).
6. Build próprio, reproduzido do zero nesta revisão: `npx tsc -b` limpo; `npm run lint` sem erros/warnings; `npm run build` (bundle Redocly → generate typescript-axios a partir do `.yaml` real do backend → `tsc -b` → `vite build`) verde, `BUILD SUCCESSFUL` — único aviso é de tamanho de chunk (767 kB, esperado numa PoC com MUI+Emotion+axios num bundle único, não é erro nem regra violada).

Achados não-bloqueantes (nenhum corrigido pelo `reviewer` — nenhum viola regra do projeto, registrados para follow-up futuro):
- **DEC-063** (novo): ramo `DIGITS_PATTERN` de `DecodedParamValue` não protege contra o que o comentário diz proteger (ver item 4 acima) — considerar remover o ramo (deixar cair no `string` genérico) ou revisitar junto da correção de serialização de `BigInteger` já sinalizada na DEC-062.
- `frontend/CLAUDE.md` ainda não documenta a regra "só `sx` aceita atalhos de layout/tipografia em `Stack`/`Typography` no MUI v9" (DEC-062 sinalizou como "vale registrar", não fez) — baixa prioridade, sugerido para a próxima sessão que tocar `frontend/CLAUDE.md`.
- `ContractFormDialog.tsx`: o link pro script `verify-contracts.ts` usa `MuiLink` com `href="https://github.com"` + `onClick={(e) => e.preventDefault()}` só pra herdar o estilo de link — funciona, mas é um uso um pouco indireto de `Link` só por estilo; poderia ser só `Typography`/`Box` com `sx` equivalente. Cosmético.

Critério de aceite (implícito da etapa — Tailwind fora, MUI dentro, telas mais didáticas, build/lint/typecheck verdes) cumprido. Etapa marcada ✅.
