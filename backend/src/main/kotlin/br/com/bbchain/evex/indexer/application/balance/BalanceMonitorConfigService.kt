package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.balance.InvalidBalanceMonitorConfigException
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/** Caso de uso: cadastro, consulta e atualização de configurações de monitoramento de saldo (doc §4.4). */
@Service
class BalanceMonitorConfigService(
    private val configs: BalanceMonitorConfigPort,
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
    ): BalanceMonitorConfig {
        validate(behavior, walletParam, fromParam, toParam)
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
            active = true,
            createdAt = Instant.now(),
        )
        return configs.save(config)
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
    ): BalanceMonitorConfig {
        validate(behavior, walletParam, fromParam, toParam)
        val existing = get(id)
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
        )
        return configs.save(updated)
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
}
