package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.BalanceMonitorsApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.BalanceBackfillStatus as BalanceBackfillStatusDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.BalanceBehavior as BalanceBehaviorDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.BalanceMonitorConfig as BalanceMonitorConfigDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.BalanceMonitorConfigPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.CreateBalanceMonitorPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateBalanceMonitorPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateBalanceMonitorStatusPayload
import br.com.bbchain.evex.indexer.application.balance.BalanceBackfillOrchestrator
import br.com.bbchain.evex.indexer.application.balance.BalanceMonitorConfigResult
import br.com.bbchain.evex.indexer.application.balance.BalanceMonitorConfigService
import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Implementação fina de [BalanceMonitorsApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [BalanceMonitorConfigService]. Nenhuma regra de negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class BalanceMonitorsApiServiceImpl(
    private val balanceMonitorConfigService: BalanceMonitorConfigService,
    private val balanceBackfillOrchestrator: BalanceBackfillOrchestrator,
) : BalanceMonitorsApiService {

    override fun createBalanceMonitor(createBalanceMonitorPayload: CreateBalanceMonitorPayload): BalanceMonitorConfigDto {
        val config = balanceMonitorConfigService.register(
            contractAddress = createBalanceMonitorPayload.contractAddress,
            network = createBalanceMonitorPayload.network.toDomain(),
            eventName = createBalanceMonitorPayload.eventName,
            behavior = createBalanceMonitorPayload.behavior.toDomain(),
            walletParam = createBalanceMonitorPayload.walletParam,
            fromParam = createBalanceMonitorPayload.fromParam,
            toParam = createBalanceMonitorPayload.toParam,
            valueParam = createBalanceMonitorPayload.valueParam,
            tokenIdParam = createBalanceMonitorPayload.tokenIdParam,
            batch = createBalanceMonitorPayload.batch ?: false,
        )
        return config.toDto()
    }

    override fun getBalanceMonitor(id: UUID): BalanceMonitorConfigDto = balanceMonitorConfigService.get(id).toDto()

    override fun listBalanceMonitors(page: kotlin.Int, size: kotlin.Int): BalanceMonitorConfigPage {
        val result = balanceMonitorConfigService.list(page, size)
        return BalanceMonitorConfigPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    override fun updateBalanceMonitor(id: UUID, updateBalanceMonitorPayload: UpdateBalanceMonitorPayload): BalanceMonitorConfigDto {
        val config = balanceMonitorConfigService.update(
            id = id,
            contractAddress = updateBalanceMonitorPayload.contractAddress,
            network = updateBalanceMonitorPayload.network.toDomain(),
            eventName = updateBalanceMonitorPayload.eventName,
            behavior = updateBalanceMonitorPayload.behavior.toDomain(),
            walletParam = updateBalanceMonitorPayload.walletParam,
            fromParam = updateBalanceMonitorPayload.fromParam,
            toParam = updateBalanceMonitorPayload.toParam,
            valueParam = updateBalanceMonitorPayload.valueParam,
            tokenIdParam = updateBalanceMonitorPayload.tokenIdParam,
            batch = updateBalanceMonitorPayload.batch ?: false,
        )
        return config.toDto()
    }

    override fun updateBalanceMonitorStatus(id: UUID, updateBalanceMonitorStatusPayload: UpdateBalanceMonitorStatusPayload): BalanceMonitorConfigDto =
        balanceMonitorConfigService.updateStatus(id, updateBalanceMonitorStatusPayload.active).toDto()

    /**
     * `get(id)` primeiro só pelo 404 (`BalanceMonitorConfigNotFoundException` → `ApiExceptionHandler`)
     * — `tryStart` decide sozinho se dispara (já havia backfill em andamento); a resposta `202` sempre
     * reflete o status global real logo depois da tentativa, útil mesmo quando `tryStart` retorna `false`.
     */
    override fun triggerBalanceBackfill(id: UUID): BalanceBackfillStatusDto {
        balanceMonitorConfigService.get(id)
        balanceBackfillOrchestrator.tryStart(id)
        return balanceBackfillOrchestrator.globalStatus().toWireDto()
    }

    /** Status global do job de backfill (DEC-067) — não é por configuração; a UI faz 1 polling só. */
    override fun getBalanceBackfillStatus(): BalanceBackfillStatusDto =
        balanceBackfillOrchestrator.globalStatus().toWireDto()

    private fun NetworkDto.toDomain(): Network = Network.valueOf(this.name)

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    private fun BalanceBehaviorDto.toDomain(): BalanceBehavior = BalanceBehavior.valueOf(this.name)

    private fun BalanceBehavior.toDto(): BalanceBehaviorDto = BalanceBehaviorDto.valueOf(this.name)

    private fun BalanceMonitorConfigResult.toDto(): BalanceMonitorConfigDto =
        config.toDto().copy(warnings = warnings.takeIf { it.isNotEmpty() }?.toMutableList())

    private fun BalanceMonitorConfig.toDto(): BalanceMonitorConfigDto = BalanceMonitorConfigDto(
        id = id,
        contractAddress = contractAddress,
        network = network.toDto(),
        eventName = eventName,
        behavior = behavior.toDto(),
        walletParam = walletParam,
        fromParam = fromParam,
        toParam = toParam,
        valueParam = valueParam,
        tokenIdParam = tokenIdParam,
        batch = batch,
        active = active,
        createdAt = createdAt.atOffset(ZoneOffset.UTC),
    )
}
