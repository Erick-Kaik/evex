package br.com.bbchain.evex.indexer.domain.blockchain

/**
 * Erro de range reportado pelo nó (ex.: Besu/geth recusando `eth_getLogs` por range de blocos ou
 * quantidade de resultados grande demais) — equivalente ao `GetLogsRpcException` do código base
 * (`performance-and-ops-notes.md`). Dispara o auto-shrink recursivo em `BlockchainPoller.processRange`
 * (`ingestion-pipeline-reference.md` §2). **Distinta de qualquer outra exceção** (timeout, `IOException`,
 * erro de transporte) — essas não disparam shrink, sobem cruas até o `poll()`, que só loga.
 */
class RpcRangeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
