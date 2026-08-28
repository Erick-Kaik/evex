# Arquitetura hexagonal do Evex — fluxo de requisição e regras

> Complementa o layout de pastas já esboçado em [`../CLAUDE.md`](../CLAUDE.md). Este documento fixa a regra mais importante: **nunca escrever um controller manualmente** — o OpenAPI já gera controller + interface de service + models. O único código handwritten no lado de entrada é a implementação da interface gerada.

## Fluxo de uma requisição, ponta a ponta

```
1. resources/api/evex-api.yaml (fonte de verdade)
        │
        │  ./gradlew compileKotlin  (task generateEvexApi, dependsOn)
        ▼
2. build/generated/evex-api/  ── GERADO, nunca editado, nunca commitado como fonte
   ├── *Controller.kt           implementa @RestController, delega pro *ApiService
   ├── *ApiService.kt           interface (o generator com serviceInterface=true gera só a interface)
   └── model/*.kt                DTOs (request/response)
        │
        │  Spring injeta a implementação do bean *ApiService
        ▼
3. adapters/inbound/api/*ApiServiceImpl.kt  ── HANDWRITTEN, é a ÚNICA peça do lado "in"
   implements <Recurso>ApiService {
       override fun getEvents(...): EventPage {
           val filter = ...            // mapeia parâmetros do DTO gerado -> objeto de domínio/filtro
           val result = eventQueryUseCase.search(filter, page, size)
           return result.toView()      // mapeia domínio -> DTO gerado
       }
   }
        │
        ▼
4. application/*UseCase.kt (ou *Service.kt)  ── orquestração, casos de uso
   - não conhece HTTP, não conhece JPA, não conhece Web3j diretamente
   - depende só de interfaces de porta (domain)
        │
        ▼
5. domain/  ── entidades + regras de negócio puras + interfaces de porta
   interface AbiStoragePort { ... }
   interface BlockchainPort { ... }
   interface WebhookDispatchPort { ... }
        │
        ▼
6. adapters/outbound/*  ── HANDWRITTEN, implementam as portas do domain
   persistence/  → JPA repositories + entidades @Entity (podem ser as mesmas classes do
                    domain com anotação, ou classes de persistência separadas + mapper —
                    decidir no início da implementação, não misturar as duas abordagens)
   blockchain/   → Web3j direto (HttpService, sem Keeper)
   abi/          → as Strategies descritas em abi-strategy-design.md
   discovery/    → cliente Blockscout (RestClient)
   webhook/      → dispatcher HTTP + fila + retry
```

## Regra "sem controllers manuais" — por quê e como

O `openapi-generator` com `serviceInterface=true` / `serviceImplementation=false` (config já usada no código base, ver `openapi-first-workflow.md`) gera:
- o `@RestController` completo (rotas, `@RequestMapping`, validação de path/query params, serialização),
- a interface `*ApiService` com um método por operação do spec,
- os models de request/response.

O controller gerado **já chama** o bean Spring que implementa a interface. Ou seja: escrever um `@RestController` manual seria trabalho duplicado e, pior, criaria uma segunda rota fora do contrato OpenAPI — quebra a garantia de "o `.yaml` é a fonte de verdade". A única coisa que falta implementar é a interface `*ApiService`, e é isso que vai em `adapters/inbound/api/*ApiServiceImpl.kt`.

**Checklist ao adicionar um novo endpoint:**
1. Editar `resources/api/evex-api.yaml` primeiro (novo path/operationId/schema).
2. Rodar `./gradlew compileKotlin` — o generator cria a nova interface `*ApiService` (ou adiciona o método na existente) em `build/generated`.
3. Implementar (ou estender) a `*ApiServiceImpl` correspondente em `adapters/inbound/api/`.
4. **Nunca** criar um `@RestController`, `@RequestMapping` ou `@GetMapping` à mão em nenhum outro lugar do código.

## `*ApiServiceImpl` deve ser fino

Seguindo o padrão já usado no código base (`EventsApiServiceImpl`, `MonitoredContractsApiServiceImpl`, `AdminApiServiceImpl`, `PositionsApiServiceImpl` — todos com corpo pequeno, log de entrada/saída, delegação imediata): a `*ApiServiceImpl` só deve:
- mapear DTO gerado → objeto de domínio/filtro,
- chamar exatamente um método da camada `application`,
- mapear o retorno de domínio → DTO gerado.

Qualquer lógica de negócio, decisão de storage, decodificação, chamada à rede, etc. pertence à camada `application`/`domain`/`adapters/outbound` — nunca ao `*ApiServiceImpl`.

## Onde entram os Strategy patterns do `abi-strategy-design.md`

As Strategies (`AbiStoragePort`, `BalanceBehaviorStrategy`, `InterfaceTemplate`, `AbiIngestionStrategy`) são **portas de saída** (ou variações de uma porta de saída) — vivem como interface em `domain`/`application` e implementação concreta em `adapters/outbound/abi/`. O `application` layer (ex.: `RegisterContractAbiUseCase`) resolve qual estratégia usar (via um `*Resolver`/`*Factory`) e chama através da interface — nunca instancia a implementação concreta diretamente.

## Diferença em relação ao código base

O código base (`base/indexer`) tem separação por pacote técnico (`abi/`, `ingest/`, `query/`, `repository/`, `web3/`) mas os services dependem diretamente de `JpaRepository`/`Web3j` — não há portas explícitas, então trocar a implementação de storage ou de acesso à rede exigiria mexer no service de domínio. O Evex introduz as interfaces de porta justamente para isolar isso, como pede a regra "arquitetura hexagonal" do doc de produto (§9).
