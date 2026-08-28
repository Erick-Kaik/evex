package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.InterfaceTemplate
import org.springframework.stereotype.Component

/**
 * Monta os 3 [InterfaceTemplate] built-in (ERC20/ERC721/ERC1155 — doc de produto §4.5,
 * `abi-strategy-design.md` §3). Cada `builtinAbi` é uma ABI JSON mínima escrita à mão, só com os
 * eventos padrão da interface — parseada 1x no construtor via o [AbiParser] já existente da E3
 * (`EventEncoder.encode` calcula `topic0`), nenhuma lógica nova de Web3j: pura reutilização do que a
 * E3 já resolve.
 *
 * `topic0` é `keccak256(nome+tipos)` — não inclui `indexed` — então `Transfer(address,address,uint256)`
 * do ERC20 e do ERC721 têm o **mesmo `topic0`** (só a posição indexed/data difere no log real, não no
 * hash da assinatura). Isso é tratado no desempate de [br.com.bbchain.evex.indexer.domain.abi.
 * InterfaceDetector], não aqui — aqui os `builtinAbi` refletem a assinatura *correta* de cada padrão
 * (inclusive quais parâmetros são `indexed`), pra decodificar certo quando `AUTO_DETECTED` escolher
 * um destes templates.
 */
@Component
class BuiltinInterfaceTemplates(abiParser: AbiParser) {

    val templates: List<InterfaceTemplate> = listOf(
        buildTemplate("ERC20", ERC20_ABI, abiParser),
        buildTemplate("ERC721", ERC721_ABI, abiParser),
        buildTemplate("ERC1155", ERC1155_ABI, abiParser),
    )

    fun findByName(name: String): InterfaceTemplate? = templates.firstOrNull { it.name == name }

    private companion object {
        fun buildTemplate(name: String, abiJson: String, abiParser: AbiParser): InterfaceTemplate {
            val descriptors = abiParser.parse(abiJson)
            return InterfaceTemplate(
                name = name,
                eventTopic0s = descriptors.map { it.topic0 }.toSet(),
                builtinAbi = abiJson,
            )
        }

        val ERC20_ABI = """
            [
              {
                "type": "event",
                "name": "Transfer",
                "inputs": [
                  {"name": "from", "type": "address", "indexed": true},
                  {"name": "to", "type": "address", "indexed": true},
                  {"name": "value", "type": "uint256", "indexed": false}
                ]
              },
              {
                "type": "event",
                "name": "Approval",
                "inputs": [
                  {"name": "owner", "type": "address", "indexed": true},
                  {"name": "spender", "type": "address", "indexed": true},
                  {"name": "value", "type": "uint256", "indexed": false}
                ]
              }
            ]
        """.trimIndent()

        val ERC721_ABI = """
            [
              {
                "type": "event",
                "name": "Transfer",
                "inputs": [
                  {"name": "from", "type": "address", "indexed": true},
                  {"name": "to", "type": "address", "indexed": true},
                  {"name": "tokenId", "type": "uint256", "indexed": true}
                ]
              },
              {
                "type": "event",
                "name": "Approval",
                "inputs": [
                  {"name": "owner", "type": "address", "indexed": true},
                  {"name": "approved", "type": "address", "indexed": true},
                  {"name": "tokenId", "type": "uint256", "indexed": true}
                ]
              },
              {
                "type": "event",
                "name": "ApprovalForAll",
                "inputs": [
                  {"name": "owner", "type": "address", "indexed": true},
                  {"name": "operator", "type": "address", "indexed": true},
                  {"name": "approved", "type": "bool", "indexed": false}
                ]
              }
            ]
        """.trimIndent()

        val ERC1155_ABI = """
            [
              {
                "type": "event",
                "name": "TransferSingle",
                "inputs": [
                  {"name": "operator", "type": "address", "indexed": true},
                  {"name": "from", "type": "address", "indexed": true},
                  {"name": "to", "type": "address", "indexed": true},
                  {"name": "id", "type": "uint256", "indexed": false},
                  {"name": "value", "type": "uint256", "indexed": false}
                ]
              },
              {
                "type": "event",
                "name": "TransferBatch",
                "inputs": [
                  {"name": "operator", "type": "address", "indexed": true},
                  {"name": "from", "type": "address", "indexed": true},
                  {"name": "to", "type": "address", "indexed": true},
                  {"name": "ids", "type": "uint256[]", "indexed": false},
                  {"name": "values", "type": "uint256[]", "indexed": false}
                ]
              },
              {
                "type": "event",
                "name": "ApprovalForAll",
                "inputs": [
                  {"name": "owner", "type": "address", "indexed": true},
                  {"name": "operator", "type": "address", "indexed": true},
                  {"name": "approved", "type": "bool", "indexed": false}
                ]
              }
            ]
        """.trimIndent()
    }
}
