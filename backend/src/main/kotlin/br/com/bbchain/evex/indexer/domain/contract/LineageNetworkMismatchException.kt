package br.com.bbchain.evex.indexer.domain.contract

/**
 * `predecessorAddress` informado no cadastro (E29/DEC-096/DEC-097) pertence a uma [network][
 * br.com.bbchain.evex.indexer.domain.common.Network] diferente do contrato novo — linhagem não pode
 * misturar TESTNET/MAINNET.
 */
class LineageNetworkMismatchException(val address: String, val predecessorAddress: String) :
    RuntimeException(
        "O predecessor informado ($predecessorAddress) pertence a uma rede diferente do contrato novo ($address)",
    )
