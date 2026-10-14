package br.com.bbchain.evex.indexer.domain.enrichment

import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import java.util.UUID

/** Códigos de falha de uma chamada de enriquecimento — strings (taxonomia aberta, nunca enum fechado). */
object EnrichmentFailureCodes {
    const val METHOD_NOT_FOUND = "METHOD_NOT_FOUND"
    const val METHOD_AMBIGUOUS = "METHOD_AMBIGUOUS"
    const val ABI_NOT_FOUND = "ABI_NOT_FOUND"
    const val CONTRACT_NOT_REGISTERED = "CONTRACT_NOT_REGISTERED"
    const val ARG_UNRESOLVED = "ARG_UNRESOLVED"
    const val BLOCK_UNRESOLVED = "BLOCK_UNRESOLVED"
    const val ENCODING_ERROR = "ENCODING_ERROR"
    const val RPC_ERROR = "RPC_ERROR"
    const val UNEXPECTED_ERROR = "UNEXPECTED_ERROR"
    const val SKIPPED_DEPENDENCY = "SKIPPED_DEPENDENCY"
    const val SKIPPED_AFTER_REPEATED_FAILURE = "SKIPPED_AFTER_REPEATED_FAILURE"
}

/**
 * Relatório de UMA [EnrichmentCall] — o que o resolver antes só logava. [call] e [profileId] identificam
 * a origem; o relatório vem sempre na ordem de execução.
 */
sealed interface EnrichmentCallOutcome {
    val profileId: UUID
    val call: EnrichmentCall

    /** Bloco efetivamente usado (`null` se nem chegou a ser determinado). */
    val blockUsed: CallBlock?

    /** [outputs]: `placeholder` (sem o prefixo `enriched.`) -> valor devolvido pelo nó, só os mapeados pelo perfil. */
    data class Success(
        override val profileId: UUID,
        override val call: EnrichmentCall,
        override val blockUsed: CallBlock,
        val outputs: Map<String, String>,
    ) : EnrichmentCallOutcome

    /**
     * [code] é um de [EnrichmentFailureCodes] (extensível). [rpcCode]/[rpcData]/[classification] só
     * existem para falhas vindas do nó e são os valores BRUTOS/derivados de indício explícito (ver
     * `BlockchainCallException`) — nunca uma causa afirmada.
     */
    data class Failure(
        override val profileId: UUID,
        override val call: EnrichmentCall,
        override val blockUsed: CallBlock?,
        val code: String,
        val message: String,
        val rpcCode: Int? = null,
        val rpcData: String? = null,
        val classification: String? = null,
    ) : EnrichmentCallOutcome
}

/** Veredictos (INDÍCIO, nunca causa) do diagnóstico comparativo de uma chamada que falhou. */
object EnrichmentDiagnosisVerdicts {
    const val FAILS_EVERYWHERE = "FAILS_EVERYWHERE"
    const val FAILS_ONLY_AT_BLOCK = "FAILS_ONLY_AT_BLOCK"
    const val OK_AT_LATEST_ONLY = "OK_AT_LATEST_ONLY"
    const val INCONCLUSIVE = "INCONCLUSIVE"
}

/** Resultado resumido da reexecução de uma chamada num bloco de referência: `null` falha = sucesso. */
data class DiagnosisAttempt(val block: CallBlock, val failure: EnrichmentCallOutcome.Failure?) {
    val ok: Boolean get() = failure == null
}

/**
 * Diagnóstico comparativo (opcional) de uma chamada que falhou: reexecuta a MESMA chamada em `LATEST` e no
 * bloco do evento. [verdict] e [probe] são INDÍCIOS — nunca afirmam causa.
 */
data class EnrichmentDiagnosis(
    val outcome: EnrichmentCallOutcome.Failure,
    val atLatest: DiagnosisAttempt?,
    val atEventBlock: DiagnosisAttempt?,
    val probe: ProbeResult?,
    val verdict: String,
)

/** Veredicto de [EnrichmentDiagnosis] — função pura. */
object EnrichmentDiagnosisClassifier {
    private val TRANSPORT = setOf("TIMEOUT", "IO")

    /**
     * @param requested bloco da chamada que falhou
     * @param atLatest tentativa em LATEST (`null` se [requested] já é LATEST ou não executada)
     * @param atEventBlock tentativa no bloco do evento (`null` se igual a [requested] ou não executada)
     */
    fun classify(atLatest: DiagnosisAttempt?, atEventBlock: DiagnosisAttempt?): String {
        val executed = listOfNotNull(atLatest, atEventBlock)
        if (executed.isEmpty()) return EnrichmentDiagnosisVerdicts.INCONCLUSIVE
        if (executed.any { it.failure?.classification in TRANSPORT }) return EnrichmentDiagnosisVerdicts.INCONCLUSIVE
        return when {
            atEventBlock?.ok == true -> EnrichmentDiagnosisVerdicts.FAILS_ONLY_AT_BLOCK
            atLatest?.ok == true -> EnrichmentDiagnosisVerdicts.OK_AT_LATEST_ONLY
            else -> EnrichmentDiagnosisVerdicts.FAILS_EVERYWHERE
        }
    }
}

/** Entrada do enriquecimento — desacopla o resolver de `WebhookDelivery` (preview parte de um evento, não de uma entrega). */
data class EnrichmentInput(
    val eventBlockNumber: Long?,
    val decodedParameters: Map<String, Any?>?,
    val payload: String,
    /** Só para logs. */
    val referenceId: UUID? = null,
)

/**
 * Opções de [resolveDetailed][br.com.bbchain.evex.indexer.application.webhook.WebhookEnrichmentResolver.resolveDetailed].
 * [DEFAULT] = comportamento do disparo normal (sem circuit-breaker, sem diagnóstico).
 */
data class EnrichmentResolveOptions(
    /** `null` = desligado. N falhas idênticas por (contrato, método, bloco) -> demais chamadas viram `SKIPPED_AFTER_REPEATED_FAILURE`. */
    val circuitBreakerThreshold: Int? = null,
    /** Reexecuta falhas em LATEST e no bloco do evento e devolve [EnrichmentDiagnosis]. */
    val diagnose: Boolean = false,
) {
    companion object {
        const val DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 3

        val DEFAULT = EnrichmentResolveOptions()

        /** Perfil para preview/reenvio: circuit-breaker ligado com default conservador. */
        fun interactive(diagnose: Boolean = false, threshold: Int = DEFAULT_CIRCUIT_BREAKER_THRESHOLD) =
            EnrichmentResolveOptions(circuitBreakerThreshold = threshold, diagnose = diagnose)
    }
}

/**
 * Resultado completo de uma resolução: [payload] final (tokens falhos = `null`, DEC-057), um
 * [outcomes] por chamada (em ordem de execução), o mapa de [outputs] resolvidos (chave
 * `enriched.<placeholder>`), [unresolvedTokens] (`{{enriched.*}}` do payload que nenhuma chamada produziu) e
 * os [diagnoses] (só com `diagnose=true`).
 */
data class EnrichmentResolution(
    val payload: String,
    val outcomes: List<EnrichmentCallOutcome>,
    val outputs: Map<String, String>,
    val unresolvedTokens: List<String>,
    val blockSpec: EnrichmentBlockSpec,
    val diagnoses: List<EnrichmentDiagnosis> = emptyList(),
)
