package br.com.bbchain.evex.indexer.domain.abi

import br.com.bbchain.evex.indexer.domain.discovery.UpgradeSource
import java.security.MessageDigest
import java.util.UUID

/** Fonte do histórico de upgrades de um proxy (DEC-105/D1). */
enum class AbiHistorySource { AUTO, INGESTED_ONLY, BLOCKSCOUT_ONLY }

/**
 * Códigos de status de um item do plano. Strings (não enum fechado): taxonomia aberta, o consumidor
 * trata código desconhecido como "não aplicável" (P-taxonomia aberta).
 */
object AbiHistoryItemStatus {
    const val APPLY = "APPLY"
    const val SKIP_ALREADY_IMPORTED = "SKIP_ALREADY_IMPORTED"
    const val SKIP_IDENTICAL = "SKIP_IDENTICAL"
    const val CONFLICT_MANUAL = "CONFLICT_MANUAL"
    const val PENDING_NO_VERIFIED_ABI = "PENDING_NO_VERIFIED_ABI"
    const val BLOCKED_AFTER_PENDING = "BLOCKED_AFTER_PENDING"

    fun isSkip(status: String): Boolean = status == SKIP_ALREADY_IMPORTED || status == SKIP_IDENTICAL
}

/** Um upgrade reconciliado (ingerido e/ou Blockscout). Endereços/hash em lowercase. */
data class UpgradeCandidate(
    val implementationAddress: String,
    val blockNumber: Long,
    val logIndex: Long?,
    val txHash: String?,
    val source: UpgradeSource,
)

/**
 * Versão de ABI já cadastrada para o proxy. [contentKey] é a identidade semântica da ABI
 * ([AbiHistoryPlanner.contentKey]); `null` = não calculada/ilegível.
 */
data class ExistingVersion(
    val abiId: UUID,
    val validFromBlock: Long,
    val validToBlock: Long?,
    val source: AbiSource,
    val implementationAddress: String?,
    val contentKey: String?,
) {
    /** Versão "legada" (D4): `BLOCKSCOUT` sem implementação, cadastrada antes da DEC-105. */
    val isLegacyBlockscout: Boolean
        get() = source == AbiSource.BLOCKSCOUT && implementationAddress == null
}

data class AbiHistoryItem(
    val sequence: Int,
    val validFromBlock: Long,
    val validToBlock: Long?,
    val upgradeBlockNumber: Long,
    val implementationAddress: String,
    val txHash: String?,
    val upgradeSource: UpgradeSource,
    val abiAvailable: Boolean,
    val status: String,
    val statusReason: String?,
    val identicalToPrevious: Boolean,
    val diffFromPrevious: AbiDiff?,
    /** Preenchido só no resultado de um import, nos itens aplicados. */
    val abiId: UUID? = null,
)

/** Versão legada que um apply com `replaceBlockscoutLegacy` apagará. */
data class LegacyVersionRef(val abiId: UUID, val validFromBlock: Long)

data class AbiHistoryPlan(
    val contractAddress: String,
    val historySource: AbiHistorySource,
    val ingestedUntilBlock: Long,
    val truncated: Boolean,
    val fingerprint: String,
    val warnings: List<String>,
    val items: List<AbiHistoryItem>,
    /** Versões legadas que o apply apagará: só com `replaceBlockscoutLegacy` e ao menos um item `APPLY`. */
    val legacyVersionsToReplace: List<LegacyVersionRef> = emptyList(),
)

/** Parâmetros do contexto de um plano que não vêm da linha do tempo em si. */
data class AbiHistoryContext(
    val contractAddress: String,
    val historySource: AbiHistorySource,
    val ingestedUntilBlock: Long,
    val truncated: Boolean,
    /** `true` se a lista de upgrades pode estar incompleta por motivo externo (ex.: Blockscout fora). */
    val historyMayBeIncomplete: Boolean = false,
    val warnings: List<String> = emptyList(),
)

/**
 * Regra pura de planejamento da importação do histórico de ABIs de um proxy (DEC-105). Sem I/O: recebe
 * os upgrades reconciliados, as ABIs (já compostas) de cada implementação, as versões existentes e
 * devolve o plano. Regras: ver `docs/decisions.md` DEC-105 (D1-D8).
 */
