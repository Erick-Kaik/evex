# Workflow OpenAPI-first

> A task Gradle e as `configOptions` do código base já resolvem parte do setup — extraído inicialmente do `base/indexer` (removido depois). A partir de E1, a spec do Evex é **multi-arquivo granular + bundle**, diferente do arquivo único do código base — decisão registrada em `docs/decisions.md` DEC-012.

## Estrutura de arquivos da spec (`backend/src/main/resources/swagger/`)

```
swagger/
├── base/
│   ├── base.yaml                 # raiz: openapi 3.1, info, servers, tags, $ref pra paths/ e components/
│   └── components/
│       ├── schemas/              # objetos "de negócio" (Contract, Abi, Event, Balance, Webhook...) + envelopes (Page)
│       │   ├── contract.yaml
│       │   ├── contract-page.yaml
│       │   └── page-metadata.yaml
│       ├── requests/             # request bodies (components.requestBodies)
│       │   └── create-contract-request.yaml
│       ├── responses/            # response envelopes reusáveis (components.responses), incl. erro padrão
│       │   ├── contract-response.yaml
│       │   ├── contract-page-response.yaml
│       │   └── error-response.yaml
│       ├── param/                # components.parameters, subdividido por localização
│       │   ├── query/
│       │   │   ├── network.yaml
│       │   │   ├── page.yaml
│       │   │   └── size.yaml
│       │   └── path/
│       │       └── contract-address.yaml
│       └── enum/                 # schemas enum isolados dos schemas "objeto", por clareza de navegação
│           └── network.yaml
├── paths/                        # 1 arquivo = 1 Path Item Object = 1 URL literal (todos os métodos daquele URL)
│   ├── contracts.yaml            # /v1/contracts (GET, POST)
│   └── contract-detail.yaml      # /v1/contracts/{address} (GET)
```

O bundle final vai em **`build/swagger-bundle/evex-api.bundled.yaml`** (não dentro de `resources/`) — é artefato gerado, mesmo tratamento de `build/generated/`, nunca commitado. Não existe uma pasta `bundle/` física em `resources/swagger/`.

**Por que 1 arquivo por URL em `paths/` (não por tag inteira)**: o `$ref` do OpenAPI só consegue apontar para um "nó" inteiro da árvore YAML — no caso de `paths`, esse nó é o **Path Item Object** de uma URL específica (`get`, `post`, `patch`... daquela URL). Não dá pra um único arquivo representar duas URLs diferentes (`/contracts` e `/contracts/{address}`) com um só `$ref`. Então a granularidade natural de `paths/` é "por URL", que na prática quase sempre coincide com "por recurso" (`contracts.yaml`, `contracts-abi.yaml`, `events.yaml`...) — cada recurso do doc de produto vira 1+ arquivos em `paths/`, um por variação de URL daquele recurso (coleção vs. item, sub-recursos como `/contracts/{address}/abi`).

**Componentes**: cada schema/request/response/parameter é seu próprio arquivo, referenciado via `$ref` relativo tanto de `base.yaml` (para registrar em `components.*`) quanto diretamente dos arquivos de `paths/` (para usar no endpoint). Enums ficam fisicamente separados de `schemas/` mesmo sendo tecnicamente também `components.schemas` no OpenAPI — só para navegação mais clara (ex.: querer ver todos os enums do domínio sem misturar com objetos compostos).

## Bundle (multi-arquivo → 1 arquivo final)

O `openapi-generator` **consegue** resolver `$ref` relativo multi-arquivo diretamente (usa `swagger-parser` por baixo). Mesmo assim, o Evex faz o **bundle explícito antes de gerar** — decisão consciente (DEC-012), não uma exigência técnica:
- o arquivo bundlado é a fonte única e inspecionável do contrato "resolvido" (útil pra revisar, importar noutra ferramenta, ou publicar);
- evita qualquer edge case de resolução de `$ref` relativo específico do generator/versão.

Ferramenta: **Redocly CLI** (`@redocly/cli`, via `npx` — sucessor do `swagger-cli`, hoje descontinuado). Comando:

```bash
npx --yes @redocly/cli bundle swagger/base/base.yaml --output build/swagger-bundle/evex-api.bundled.yaml
```

Isso roda como task Gradle (`bundleEvexApi`, tipo `Exec`) antes de `generateEvexApi` — `./gradlew build` sozinho já bundla e gera, sem passo manual. **Node.js precisa estar disponível no `PATH`** de quem builda o projeto (CI incluído) — é uma dependência de build nova que o projeto não tinha antes de E1; documentar isso no README/setup do backend.

## Task Gradle (padrão, adaptado do código base)

