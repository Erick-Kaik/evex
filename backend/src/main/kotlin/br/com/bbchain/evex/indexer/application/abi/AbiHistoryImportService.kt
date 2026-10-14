package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiEntryCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryContext
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryItemStatus
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryPlan
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryPlanMismatchException
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryPlanner
import br.com.bbchain.evex.indexer.domain.abi.AbiHistorySource
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.ExistingVersion
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiEntry
import br.com.bbchain.evex.indexer.domain.abi.UpgradeCandidate
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.discovery.ProxyUpgradeLookupPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeReconciler
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Resultado de [AbiHistoryImportService.apply]: plano (itens aplicados com `abiId`) + totais. */
data class AbiHistoryResult(
    val plan: AbiHistoryPlan,
    val appliedCount: Int,
    val redecodeStarted: Boolean,
)

/**
 * Caso de uso (DEC-105): reconstrói o histórico de upgrades de um proxy UUPS (eventos ingeridos +
 * logs do Blockscout), planeja (dry-run) e importa uma versão de ABI por upgrade. A regra de
 * planejamento é do domínio ([AbiHistoryPlanner]); aqui só se orquestram portas.
 *
 * Decisão D4 / legado: com `replaceBlockscoutLegacy`, o planejamento trata as versões legadas
 * (`BLOCKSCOUT` sem implementação) como removidas, e o mesmo flag é repassado a `registerAll`, que as
 * apaga na mesma transação do import. Se nenhum item for `APPLY`, nada é apagado.
 */
