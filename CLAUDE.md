# Evex — EVM Event Indexer

> Status: PoC em desenvolvimento incremental por etapas (ver roadmap).

## ⚑ Checklist de início de sessão (obrigatório, primeira ação — não só "antes de codar")

Qualquer sessão aberta neste repositório — terminal, IntelliJ, subagent — deve, **antes de responder qualquer coisa sobre o projeto ou tocar em código**:

1. Ler [`docs/roadmap.md`](docs/roadmap.md) inteiro — qual etapa está em andamento/próxima, o que já foi feito, notas de execução da última etapa concluída.
2. Ler as entradas mais recentes de [`docs/decisions.md`](docs/decisions.md) — decisões técnicas já fechadas, pra não propor de novo algo já decidido (ou pior, contradizer sem perceber).
3. Se a tarefa for no backend ou frontend, ler também [`backend/CLAUDE.md`](backend/CLAUDE.md) ou [`frontend/CLAUDE.md`](frontend/CLAUDE.md).
4. Reportar pro usuário, em 1-2 frases, **onde o projeto parou** antes de propor o que fazer a seguir — não assumir que ele lembra o estado exato.

Ao terminar qualquer unidade de trabalho: atualizar `docs/roadmap.md` (status + notas de execução) e, se houve decisão técnica não trivial, `docs/decisions.md` (novo `DEC-0XX`). Essa regra vale igual para a sessão principal e para qualquer agente especializado (`.claude/agents/`) — cada um já tem essa mesma instrução na própria definição.

## Documentos de referência

- [`base/indexer-product-draft.md`](base/indexer-product-draft.md) — fonte de verdade de **produto** (problema, funcionalidades, escopo, regras).
- [`backend/CLAUDE.md`](backend/CLAUDE.md) — decisões técnicas e estrutura do backend.
- [`frontend/CLAUDE.md`](frontend/CLAUDE.md) — decisões técnicas e estrutura do frontend.
- [`docs/roadmap.md`](docs/roadmap.md) — **onde estamos**: etapas, status, dependências, critério de aceite de cada uma.
- [`docs/decisions.md`](docs/decisions.md) — **por que decidimos assim**: log histórico de decisões técnicas (ADR).

`base/indexer` (código legado Anbima) já foi removido depois de extraído o que era reaproveitável para `backend/docs/`. Não existe mais no repositório — não procurar lá.

## O que é

API de indexação de eventos EVM **genérica e configurável**, para redes privadas Besu, instalável por qualquer projeto/cliente BBChain sem reimplementar a camada de indexação. Ver `base/indexer-product-draft.md` para o problema completo, funcionalidades e escopo da PoC.

## Código base de referência (removido)

`base/indexer` foi a implementação **semi-genérica** feita para o projeto Anbima (pacote `br.com.bbchain.anbima.indexer`) — lida e analisada por completo antes de qualquer código deste projeto, e **removida** depois (não é para ser copiada 1:1; foi construída sob premissas diferentes: Keeper, auth JWT, ABIs estáticas, escuta de rede inteira sem cadastro). Tudo que era reaproveitável dela — algoritmos, padrões, cenários de teste, ajustes de performance — já foi extraído como documentação em `backend/docs/` antes da remoção. O que reaproveitar/adaptar/reescrever está detalhado em `backend/CLAUDE.md`.

## Decisões travadas na análise (2026-08-21)

Decisões que não estão no `indexer-product-draft.md` mas foram fechadas em sessão de análise do código base, antes de estruturar o projeto:

| Tema | Decisão | Por quê |
|---|---|---|
| Escopo do `eth_getLogs` | Rede inteira, **sem filtro de endereço** (igual Anbima) | Coerente com "nenhum evento é descartado" (doc §4.2); atribuição por endereço acontece na ingestão, não no filtro RPC |
| Cursor de indexação | **Global único** (não por contrato) | Decorre da decisão acima |
| Acesso à rede | Só **polling** (`eth_getLogs` agendado), sem WebSocket/subscription | Reaproveita o padrão `PollWindow`/auto-shrink do código base; WebSocket fica como possível item pós-aprovação |
| Webhooks (doc §4.6) | **Feature separada** do pipeline de indexação — não é sobre a conexão com a rede | O indexer faz `POST` no endpoint que o usuário configurou quando um evento decodificado bate com os filtros da config. Não confundir com a decisão de polling acima: uma é RPC de entrada (rede→indexer), a outra é notificação de saída (indexer→sistema externo) |
| Reorg / `confirmations` | Configurável, default baixo | Besu com IBFT2/QBFT tem finalidade determinística por bloco — não é PoW/PoS público |
| Schema management | **JPA `ddl-auto: update` como padrão + Flyway parcial quando necessário** | Mesmo padrão híbrido do código base Anbima (não Flyway puro) |
| Proxy upgradável (ex.: ERC1967Proxy) | **Precisa suportar** | `contract_abis` versionado por faixa de blocos (`valid_from_block`/`valid_to_block` por endereço) — decodifica eventos antigos com a ABI vigente na época, novos com a ABI atual. Afeta também o cache em memória (não é mais `Map<address, Abi>` simples) |
| Stack backend | Java 21 + Spring Boot 4.1.1 + Kotlin 2.4.10 + Web3j 6.0.0 + Gradle 9.2.0 | Validado via spike de compilação real (ver `backend/CLAUDE.md`) — Gradle wrapper do projeto **precisa ser 9.x**, não 8.8 |
| Testes | JUnit 5 + MockK + Testcontainers (Postgres real) | Diverge do código base (que usa Mockito-Kotlin + H2) — não copiar o padrão de teste do Anbima |
| Ambiente Hardhat de teste | Já existe fora deste diretório, será importado depois | Fora do escopo desta fase de análise |

## Módulos

- **[backend/](backend/CLAUDE.md)** — Kotlin/Spring Boot, arquitetura hexagonal, API OpenAPI-first.
- **[frontend/](frontend/CLAUDE.md)** — React/Vite, UI mínima da PoC.
- **[local-env/](local-env/CLAUDE.md)** — ambiente local de teste (Hardhat + Blockscout + contratos de teste), **não é parte do produto entregável** — existe só para validar o Evex ponta a ponta e para demonstrações.

## Agentes especializados (`.claude/agents/`)

Cada agente tem escopo definido, lista de leitura obrigatória antes de começar, e a instrução de atualizar `docs/roadmap.md`/`docs/decisions.md` ao terminar:

| Agente | Responsabilidade |
|---|---|
| `kotlin-backend-dev` | Kotlin/Spring Boot: `application`/`domain`, persistence, `*ApiServiceImpl`, contrato monitorado, saldos, webhooks. |
| `blockchain-integration` | Web3j direto, poller/cursor, decodificação dinâmica de ABI, storage strategies, Blockscout, reindexação. |
| `hexagonal-architect` | Design/limites das camadas, interfaces de porta, integridade da separação hexagonal — não implementa feature, revisa/decide estrutura. |
| `frontend-dev` | React/TS/Vite — telas da UI mínima, consumindo o cliente gerado. |
| `reviewer` | Revisão de código contra as regras deste repositório antes de fechar uma etapa do roadmap — não implementa. |

Invocar via `subagent_type` com o nome acima. Todos leem `CLAUDE.md` (raiz + módulo) e `docs/roadmap.md`/`docs/decisions.md` antes de começar, e atualizam os dois ao terminar.

## Regras gerais (válidas para todo o repositório)

- **Idioma do código:** inglês para identificadores (variáveis, funções, classes, pacotes).
- **Idioma dos comentários/KDoc inline:** português — mesmo idioma da documentação do projeto (decisão da E11, ver `docs/decisions.md`). Só os identificadores ficam em inglês; comentários explicativos, KDoc e mensagens de commit acompanham o idioma da documentação solta (`.md`), não o do código.
- **Idioma da documentação:** português.
- **Sem autenticação/autorização na PoC** — nenhuma camada de auth neste momento.
- **Instância por rede:** uma instância do Evex por cliente/ambiente; segregação interna por campo `network` (`MAINNET` | `TESTNET`).
- Ver o checklist de início/fim de sessão no topo deste arquivo.
