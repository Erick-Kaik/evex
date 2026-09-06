package br.com.bbchain.evex.indexer.application.wallet

import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.wallet.Wallet
import br.com.bbchain.evex.indexer.domain.wallet.WalletAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.wallet.WalletNotFoundException
import br.com.bbchain.evex.indexer.domain.wallet.WalletPort
import br.com.bbchain.evex.indexer.domain.wallet.WalletStatus
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Caso de uso: cadastro, consulta e atualização de carteiras cadastradas (E22). Espelha
 * [br.com.bbchain.evex.indexer.application.contract.MonitoredContractService] estruturalmente — não
 * conhece HTTP nem persistência concreta.
 */
@Service
class WalletService(
    private val wallets: WalletPort,
    private val blockchain: BlockchainPort,
    private val assetBalances: AssetBalancePort,
) {
    /**
     * `looksLikeContract` (DEC-080): calculado via [BlockchainPort.hasCode] direto, sem serviço
     * intermediário — nunca bloqueia o cadastro, é só um aviso exibido na UI.
     */
    fun register(address: String, label: String, network: Network, description: String?): Wallet {
        val normalizedAddress = address.lowercase()
        if (wallets.findByAddress(normalizedAddress) != null) {
            throw WalletAlreadyExistsException(normalizedAddress)
        }
        val wallet = Wallet(
            id = UUID.randomUUID(),
            address = normalizedAddress,
            label = label,
            description = description,
            network = network,
            status = WalletStatus.ACTIVE,
            looksLikeContract = blockchain.hasCode(normalizedAddress),
            looksLikeContractAcknowledged = false,
        )
        return wallets.save(wallet)
    }

    fun getByAddress(address: String): Wallet =
        wallets.findByAddress(address.lowercase()) ?: throw WalletNotFoundException(address)

    fun list(network: Network?, page: Int, size: Int): PageResult<Wallet> =
        wallets.findAll(network, page, size)

    /** `address`, `network` e `looksLikeContract` são imutáveis — só `label`/`description` mudam. */
    fun update(address: String, label: String, description: String?): Wallet {
        val existing = getByAddress(address)
        val updated = existing.copy(label = label, description = description)
        return wallets.save(updated)
    }

    /** Transição de status é livre (sem máquina de estados) — mesma regra de contrato. */
    fun updateStatus(address: String, status: WalletStatus): Wallet {
        val existing = getByAddress(address)
        val updated = existing.copy(status = status)
        return wallets.save(updated)
    }

    /**
     * Marca `looksLikeContractAcknowledged = true` — o usuário viu o aviso de "endereço parece um
     * contrato" e decidiu manter o cadastro assim mesmo. Idempotente (chamar de novo numa carteira já
     * confirmada, ou numa que nunca teve `looksLikeContract`, não é erro — só um `save()` a mais).
     */
    fun acknowledgeContractWarning(address: String): Wallet {
        val existing = getByAddress(address)
        val updated = existing.copy(looksLikeContractAcknowledged = true)
        return wallets.save(updated)
    }

    /**
     * Descoberta automática de carteiras (DEC-080 item 4): candidatos brutos de
     * [AssetBalancePort.findDistinctWalletAddresses] menos os endereços já cadastrados
     * ([WalletPort.findAllAddresses]) — exclusão em memória, de propósito (ver DEC-080), pra não
     * acoplar o adapter de saldo à tabela de carteiras.
     */
    fun suggestions(network: Network?): List<String> {
        val candidates = assetBalances.findDistinctWalletAddresses(network)
        val registered = wallets.findAllAddresses(network)
        return candidates - registered
    }
}
