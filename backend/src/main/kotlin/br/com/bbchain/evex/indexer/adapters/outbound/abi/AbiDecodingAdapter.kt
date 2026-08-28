package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiDecodingPort
import br.com.bbchain.evex.indexer.domain.abi.DecodedEvent
import br.com.bbchain.evex.indexer.domain.abi.RawLog
import org.springframework.stereotype.Component
import org.web3j.abi.EventValues
import org.web3j.abi.datatypes.Type
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import org.web3j.utils.Numeric

/**
 * Implementação de [AbiDecodingPort] — lê **só** do [AbiCache] (nunca banco/disco, regra
 * não-negociável do projeto): resolve a versão de ABI vigente pro bloco informado, casa o `topic0`
 * do log com um [EventDescriptor] conhecido, decodifica via
 * `Contract.staticExtractEventParameters` (a única borda onde [RawLog] vira
 * `org.web3j.protocol.core.methods.response.Log`, pra não vazar o tipo do Web3j pro domain) e nomeia
 * os parâmetros na ordem declarada no ABI.
 */
@Component
class AbiDecodingAdapter(
    private val cache: AbiCache,
) : AbiDecodingPort {

    override fun decode(contractAddress: String, blockNumber: Long, log: RawLog): DecodedEvent? {
        val topic0 = log.topics.firstOrNull() ?: return null
        val version = cache.versionsFor(contractAddress).firstOrNull { version ->
            blockNumber >= version.validFromBlock && (version.validToBlock == null || blockNumber <= version.validToBlock)
        } ?: return null
        val descriptor = version.eventDescriptors.firstOrNull { it.topic0.equals(topic0, ignoreCase = true) } ?: return null

        val web3jLog = Log().apply {
            topics = log.topics
            data = log.data
        }
        val values = Contract.staticExtractEventParameters(descriptor.event, web3jLog) ?: return null

        return DecodedEvent(eventName = descriptor.name, parameters = namedParameters(descriptor, values))
    }

    private fun namedParameters(descriptor: EventDescriptor, values: EventValues): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        var indexedIndex = 0
        var nonIndexedIndex = 0
        for (input in descriptor.inputs) {
            val typedValue: Type<*> = if (input.indexed) {
                values.indexedValues[indexedIndex++]
            } else {
                values.nonIndexedValues[nonIndexedIndex++]
            }
            result[input.name] = formatValue(typedValue.value)
        }
        return result
    }

    /**
     * Formata `BigInteger`/`ByteArray`/`List` pro payload nomeado, replicando o código base
     * (`abi-strategy-design.md`, "Reaproveitado"): `ByteArray` (`bytes`/`bytesN`) vira hex string;
     * `List` (arrays Solidity) mapeia elemento a elemento — cada elemento de um `Array<Type>` é ele
     * mesmo um `Type` do Web3j, então precisa descer mais um nível (`.value`) antes de formatar.
     * `BigInteger`/`String`/`Boolean`/etc. já estão no formato final, passam direto.
     */
    private fun formatValue(raw: Any?): Any? = when (raw) {
        is ByteArray -> Numeric.toHexString(raw)
        is List<*> -> raw.map { element -> if (element is Type<*>) formatValue(element.value) else formatValue(element) }
        else -> raw
    }
}
