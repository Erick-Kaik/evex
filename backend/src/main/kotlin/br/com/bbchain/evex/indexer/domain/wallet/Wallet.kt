package br.com.bbchain.evex.indexer.domain.wallet

import br.com.bbchain.evex.indexer.domain.common.Network
import java.util.UUID

/**
 * Carteira cadastrada pelo Evex (E22) — catálogo de endereços conhecidos, estruturalmente simétrico
 * a [br.com.bbchain.evex.indexer.domain.contract.MonitoredContract]. `address` e `network` são
 * imutáveis após a criação (chave natural), igual contrato — `update()` não os altera.
 *
 * `label` (não `name`) — nome dado pelo usuário à carteira, pra não colidir semanticamente com "nome
 * de evento"/"nome de parâmetro" já usados em outros DTOs do projeto (`eventName`, `walletParam` etc.).
 * Sem `version` (não existe noção de "versão de carteira" — esse campo é específico de contrato/ABI).
 *
 * `looksLikeContract` (DEC-080): resultado, no momento do cadastro, de
 * [br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort.hasCode] contra `address` — **não** é
 * uma regra de negócio que bloqueia o cadastro (um endereço com bytecode pode legitimamente ser
 * cadastrado como carteira, ex.: multisig Gnosis Safe); é só um aviso exibido na UI. Calculado uma vez
 * no `register()` e persistido — não recalculado a cada leitura (o bytecode de um EOA não muda; o de
 * um contrato pode ser recriado num CREATE2 mas isso é cenário de borda fora do escopo da PoC).
 *
 * `looksLikeContractAcknowledged` (complemento à DEC-080, feedback do usuário em teste manual): sem
 * isso, o aviso `looksLikeContract` reaparecia **toda vez** que a tela de detalhe da carteira era
 * aberta, mesmo já tendo sido visto — irritante pro caso legítimo (multisig cadastrada de propósito).
 * `false` no cadastro; vira `true` via `WalletService.acknowledgeContractWarning()`, uma decisão
 * explícita e unidirecional do usuário ("já vi, quero manter assim mesmo") — não há caminho de volta
 * pra `false` (não existe cenário de "desfazer" a ciência do aviso).
 */
data class Wallet(
    val id: UUID,
    val address: String,
    val label: String,
    val description: String?,
    val network: Network,
    val status: WalletStatus,
    val looksLikeContract: Boolean,
    val looksLikeContractAcknowledged: Boolean,
)
