package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.application.abi.ContractEventCatalogService
import br.com.bbchain.evex.indexer.domain.abi.CatalogedEvent
import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.balance.InvalidBalanceMonitorConfigException
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Resultado de `register`/`update`: a config persistida mais avisos PT-BR não bloqueantes de validação
 * contra o catálogo de ABI (DEC-110) — vazio quando não há nada a avisar.
 */
data class BalanceMonitorConfigResult(val config: BalanceMonitorConfig, val warnings: List<String> = emptyList())

/** Caso de uso: cadastro, consulta e atualização de configurações de monitoramento de saldo (doc §4.4). */
@Service
class BalanceMonitorConfigService(
    private val configs: BalanceMonitorConfigPort,
    private val ingestionCursor: IngestionCursorPort,
    private val eventCatalog: ContractEventCatalogService,
) {
    fun register(
        contractAddress: String,
        network: Network,
        eventName: String,
        behavior: BalanceBehavior,
        walletParam: String?,
        fromParam: String?,
        toParam: String?,
        valueParam: String,
        tokenIdParam: String?,
        batch: Boolean = false,
    ): BalanceMonitorConfigResult {
        validate(behavior, walletParam, fromParam, toParam)
        val warnings = validateAgainstAbi(contractAddress, eventName, valueParam, tokenIdParam, batch)
        val config = BalanceMonitorConfig(
            id = UUID.randomUUID(),
            contractAddress = contractAddress.lowercase(),
            network = network,
            eventName = eventName,
            behavior = behavior,
            walletParam = walletParam,
            fromParam = fromParam,
            toParam = toParam,
            valueParam = valueParam,
            tokenIdParam = tokenIdParam,
            batch = batch,
            active = true,
            createdAt = Instant.now(),
            // E14/DEC-066: marca d'água = cursor de ingestão AGORA, imutável. O backfill sob demanda
            // só reprojeta eventos até este bloco; o que a ingestão ao vivo processar daqui pra
            // frente já entra via IngestionBatchProcessor, sem contagem dupla. Cursores de progresso
            // em -1 (nada reprojetado ainda).
            backfillWatermarkBlock = ingestionCursor.current(),
            backfillCursorBlock = -1,
            backfillCursorLogIndex = -1,
        )
        return BalanceMonitorConfigResult(configs.save(config), warnings)
    }

    fun get(id: UUID): BalanceMonitorConfig = configs.findById(id) ?: throw BalanceMonitorConfigNotFoundException(id)

    fun list(page: Int, size: Int): PageResult<BalanceMonitorConfig> = configs.findAll(page, size)

    fun update(
        id: UUID,
        contractAddress: String,
        network: Network,
        eventName: String,
        behavior: BalanceBehavior,
        walletParam: String?,
        fromParam: String?,
        toParam: String?,
        valueParam: String,
        tokenIdParam: String?,
        batch: Boolean = false,
    ): BalanceMonitorConfigResult {
        validate(behavior, walletParam, fromParam, toParam)
        val warnings = validateAgainstAbi(contractAddress, eventName, valueParam, tokenIdParam, batch)
        val existing = get(id)
        // `copy` só dos campos editáveis — os 3 campos de backfill (marca d'água + cursor de
        // progresso, E14/DEC-066) NÃO entram aqui de propósito: a marca d'água é imutável depois da
        // criação e o cursor não pode retroceder por uma edição de config.
        val updated = existing.copy(
            contractAddress = contractAddress.lowercase(),
            network = network,
            eventName = eventName,
            behavior = behavior,
            walletParam = walletParam,
            fromParam = fromParam,
            toParam = toParam,
            valueParam = valueParam,
            tokenIdParam = tokenIdParam,
            batch = batch,
        )
        return BalanceMonitorConfigResult(configs.save(updated), warnings)
    }

    fun updateStatus(id: UUID, active: Boolean): BalanceMonitorConfig {
        val existing = get(id)
        return configs.save(existing.copy(active = active))
    }

    /**
     * `TRANSFER` exige `fromParam`+`toParam` presentes e `walletParam` ausente; os outros 3
     * (`CREDIT`/`DEBIT`/`DELTA`) exigem `walletParam` presente e `fromParam`/`toParam` ausentes — ver
     * KDoc de [BalanceMonitorConfig].
     */
    private fun validate(behavior: BalanceBehavior, walletParam: String?, fromParam: String?, toParam: String?) {
        when (behavior) {
            BalanceBehavior.TRANSFER -> {
                if (fromParam.isNullOrBlank() || toParam.isNullOrBlank()) {
                    throw InvalidBalanceMonitorConfigException("behavior=TRANSFER exige fromParam e toParam preenchidos")
                }
                if (!walletParam.isNullOrBlank()) {
                    throw InvalidBalanceMonitorConfigException("behavior=TRANSFER não usa walletParam — use fromParam/toParam")
                }
            }
            BalanceBehavior.CREDIT, BalanceBehavior.DEBIT, BalanceBehavior.DELTA -> {
                if (walletParam.isNullOrBlank()) {
                    throw InvalidBalanceMonitorConfigException("behavior=$behavior exige walletParam preenchido")
                }
                if (!fromParam.isNullOrBlank() || !toParam.isNullOrBlank()) {
                    throw InvalidBalanceMonitorConfigException("behavior=$behavior não usa fromParam/toParam — use walletParam")
                }
            }
        }
    }

    /**
     * DEC-110: valida `batch` contra os tipos dos parâmetros no catálogo de ABI do contrato —
     * considerando TODAS as versões (proxy upgradável pode ter assinaturas diferentes por faixa de
     * blocos, DEC-007). Só roda no register/update. Retrocompat: contrato não monitorado, sem ABI, ou
     * evento inexistente em todas as versões => não valida. Um parâmetro ausente numa versão é neutro
     * (continua sendo problema de runtime, como antes).
     *
     * Nenhuma versão compatível => [InvalidBalanceMonitorConfigException] (400); só algumas =>
     * aceita e devolve aviso(s).
     */
    private fun validateAgainstAbi(
        contractAddress: String,
        eventName: String,
        valueParam: String,
        tokenIdParam: String?,
        batch: Boolean,
    ): List<String> {
        val versions: List<CatalogedEvent> = try {
            eventCatalog.catalogFor(contractAddress).filter { it.name == eventName }
        } catch (ex: ContractNotFoundException) {
            return emptyList()
        }
        if (versions.isEmpty()) return emptyList()

        val params = listOfNotNull(valueParam, tokenIdParam)
        fun CatalogedEvent.isCompatible(): Boolean = params.all { param ->
            val type = inputs.firstOrNull { it.name == param }?.type ?: return@all true
            if (batch) NUMERIC_ARRAY.matches(type) else !type.endsWith("]")
        }

        val incompatible = versions.filterNot { it.isCompatible() }
        if (incompatible.isEmpty()) return emptyList()
        if (incompatible.size == versions.size) {
            throw InvalidBalanceMonitorConfigException(
                if (batch) {
                    "batch=true exige que ${params.joinToString { "'$it'" }} sejam arrays numéricos (ex.: uint256[]) no evento '$eventName', " +
                        "mas nenhuma versão de ABI deste contrato declara assim"
                } else {
                    "batch=false exige parâmetros escalares, mas ${params.joinToString { "'$it'" }} é/são array(s) no evento '$eventName' " +
                        "em todas as versões de ABI deste contrato — use batch=true"
                },
            )
        }
        val ranges = incompatible.joinToString(", ") { "${it.validFromBlock}..${it.validToBlock ?: "atual"}" }
        return listOf(
            "A flag batch=$batch é incompatível com a ABI das versões nas faixas de blocos $ranges deste contrato; " +
                "nessas faixas o evento '$eventName' será descartado com WARN.",
        )
    }

    private companion object {
        /** `uint256[]`, `int[]`, `uint8[3]` — array (dinâmico ou fixo) de inteiro. */
        val NUMERIC_ARRAY = Regex("^u?int\\d*\\[\\d*]$")
    }
}
