package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Assinatura `Upgraded(address indexed implementation)` (ERC-1967/UUPS/Transparent) — único dono da
 * constante de `topic0` e do parse/formatação do `topic1`. O valor é conferido contra o
 * `EventEncoder` do Web3j em teste (`UpgradedEventTest`); o domain não importa Web3j.
 */
object UpgradedEvent {
    /** keccak256("Upgraded(address)"). `const val` para poder ser interpolada em `@Query` nativa. */
    const val TOPIC0 = "0xbc7cd75a20ee27fd9adebab32041f755214dbc6bffa90cc0225b39da2e5c2d3b"

    private val TOPIC_REGEX = Regex("^0x[0-9a-fA-F]{64}$")
    private const val ZERO_PADDING = "000000000000000000000000"

    /** `topic1` (32 bytes, endereço à direita) para o [address] informado; lowercase. */
    fun topicFor(address: String): String =
        "0x" + ZERO_PADDING + address.removePrefix("0x").removePrefix("0X").lowercase()

    /**
     * Extrai o endereço (lowercase) de um `topic1`; `null` se não for `0x` + 64 hex ou se os 12 bytes
     * iniciais não forem zero (não é um endereço).
     */
    fun addressFromTopic(topic: String?): String? {
        if (topic == null || !TOPIC_REGEX.matches(topic)) return null
        val hex = topic.substring(2).lowercase()
        if (!hex.startsWith(ZERO_PADDING)) return null
        return "0x" + hex.substring(24)
    }
}
