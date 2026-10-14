package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Compõe a ABI efetiva de um proxy (DEC-105): ABI da implementação + os eventos/erros do proxy que a
 * implementação não declara (ex.: `Upgraded`/`AdminChanged`/`BeaconUpgraded`), para que trocar a ABI
 * do proxy pela da implementação não deixe de decodificar o próprio evento de upgrade.
 *
 * Os itens da implementação vêm primeiro e prevalecem; só entram do proxy os itens `event`/`error`
 * cuja chave (tipo + nome + tipos das entradas) ainda não existe. Funções/constructor/fallback do
 * proxy não são trazidos (a implementação é quem responde pelas chamadas). Usa Jackson (biblioteca
 * pura de JSON, sem Spring) só para manipular o texto da ABI.
 */
object ProxyAbiComposer {
    private val mapper = ObjectMapper()
    private val MERGED_TYPES = setOf("event", "error")

    /**
     * @param proxyAbi ABI do proxy (JSON) ou `null` se o proxy não tem ABI própria — devolve a da implementação.
     * @throws InvalidAbiContentException se alguma das ABIs não for um array JSON.
     */
    fun compose(implAbi: String, proxyAbi: String?): String {
        val impl = parseArray(implAbi, "da implementação")
        if (proxyAbi.isNullOrBlank()) return mapper.writeValueAsString(impl)
        val proxy = parseArray(proxyAbi, "do proxy")

        val seen = impl.map(::keyOf).toMutableSet()
        val result = mapper.createArrayNode().addAll(impl)
        proxy.filter { it.path("type").asText() in MERGED_TYPES }.forEach { item ->
            if (seen.add(keyOf(item))) result.add(item)
        }
        return mapper.writeValueAsString(result)
    }

    private fun keyOf(item: JsonNode): String =
        item.path("type").asText() + ":" + item.path("name").asText("") + "(" +
            item.path("inputs").joinToString(",") { it.path("type").asText("") } + ")"

    private fun parseArray(json: String, label: String): com.fasterxml.jackson.databind.node.ArrayNode {
        val root = try {
            mapper.readTree(json)
        } catch (ex: Exception) {
            throw InvalidAbiContentException("ABI $label não é um JSON válido: ${ex.message}", ex)
        }
        if (root == null || !root.isArray) {
            throw InvalidAbiContentException("ABI $label deve ser um array JSON.")
        }
        return root as com.fasterxml.jackson.databind.node.ArrayNode
    }
}
