package br.com.bbchain.evex.indexer.domain.enrichment

/**
 * Cadastro/edição de [EnrichmentProfile] inválido — "falhar cedo" (mesmo princípio do resto da API):
 * `EnrichmentCall.targetContractAddress` sem ABI registrada, `methodName` que não existe ou não é
 * `view`/`pure` naquela ABI, ou `EnrichmentOutputMapping.outputIndex` fora da faixa de saídas do
 * método. Validado no cadastro do **perfil**, nunca silenciosamente no 1º disparo real de um webhook
 * que o referencia.
 */
class InvalidEnrichmentProfileException(message: String) : RuntimeException(message)
