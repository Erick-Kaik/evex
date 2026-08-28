# Notas de performance e operação — validadas em produção na Anbima

> Ajustes que não são óbvios de redescobrir e já foram validados em produção no código base. Vale aplicar por padrão no Evex desde o início, revisando se ainda fazem sentido na stack nova (Spring Boot 4 / Hibernate mais recente).

## Batching de escrita (Hibernate/JPA)

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
```

Sem isso, `saveAll()` em lote (usado tanto na ingestão quanto nos backfills) faz 1 `INSERT`/`UPDATE` por linha em vez de agrupar em batches JDBC — relevante porque o pipeline de ingestão insere em lote a cada janela de blocos processada.

## Sequences com `allocationSize` alto

Todas as entidades de alto volume (`blockchain_event`, `token_balance`, `token_balance_change`) usam `@SequenceGenerator(allocationSize = 50)` — o Hibernate reserva 50 IDs por vez em vez de 1 `SELECT nextval` por insert. Sem isso, ingestão em lote gera 1 roundtrip ao banco por linha só para pegar o ID.

## `@ElementCollection` de alto volume com `@BatchSize`

`BlockchainEvent.participants` (usado para permitir filtro `GET /events?participant=0x...`) é um `@ElementCollection` numa tabela separada — sem `@BatchSize(size = 200)`, carregar N eventos com seus participantes vira N+1 queries. Vale replicar esse padrão em qualquer `@ElementCollection`/coleção associada usada em filtro de listagem.

## `eth_getLogs` — limite de range do nó

O auto-shrink do poller (ver `ingestion-pipeline-reference.md`) existe porque nós Ethereum/Besu costumam limitar a quantidade de resultados/tamanho de range de um `eth_getLogs`. O código base tratava isso via `GetLogsRpcException` carregando `rpcCode`/`rpcMessage` do erro JSON-RPC — vale manter uma exceção de domínio equivalente (não deixar o erro cru do Web3j vazar) para permitir a lógica de shrink genérica.

## `confirmations` — validar valor pra Besu

O código base usa `confirmations=5` como default (herdado de premissas de rede pública/PoW). Como já registrado nas decisões do projeto (`../CLAUDE.md`), Besu com IBFT2/QBFT tem finalidade determinística por bloco — validar com quem opera a rede se `confirmations` pode ser 0 ou baixo na prática, em vez de assumir o valor herdado.

## Flyway + `ddl-auto: update` juntos (decisão confirmada do Evex)

O código base roda com as duas coisas ativas simultaneamente, usando um baseline explícito:

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0   # garante que a 1ª migration real (V1) ainda rode de fato —
                           # o baseline default seria versão 1, que empataria com V1 e a
                           # "puraria" sem executar
```

O Evex vai usar esse mesmo padrão híbrido (`ddl-auto: update` no dia a dia + Flyway para migrations pontuais quando necessário) — se alguma migration Flyway for adicionada depois que o schema já existe via `ddl-auto`, esse baseline explícito evita que a primeira migration seja silenciosamente ignorada.

## Timestamp de bloco memoizado por lote

Ao ingerir uma janela com múltiplos eventos do mesmo bloco, `getBlockTimestamp(block)` é chamado **uma vez por bloco distinto** (cache local ao lote: `Map<Long, Instant>`), não uma vez por evento — evita N chamadas RPC redundantes numa janela com muitos eventos do mesmo bloco.
