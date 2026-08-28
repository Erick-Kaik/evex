package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.BalancesApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AssetBalance as AssetBalanceDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AssetBalancePage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AssetBalanceSnapshot as AssetBalanceSnapshotDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AssetBalanceSnapshotPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.domain.balance.AssetBalance
import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.balance.AssetBalanceSnapshot
import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.stereotype.Service

/**
 * Implementação fina de [BalancesApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [AssetBalancePort] (leitura pura, filtro+paginação — não precisa de serviço de aplicação
 * intermediário, mesmo padrão de `EventsApiServiceImpl`/`EventQueryService` sendo dispensável aqui).
 * Nenhuma regra de negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class BalancesApiServiceImpl(
    private val balances: AssetBalancePort,
) : BalancesApiService {

    override fun listBalances(
        walletAddress: kotlin.String?,
        contractAddress: kotlin.String?,
        tokenId: kotlin.String?,
        network: NetworkDto?,
        page: kotlin.Int,
        size: kotlin.Int,
    ): AssetBalancePage {
        val result = balances.search(
            walletAddress = walletAddress?.lowercase(),
            contractAddress = contractAddress?.lowercase(),
            tokenId = tokenId,
            network = network?.toDomain(),
            page = page,
            size = size,
        )
        return AssetBalancePage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    override fun listBalanceHistory(
        date: LocalDate,
        walletAddress: kotlin.String?,
        contractAddress: kotlin.String?,
        tokenId: kotlin.String?,
        network: NetworkDto?,
        page: kotlin.Int,
        size: kotlin.Int,
    ): AssetBalanceSnapshotPage {
        val result = balances.searchHistory(
            walletAddress = walletAddress?.lowercase(),
            contractAddress = contractAddress?.lowercase(),
            tokenId = tokenId,
            network = network?.toDomain(),
            date = date,
            page = page,
            size = size,
        )
        return AssetBalanceSnapshotPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    private fun NetworkDto.toDomain(): Network = Network.valueOf(this.name)

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    // `balance` é BigInteger no domínio, `string` no DTO (`AssetBalance.balance`, DEC — uint256/int256
    // excedem a precisão segura de `number` em JSON).
    private fun AssetBalance.toDto(): AssetBalanceDto = AssetBalanceDto(
        walletAddress = walletAddress,
        contractAddress = contractAddress,
        network = network.toDto(),
        tokenId = tokenId,
        balance = balance.toString(),
        updatedAt = updatedAt.atOffset(ZoneOffset.UTC),
    )

    private fun AssetBalanceSnapshot.toDto(): AssetBalanceSnapshotDto = AssetBalanceSnapshotDto(
        walletAddress = walletAddress,
        contractAddress = contractAddress,
        network = network.toDto(),
        tokenId = tokenId,
        balance = balance.toString(),
        snapshotDate = snapshotDate,
        blockNumber = blockNumber,
    )
}
