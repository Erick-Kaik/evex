package br.com.bbchain.evex.indexer.domain.abi

/**
 * Diferença entre duas versões de ABI (DEC-105): eventos e métodos (funções) adicionados/removidos,
 * identificados pela assinatura canônica `nome(tipo1,tipo2)`. Listas ordenadas, sem duplicatas.
 *
 * Limitação: os tipos vêm de [ParsedAbiEntry] (campo `type` bruto); `tuple`/`tuple[]` aparecem como
 * tal, sem expandir componentes — duas versões que só diferem nos componentes de uma tupla aparecem
 * como idênticas.
 */
data class AbiDiff(
    val eventsAdded: List<String>,
    val eventsRemoved: List<String>,
    val methodsAdded: List<String>,
    val methodsRemoved: List<String>,
) {
    /** `true` se nenhum evento/método foi adicionado ou removido. */
    val identical: Boolean
        get() = eventsAdded.isEmpty() && eventsRemoved.isEmpty() && methodsAdded.isEmpty() && methodsRemoved.isEmpty()

    companion object {
        fun compute(prev: List<ParsedAbiEntry>, curr: List<ParsedAbiEntry>): AbiDiff {
            val prevEvents = signatures(prev, AbiEntryKind.EVENT)
            val currEvents = signatures(curr, AbiEntryKind.EVENT)
            val prevMethods = signatures(prev, AbiEntryKind.FUNCTION)
            val currMethods = signatures(curr, AbiEntryKind.FUNCTION)
            return AbiDiff(
                eventsAdded = (currEvents - prevEvents).sorted(),
                eventsRemoved = (prevEvents - currEvents).sorted(),
                methodsAdded = (currMethods - prevMethods).sorted(),
                methodsRemoved = (prevMethods - currMethods).sorted(),
            )
        }

        /** Assinatura canônica `nome(tipos)` de um item nomeado; `null` se o item não tem nome. */
        fun signatureOf(entry: ParsedAbiEntry): String? =
            entry.name?.let { name -> "$name(${entry.inputs.joinToString(",") { it.type }})" }

        private fun signatures(entries: List<ParsedAbiEntry>, kind: AbiEntryKind): Set<String> =
            entries.filter { it.kind == kind }.mapNotNull(::signatureOf).toSet()
    }
}
