package br.com.bbchain.evex.indexer.domain.abi

/**
 * Um parâmetro (input) de um evento de ABI, na ordem de declaração Solidity — mistura indexados e
 * não-indexados. Mesmo trio de campos que o `AbiEventInput` de `adapters/outbound/abi/AbiParser`,
 * mas modelado no domínio: `AbiEventInput` é detalhe interno do decode (anda junto do `Event`/
 * `topic0` do Web3j), este tipo é o contrato "semântico" que sai pela porta.
 */
data class CatalogedEventInput(
    val name: String,
    val type: String,
    val indexed: Boolean,
)

/**
 * Um evento decodificável de um contrato, já associado à faixa de blocos da versão de ABI que o
 * declara (DEC-007: num contrato proxy upgradável o mesmo evento pode aparecer mais de uma vez, com
 * faixas de validade distintas). É o item do catálogo devolvido por
 * `GET /v1/contracts/{address}/events` (DEC-073).
 */
data class CatalogedEvent(
    val name: String,
    val inputs: List<CatalogedEventInput>,
    val validFromBlock: Long,
    val validToBlock: Long?,
)
