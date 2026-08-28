package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.BuiltinInterfaceTemplates
import br.com.bbchain.evex.indexer.domain.abi.DetectionLevel
import br.com.bbchain.evex.indexer.domain.abi.InterfaceDetector
import br.com.bbchain.evex.indexer.domain.abi.InterfaceMatch
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Caso de uso: sugestão de interface padrão pra um contrato (`GET /v1/contracts/{address}/abi/
 * suggestions`, doc §4.5) e resolução do template usado por `AbiSource.AUTO_DETECTED`. Não conhece
 * HTTP nem persistência concreta.
 */
@Service
class InterfaceDetectionService(
    private val blockchainEvents: BlockchainEventPort,
    private val blockscout: BlockscoutPort,
    private val templates: BuiltinInterfaceTemplates,
) {
    private val logger = LoggerFactory.getLogger(InterfaceDetectionService::class.java)

    /**
     * Resolve os `topic0`s observados preferindo o que já foi ingerido (`BlockchainEventPort`, DEC-001
     * — a ingestão é da rede inteira, então já observamos os logs de qualquer contrato). Se ainda
     * estiver vazio (poller não alcançou esse contrato ainda), cai pro Blockscout como *fallback* (doc
     * §7) — mas [BlockscoutUnavailableException] nesse fallback (não configurado, ou a chamada falhou)
     * é **engolida**, não propagada: Blockscout nunca pode ser dependência crítica do caminho principal
     * (regra não-negociável do projeto, mesmo espírito do doc §4.8 pra storage externo de ABI) — sem
     * dado nenhum, o resultado é simplesmente "nenhum template bate" (`NONE` pra todos, filtrado
     * abaixo), não um erro. Diferente de `AbiSource.BLOCKSCOUT`/`GET /v1/discovery/blockscout/
     * contracts`, que usam o Blockscout como fonte *primária* e propagam a falha normalmente.
     *
     * Log em `WARN`, não `DEBUG` (achado do `reviewer` na E6, corrigido em E8.1) — `DEBUG` normalmente
     * fica desligado em produção, deixando o operador sem nenhum sinal de que o fallback está sempre
     * vazio por falta de configuração do Blockscout (`indexer.blockscout.base-url`).
     */
    fun suggest(address: String): List<InterfaceMatch> {
        val normalizedAddress = address.lowercase()
        var observedTopic0s = blockchainEvents.findDistinctTopic0s(normalizedAddress)
        if (observedTopic0s.isEmpty()) {
            observedTopic0s = try {
                blockscout.getLogTopic0s(normalizedAddress)
            } catch (ex: BlockscoutUnavailableException) {
                logger.warn("Blockscout indisponível pro fallback de detecção de interface de {}: {}", normalizedAddress, ex.message)
                emptySet()
            }
        }
        return InterfaceDetector.detect(observedTopic0s, templates.templates)
            .filter { it.level != DetectionLevel.NONE }
    }
}
