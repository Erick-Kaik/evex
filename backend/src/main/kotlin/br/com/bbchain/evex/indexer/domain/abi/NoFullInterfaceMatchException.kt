package br.com.bbchain.evex.indexer.domain.abi

/**
 * `POST /v1/contracts/{address}/abi` com `source=AUTO_DETECTED` quando nenhum [InterfaceTemplate]
 * bate em nível `FULL` (match total) com os `topic0`s observados do contrato — decisão tomada com o
 * usuário na E6: um match `PARTIAL` (nível 2) nunca é aplicado automaticamente, fica só como sugestão
 * em `GET /v1/contracts/{address}/abi/suggestions`.
 */
class NoFullInterfaceMatchException(val address: String) : RuntimeException(
    "Nenhuma interface padrão (ERC20/ERC721/ERC1155) bate em match total com os eventos observados de $address; " +
        "cadastre a ABI manualmente (UPLOAD_FILE/UPLOAD_TEXT) ou via BLOCKSCOUT.",
)