@Service
class AbiHistoryImportService(
    private val contracts: MonitoredContractPort,
    private val contractAbis: ContractAbiPort,
    private val upgradeLookup: ProxyUpgradeLookupPort,
    private val blockscout: BlockscoutPort,
    private val ingestionCursor: IngestionCursorPort,
    private val abiEntryCatalog: AbiEntryCatalogPort,
    private val abiContentReader: AbiContentReaderPort,
    private val blockscoutAbiResolver: BlockscoutAbiResolver,
    private val abiRegistrationService: AbiRegistrationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Plano calculado + JSON (composto) de cada implementação com ABI, necessário só no apply. */
    private data class Computed(val plan: AbiHistoryPlan, val abiJsonByImpl: Map<String, String>)

    /** Mesma função de plano do [apply]: com a mesma flag, o fingerprint do dry-run é o do apply. */
    fun plan(
        address: String,
        historySource: AbiHistorySource = AbiHistorySource.AUTO,
        replaceBlockscoutLegacy: Boolean = false,
    ): AbiHistoryPlan = compute(address.lowercase(), historySource, replaceBlockscoutLegacy).plan

    fun apply(
        address: String,
        historySource: AbiHistorySource = AbiHistorySource.AUTO,
        storage: AbiStorageType? = null,
        expectedFingerprint: String? = null,
        replaceBlockscoutLegacy: Boolean = false,
    ): AbiHistoryResult {
        val normalized = address.lowercase()
        val computed = compute(normalized, historySource, replaceBlockscoutLegacy)
        val plan = computed.plan
        if (expectedFingerprint != null && expectedFingerprint != plan.fingerprint) {
            throw AbiHistoryPlanMismatchException(normalized, expectedFingerprint, plan.fingerprint)
        }

        val toApply = plan.items.filter { it.status == AbiHistoryItemStatus.APPLY }
        if (toApply.isEmpty()) return AbiHistoryResult(plan, appliedCount = 0, redecodeStarted = false)

        val batch = abiRegistrationService.registerAll(
            address = normalized,
            versions = toApply.map {
                AbiVersionDraft(
                    abiJson = computed.abiJsonByImpl.getValue(it.implementationAddress),
                    validFromBlock = it.validFromBlock,
                    implementationAddress = it.implementationAddress,
                )
            },
            source = AbiSource.BLOCKSCOUT,
            storage = storage,
            replaceBlockscoutLegacy = replaceBlockscoutLegacy,
        )
        val idBySequence = toApply.zip(batch.versions).associate { (item, saved) -> item.sequence to saved.id }
        val applied = plan.copy(items = plan.items.map { item -> idBySequence[item.sequence]?.let { item.copy(abiId = it) } ?: item })
        return AbiHistoryResult(applied, appliedCount = batch.versions.size, redecodeStarted = batch.redecodeStarted)
    }

    private fun compute(address: String, requested: AbiHistorySource, replaceBlockscoutLegacy: Boolean): Computed {
        contracts.findByAddress(address) ?: throw ContractNotFoundException(address)
        val cursor = ingestionCursor.current()
        val warnings = mutableListOf<String>()

        // 1) histórico de upgrades (D1): ingeridos primários, Blockscout complementar.
        val ingested = if (requested != AbiHistorySource.BLOCKSCOUT_ONLY) upgradeLookup.findUpgradeHistory(address) else emptyList()
        var effective = requested
        var truncated = false
        var incomplete = false
        val remote = if (requested == AbiHistorySource.INGESTED_ONLY) {
            emptyList()
        } else {
            try {
                val history = blockscout.getUpgradeHistory(address)
                truncated = history.truncated
                history.entries
            } catch (ex: BlockscoutUnavailableException) {
                if (requested == AbiHistorySource.BLOCKSCOUT_ONLY) throw ex
                // Não loga ex.message: pode carregar a URL do ambiente; só o tipo da causa.
                log.warn("Blockscout indisponível ao buscar histórico de upgrades de {} ({})", address, (ex.cause ?: ex).javaClass.simpleName)
                effective = AbiHistorySource.INGESTED_ONLY
                incomplete = true
                warnings += "Blockscout indisponível: histórico montado só com upgrades ingeridos, pode estar incompleto."
                emptyList()
            }
        }
        val upgrades = reconcile(ingested, remote, warnings)

        // 2) ABIs: busca a do proxy UMA vez e compõe com a de cada implementação distinta.
        val abiJsonByImpl = mutableMapOf<String, String>()
        val abis = mutableMapOf<String, List<ParsedAbiEntry>>()
        if (upgrades.isNotEmpty()) {
            val proxyAbi = blockscoutAbiResolver.abiOf(address)
            upgrades.map { it.implementationAddress }.distinct().forEach { impl ->
                blockscoutAbiResolver.composeOrNull(proxyAbi, impl)?.let { json ->
                    abiJsonByImpl[impl] = json
                    abis[impl] = abiEntryCatalog.entriesOf(json)
                }
            }
        }

        // 3) versões existentes; só lê o conteúdo das que caem no mesmo bloco de algum item (comparação).
        val itemFroms = upgrades.mapIndexed { i, u -> if (i == 0) 0L else u.blockNumber }.toSet()
        val existing = contractAbis.findAllByContractAddress(address).map { version ->
            val needsContent = version.validFromBlock in itemFroms
            ExistingVersion(
                abiId = version.id,
                validFromBlock = version.validFromBlock,
                validToBlock = version.validToBlock,
                source = version.source,
                implementationAddress = version.implementationAddress,
                contentKey = if (needsContent) contentKeyOf(version) else null,
            )
        }

        val plan = AbiHistoryPlanner.plan(
            context = AbiHistoryContext(
                contractAddress = address,
                historySource = effective,
                ingestedUntilBlock = cursor,
                truncated = truncated,
                historyMayBeIncomplete = incomplete,
                warnings = warnings,
            ),
            upgrades = upgrades,
            abis = abis,
            existing = existing,
            replaceBlockscoutLegacy = replaceBlockscoutLegacy,
        )
        return Computed(plan, abiJsonByImpl)
    }

    private fun contentKeyOf(version: br.com.bbchain.evex.indexer.domain.abi.ContractAbi): String? = try {
        AbiHistoryPlanner.contentKey(abiEntryCatalog.entriesOf(abiContentReader.read(version)))
    } catch (ex: Exception) {
        log.warn("Não foi possível ler a ABI da versão {} para comparação: {}", version.id, ex.message)
        null
    }

    /** Une ingeridos e Blockscout (D1) via [UpgradeReconciler]; divergências viram warnings do plano. */
    private fun reconcile(
        ingested: List<UpgradeRecord>,
        remote: List<BlockscoutUpgradeLog>,
        warnings: MutableList<String>,
    ): List<UpgradeCandidate> {
        val merged = UpgradeReconciler.merge(ingested, remote)
        warnings += merged.divergences.map { it.message }
        return merged.entries.map {
            UpgradeCandidate(it.implementationAddress, it.blockNumber, it.logIndex, it.txHash, it.source)
        }
    }
}
