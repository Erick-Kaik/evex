package br.com.bbchain.evex.indexer.domain.abi

/**
 * Detecção automática de interface padrão (doc de produto §4.5, `abi-strategy-design.md` §3) — função
 * pura, sem I/O (mesmo espírito de [br.com.bbchain.evex.indexer.domain.blockchain.PollWindow]).
 *
 * Para cada [InterfaceTemplate], calcula a interseção entre `observedTopic0s` e `template.eventTopic0s`:
 * - `FULL` (nível 1) quando a interseção é exatamente igual a `template.eventTopic0s` (não vazio) —
 *   todo evento do template foi observado no contrato.
 * - `PARTIAL` (nível 2) quando a interseção é não vazia mas não cobre o template inteiro.
 * - `NONE` (nível 3) quando não há interseção nenhuma.
 *
 * Resultado ordenado por [DetectionLevel] (`FULL` primeiro) e, dentro do mesmo nível, por
 * `eventTopic0s.size` decrescente — desempate de **ambiguidade real de `topic0`**: `topic0` é
 * `keccak256(nome+tipos)`, não inclui `indexed`, então `Transfer(address,address,uint256)` do ERC20 e
 * do ERC721 têm o **mesmo `topic0`**. Um contrato que emite os 3 eventos do ERC721 (`Transfer`,
 * `Approval`, `ApprovalForAll`) também dá match total no template ERC20 (subconjunto do observado) —
 * dois `FULL` simultâneos. O desempate por "mais eventos" escolhe o template mais específico (cobre
 * mais do que foi observado): ERC721 (3 eventos) vence ERC20 (2 eventos) nesse cenário. ERC1155 não
 * tem esse problema — `TransferSingle`/`TransferBatch` são assinaturas próprias, sem overlap com
 * ERC20/721.
 */
object InterfaceDetector {

    fun detect(observedTopic0s: Set<String>, templates: List<InterfaceTemplate>): List<InterfaceMatch> =
        templates
            .map { template ->
                val matched = template.eventTopic0s.intersect(observedTopic0s)
                val level = when {
                    matched.isNotEmpty() && matched == template.eventTopic0s -> DetectionLevel.FULL
                    matched.isNotEmpty() -> DetectionLevel.PARTIAL
                    else -> DetectionLevel.NONE
                }
                InterfaceMatch(
                    templateName = template.name,
                    level = level,
                    matchedTopic0s = matched,
                    missingTopic0s = template.eventTopic0s - matched,
                ) to template.eventTopic0s.size
            }
            .sortedWith(compareBy<Pair<InterfaceMatch, Int>> { it.first.level }.thenByDescending { it.second })
            .map { it.first }
}
