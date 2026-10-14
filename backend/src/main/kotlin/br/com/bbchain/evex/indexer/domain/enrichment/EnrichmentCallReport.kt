package br.com.bbchain.evex.indexer.domain.enrichment

import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import java.util.UUID

/**
 * Representação **serializável e pura** do resultado de uma chamada de enriquecimento
 * ([EnrichmentCallOutcome]) — é o que se persiste por tentativa de entrega e o que a API expõe. Só tipos
 * simples (strings/números/mapa): sem Jackson nem classes selecionadas do domínio, para a persistência
 * (JSONB) poder gravar e reler sem depender de polimorfismo.
 *
 * [status] e [code]/[classification] são strings EXTENSÍVEIS (nunca enum fechado): um leitor deve tratar
 * valor desconhecido como "não aplicável". [blockUsed]: número em decimal ou `latest`.
 */
data class EnrichmentCallReport(
    val profileId: UUID,
    val targetContractAddress: String,
    val methodName: String,
    val status: String,
    val outputs: Map<String, String>? = null,
    val code: String? = null,
    val message: String? = null,
    val rpcCode: Int? = null,
    val rpcData: String? = null,
    val classification: String? = null,
    val blockUsed: String? = null,
) {
    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_ERROR = "ERROR"

        /** `block#N` -> `N`; `latest` -> `latest` (mesma forma usada pela API). */
        fun blockLabel(block: CallBlock?): String? = when (block) {
            null -> null
            is CallBlock.Number -> block.value.toString()
            CallBlock.Latest -> "latest"
        }

        fun from(outcome: EnrichmentCallOutcome): EnrichmentCallReport = when (outcome) {
            is EnrichmentCallOutcome.Success -> EnrichmentCallReport(
                profileId = outcome.profileId,
                targetContractAddress = outcome.call.targetContractAddress,
                methodName = outcome.call.methodName,
                status = STATUS_OK,
                outputs = outcome.outputs,
                blockUsed = blockLabel(outcome.blockUsed),
            )
            is EnrichmentCallOutcome.Failure -> EnrichmentCallReport(
                profileId = outcome.profileId,
                targetContractAddress = outcome.call.targetContractAddress,
                methodName = outcome.call.methodName,
                status = STATUS_ERROR,
                code = outcome.code,
                message = outcome.message,
                rpcCode = outcome.rpcCode,
                rpcData = outcome.rpcData,
                classification = outcome.classification,
                blockUsed = blockLabel(outcome.blockUsed),
            )
        }
    }
}

/** Atalho de [EnrichmentCallReport.from] para uma lista inteira de resultados. */
fun List<EnrichmentCallOutcome>.toReports(): List<EnrichmentCallReport> = map { EnrichmentCallReport.from(it) }
