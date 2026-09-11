package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test puro — sem HTTP/WebSocket, sem mock de transporte (E27/DEC-094). Cobre as 4 combinações de
 * `authToken`/`authEnabled` que [BlockchainProperties.authHeaders] precisa resolver; o wiring em si
 * ([Web3jBlockchainClient]/[WebSocketPollTrigger]) é código de 1 linha, coberto indiretamente pelos
 * testes existentes desses 2 arquivos (que substituem `web3j`/`webSocketService` por mocks depois da
 * construção, sem exercitar os headers reais).
 */
class BlockchainAuthTest {

    @Test
    fun `empty token and auth enabled produces no header`() {
        val properties = BlockchainProperties().apply {
            authToken = ""
            authEnabled = true
        }

        assertThat(properties.authHeaders()).isEmpty()
    }

    @Test
    fun `token present and auth enabled produces the Authorization Bearer header`() {
        val properties = BlockchainProperties().apply {
            authToken = "my-pat-token"
            authEnabled = true
        }

        assertThat(properties.authHeaders()).isEqualTo(mapOf("Authorization" to "Bearer my-pat-token"))
    }

    @Test
    fun `token present but auth disabled produces no header`() {
        val properties = BlockchainProperties().apply {
            authToken = "my-pat-token"
            authEnabled = false
        }

        assertThat(properties.authHeaders()).isEmpty()
    }

    @Test
    fun `empty token and auth disabled produces no header`() {
        val properties = BlockchainProperties().apply {
            authToken = ""
            authEnabled = false
        }

        assertThat(properties.authHeaders()).isEmpty()
    }
}
