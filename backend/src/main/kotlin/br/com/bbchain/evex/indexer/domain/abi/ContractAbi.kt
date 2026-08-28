package br.com.bbchain.evex.indexer.domain.abi

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Uma versão de ABI cadastrada para um contrato, válida numa faixa de blocos (DEC-007, suporte a
 * proxy upgradável). `storageLocation` é a única exceção à regra "não guarda o conteúdo bruto da
 * ABI": ela não carrega o JSON inteiro sempre (isso só existe quando `storageType = DATABASE`, e
 * mesmo assim é um valor imutável já resolvido, não recomputado), mas precisa carregar o *ponteiro*
 * (`abiContent` ou `storagePath`) — sem ele o `AbiStoragePort.retrieve()` não teria o que ler de
 * volta em nenhum caminho (cadastro seguinte de nova versão, warm-up do cache no boot). Ver
 * docs/decisions.md para o registro desta decisão (deviation do plano original, que listava só os
 * campos de metadados).
 */
data class ContractAbi(
    val id: UUID,
    val contractAddress: String,
    val network: Network,
    val source: AbiSource,
    val storageType: AbiStorageType,
    val storageLocation: StorageLocation,
    val validFromBlock: Long,
    val validToBlock: Long?,
    val createdAt: Instant,
)
