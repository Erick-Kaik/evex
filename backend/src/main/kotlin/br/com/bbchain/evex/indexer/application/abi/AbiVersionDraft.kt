package br.com.bbchain.evex.indexer.application.abi

/**
 * Uma versão de ABI a cadastrar em lote ([AbiRegistrationService.registerAll]). [abiJson] já é o
 * conteúdo resolvido (JSON da ABI); [implementationAddress] (lowercase) marca versões compostas a
 * partir da implementação de um proxy (DEC-105).
 */
data class AbiVersionDraft(
    val abiJson: String,
    val validFromBlock: Long,
    val implementationAddress: String? = null,
)

/** Resultado de [AbiRegistrationService.registerAll]: versões persistidas e se o redecode único foi disparado. */
data class AbiBatchRegistrationResult(
    val versions: List<br.com.bbchain.evex.indexer.domain.abi.ContractAbi>,
    val redecodeStarted: Boolean,
)
