package br.com.bbchain.evex.indexer.adapters.outbound.webhook

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Renderiza o corpo `POST` de um webhook (doc §4.6, decisão tomada com o usuário nesta etapa: payload
 * customizável via template com placeholders, não um envelope fixo obrigatório).
 *
 * [renderDefault]: envelope JSON padrão (usado quando `WebhookConfig.payloadTemplate` é `null`).
 * [renderTemplate]: substitui `{{campo}}`/`{{decodedParameters.nome}}` por interpolação simples
 * (regex, sem template engine externo) — chave desconhecida vira `null` bare, sintaticamente seguro em
 * posição de valor JSON. [validateTemplate]: usado só no cadastro/edição (`WebhookConfigService`), pra
 * pegar template malformado na hora, não silenciosamente no primeiro disparo real.
 *
 * `ObjectMapper` local (Jackson 2, DEC-020) — mesmo padrão do resto do projeto.
 */
@Component
class WebhookPayloadRenderer {

    private val objectMapper = ObjectMapper()
    private val placeholderPattern = Regex("\\{\\{\\s*([\\w.]+)\\s*}}")

    fun renderDefault(event: BlockchainEvent): String {
        val envelope = linkedMapOf<String, Any?>(
            "contractAddress" to event.contractAddress,
            "network" to event.network.name,
            "eventName" to event.eventName,
            "blockNumber" to event.blockNumber,
            "blockHash" to event.blockHash,
            "txHash" to event.txHash,
            "logIndex" to event.logIndex,
            "blockTimestamp" to event.blockTimestamp?.toString(),
            "decodedParameters" to event.decodedParameters,
        )
        return objectMapper.writeValueAsString(envelope)
    }

    fun renderTemplate(template: String, event: BlockchainEvent): String =
        placeholderPattern.replace(template) { match -> toJsonLiteral(resolvePlaceholder(match.groupValues[1], event)) }

    /** Renderiza [template] com um evento-dummy e tenta parsear o resultado como JSON — nunca lança. */
    fun validateTemplate(template: String): Boolean =
        try {
            objectMapper.readTree(renderTemplate(template, DUMMY_EVENT))
            true
        } catch (ex: Exception) {
            false
        }

    private fun resolvePlaceholder(key: String, event: BlockchainEvent): Any? {
        if (key.startsWith("decodedParameters.")) {
            return event.decodedParameters?.get(key.removePrefix("decodedParameters."))
        }
        return when (key) {
            "contractAddress" -> event.contractAddress
            "network" -> event.network.name
            "eventName" -> event.eventName
            "blockNumber" -> event.blockNumber
            "blockHash" -> event.blockHash
            "txHash" -> event.txHash
            "logIndex" -> event.logIndex
            "blockTimestamp" -> event.blockTimestamp?.toString()
            else -> null
        }
    }

    /** `null`/número/booleano viram literal bare; qualquer outra coisa vira string JSON escapada. */
    private fun toJsonLiteral(value: Any?): String = when (value) {
        null -> "null"
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> objectMapper.writeValueAsString(value.toString())
    }

    private companion object {
        val DUMMY_EVENT = BlockchainEvent(
            id = UUID.randomUUID(),
            contractAddress = "0x0000000000000000000000000000000000dead",
            network = Network.TESTNET,
            blockNumber = 0,
            blockHash = "0x0",
            txHash = "0x0",
            logIndex = 0,
            topic0 = null,
            rawTopics = emptyList(),
            rawData = "0x",
            decoded = true,
            eventName = "Dummy",
            decodedParameters = mapOf("amount" to BigInteger.ONE),
            blockTimestamp = Instant.EPOCH,
            createdAt = Instant.EPOCH,
        )
    }
}
