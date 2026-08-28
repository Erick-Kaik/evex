package br.com.bbchain.evex.indexer.domain.abi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test puro (sem I/O) — mesmo espírito de `PollWindowTest`. */
class InterfaceDetectorTest {

    // Valores reais de topic0 (keccak256 da assinatura, sem "indexed") — confirmados via
    // org.web3j.abi.EventEncoder, mesmo valor documentado em AbiParserTest/BuiltinInterfaceTemplatesTest.
    private val transferTopic0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private val erc20ApprovalTopic0 = "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925"
    private val erc721ApprovalTopic0 = erc20ApprovalTopic0 // Approval(address,address,uint256) — mesmo topic0, ERC20/721
    private val approvalForAllTopic0 = "0x17307eab39ab6107e8899845ad3d59bd9653f200f220920489ca2b5937696c31"
    private val transferSingleTopic0 = "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62"
    private val transferBatchTopic0 = "0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb"

    private val erc20 = InterfaceTemplate("ERC20", setOf(transferTopic0, erc20ApprovalTopic0), "erc20-abi")
    private val erc721 = InterfaceTemplate("ERC721", setOf(transferTopic0, erc721ApprovalTopic0, approvalForAllTopic0), "erc721-abi")
    private val erc1155 = InterfaceTemplate("ERC1155", setOf(transferSingleTopic0, transferBatchTopic0, approvalForAllTopic0), "erc1155-abi")

    private val templates = listOf(erc20, erc721, erc1155)

    @Test
    fun `full match when every topic0 of a template was observed`() {
        val observed = setOf(transferTopic0, erc20ApprovalTopic0, "0xsomeUnrelatedTopic")

        val results = InterfaceDetector.detect(observed, listOf(erc20))

        assertThat(results).hasSize(1)
        assertThat(results[0].templateName).isEqualTo("ERC20")
        assertThat(results[0].level).isEqualTo(DetectionLevel.FULL)
        assertThat(results[0].matchedTopic0s).isEqualTo(setOf(transferTopic0, erc20ApprovalTopic0))
        assertThat(results[0].missingTopic0s).isEmpty()
    }

    @Test
    fun `partial match when only some of a template's topic0s were observed`() {
        val observed = setOf(transferTopic0)

        val results = InterfaceDetector.detect(observed, listOf(erc20))

        assertThat(results[0].level).isEqualTo(DetectionLevel.PARTIAL)
        assertThat(results[0].matchedTopic0s).isEqualTo(setOf(transferTopic0))
        assertThat(results[0].missingTopic0s).isEqualTo(setOf(erc20ApprovalTopic0))
    }

    @Test
    fun `no match when none of a template's standard topic0s were observed`() {
        val observed = setOf("0xsomeCustomEventTopic0")

        val results = InterfaceDetector.detect(observed, listOf(erc20))

        assertThat(results[0].level).isEqualTo(DetectionLevel.NONE)
        assertThat(results[0].matchedTopic0s).isEmpty()
        assertThat(results[0].missingTopic0s).isEqualTo(erc20.eventTopic0s)
    }

    @Test
    fun `erc1155 has no ambiguity with erc20-721 - TransferSingle-TransferBatch are unique signatures`() {
        val observed = setOf(transferSingleTopic0, transferBatchTopic0, approvalForAllTopic0)

        val results = InterfaceDetector.detect(observed, templates)

        assertThat(results[0].templateName).isEqualTo("ERC1155")
        assertThat(results[0].level).isEqualTo(DetectionLevel.FULL)
        // ERC721 também observa `ApprovalForAll` isolado (PARTIAL); ERC20 não observa nada (NONE) —
        // nenhum dos dois é FULL, sem ambiguidade nenhuma com o ERC1155.
        assertThat(results.first { it.templateName == "ERC20" }.level).isEqualTo(DetectionLevel.NONE)
    }

    /**
     * Achado central desta etapa: `topic0` é `keccak256(nome+tipos)`, não inclui `indexed` — então
     * `Transfer(address,address,uint256)` do ERC20 e do ERC721 têm o **mesmo `topic0`**. Um contrato
     * que emite os 3 eventos do ERC721 (`Transfer`, `Approval`, `ApprovalForAll`) também dá match
     * total no ERC20 (2 eventos, subconjunto do observado) — dois `FULL` simultâneos. O desempate por
     * `eventTopic0s.size` decrescente escolhe o template mais específico: ERC721 (3 eventos) antes de
     * ERC20 (2 eventos).
     */
    @Test
    fun `a contract emitting all 3 ERC721 events resolves as ERC721, not the ambiguous ERC20 subset match`() {
        val observed = setOf(transferTopic0, erc721ApprovalTopic0, approvalForAllTopic0)

        val results = InterfaceDetector.detect(observed, templates)

        // Ambos ERC20 e ERC721 batem FULL (ambiguidade real de topic0) — mas o primeiro do resultado
        // ordenado precisa ser o mais específico.
        assertThat(results.filter { it.level == DetectionLevel.FULL }.map { it.templateName })
            .containsExactlyInAnyOrder("ERC20", "ERC721")
        assertThat(results[0].templateName).isEqualTo("ERC721")
        assertThat(results[0].level).isEqualTo(DetectionLevel.FULL)
    }

    @Test
    fun `results are sorted FULL first, then by template size descending as an ambiguity tiebreak`() {
        val observed = setOf(transferTopic0, erc721ApprovalTopic0, approvalForAllTopic0, "0xsomeCustomEventTopic0")

        val results = InterfaceDetector.detect(observed, templates)

        assertThat(results.map { it.templateName }).containsExactly("ERC721", "ERC20", "ERC1155")
    }
}
