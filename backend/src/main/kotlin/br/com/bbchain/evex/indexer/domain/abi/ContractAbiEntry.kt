package br.com.bbchain.evex.indexer.domain.abi

/**
 * Discrimina o tipo de item de um ABI JSON — o campo `"type"` bruto do Solidity, sem tradução. Mirror
 * do mesmo vocabulário que já existe em [AbiEventCatalogPort]/[AbiMethodCatalogPort] (`"event"`/
 * `"function"`), só que cobrindo **todo** o ABI desta vez (nenhum item é ignorado), pra alimentar a
 * tela "Explorador de ABI" (métodos/eventos/erros numa tabela só).
 */
enum class AbiEntryKind {
    FUNCTION,
    EVENT,
    ERROR,
    CONSTRUCTOR,
    FALLBACK,
    RECEIVE,
}

/**
 * Um parâmetro (input ou output) de qualquer item de ABI, na ordem de declaração Solidity. Mirror de
 * [CatalogedEventInput]/[CatalogedMethodInput]/[CatalogedMethodOutput] fundidos num tipo só — os 3
 * campos nunca fazem sentido juntos ao mesmo tempo (`indexed` só existe em input de evento), por isso
 * ambos são nullable aqui: `name` porque nem toda saída de função/erro é nomeada no Solidity (mesmo
 * racional de [CatalogedMethodOutput.name]), `indexed` porque só se aplica a inputs de [AbiEntryKind.EVENT].
 */
data class CatalogedAbiEntryParam(
    val name: String?,
    val type: String,
    val indexed: Boolean? = null,
)

/**
 * Um item de ABI (função, evento, erro customizado, constructor, fallback ou receive), já associado à
 * faixa de blocos da versão de ABI que o declara (mesmo raciocínio de [CatalogedEvent]/[CatalogedMethod]
 * — DEC-007, proxy upgradável). É o item do catálogo devolvido por
 * `GET /v1/contracts/{address}/abi/entries` — cobre o ABI inteiro, diferente dos 2 catálogos
 * existentes ([AbiEventCatalogPort]/[AbiMethodCatalogPort]), que filtram só o que cada um precisa.
 *
 * [name] é `null` para [AbiEntryKind.CONSTRUCTOR]/[AbiEntryKind.FALLBACK]/[AbiEntryKind.RECEIVE] (não
 * têm nome no Solidity). [stateMutability] só se aplica a `FUNCTION`/`CONSTRUCTOR`. [outputs] só
 * `FUNCTION` declara de fato (`ERROR`/os demais ficam com lista vazia — erro Solidity só tem
 * parâmetros de entrada, sem retorno). [anonymous] só se aplica a `EVENT`.
 */
data class CatalogedAbiEntry(
    val kind: AbiEntryKind,
    val name: String?,
    val stateMutability: String?,
    val inputs: List<CatalogedAbiEntryParam>,
    val outputs: List<CatalogedAbiEntryParam>,
    val anonymous: Boolean?,
    val validFromBlock: Long,
    val validToBlock: Long?,
)
