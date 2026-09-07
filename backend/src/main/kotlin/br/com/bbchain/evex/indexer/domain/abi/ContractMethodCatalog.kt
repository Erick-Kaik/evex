package br.com.bbchain.evex.indexer.domain.abi

/**
 * Um parâmetro de entrada de um método de ABI, na ordem de declaração Solidity. Mirror de
 * [CatalogedEventInput], sem `indexed` (não existe essa noção para argumentos de função).
 */
data class CatalogedMethodInput(
    val name: String,
    val type: String,
)

/**
 * Uma saída de um método de leitura, na ordem de declaração Solidity (`returns (...)`). [name] pode
 * ser `null`/vazio — nem toda saída de uma tupla de retorno é nomeada no Solidity (ex.:
 * `returns (uint256, uint256)`); por isso o casamento de `EnrichmentOutputMapping.outputIndex`
 * (E19/DEC-082, ver `domain/enrichment/`) é sempre por **posição**, nunca por nome — o nome é só dica
 * de UI quando presente.
 */
data class CatalogedMethodOutput(
    val name: String?,
    val type: String,
)

/**
 * Um método de leitura decodificável de um contrato, já associado à faixa de blocos da versão de ABI
 * que o declara (mesmo raciocínio de [CatalogedEvent] — DEC-007, proxy upgradável: o mesmo método pode
 * aparecer mais de uma vez, com faixas de validade distintas). É o item do catálogo devolvido por
 * `GET /v1/contracts/{address}/methods` (E19/DEC-082).
 */
data class CatalogedMethod(
    val name: String,
    val inputs: List<CatalogedMethodInput>,
    val outputs: List<CatalogedMethodOutput>,
    val validFromBlock: Long,
    val validToBlock: Long?,
)
