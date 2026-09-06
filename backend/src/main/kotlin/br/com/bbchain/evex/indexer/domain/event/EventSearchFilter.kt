package br.com.bbchain.evex.indexer.domain.event

import java.time.Instant

/**
 * Filtros opcionais de `GET /v1/events` — todos `null` = sem filtro naquele campo.
 *
 * [walletAddress] (E22 / DEC-080): diferente dos demais campos (colunas tipadas), não filtra uma
 * coluna fixa — casa com o endereço aparecendo em **qualquer** parâmetro decodificado do tipo
 * `address` dentro do JSONB `decoded_parameters` (chaves dinâmicas por evento: `to`/`from`/`user`/
 * `owner`/`spender`/...). A porta continua expondo um único campo opcional — a implementação
 * (`JpaBlockchainEventAdapter`) decide internamente como resolver a consulta; ver DEC-080 para o
 * porquê de não caber como mais um predicado dentro do `Specification.allOf(...)` dinâmico que os
 * outros campos usam.
 */
data class EventSearchFilter(
    val contractAddress: String? = null,
    val eventName: String? = null,
    val fromBlock: Long? = null,
    val toBlock: Long? = null,
    val fromDate: Instant? = null,
    val toDate: Instant? = null,
    val walletAddress: String? = null,
)