object AbiHistoryPlanner {

    /**
     * @param abis ABI parseada por endereço de implementação; ausente = sem ABI verificada.
     * @param existing versões já cadastradas do proxy (com [ExistingVersion.contentKey] preenchida para
     *   as que cairem no mesmo bloco de algum item).
     * @param replaceBlockscoutLegacy com `true`, versões legadas são tratadas como já removidas (D4).
     */
    fun plan(
        context: AbiHistoryContext,
        upgrades: List<UpgradeCandidate>,
        abis: Map<String, List<ParsedAbiEntry>>,
        existing: List<ExistingVersion>,
        replaceBlockscoutLegacy: Boolean,
    ): AbiHistoryPlan {
        val warnings = context.warnings.toMutableList()
        val timeline = collapseSameBlock(
            upgrades.sortedWith(compareBy({ it.blockNumber }, { it.logIndex ?: Long.MAX_VALUE })),
            warnings,
        )
        val effective = if (replaceBlockscoutLegacy) existing.filterNot { it.isLegacyBlockscout } else existing
        val open = effective.firstOrNull { it.validToBlock == null }

        var blocked = false
        var previousEntries: List<ParsedAbiEntry>? = null
        val items = timeline.mapIndexed { index, upgrade ->
            val from = if (index == 0) 0L else upgrade.blockNumber
            val to = timeline.getOrNull(index + 1)?.let { it.blockNumber - 1 }
            val entries = abis[upgrade.implementationAddress]
            val previous = previousEntries
            val diff = if (entries != null && previous != null) AbiDiff.compute(previous, entries) else null
            previousEntries = entries

            val (status, reason) = decide(upgrade, from, entries, effective, open, blocked)
            if (!AbiHistoryItemStatus.isSkip(status) && status != AbiHistoryItemStatus.APPLY) blocked = true

            AbiHistoryItem(
                sequence = index + 1,
                validFromBlock = from,
                validToBlock = to,
                upgradeBlockNumber = upgrade.blockNumber,
                implementationAddress = upgrade.implementationAddress,
                txHash = upgrade.txHash,
                upgradeSource = upgrade.source,
                abiAvailable = entries != null,
                status = status,
                statusReason = reason,
                identicalToPrevious = diff?.identical == true,
                diffFromPrevious = diff,
            )
        }

        if (items.isEmpty()) warnings += "Nenhum upgrade (`Upgraded`) encontrado para este contrato."
        if (context.truncated) {
            warnings += "Histórico do Blockscout truncado no limite de resultados: pode haver upgrades faltando."
        }
        if (context.ingestedUntilBlock < 0) {
            warnings += "Nenhum bloco ingerido ainda (cursor = -1): upgrades ingeridos não puderam ser considerados."
        } else if (items.any { it.upgradeBlockNumber > context.ingestedUntilBlock }) {
            warnings += "Há upgrade em bloco acima do cursor de indexação (${context.ingestedUntilBlock}): " +
                "eventos desses blocos serão decodificados na ingestão com a versão correta."
        }
        if (items.isNotEmpty() && (context.truncated || context.ingestedUntilBlock < 0 || context.historyMayBeIncomplete)) {
            warnings += "A 1ª versão usa validFromBlock=0 sem confirmação do bloco de deploy; " +
                "o histórico pode estar incompleto, então a faixa inicial pode ser imprecisa."
        }

        return AbiHistoryPlan(
            contractAddress = context.contractAddress,
            historySource = context.historySource,
            ingestedUntilBlock = context.ingestedUntilBlock,
            truncated = context.truncated,
            fingerprint = fingerprintOf(items),
            warnings = warnings,
            items = items,
            legacyVersionsToReplace = if (replaceBlockscoutLegacy && items.any { it.status == AbiHistoryItemStatus.APPLY }) {
                existing.filter { it.isLegacyBlockscout }.map { LegacyVersionRef(it.abiId, it.validFromBlock) }
            } else {
                emptyList()
            },
        )
    }

