package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
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
 * ficam ausentes (nunca populados) — loga `WARN` e a resolução continua pras próximas chamadas. Nunca
 * deixa uma falha de leitura derrubar a entrega inteira.
 */
@Service
class WebhookEnrichmentResolver(
    private val enrichmentProfiles: EnrichmentProfilePort,
    private val monitoredContracts: MonitoredContractPort,
    private val contractAbis: ContractAbiPort,
    private val abiContentReader: AbiContentReaderPort,
    private val abiMethodCatalog: AbiMethodCatalogPort,
    private val blockchain: BlockchainPort,
) {
    private val logger = LoggerFactory.getLogger(WebhookEnrichmentResolver::class.java)
    private val objectMapper = ObjectMapper()
    private val placeholderPattern = Regex("\\{\\{\\s*([\\w.]+)\\s*}}")
    private val enrichedPlaceholderPattern = Regex("\\{\\{\\s*(enriched\\.[\\w.]+)\\s*}}")

    /**
     * Devolve [delivery.payload][WebhookDelivery.payload] com todo `{{enriched.*}}` restante
     * substituído pelo resultado das chamadas de [enrichmentProfileIds] resolvidas em
     * [delivery.blockNumber][WebhookDelivery.blockNumber]. `enrichmentProfileIds` vazia -> devolve o
     * `payload` sem tocar (regressão zero pra quem não usa a feature — nem `enrichmentProfiles.
     * findAllByIds` chega a ser chamado).
     */
    fun resolve(delivery: WebhookDelivery, enrichmentProfileIds: List<UUID>): String {
        if (enrichmentProfileIds.isEmpty()) return delivery.payload

        val profilesById = enrichmentProfiles.findAllByIds(enrichmentProfileIds).associateBy { it.id }
        // findAllByIds não garante ordem (KDoc da porta) — reordena pela lista original, que é
        // significativa (encadeamento via {{enriched.*}} entre perfis).
        val orderedProfiles = enrichmentProfileIds.mapNotNull { profilesById[it] }

        val resolvedOutputs = mutableMapOf<String, Any?>()
        for (profile in orderedProfiles) {
            for (call in profile.calls) {
                resolveCallSafely(call, delivery, resolvedOutputs)
            }
        }
        return substituteEnriched(delivery.payload, resolvedOutputs)
    }

    private fun resolveCallSafely(call: EnrichmentCall, delivery: WebhookDelivery, resolvedOutputs: MutableMap<String, Any?>) {
        try {
            val blockNumber = delivery.blockNumber
                ?: throw IllegalStateException("WebhookDelivery sem blockNumber — não é possível resolver eth_call histórico.")
            val normalizedAddress = call.targetContractAddress.lowercase()
            // 404 cedo se o contrato-alvo não estiver cadastrado — mesma checagem de qualquer outro
            // fluxo que resolve ABI por endereço monitorado.
            monitoredContracts.findByAddress(normalizedAddress)
                ?: throw IllegalStateException("targetContractAddress não cadastrado: $normalizedAddress")
            val abi = contractAbis.findAllByContractAddress(normalizedAddress).find {
                blockNumber >= it.validFromBlock && (it.validToBlock == null || blockNumber <= it.validToBlock)
            } ?: throw IllegalStateException("nenhuma versão de ABI vigente no bloco $blockNumber para $normalizedAddress")
            val method = abiMethodCatalog.methodsOf(abiContentReader.read(abi)).find { it.name == call.methodName }
                ?: throw IllegalStateException("método '${call.methodName}' não encontrado (ou não é view/pure) na ABI de $normalizedAddress")

            val args = call.args.map { resolveArg(it, delivery, resolvedOutputs) }
            val results = blockchain.call(
                contractAddress = normalizedAddress,
                methodName = call.methodName,
                inputTypes = method.inputs.map { it.type },
                outputTypes = method.outputs.map { it.type },
                args = args,
                blockNumber = blockNumber,
            )
            call.outputs.forEach { mapping ->
                if (mapping.outputIndex in results.indices) {
                    resolvedOutputs["enriched.${mapping.placeholder}"] = results[mapping.outputIndex]
                }
            }
        } catch (ex: Exception) {
            logger.warn(
                "Falha ao resolver EnrichmentCall (targetContractAddress={}, methodName={}) pra webhookDelivery={}: {}",
                call.targetContractAddress,
                call.methodName,
                delivery.id,
                ex.message,
            )
        }
    }

    /**
     * Substitui um único argumento — literal, `{{decodedParameters.x}}` ou `{{enriched.y}}` (de uma
     * chamada já resolvida antes nesta mesma sequência). Placeholder sem valor resolvido lança (a
     * chamada inteira falha e é pulada, DEC-057) — não faz sentido enviar `eth_call` com um argumento
     * parcialmente resolvido.
     */
    private fun resolveArg(arg: String, delivery: WebhookDelivery, resolvedOutputs: Map<String, Any?>): String {
        var unresolved = false
        val substituted = placeholderPattern.replace(arg) { match ->
            val key = match.groupValues[1]
            val value: Any? = when {
                key.startsWith("decodedParameters.") -> delivery.decodedParameters?.get(key.removePrefix("decodedParameters."))
                key.startsWith("enriched.") -> {
                    if (!resolvedOutputs.containsKey(key)) unresolved = true
                    resolvedOutputs[key]
                }
                else -> null
            }
            if (value == null) unresolved = true
            value?.toString().orEmpty()
        }
        if (unresolved) throw IllegalStateException("argumento '$arg' não pôde ser resolvido (placeholder ausente/sem valor)")
        return substituted
    }

    /** 2ª passada — só troca `{{enriched.*}}`; qualquer outro token remanescente (não deveria haver) fica intacto. */
    private fun substituteEnriched(payload: String, resolvedOutputs: Map<String, Any?>): String =
        enrichedPlaceholderPattern.replace(payload) { match ->
            val key = match.groupValues[1]
            toJsonLiteral(resolvedOutputs[key])
        }

    private fun toJsonLiteral(value: Any?): String = when (value) {
        null -> "null"
        else -> objectMapper.writeValueAsString(value.toString())
    }
}
