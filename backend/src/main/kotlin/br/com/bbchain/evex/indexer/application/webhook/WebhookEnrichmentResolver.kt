package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.application.common.WarnLogDeduper
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodResolver
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.MethodResolution
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiMethod
import br.com.bbchain.evex.indexer.domain.abi.signature
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainCallException
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainErrorClassification
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.enrichment.DiagnosisAttempt
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockStrategies
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallOutcome
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentDiagnosis
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentDiagnosisClassifier
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentFailureCodes
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentInput
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentResolution
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentResolveOptions
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Fase 2 do render de payload em 2 fases (E19/DEC-082) — chamada por [WebhookDispatchJob], já **fora**
 * de qualquer transação de banco (mesmo lugar simétrico de onde o `POST` HTTP acontece), imediatamente
 * antes de [br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort.send]. Resolve, em ordem,
 * todas as `EnrichmentCall` de todos os `enrichmentProfileIds` referenciados pelo
 * [br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig] da entrega — usando
 * [WebhookDelivery.blockNumber]/[WebhookDelivery.decodedParameters] (snapshot tirado na Fase 1) pra
 * montar os argumentos e resolver o `eth_call` **no bloco exato em que o evento ocorreu** — e faz uma
 * 2ª passada de substituição só nos tokens `{{enriched.*}}` que sobraram no `payload` já armazenado
 * (a 1ª passada, [WebhookPayloadRenderer.renderTemplate] com `reservedPrefixes`, os deixou intactos).
 *
 * Encadeamento: uma `EnrichmentCall` pode referenciar `{{enriched.x}}` de uma chamada anterior (deste
 * ou de outro perfil já resolvido antes na sequência de `enrichmentProfileIds`) — por isso a resolução
 * é sequencial, nunca paralela, acumulando um mapa de saídas já resolvidas conforme avança.
 *
 * **Resiliência (DEC-057)**: `try/catch` por [EnrichmentCall] — se uma falhar (contrato-alvo sem
 * versão de ABI vigente no bloco, método não encontrado, `eth_call` falhando/nó podado, placeholder
 * `{{enriched.*}}`/`{{decodedParameters.*}}` sem valor resolvido), os placeholders daquela chamada
 * ficam ausentes (nunca populados) — loga `WARN` (agregado por janela) e a resolução continua pras
 * próximas chamadas. Nunca deixa uma falha de leitura derrubar a entrega inteira.
 *
 * **Relatório por chamada (E33 / fatia 1)**: [resolveDetailed] devolve, além do payload, um
 * [EnrichmentCallOutcome] por chamada (código de falha extensível, erro bruto do nó, bloco usado),
 * aceita um [EnrichmentBlockSpec] (modo de bloco por estratégia) e [EnrichmentResolveOptions]
 * (circuit-breaker e diagnóstico, só para preview/reenvio). [resolve] continua sendo o wrapper do
 * disparo normal — mesmo retorno `String`, mesmo comportamento (placeholder `null` na falha, DEC-057).
 *
 * **Qual ABI vale**: `EVENT_BLOCK`/`BLOCK_NUMBER`/`BLOCK_AT_DATE` usam a versão de ABI vigente **no
 * bloco da chamada** (DEC-007, faixa `validFromBlock..validToBlock`); `LATEST` usa a versão **aberta**
 * (`validToBlock == null`, a vigente no bloco-cabeça) — sem versão aberta, `ABI_NOT_FOUND`.
 *
 * **Memoização** (por [EnrichmentExecutionContext], uma execução): ABI/métodos por (endereço, bloco) e
 * resultado — inclusive FALHA — por (contrato, método, args, bloco). Evita repetir `eth_call` falho e
 * os acessos a banco de ABI para eventos do mesmo bloco.
 */
