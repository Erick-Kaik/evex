package br.com.bbchain.evex.indexer.domain.abi

/**
 * `POST /v1/contracts/{address}/abi` com `source=BLOCKSCOUT` quando o Blockscout configurado não tem
 * ABI verificada para este endereço (`BlockscoutPort.getAbi` retornou `null`) — diferente de
 * [br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException] (503, Blockscout nem
 * está configurado/acessível): aqui o Blockscout respondeu, só não tem o contrato verificado.
 */
class BlockscoutAbiNotFoundException(val address: String) : RuntimeException(
    "Nenhuma ABI verificada encontrada no Blockscout para o contrato $address.",
)
