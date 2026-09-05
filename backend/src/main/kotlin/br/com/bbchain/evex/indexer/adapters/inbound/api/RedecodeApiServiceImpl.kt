package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.RedecodeApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.RedecodeFilterPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.RedecodeStatus as RedecodeStatusDto
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import org.springframework.stereotype.Service

/**
 * Implementação fina de [RedecodeApiService]: mapeia DTO gerado <-> domínio e delega direto pro
 * [RedecodeOrchestrator] — ele já É o caso de uso (`ingestion-pipeline-reference.md` §4), não precisa
 * de um serviço de aplicação intermediário adicional. Nenhuma regra de negócio aqui — ver
 * docs/hexagonal-architecture.md.
 */
@Service
class RedecodeApiServiceImpl(
    private val redecodeOrchestrator: RedecodeOrchestrator,
) : RedecodeApiService {

    /**
     * `tryStart` decide sozinho se dispara ou não (já havia uma execução em andamento) — a resposta
     * `202` sempre reflete o [RedecodeOrchestrator.status] real logo depois da tentativa, então mesmo
     * quando `tryStart` retorna `false` a resposta ainda é útil pro chamador.
     */
    override fun triggerRedecode(redecodeFilterPayload: RedecodeFilterPayload?): RedecodeStatusDto {
        val filter = redecodeFilterPayload.toDomain()
        redecodeOrchestrator.tryStart(filter)
        return redecodeOrchestrator.status().toWireDto()
    }

    override fun getRedecodeStatus(): RedecodeStatusDto = redecodeOrchestrator.status().toWireDto()

    private fun RedecodeFilterPayload?.toDomain(): RedecodeFilter = RedecodeFilter(
        contractAddress = this?.contractAddress?.lowercase(),
        topic0 = this?.topic0,
        fromBlock = this?.fromBlock,
        toBlock = this?.toBlock,
    )
}
