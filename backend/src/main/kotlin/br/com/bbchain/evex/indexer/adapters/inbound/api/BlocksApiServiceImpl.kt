package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.BlocksApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ResolvedBlock
import br.com.bbchain.evex.indexer.application.block.BlockResolutionService
import org.springframework.stereotype.Service

/**
 * Implementação fina de [BlocksApiService]: delega a [BlockResolutionService] e mapeia o resultado.
 * `blockNumber` nulo com `warnings` é um 200 válido (não existe bloco no sentido pedido); `direction`
 * inválida vira 400 via `InvalidBlockDirectionException` (tratada em `ApiExceptionHandler`).
 */
@Service
class BlocksApiServiceImpl(
    private val blockResolutionService: BlockResolutionService,
) : BlocksApiService {

    override fun resolveBlockByDate(date: java.time.OffsetDateTime, direction: kotlin.String): ResolvedBlock =
        blockResolutionService.resolve(date.toInstant(), direction).toDto()
}
