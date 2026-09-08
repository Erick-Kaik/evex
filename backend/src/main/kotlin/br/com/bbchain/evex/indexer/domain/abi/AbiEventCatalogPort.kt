package br.com.bbchain.evex.indexer.domain.abi

/**
 * Porta de saída: extrai de um ABI JSON só a face "semântica" dos eventos — nome + inputs tipados
 * (`name`/`type`/`indexed`) — **sem** os artefatos de decodificação do Web3j (`org.web3j.abi.datatypes.Event`,
 * `topic0`), que são detalhe de infraestrutura e não podem cruzar a fronteira pro domínio.
 *
 * Por que uma porta nova e não reusar `AbiParser` direto do use case (o "ponto em aberto" da DEC-073):
 * `AbiParser` mora em `adapters/outbound/abi/` e importa `org.web3j.abi.*` — um use case de
 * `application/` não pode depender dele. O `EventDescriptor` que `AbiParser.parse` devolve carrega um
 * `Event` e o `topic0` (calculado via `EventEncoder`, código do Web3j) — o catálogo de eventos não
 * precisa de nenhum dos dois. Esta porta expõe um tipo magro ([ParsedAbiEvent]) que não arrasta Web3j.
 *
 * Implementação em `adapters/outbound/abi/` — o próprio `AbiParser` passa a implementar esta porta
 * (reaproveitando o mesmo parse que já faz, só descartando `Event`/`topic0` no retorno).
 *
 * Escopo: só eventos. `"type": "event"` no ABI. Funções/constructor/error/fallback são ignorados —
 * se um consumidor futuro precisar de funções (ex.: `staticcall` de saldo on-chain), é outra porta.
 */
interface AbiEventCatalogPort {
    /**
     * Parseia [abiJson] e devolve seus eventos. Não conhece faixa de blocos — isso é metadado de
     * [ContractAbi], combinado pelo use case ([br.com.bbchain.evex.indexer.application.abi.ContractEventCatalogService]).
     * Lança [InvalidAbiContentException] em 2 casos, ambos na prática inalcançáveis pra conteúdo vindo
     * do storage (já validado no cadastro): (1) [abiJson] não é um ABI JSON válido; (2) algum item
     * `"type": "event"` não tem `name` (ou `name` em branco) — a implementação (`AbiParser.readEvent`)
     * exige `name` pra todo evento, evento é o único tipo de item que sempre declara um.
     *
     * **Não rejeita tipo Solidity não suportado** (ex. `tuple`/`struct` num input) — ao contrário de
     * [br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser.parse] (usado pela decodificação de
     * verdade, que precisa resolver o tipo pro Web3j via `TypeReference.makeTypeReference` e falha
     * cedo), este catálogo só lê `name`/`type` como texto cru pra exibição — um `tuple` aparece na
     * lista com `type = "tuple"`, sem erro. Consistente com o catálogo ser "espelho pra UI", não
     * caminho de decodificação.
     */
    fun eventsOf(abiJson: String): List<ParsedAbiEvent>
}

/**
 * Um evento cru parseado de um único documento de ABI (sem faixa de blocos — o documento de ABI não
 * tem essa noção). O use case combina cada um com o `validFromBlock`/`validToBlock` do [ContractAbi]
 * de origem pra produzir os [CatalogedEvent] finais.
 */
data class ParsedAbiEvent(
    val name: String,
    val inputs: List<CatalogedEventInput>,
)
