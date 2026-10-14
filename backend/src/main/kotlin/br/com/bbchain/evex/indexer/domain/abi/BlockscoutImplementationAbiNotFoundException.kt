package br.com.bbchain.evex.indexer.domain.abi

/**
 * Cadastro `source=BLOCKSCOUT` de um proxy cuja implementação atual ([implementationAddress]) não tem
 * ABI verificada no Blockscout (DEC-105). Sem fallback silencioso para a ABI do proxy (seria o bug que
 * esta exceção evita: ABI do proxy não decodifica os eventos da implementação). Mapeada para 422.
 */
class BlockscoutImplementationAbiNotFoundException(
    val proxyAddress: String,
    val implementationAddress: String,
) : RuntimeException(
    "O contrato $proxyAddress é um proxy, mas a implementação $implementationAddress não tem ABI " +
        "verificada no Blockscout; verifique a implementação ou cadastre a ABI manualmente (upload).",
)