    private fun decide(
        upgrade: UpgradeCandidate,
        from: Long,
        entries: List<ParsedAbiEntry>?,
        existing: List<ExistingVersion>,
        open: ExistingVersion?,
        blocked: Boolean,
    ): Pair<String, String?> {
        val sameFrom = existing.firstOrNull { it.validFromBlock == from }
        if (sameFrom != null) {
            if (sameFrom.implementationAddress == upgrade.implementationAddress) {
                return AbiHistoryItemStatus.SKIP_ALREADY_IMPORTED to "Versão desta implementação já importada neste bloco."
            }
            if (entries == null) {
                // Cadastro manual guiado pelo plano: a faixa já existe, não há o que comparar nem importar.
                return AbiHistoryItemStatus.SKIP_ALREADY_IMPORTED to
                    "Já existe versão de ABI cadastrada (origem ${sameFrom.source}) neste bloco; ABI verificada indisponível para comparar."
            }
            return if (sameFrom.contentKey != null && sameFrom.contentKey == contentKey(entries)) {
                AbiHistoryItemStatus.SKIP_IDENTICAL to "Versão existente neste bloco tem conteúdo idêntico."
            } else {
                blockedOr(AbiHistoryItemStatus.CONFLICT_MANUAL, "Já existe versão de ABI diferente neste bloco (origem ${sameFrom.source}).", blocked)
            }
        }
        if (open != null && from <= open.validFromBlock) {
            return blockedOr(
                AbiHistoryItemStatus.CONFLICT_MANUAL,
                "Bloco ${from} não é posterior à versão aberta existente (a partir do bloco ${open.validFromBlock}); " +
                    "o cadastro exige ordem crescente.",
                blocked,
            )
        }
        if (entries == null) {
            return blockedOr(
                AbiHistoryItemStatus.PENDING_NO_VERIFIED_ABI,
                "Implementação ${upgrade.implementationAddress} sem ABI verificada no Blockscout; cadastre manualmente a partir do bloco $from.",
                blocked,
            )
        }
        return if (blocked) {
            AbiHistoryItemStatus.BLOCKED_AFTER_PENDING to "Aguarda a resolução de um item anterior pendente/em conflito."
        } else {
            AbiHistoryItemStatus.APPLY to null
        }
    }

    private fun blockedOr(status: String, reason: String, blocked: Boolean): Pair<String, String?> =
        if (blocked) AbiHistoryItemStatus.BLOCKED_AFTER_PENDING to "Aguarda a resolução de um item anterior pendente/em conflito."
        else status to reason

    /** Upgrades no mesmo bloco: só o último vale (faixas são por bloco); avisa quando descarta. */
    private fun collapseSameBlock(sorted: List<UpgradeCandidate>, warnings: MutableList<String>): List<UpgradeCandidate> {
        val result = mutableListOf<UpgradeCandidate>()
        sorted.forEach { upgrade ->
            if (result.lastOrNull()?.blockNumber == upgrade.blockNumber) {
                warnings += "Mais de um upgrade no bloco ${upgrade.blockNumber}: considerado só o último (${upgrade.implementationAddress})."
                result[result.lastIndex] = upgrade
            } else {
                result += upgrade
            }
        }
        return result
    }

    /** Identidade semântica de uma ABI: sha256 do conjunto ordenado de itens (tipo, nome, mutabilidade, parâmetros). */
    fun contentKey(entries: List<ParsedAbiEntry>): String {
        fun params(list: List<CatalogedAbiEntryParam>) = list.joinToString(",") { "${it.type}:${it.name.orEmpty()}:${it.indexed ?: ""}" }
        val canonical = entries
            .map { "${it.kind}|${it.name.orEmpty()}|${it.stateMutability.orEmpty()}|${params(it.inputs)}|${params(it.outputs)}|${it.anonymous ?: ""}" }
            .sorted()
            .joinToString("\n")
        return sha256(canonical)
    }

    fun fingerprintOf(items: List<AbiHistoryItem>): String =
        sha256(items.joinToString("\n") { "${it.validFromBlock}|${it.implementationAddress}|${it.status}" })

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
