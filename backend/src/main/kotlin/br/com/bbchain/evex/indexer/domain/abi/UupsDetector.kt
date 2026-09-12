package br.com.bbchain.evex.indexer.domain.abi

/**
 * Resultado da detecção de proxy upgradável UUPS/ERC-1822 (E28) a partir de um catálogo de ABI já
 * filtrado para a versão vigente do contrato (ver [UupsDetector.detect]).
 */
data class UupsDetectionResult(
    val isUpgradeable: Boolean,
    val availableUpgradeMethods: List<String>,
)

/**
 * Detecção de proxy upgradável UUPS/ERC-1822 (doc de produto, E28) — função pura, sem I/O (mesmo
 * espírito de [InterfaceDetector]/[br.com.bbchain.evex.indexer.domain.blockchain.PollWindow]). Só
 * **leitura/detecção**: nenhuma ação de escrita entra no Evex (não assina nem envia transação de
 * upgrade) — isso é escopo de um projeto separado.
 *
 * Regra de detecção (ver `docs/decisions.md`):
 * - `proxiableUUID()` (sem argumentos) é o **marcador padrão/autoritativo** do ERC-1822 em si —
 *   presença dele já basta pra considerar o contrato upgradeable, independente de ter ou não os
 *   métodos de upgrade declarados no ABI cadastrado.
 * - `upgradeTo(address)` e `upgradeToAndCall(address,bytes)` são os métodos de upgrade do
 *   `UUPSUpgradeable` da OpenZeppelin (implementação de referência mais comum, mas não exigidos pelo
 *   ERC-1822 em si) — reportados em [UupsDetectionResult.availableUpgradeMethods] porque são "o
 *   caminho que o usuário chamaria" (motivação original da feature), mesmo quando `proxiableUUID()`
 *   também está presente.
 * - `isUpgradeable = tem proxiableUUID() OU tem pelo menos um dos 2 métodos de upgrade` — mais robusto
 *   que exigir só `proxiableUUID()`: cobre ABI parcial/curada colada manualmente pelo usuário (cenário
 *   real do cadastro por texto/upload, doc §4.1), onde `proxiableUUID()` pode ter sido omitido mas os
 *   métodos de upgrade não.
 *
 * Casamento por **nome + tipos dos parâmetros na ordem certa** (não só nome) — evita falso positivo de
 * uma função homônima com assinatura diferente (ex.: `upgradeTo(uint256)` não é o `upgradeTo(address)`
 * do UUPS).
 */
object UupsDetector {

    private val PROXIABLE_UUID = FunctionSignature(name = "proxiableUUID", paramTypes = emptyList())
    private val UPGRADE_TO = FunctionSignature(name = "upgradeTo", paramTypes = listOf("address"))
    private val UPGRADE_TO_AND_CALL = FunctionSignature(name = "upgradeToAndCall", paramTypes = listOf("address", "bytes"))

    fun detect(currentAbiEntries: List<CatalogedAbiEntry>): UupsDetectionResult {
        val functions = currentAbiEntries.filter { it.kind == AbiEntryKind.FUNCTION }
        val hasProxiableUuid = functions.any { it.matches(PROXIABLE_UUID) }
        val availableUpgradeMethods = listOf(UPGRADE_TO, UPGRADE_TO_AND_CALL)
            .filter { signature -> functions.any { it.matches(signature) } }
            .map { it.rendered() }

        return UupsDetectionResult(
            isUpgradeable = hasProxiableUuid || availableUpgradeMethods.isNotEmpty(),
            availableUpgradeMethods = availableUpgradeMethods,
        )
    }

    private fun CatalogedAbiEntry.matches(signature: FunctionSignature): Boolean =
        name == signature.name && inputs.map { it.type } == signature.paramTypes

    private data class FunctionSignature(val name: String, val paramTypes: List<String>) {
        fun rendered(): String = "$name(${paramTypes.joinToString(",")})"
    }
}