@Service
class WebhookEnrichmentResolver(
    private val enrichmentProfiles: EnrichmentProfilePort,
    private val monitoredContracts: MonitoredContractPort,
    private val contractAbis: ContractAbiPort,
    private val abiContentReader: AbiContentReaderPort,
    private val abiMethodCatalog: AbiMethodCatalogPort,
    private val blockchain: BlockchainPort,
    private val blockStrategies: EnrichmentBlockStrategies = EnrichmentBlockStrategies.default(),
    indexerProperties: IndexerProperties = IndexerProperties(),
) {
    private val logger = LoggerFactory.getLogger(WebhookEnrichmentResolver::class.java)
    private val objectMapper = ObjectMapper()
    private val placeholderPattern = Regex("\\{\\{\\s*([\\w.]+)\\s*}}")
    private val enrichedPlaceholderPattern = Regex("\\{\\{\\s*(enriched\\.[\\w.]+)\\s*}}")

    // WARN agregado por janela (chave textual contrato|método|código|classificação|rpcCode) — 1 linha com
    // contador em vez de 1 linha por chamada falha.
    private val warnLogDeduper = WarnLogDeduper(indexerProperties.warnLogDedupeInterval)

    /**
     * Devolve [delivery.payload][WebhookDelivery.payload] com todo `{{enriched.*}}` restante
     * substituído pelo resultado das chamadas de [enrichmentProfileIds] resolvidas em
     * [delivery.blockNumber][WebhookDelivery.blockNumber]. `enrichmentProfileIds` vazia -> devolve o
     * `payload` sem tocar (regressão zero pra quem não usa a feature — nem `enrichmentProfiles.
     * findAllByIds` chega a ser chamado). Wrapper de [resolveDetailed] no modo `EVENT_BLOCK`.
     */
    fun resolve(delivery: WebhookDelivery, enrichmentProfileIds: List<UUID>): String =
        resolveDetailed(delivery, enrichmentProfileIds).payload

    /** [resolveDetailed] a partir de uma [WebhookDelivery] (bloco/parâmetros/payload da Fase 1). */
    fun resolveDetailed(
        delivery: WebhookDelivery,
        enrichmentProfileIds: List<UUID>,
        block: EnrichmentBlockSpec = EnrichmentBlockSpec.EventBlock,
        options: EnrichmentResolveOptions = EnrichmentResolveOptions.DEFAULT,
        context: EnrichmentExecutionContext = EnrichmentExecutionContext(options),
    ): EnrichmentResolution = resolveDetailed(
        EnrichmentInput(delivery.blockNumber, delivery.decodedParameters, delivery.payload, delivery.id),
        enrichmentProfileIds,
        block,
        options,
        context,
    )

    /**
     * Resolução com relatório por chamada. [block] define onde o `eth_call` roda (modo desconhecido ->
     * `UnknownEnrichmentBlockModeException`; spec inválida -> `InvalidEnrichmentBlockSpecException`).
     * Passe o mesmo [context] para vários eventos de uma requisição (preview/reenvio) para
     * compartilhar memoização e circuit-breaker; o padrão é um contexto novo por chamada.
     */
    fun resolveDetailed(
        input: EnrichmentInput,
        enrichmentProfileIds: List<UUID>,
        block: EnrichmentBlockSpec = EnrichmentBlockSpec.EventBlock,
        options: EnrichmentResolveOptions = EnrichmentResolveOptions.DEFAULT,
        context: EnrichmentExecutionContext = EnrichmentExecutionContext(options),
    ): EnrichmentResolution {
        // Valida o modo/spec ANTES de qualquer trabalho (erro claro para o chamador, nunca falha por chamada).
        val callBlock = blockStrategies.resolve(block, input.eventBlockNumber)
        if (enrichmentProfileIds.isEmpty()) {
            return EnrichmentResolution(input.payload, emptyList(), emptyMap(), unresolvedTokens(input.payload, emptyMap()), block)
        }

        val profilesById = enrichmentProfiles.findAllByIds(enrichmentProfileIds).associateBy { it.id }
        // findAllByIds não garante ordem (KDoc da porta) — reordena pela lista original, que é
        // significativa (encadeamento via {{enriched.*}} entre perfis).
        val orderedProfiles = enrichmentProfileIds.mapNotNull { profilesById[it] }

        val resolvedOutputs = linkedMapOf<String, String>()
        val failedPlaceholders = mutableMapOf<String, EnrichmentCallOutcome.Failure>()
        val outcomes = mutableListOf<EnrichmentCallOutcome>()
        val diagnoses = mutableListOf<EnrichmentDiagnosis>()
        for (profile in orderedProfiles) {
            for (call in profile.calls) {
                val outcome = executeCall(profile.id, call, input, callBlock, context, resolvedOutputs, failedPlaceholders, diagnoses)
                outcomes += outcome
                if (outcome is EnrichmentCallOutcome.Failure) {
                    call.outputs.forEach { failedPlaceholders["enriched.${it.placeholder}"] = outcome }
                }
            }
        }
        return EnrichmentResolution(
            payload = substituteEnriched(input.payload, resolvedOutputs),
            outcomes = outcomes,
            outputs = resolvedOutputs,
            unresolvedTokens = unresolvedTokens(input.payload, resolvedOutputs),
            blockSpec = block,
            diagnoses = diagnoses,
        )
    }

    private class ArgResolutionException(val code: String, message: String) : RuntimeException(message)

    private fun executeCall(
        profileId: UUID,
        call: EnrichmentCall,
        input: EnrichmentInput,
        callBlock: CallBlock?,
        context: EnrichmentExecutionContext,
        resolvedOutputs: MutableMap<String, String>,
        failedPlaceholders: Map<String, EnrichmentCallOutcome.Failure>,
        diagnoses: MutableList<EnrichmentDiagnosis>,
    ): EnrichmentCallOutcome {
        val address = call.targetContractAddress.lowercase()

        fun failure(code: String, message: String, rpcCode: Int? = null, rpcData: String? = null, classification: String? = null) =
            EnrichmentCallOutcome.Failure(profileId, call, callBlock, code, message, rpcCode, rpcData, classification)

        fun failureLogged(code: String, message: String, rpcCode: Int? = null, rpcData: String? = null, classification: String? = null) =
            failure(code, message, rpcCode, rpcData, classification).also { logFailure(it, address, input) }

        if (callBlock == null) {
            return failureLogged(
                EnrichmentFailureCodes.BLOCK_UNRESOLVED,
                "bloco da chamada não pôde ser determinado (evento/entrega sem blockNumber — não é possível resolver eth_call histórico)",
            )
        }

        val methods = when (val lookup = context.abiMethods.getOrPut("$address|${callBlock.key()}") { loadAbiMethods(address, callBlock) }) {
            is AbiLookup.Failed -> return failureLogged(lookup.code, lookup.message)
            is AbiLookup.Methods -> lookup.methods
        }
        val method = when (val resolution = AbiMethodResolver.resolve(methods, call.methodName, call.args.size)) {
            is MethodResolution.Found -> resolution.method
            is MethodResolution.NameNotFound -> return failureLogged(
                EnrichmentFailureCodes.METHOD_NOT_FOUND,
                "método '${call.methodName}' não encontrado (ou não é view/pure) na ABI de $address",
            )
            is MethodResolution.ArityMismatch -> return failureLogged(
                EnrichmentFailureCodes.METHOD_NOT_FOUND,
                "método '${call.methodName}' não tem sobrecarga com ${call.args.size} argumento(s) na ABI de $address — disponíveis: ${resolution.availableSignatures.joinToString()}",
            )
            is MethodResolution.Ambiguous -> return failureLogged(
                EnrichmentFailureCodes.METHOD_AMBIGUOUS,
                "método '${call.methodName}' é ambíguo para ${call.args.size} argumento(s) em $address: ${resolution.candidates.joinToString()} — informe a assinatura canônica",
            )
        }

        val args = try {
            call.args.map { resolveArg(it, input, resolvedOutputs, failedPlaceholders) }
        } catch (ex: ArgResolutionException) {
            return if (ex.code == EnrichmentFailureCodes.SKIPPED_DEPENDENCY) {
                logger.debug("EnrichmentCall pulada (referenceId={}, {}.{}): {}", input.referenceId, address, call.methodName, ex.message)
                failure(ex.code, ex.message.orEmpty())
            } else {
                failureLogged(ex.code, ex.message.orEmpty())
            }
        }

        val memoKey = EnrichmentExecutionContext.CallKey(address, method.signature(), args, callBlock.key())
        val breakerKey = "$address|${method.signature()}|${callBlock.key()}"
        val cached = context.callMemo[memoKey]
        val result: CallResult
        if (cached != null) {
            result = cached
        } else {
            context.trippedFingerprint(breakerKey)?.let { fingerprint ->
                return failure(
                    EnrichmentFailureCodes.SKIPPED_AFTER_REPEATED_FAILURE,
                    "chamada não executada: ${context.options.circuitBreakerThreshold} falha(s) idêntica(s) ($fingerprint) " +
                        "já ocorreram para $address.${method.name} em ${callBlock} nesta execução",
                )
            }
            result = invoke(address, method, args, callBlock)
            context.callMemo[memoKey] = result
            when (result) {
                is CallResult.Ok -> context.registerSuccess(breakerKey)
                is CallResult.Err -> {
                    context.registerFailure(breakerKey, result.fingerprint())
                    logFailure(failure(result.code, result.message, result.rpcCode, result.rpcData, result.classification), address, input)
                    if (context.options.diagnose) {
                        diagnoses += diagnose(
                            failure(result.code, result.message, result.rpcCode, result.rpcData, result.classification),
                            address, method, args, callBlock, input.eventBlockNumber,
                        )
                    }
                }
            }
        }

        return when (result) {
            is CallResult.Err -> failure(result.code, result.message, result.rpcCode, result.rpcData, result.classification)
            is CallResult.Ok -> {
                val outputs = linkedMapOf<String, String>()
                call.outputs.forEach { mapping ->
                    if (mapping.outputIndex in result.values.indices) {
                        outputs[mapping.placeholder] = result.values[mapping.outputIndex]
                        resolvedOutputs["enriched.${mapping.placeholder}"] = result.values[mapping.outputIndex]
                    }
                }
                EnrichmentCallOutcome.Success(profileId, call, callBlock, outputs)
            }
        }
    }

    private fun loadAbiMethods(address: String, callBlock: CallBlock): AbiLookup = try {
        // 404 cedo se o contrato-alvo não estiver cadastrado — mesma checagem de qualquer outro
        // fluxo que resolve ABI por endereço monitorado.
        if (monitoredContracts.findByAddress(address) == null) {
            AbiLookup.Failed(EnrichmentFailureCodes.CONTRACT_NOT_REGISTERED, "targetContractAddress não cadastrado: $address")
        } else {
            val versions = contractAbis.findAllByContractAddress(address)
            val abi = when (callBlock) {
                is CallBlock.Number -> versions.find {
                    callBlock.value >= it.validFromBlock && (it.validToBlock == null || callBlock.value <= it.validToBlock)
                }
                // LATEST: a versão aberta (vigente no bloco-cabeça); se houver mais de uma, a mais recente.
                CallBlock.Latest -> versions.filter { it.validToBlock == null }.maxByOrNull { it.validFromBlock }
            }
            if (abi == null) {
                AbiLookup.Failed(
                    EnrichmentFailureCodes.ABI_NOT_FOUND,
                    when (callBlock) {
                        is CallBlock.Number -> "nenhuma versão de ABI vigente no bloco ${callBlock.value} para $address"
                        CallBlock.Latest -> "nenhuma versão de ABI aberta (vigente no bloco mais recente) para $address"
                    },
                )
            } else {
                AbiLookup.Methods(abiMethodCatalog.methodsOf(abiContentReader.read(abi)))
            }
        }
    } catch (ex: Exception) {
        AbiLookup.Failed(EnrichmentFailureCodes.UNEXPECTED_ERROR, "falha ao carregar a ABI de $address: ${ex.message ?: ex.javaClass.simpleName}")
    }

    /** Executa o `eth_call` e converte qualquer falha em [CallResult.Err] (nunca lança). */
    private fun invoke(address: String, method: ParsedAbiMethod, args: List<String>, block: CallBlock): CallResult = try {
        CallResult.Ok(
            blockchain.call(
                contractAddress = address,
                methodName = method.name,
                inputTypes = method.inputs.map { it.type },
                outputTypes = method.outputs.map { it.type },
                args = args,
                block = block,
            ),
        )
    } catch (ex: BlockchainCallException) {
        CallResult.Err(
            EnrichmentFailureCodes.RPC_ERROR,
            ex.message.orEmpty(),
            ex.code,
            ex.data?.take(MAX_RPC_DATA_LENGTH),
            ex.classification,
        )
    } catch (ex: IllegalArgumentException) {
        CallResult.Err(EnrichmentFailureCodes.ENCODING_ERROR, ex.message ?: ex.javaClass.simpleName)
    } catch (ex: UnsupportedOperationException) {
        CallResult.Err(EnrichmentFailureCodes.ENCODING_ERROR, ex.message ?: ex.javaClass.simpleName)
    } catch (ex: Exception) {
        CallResult.Err(
            EnrichmentFailureCodes.UNEXPECTED_ERROR,
            ex.message ?: ex.javaClass.simpleName,
            classification = BlockchainErrorClassification.UNCLASSIFIED,
        )
    }

    /**
     * Diagnóstico comparativo (INDÍCIO, nunca causa): reexecuta a MESMA chamada em LATEST e no bloco do
     * evento (quando diferentes do bloco que falhou) e roda a sonda de estado no bloco da chamada. Só
     * executa sob `diagnose=true` (preview) — nunca no disparo normal.
     */
    private fun diagnose(
        failure: EnrichmentCallOutcome.Failure,
        address: String,
        method: ParsedAbiMethod,
        args: List<String>,
        requested: CallBlock,
        eventBlockNumber: Long?,
    ): EnrichmentDiagnosis {
        fun attempt(block: CallBlock): DiagnosisAttempt {
            val r = invoke(address, method, args, block)
            return DiagnosisAttempt(
                block,
                (r as? CallResult.Err)?.let {
                    EnrichmentCallOutcome.Failure(failure.profileId, failure.call, block, it.code, it.message, it.rpcCode, it.rpcData, it.classification)
                },
            )
        }

        val atLatest = if (requested != CallBlock.Latest) attempt(CallBlock.Latest) else null
        val eventBlock = eventBlockNumber?.let { CallBlock.Number(it) }?.takeIf { it != requested }
        val atEventBlock = eventBlock?.let { attempt(it) }
        val probeBlock = (requested as? CallBlock.Number)?.value ?: eventBlockNumber
        val probe = probeBlock?.let {
            try {
                blockchain.probeStateAt(address, it)
            } catch (ex: Exception) {
                ProbeResult.Failed(null, null, null, BlockchainErrorClassification.UNCLASSIFIED)
            }
        }
        return EnrichmentDiagnosis(failure, atLatest, atEventBlock, probe, EnrichmentDiagnosisClassifier.classify(atLatest, atEventBlock))
    }

    /** WARN agregado por janela — só tipo/código/classificação/rpcCode (sem URLs nem corpo do erro; detalhe completo vai no relatório e em DEBUG). */
    private fun logFailure(failure: EnrichmentCallOutcome.Failure, address: String, input: EnrichmentInput) {
        val key = "$address|${failure.call.methodName}|${failure.code}|${failure.classification}|${failure.rpcCode}"
        val occurrences = warnLogDeduper.occurrencesIfDue(key)
        if (occurrences != null) {
            logger.warn(
                "Falha ao resolver EnrichmentCall (targetContractAddress={}, methodName={}, code={}, classification={}, rpcCode={}, ocorrências na janela={}, referenceId={})",
                address,
                failure.call.methodName,
                failure.code,
                failure.classification,
                failure.rpcCode,
                occurrences,
                input.referenceId,
            )
        }
        logger.debug("Detalhe da falha de EnrichmentCall ({}.{}): {}", address, failure.call.methodName, failure.message)
    }

    /**
     * Substitui um único argumento — literal, `{{decodedParameters.x}}` ou `{{enriched.y}}` (de uma
     * chamada já resolvida antes nesta mesma sequência). Placeholder sem valor resolvido lança (a
     * chamada inteira falha e é pulada, DEC-057) — não faz sentido enviar `eth_call` com um argumento
     * parcialmente resolvido. `{{enriched.y}}` cuja chamada de origem FALHOU vira `SKIPPED_DEPENDENCY`
     * (com a causa), não falha opaca.
     */
    private fun resolveArg(
        arg: String,
        input: EnrichmentInput,
        resolvedOutputs: Map<String, String>,
        failedPlaceholders: Map<String, EnrichmentCallOutcome.Failure>,
    ): String {
        var unresolved = false
        val substituted = placeholderPattern.replace(arg) { match ->
            val key = match.groupValues[1]
            val value: Any? = when {
                key.startsWith("decodedParameters.") -> input.decodedParameters?.get(key.removePrefix("decodedParameters."))
                key.startsWith("enriched.") -> {
                    if (!resolvedOutputs.containsKey(key)) {
                        val cause = failedPlaceholders[key]
                        if (cause != null) {
                            throw ArgResolutionException(
                                EnrichmentFailureCodes.SKIPPED_DEPENDENCY,
                                "depende de {{$key}}, cuja chamada (${cause.call.targetContractAddress.lowercase()}.${cause.call.methodName}) " +
                                    "falhou com ${cause.code}",
                            )
                        }
                        unresolved = true
                    }
                    resolvedOutputs[key]
                }
                else -> null
            }
            if (value == null) unresolved = true
            value?.toString().orEmpty()
        }
        if (unresolved) {
            throw ArgResolutionException(
                EnrichmentFailureCodes.ARG_UNRESOLVED,
                "argumento '$arg' não pôde ser resolvido (placeholder ausente/sem valor)",
            )
        }
        return substituted
    }

    /** `{{enriched.*}}` presentes no payload que nenhuma chamada produziu (inclui typo de placeholder). */
    private fun unresolvedTokens(payload: String, resolvedOutputs: Map<String, String>): List<String> =
        enrichedPlaceholderPattern.findAll(payload).map { it.groupValues[1] }.distinct().filterNot { it in resolvedOutputs }.toList()

    /** 2ª passada — só troca `{{enriched.*}}`; qualquer outro token remanescente (não deveria haver) fica intacto. */
    private fun substituteEnriched(payload: String, resolvedOutputs: Map<String, String>): String =
        enrichedPlaceholderPattern.replace(payload) { match ->
            val key = match.groupValues[1]
            toJsonLiteral(resolvedOutputs[key])
        }

    private fun toJsonLiteral(value: Any?): String = when (value) {
        null -> "null"
        else -> objectMapper.writeValueAsString(value.toString())
    }

    private companion object {
        const val MAX_RPC_DATA_LENGTH = 2000
    }
}