```kotlin
val bundleEvexApi by tasks.registering(Exec::class) {
    val input = "$projectDir/src/main/resources/swagger/base/base.yaml"
    val outputDir = layout.buildDirectory.dir("swagger-bundle").get().asFile
    val output = File(outputDir, "evex-api.bundled.yaml")
    inputs.dir("$projectDir/src/main/resources/swagger")
    outputs.file(output)
    doFirst { outputDir.mkdirs() }
    commandLine("npx", "--yes", "@redocly/cli", "bundle", input, "--output", output.absolutePath)
}

val generateEvexApi by tasks.registering(GenerateTask::class) {
    dependsOn(bundleEvexApi)
    generatorName.set("kotlin-spring")
    inputSpec.set(layout.buildDirectory.file("swagger-bundle/evex-api.bundled.yaml").get().asFile.absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/evex-api").get().asFile.absolutePath)
    apiPackage.set("br.com.bbchain.evex.indexer.adapters.inbound.api.generated")
    modelPackage.set("br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model")
    configOptions.set(mapOf(
        "useSpringBoot3" to "true",
        "useTags" to "true",
        "documentationProvider" to "springdoc",
        "openApiNullable" to "false",
        "useOptional" to "false",
        "serviceInterface" to "true",
        "serviceImplementation" to "false",
        "modelMutable" to "true",
    ))
    doLast {
        val oa = layout.buildDirectory.dir("generated/evex-api/src/main/kotlin/org/openapitools").get().asFile
        if (oa.exists()) {
            oa.resolve("Application.kt").takeIf { it.exists() }?.delete()
            oa.resolve("HomeController.kt").takeIf { it.exists() }?.delete()
        }
    }
}

sourceSets { named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/evex-api/src/main/kotlin")) } }
tasks.compileKotlin { dependsOn(generateEvexApi) }
tasks.processResources { dependsOn(generateEvexApi) }
```

**Gotcha herdado do código base**: sem o `doLast` removendo `Application.kt`/`HomeController.kt` gerados, o boot falharia com múltiplas classes `@SpringBootApplication`/rota raiz duplicada. **Validado em E1**: com o `apiPackage`/generator/config atual, o plugin 7.24.0 **não gerou** esses arquivos indesejados (só `SpringDocConfiguration.kt`/`EnumConverterConfiguration.kt`, ambos legítimos) — o gotcha não se reproduziu nesta configuração. O `doLast` foi mantido mesmo assim (inofensivo se nunca disparar, guarda contra o caso voltar a acontecer com config diferente).

## O que é gerado vs. o que é handwritten

| | Gerado (`build/generated`, nunca editado) | Handwritten |
|---|---|---|
| Controllers | ✅ `*Controller.kt` completo, com rotas | ❌ nunca criar manualmente — **exceção**: transporte de streaming não modelado pelo generator (SSE `text/event-stream`), ver E17/DEC-078 |
| Interface de service | ✅ `*ApiService.kt` (1 método por operação) | — |
| Implementação do service | ❌ | ✅ `adapters/inbound/api/*ApiServiceImpl.kt` |
| Models (DTOs) | ✅ `model/*.kt` | ❌ nunca escrever DTO de request/response à mão |

Ver `hexagonal-architecture.md` para o fluxo completo de uma requisição e a regra "ApiServiceImpl deve ser fino".

## Convenções REST do Evex

- **Tags por recurso**: 1 tag por recurso do doc de produto (`Contracts`, `Abi`, `Events`, `Balances`, `Webhooks`, `Admin`) — cada tag corresponde a 1+ arquivos em `paths/` e, futuramente, a 1 `*ApiServiceImpl`.
- **URLs com prefixo de versão**: `/v1/...`, plural, recurso-orientado (`/v1/contracts`, `/v1/contracts/{address}`, `/v1/contracts/{address}/abi`) — mesmo padrão do código base.
- **Verbos HTTP semânticos**: `GET` (leitura, idempotente), `POST` (criação, ou ação não idempotente como `/redecode`), `PATCH` (atualização parcial), `DELETE` — nunca usar `GET`/`POST` genérico pra tudo.
- **Paginação**: parâmetros de query `page`/`size` (`components/param/query/`) + schema de envelope composto por `allOf` de um `PageMetadata` (`page`, `size`, `totalElements`, `totalPages`) genérico com `content: [...]` específico por recurso — ex.: `ContractPage = PageMetadata + { content: Contract[] }`. Mapeamento Spring `Page<T>` → DTO gerado é manual na `*ApiServiceImpl` (o generator não faz isso sozinho).
- **Erros — DEC-013**: schema de erro próprio, **não** o corpo default do Spring Boot. `ErrorResponse { code: string, message: string, timestamp: string(date-time) }` — usado em todas as respostas de erro documentadas no spec (`components/responses/error-response.yaml`). Implementação (`@ControllerAdvice`/`ProblemDetail` equivalente) fica para quando o primeiro cenário de erro de negócio real aparecer (não é escopo de E1).
- **`operationId`**: nome do método na interface gerada — inglês, verbo+recurso (`listMonitoredContracts`, `createMonitoredContract`, `getMonitoredContract`).
- **Status codes**: `200` (GET/PATCH ok), `201` (POST criou), `204` (DELETE ok sem corpo), `400` (validação), `404` (não encontrado) — cada operação documenta explicitamente os status que pode retornar, incluindo os de erro com `$ref` pro `error-response.yaml`.

## Regra geral

O `.yaml` (multi-arquivo, bundlado em `build/swagger-bundle/`) é a única fonte de verdade de contrato de API. Qualquer mudança de rota, parâmetro ou schema começa editando os arquivos-fonte em `swagger/base/`, `swagger/paths/`, nunca o bundle gerado nem o código gerado nem um controller paralelo.
