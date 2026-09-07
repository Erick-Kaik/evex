package br.com.bbchain.evex.indexer.domain.abi

/**
 * Porta de saída: extrai de um ABI JSON só a face "semântica" dos métodos de **leitura** (`"type":
 * "function"` com `stateMutability` `view`/`pure`) — nome + inputs + outputs tipados, **sem** os
 * artefatos de codificação/decodificação do Web3j (`org.web3j.abi.datatypes.Function`,
 * `FunctionEncoder`), que são detalhe de infraestrutura e não podem cruzar a fronteira pro domínio.
 * Mirror exato de [AbiEventCatalogPort] (E16/DEC-076), só que para métodos em vez de eventos — mesmo
 * racional de porta dedicada (um use case de `application/` não pode depender de
 * `adapters/outbound/abi/AbiParser` diretamente).
 *
 * Escopo: só funções `view`/`pure` (o Evex nunca assina transação, só lê estado via `eth_call` —
 * E19/DEC-082, "Perfil de Enriquecimento"). Funções que mutam estado, constructor/fallback/
 * receive/error são ignorados.
 *
 * Implementação em `adapters/outbound/abi/` — o próprio `AbiParser` também implementa esta porta
 * (reaproveitando o mesmo parse de JSON que já faz para eventos, só filtrando por `"type": "function"`
 * em vez de `"type": "event"`).
 */
interface AbiMethodCatalogPort {
    /**
     * Parseia [abiJson] e devolve seus métodos de leitura. Não conhece faixa de blocos — isso é
     * metadado de [ContractAbi], combinado pelo use case
     * ([br.com.bbchain.evex.indexer.application.abi.ContractMethodCatalogService]).
     * Lança [InvalidAbiContentException] se [abiJson] não for um ABI JSON válido (na prática não
     * ocorre pra conteúdo vindo do storage — já foi validado no cadastro).
     */
    fun methodsOf(abiJson: String): List<ParsedAbiMethod>
}

/**
 * Um método de leitura cru parseado de um único documento de ABI (sem faixa de blocos — o documento de
 * ABI não tem essa noção). O use case combina cada um com o `validFromBlock`/`validToBlock` do
 * [ContractAbi] de origem pra produzir os [CatalogedMethod] finais.
 *
 * [outputs] preserva a **ordem** de retorno declarada no Solidity — o casamento de
 * `EnrichmentOutputMapping.outputIndex` (E19/DEC-082) é por posição, nunca por nome (nem toda saída de
 * uma tupla de retorno tem nome no Solidity).
 */
data class ParsedAbiMethod(
    val name: String,
    val inputs: List<CatalogedMethodInput>,
    val outputs: List<CatalogedMethodOutput>,
)
