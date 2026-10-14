package br.com.bbchain.evex.indexer.domain.discovery

/** Origem de um upgrade na linha do tempo: ingerido pelo Evex, só no Blockscout, ou nos dois. */
enum class UpgradeSource { INGESTED, BLOCKSCOUT, BOTH }
