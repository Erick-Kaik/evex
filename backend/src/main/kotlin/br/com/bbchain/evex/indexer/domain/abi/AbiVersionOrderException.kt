package br.com.bbchain.evex.indexer.domain.abi

/**
 * Cadastro de uma nova versão de ABI com `validFromBlock` menor ou igual ao `validFromBlock` da
 * versão atualmente aberta (`validToBlock == null`) para o mesmo endereço — faixas de validade
 * precisam ser estritamente crescentes.
 */
class AbiVersionOrderException(
    val address: String,
    val attemptedFromBlock: Long,
    val currentOpenFromBlock: Long,
) : RuntimeException(
    "Nova versão de ABI para $address precisa de validFromBlock maior que $currentOpenFromBlock " +
        "(versão atualmente aberta); recebido: $attemptedFromBlock",
)
