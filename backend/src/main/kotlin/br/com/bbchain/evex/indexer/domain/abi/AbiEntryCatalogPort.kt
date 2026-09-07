package br.com.bbchain.evex.indexer.domain.abi

/**
 * Porta de saída: extrai de um ABI JSON **todos** os itens — funções (qualquer `stateMutability`,
 * não só `view`/`pure` como [AbiMethodCatalogPort]), eventos, erros customizados (`"type": "error"`,
 * motivos de revert declarados — Solidity ≥0.8.4), constructor, fallback e receive. Mirror de
 * [AbiEventCatalogPort]/[AbiMethodCatalogPort], só que sem filtro nenhum — alimenta a tela
 * "Explorador de ABI" (métodos/eventos/erros numa tabela só, somente leitura), não decodifica nada
 * nem chama a rede.
 *
 * **Escopo explícito**: só lista os erros customizados que o contrato **declara** no ABI (nome +
 * parâmetros) — não decodifica o motivo real de uma transação que já reverteu (isso exigiria
 * capturar/tracear a transação e casar o seletor do erro com o retorno cru, um dado de execução, não
 * de ABI estática — fora de escopo, feature própria se algum dia for necessária).
 *
 * Mesma razão de porta dedicada das outras 2 (um use case de `application/` não pode depender de
 * `adapters/outbound/abi/AbiParser` diretamente, que importa `org.web3j.abi.*`). Implementação em
 * `adapters/outbound/abi/` — o próprio `AbiParser` também implementa esta porta (reaproveitando o
 * mesmo walk de JSON, sem filtrar por `"type"`).
 */
interface AbiEntryCatalogPort {
    /**
     * Parseia [abiJson] e devolve **todos** os seus itens, na ordem de declaração. Não conhece faixa
     * de blocos — isso é metadado de [ContractAbi], combinado pelo use case
     * ([br.com.bbchain.evex.indexer.application.abi.ContractAbiEntryCatalogService]).
     * Lança [InvalidAbiContentException] se [abiJson] não for um ABI JSON válido (na prática não
     * ocorre pra conteúdo vindo do storage — já foi validado no cadastro).
     */
    fun entriesOf(abiJson: String): List<ParsedAbiEntry>
}

/**
 * Um item de ABI cru parseado de um único documento (sem faixa de blocos — o documento de ABI não tem
 * essa noção). O use case combina cada um com o `validFromBlock`/`validToBlock` do [ContractAbi] de
 * origem pra produzir os [CatalogedAbiEntry] finais.
 */
data class ParsedAbiEntry(
    val kind: AbiEntryKind,
    val name: String?,
    val stateMutability: String?,
    val inputs: List<CatalogedAbiEntryParam>,
    val outputs: List<CatalogedAbiEntryParam>,
    val anonymous: Boolean?,
)
