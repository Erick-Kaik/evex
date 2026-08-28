# Pipeline de ingestão — referência extraída do código base

> Extraído de `base/indexer` antes da remoção da pasta. Descrito como **algoritmo/pseudocódigo**, não como código Kotlin literal — a implementação do Evex deve ser escrita do zero seguindo estes padrões, não copiada.

## 1. Janela de blocos do poller (`PollWindow`)

Função pura, sem I/O, calculando a próxima janela `[from..to]` a processar:

```
next(lastProcessed, latest, confirmations, batchSize):
    from = lastProcessed + 1
    safeHead = latest - confirmations
    if from > safeHead: return null   # nada confirmado pendente
    to = min(from + batchSize - 1, safeHead)
    return from..to
```

**Cenários de teste que a Anbima já cobria (replicar no Evex):**
- Avanço normal dentro do batch (`to` limitado por `batchSize`).
- Clamp pelo `batchSize` quando a distância até `safeHead` é maior que o batch.
- Retorna `null` quando não há blocos confirmados pendentes (`from > safeHead`).
- Processa exatamente 1 bloco quando `from == safeHead`.

## 2. Poller com auto-shrink em erro de RPC

```
poll():
    latest = rpc.currentBlockNumber()
    lastProcessed = cursor.current()
    window = PollWindow.next(lastProcessed, latest, confirmations, batchSize)
    if window == null: return  # em dia
    processRange(window.from, window.to)
    # qualquer exceção aqui é capturada e logada — nunca derruba o @Scheduled

processRange(from, to):
    try:
        logs = rpc.getLogs(from, to)
    catch RpcRangeError as ex:
        if (from >= to) or (to - from + 1 <= minBatchSize):
            throw ex  # não dá pra encolher mais, sobe o erro
        mid = from + (to - from) / 2
        processRange(from, mid)
        processRange(mid + 1, to)
        return
    ingest(from..to, logs)  # avança o cursor no mesmo commit da ingestão
```

Pontos importantes:
- **Erros de range do nó** (ex.: "query returned more than N results") disparam o shrink recursivo — cada sub-janela bem-sucedida avança o cursor independentemente (progresso incremental, não tudo ou nada).
- **Erros de transporte** (timeout, conexão caída) **não** disparam shrink — sobem para o `poll()`, que loga e tenta de novo no próximo tick inteiro.
- O cursor só avança dentro da mesma transação da ingestão da janela — nunca antes.

**Cenários de teste a cobrir:**
- Divide a janela ao meio quando o nó recusa por range grande demais.
- Divide recursivamente (mais de um nível de shrink).
- Janela de 1 bloco que falha não trava o scheduler e não ingere nada (não tenta encolher abaixo de `minBatchSize`).
- Erro de transporte (`IOException`) não dispara shrink — só 1 tentativa por tick.
- Happy path: janela inteira processada de uma vez.

## 3. Idempotência da ingestão

- Chave natural: `(tx_hash, log_index)` com `UNIQUE constraint` no banco + checagem `existsByTxHashAndLogIndex` antes de inserir.
- Cada log da janela: se já existe, pula; senão decodifica (ou grava raw) e acumula em lote para `saveAll` no fim da transação.
- **Nenhum evento é descartado**: contrato sem ABI cadastrada ainda grava o log cru (`decoded=false`, `rawTopics`+`rawData` preservados) — permite reindexação posterior sem reconsultar a rede.

## 4. Reindexação assíncrona em lotes (padrão replicado 3x na base: redecode, backfill de saldo, backfill de timestamp)

Estrutura em duas camadas:

```
Orquestrador (Service):
    running = AtomicBoolean(false)

    tryStart(): compareAndSet(false, true)  # garante 1 execução por vez

    @Async
    run():
        try:
            loop:
                result = batchProcessor.processBatch(batchSize)
                acumula contadores (processados, decodificados/projetados)
                if result.decodedOrProjectedCount == 0: break  # sem mais progresso
        finally:
            running.set(false)

    status(): snapshot { running, processados, decodificados, remaining }
```

```
BatchProcessor (bean SEPARADO do orquestrador — @Transactional só funciona
                 assim, por causa de self-invocation em Spring proxies):
    @Transactional
    processBatch(size):
        rows = repository.findPendingCandidates(PageRequest(0, size))
        for row in rows:
            # tenta processar (decodificar / projetar saldo / preencher timestamp)
            # sucesso -> marca linha como resolvida, nunca mais candidata
            # falha -> loga e pula, linha continua candidata no próximo lote
        flush em lote (saveAll por tabela, não save 1-a-1)
        return contadores do lote
```

**Por que parar quando o lote não progride (não quando não sobram linhas):** eventos permanentemente não-decodificáveis (topic0 que nenhuma ABI cobre) continuariam aparecendo em todo lote para sempre — "sem mais linhas" nunca aconteceria. O critério de parada correto é "lote não decodificou/projetou nada".

**Redecode sem tocar a rede:** o `Log` do Web3j é reconstruído em memória a partir de `rawTopics` (JSON) + `rawData` já persistidos — logs de blocos confirmados são imutáveis on-chain, não há motivo para reconsultar o nó.

**Adaptação necessária para o Evex** (doc de produto §4.3): o filtro do `findPendingCandidates` hoje é só "globalmente pendente" (`decoded=false`, ou `balanceProjected is null`). O Evex precisa parametrizar esse filtro por `contract_address`, `topic0` ou janela de blocos — a estrutura do orquestrador/batch processor continua igual.

**Cenários de teste a cobrir** (redecode como exemplo, replicar para os outros dois):
- Decodifica evento com `topic0` conhecido (ABI cadastrada depois do evento cru).
- Mantém `decoded=false` quando `topic0` continua desconhecido.
- Evento decodificável mas **sem `block_timestamp`** fica adiado (não decodifica, não mexe em saldo) — dependência entre os 3 backfills: saldo depende de timestamp, então a ordem de execução importa.
- Projeção de saldo/supply correta quando o evento decodificado neste lote é do tipo que afeta saldo.
- Paginação: processa em lotes menores que o total pendente, sem duplicar nem pular linhas entre lotes.

## 5. Garantia de "aplicar delta uma única vez"

O comentário do código base explica bem o raciocínio, vale preservar: a garantia de idempotência do saldo não vem de um lock ou de uma checagem extra — vem de **nunca revisitar uma linha depois que ela sai do estado "pendente"**. Se a transação do lote não commitar, a linha continua pendente (nada foi duplicado); se commitar, ela nunca mais é candidata a reprocessamento. Isso vale tanto para o redecode quanto para o backfill de saldo.
