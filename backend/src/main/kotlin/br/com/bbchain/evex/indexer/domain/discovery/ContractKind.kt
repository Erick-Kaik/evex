package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Classificação estrutural de um contrato listado no discovery (DEC-104). Enum fechado de propósito:
 * os 3 estados são a própria regra, não uma taxonomia de negócio aberta.
 *
 * [STANDALONE] significa apenas "nenhum `Upgraded` observado nos eventos ingeridos" — nunca é garantia
 * de que o contrato não é proxy/implementação (depende da cobertura da ingestão).
 */
enum class ContractKind { PROXY, IMPLEMENTATION, STANDALONE }