/** Resultado memoizável da carga de ABI/métodos por (endereço, bloco). */
internal sealed interface AbiLookup {
    data class Methods(val methods: List<ParsedAbiMethod>) : AbiLookup
    data class Failed(val code: String, val message: String) : AbiLookup
}

/** Resultado memoizável de um `eth_call` (sucesso ou falha) — interno ao resolver/contexto. */
internal sealed interface CallResult {
    data class Ok(val values: List<String>) : CallResult
    data class Err(
        val code: String,
        val message: String,
        val rpcCode: Int? = null,
        val rpcData: String? = null,
        val classification: String? = null,
    ) : CallResult {
        /** Identidade de "falha idêntica" para o circuit-breaker. */
        fun fingerprint(): String = "code=$code, rpcCode=$rpcCode, classification=$classification"
    }
}

/**
 * Estado de UMA execução de enriquecimento (ou de uma requisição de preview/reenvio que processa N
 * eventos): memoização de ABI/métodos e de resultados (inclusive falhas) e contadores do
 * circuit-breaker. **Não é thread-safe** — uma execução = uma thread (dispatch single-thread / request).
 */
class EnrichmentExecutionContext(val options: EnrichmentResolveOptions = EnrichmentResolveOptions.DEFAULT) {
    internal data class CallKey(val address: String, val signature: String, val args: List<String>, val blockKey: String)

    internal val abiMethods = HashMap<String, AbiLookup>()
    internal val callMemo = HashMap<CallKey, CallResult>()

    // breakerKey -> (fingerprint -> contagem consecutiva)
    private val failureCounts = HashMap<String, MutableMap<String, Int>>()

    internal fun registerFailure(breakerKey: String, fingerprint: String) {
        if (options.circuitBreakerThreshold == null) return
        failureCounts.getOrPut(breakerKey) { mutableMapOf() }.merge(fingerprint, 1, Int::plus)
    }

    internal fun registerSuccess(breakerKey: String) {
        failureCounts.remove(breakerKey)
    }

    /** Fingerprint que atingiu o limite para [breakerKey], ou `null` se o breaker está desligado/não disparou. */
    internal fun trippedFingerprint(breakerKey: String): String? {
        val threshold = options.circuitBreakerThreshold ?: return null
        return failureCounts[breakerKey]?.entries?.firstOrNull { it.value >= threshold }?.key
    }
}
