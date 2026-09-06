package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.WalletsApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.CreateWalletPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateWalletPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateWalletStatusPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Wallet as WalletDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WalletPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WalletStatus as WalletStatusDto
import br.com.bbchain.evex.indexer.application.wallet.WalletService
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.wallet.Wallet
import br.com.bbchain.evex.indexer.domain.wallet.WalletStatus
import org.springframework.stereotype.Service

/**
 * Implementação fina de [WalletsApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [WalletService]. Nenhuma regra de negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class WalletsApiServiceImpl(
    private val walletService: WalletService,
) : WalletsApiService {

    override fun createWallet(createWalletPayload: CreateWalletPayload): WalletDto {
        val wallet = walletService.register(
            address = createWalletPayload.address,
            label = createWalletPayload.label,
            network = createWalletPayload.network.toDomain(),
            description = createWalletPayload.description,
        )
        return wallet.toDto()
    }

    override fun getWallet(address: kotlin.String): WalletDto =
        walletService.getByAddress(address).toDto()

    override fun listWallets(network: NetworkDto?, page: kotlin.Int, size: kotlin.Int): WalletPage {
        val result = walletService.list(network?.toDomain(), page, size)
        return WalletPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    override fun updateWallet(address: kotlin.String, updateWalletPayload: UpdateWalletPayload): WalletDto {
        val wallet = walletService.update(
            address = address,
            label = updateWalletPayload.label,
            description = updateWalletPayload.description,
        )
        return wallet.toDto()
    }

    override fun updateWalletStatus(address: kotlin.String, updateWalletStatusPayload: UpdateWalletStatusPayload): WalletDto {
        val wallet = walletService.updateStatus(
            address = address,
            status = updateWalletStatusPayload.status.toDomain(),
        )
        return wallet.toDto()
    }

    override fun suggestWallets(network: NetworkDto?): MutableList<kotlin.String> =
        walletService.suggestions(network?.toDomain()).toMutableList()

    override fun acknowledgeWalletContractWarning(address: kotlin.String): WalletDto =
        walletService.acknowledgeContractWarning(address).toDto()

    private fun Wallet.toDto(): WalletDto = WalletDto(
        id = id,
        address = address,
        label = label,
        description = description,
        network = network.toDto(),
        status = status.toDto(),
        looksLikeContract = looksLikeContract,
        looksLikeContractAcknowledged = looksLikeContractAcknowledged,
    )

    private fun NetworkDto.toDomain(): Network = Network.valueOf(this.name)

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    private fun WalletStatusDto.toDomain(): WalletStatus = WalletStatus.valueOf(this.name)

    private fun WalletStatus.toDto(): WalletStatusDto = WalletStatusDto.valueOf(this.name)
}
