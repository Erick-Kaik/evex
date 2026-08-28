package br.com.bbchain.evex.indexer.domain.abi

/**
 * Resultado de comparar os `topic0`s observados de um contrato contra um [InterfaceTemplate]
 * (`abi-strategy-design.md` §3). `matchedTopic0s`/`missingTopic0s` são relativos só a este template
 * (não ao conjunto observado inteiro) — úteis pra UI mostrar exatamente o que faltou num match parcial.
 */
data class InterfaceMatch(
    val templateName: String,
    val level: DetectionLevel,
    val matchedTopic0s: Set<String>,
    val missingTopic0s: Set<String>,
)
