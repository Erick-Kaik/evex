package br.com.bbchain.evex.indexer.domain.enrichment

/**
 * Falhas de enriquecimento agregadas por (contrato, método, bloco, código, rpcCode): evita N linhas
 * idênticas na UI quando a mesma chamada falha em vários eventos/contratos. [message] é a do primeiro
 * relatório do grupo (mensagem BRUTA do nó, nunca uma causa afirmada).
 */
data class EnrichmentFailureAggregate(
    val contract: String,
    val method: String,
    val block: String?,
    val code: String,
    val rpcCode: Int?,
    val message: String?,
    val count: Int,
)

/** Agrega os relatórios `ERROR` (ordem de primeira ocorrência preservada). */
fun Collection<EnrichmentCallReport>.summarizeFailures(): List<EnrichmentFailureAggregate> =
    filter { it.status == EnrichmentCallReport.STATUS_ERROR }
        .groupBy { listOf(it.targetContractAddress, it.methodName, it.blockUsed, it.code, it.rpcCode) }
        .values
        .map { group ->
            val first = group.first()
            EnrichmentFailureAggregate(
                contract = first.targetContractAddress,
                method = first.methodName,
                block = first.blockUsed,
                code = first.code ?: EnrichmentFailureCodes.UNEXPECTED_ERROR,
                rpcCode = first.rpcCode,
                message = first.message,
                count = group.size,
            )
        }
