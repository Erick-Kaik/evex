package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.discovery.UpgradedEvent
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * `JpaSpecificationExecutor` para a `Specification` dinâmica de [JpaBlockchainEventAdapter.search]
 * (filtros opcionais combináveis — mesmo padrão do `EventQueryService` do código base, citado em
 * `backend/CLAUDE.md`).
 */
interface BlockchainEventJpaRepository : JpaRepository<BlockchainEventEntity, UUID>, JpaSpecificationExecutor<BlockchainEventEntity> {
    fun existsByTxHashAndLogIndex(txHash: String, logIndex: Long): Boolean

    /** `topic0`s distintos já ingeridos para um contrato (E6, detecção automática de interface). */
    @Query("SELECT DISTINCT e.topic0 FROM BlockchainEventEntity e WHERE e.contractAddress = :contractAddress AND e.topic0 IS NOT NULL")
    fun findDistinctTopic0sByContractAddress(@Param("contractAddress") contractAddress: String): Set<String>

    /**
     * Caminho de query **dedicado** pro filtro `walletAddress` de `GET /v1/events` (E22, DEC-080,
     * complemento 2026-09-05) — usado só quando `EventSearchFilter.walletAddress != null`; o caso
     * `null` continua 100% pela `Specification` dinâmica de [JpaBlockchainEventAdapter.buildSpecification]
     * (nenhuma regressão nos filtros já existentes).
     *
     * Por que query nativa e não `Specification.allOf(...)` com `cb.function()`: `decoded_parameters`
     * é JSONB de chaves dinâmicas (`to`/`from`/`user`/...), então "endereço aparece em qualquer
     * parâmetro" só dá pra expressar com `jsonb_path_exists` (JSON Path com wildcard `$.*` sobre os
     * *valores*). O `path` do `jsonb_path_exists` (segundo argumento) precisa chegar ao Postgres como
     * `jsonpath` — um literal de texto cru no corpo do SQL é tratado como tipo "unknown" e o Postgres
     * aplica sua função de entrada (`jsonpath_in`) implicitamente; um bind parameter JDBC, por outro
     * lado, chega tipado (`text`/`varchar`) e não tem cast implícito registrado pra `jsonpath` — por
     * isso o padrão `'$.* ? (@ == $addr)'::jsonpath` abaixo é escrito como **literal fixo no texto da
     * query** (nunca como parâmetro), e só o valor comparado (`$addr`, via `jsonb_build_object`) é um
     * bind parameter normal (`text` -> `jsonb`, sem fricção de cast nenhuma). Esse desenho evita por
     * completo o problema de tipagem que a DEC-080 documentou pro `cb.function()` genérico.
     *
     * Normalização de caixa (pendência da DEC-080): a decodificação (`AbiDecodingAdapter`, via
     * `org.web3j.abi.datatypes.Address`) grava endereços **sempre em lowercase** — confirmado
     * empiricamente (Web3j decodifica `address` a partir do `BigInteger`/`Numeric.toHexString`, que
     * nunca aplica o checksum EIP-55, mesmo que o valor on-chain original/o input do ABI encoder
     * estivesse em outra caixa). Por isso a comparação usa `==` exata (não `like_regex` case-insensitive)
     * — o adapter normaliza `walletAddress` pra lowercase antes de vincular o parâmetro, mesmo padrão já
     * usado pros outros filtros de endereço deste repositório (`contractAddress.lowercase()`).
     *
     * `decoded_parameters IS NULL` (evento ainda não decodificado) faz `jsonb_path_exists` retornar
     * `NULL`, que o `WHERE` trata como não-casamento — correto: um log cru sem parâmetros não pode
     * "conter" um endereço.
     *
     * Filtros combináveis via `(CAST(:param AS tipo) IS NULL OR coluna = CAST(:param AS tipo))` — mesma
     * semântica de "todos os filtros informados aplicam em AND, os ausentes (`null`) não restringem
     * nada" do `Specification` dinâmico. O `CAST(...)` explícito em cada parâmetro (mesmo nos que já
     * comparam contra uma coluna tipada, redundante à primeira vista) é necessário achado empírico
     * contra Testcontainers: sem ele, um parâmetro `null` de tipo `Instant`/`Long` isolado num `? IS
     * NULL` sem nenhum outro operador na mesma posição sintática falha com "could not determine data
     * type of parameter" (o driver JDBC do Postgres não consegue inferir o tipo do bind — `String`
     * aparentemente escapa disso, mas `Instant`/`Long` não; mais seguro deixar explícito nos 6). `ORDER
     * BY` fixo (mesma ordenação do `search` via `Specification`: `blockNumber DESC, logIndex DESC`) —
     * native query com `Pageable` no Spring Data JPA não aplica `Sort` dinâmico automaticamente, só usa
     * `Pageable` pra `LIMIT`/`OFFSET`.
     */
    @Query(
        value = """
            SELECT * FROM blockchain_event
            WHERE jsonb_path_exists(decoded_parameters, '${'$'}.* ? (@ == ${'$'}addr)'::jsonpath, jsonb_build_object('addr', :walletAddress))
              AND (CAST(:contractAddress AS varchar) IS NULL OR contract_address = CAST(:contractAddress AS varchar))
              AND (CAST(:eventName AS varchar) IS NULL OR event_name = CAST(:eventName AS varchar))
              AND (CAST(:fromBlock AS bigint) IS NULL OR block_number >= CAST(:fromBlock AS bigint))
              AND (CAST(:toBlock AS bigint) IS NULL OR block_number <= CAST(:toBlock AS bigint))
              AND (CAST(:fromDate AS timestamptz) IS NULL OR block_timestamp >= CAST(:fromDate AS timestamptz))
              AND (CAST(:toDate AS timestamptz) IS NULL OR block_timestamp <= CAST(:toDate AS timestamptz))
            ORDER BY block_number DESC, log_index DESC
        """,
        countQuery = """
            SELECT count(*) FROM blockchain_event
            WHERE jsonb_path_exists(decoded_parameters, '${'$'}.* ? (@ == ${'$'}addr)'::jsonpath, jsonb_build_object('addr', :walletAddress))
              AND (CAST(:contractAddress AS varchar) IS NULL OR contract_address = CAST(:contractAddress AS varchar))
              AND (CAST(:eventName AS varchar) IS NULL OR event_name = CAST(:eventName AS varchar))
              AND (CAST(:fromBlock AS bigint) IS NULL OR block_number >= CAST(:fromBlock AS bigint))
              AND (CAST(:toBlock AS bigint) IS NULL OR block_number <= CAST(:toBlock AS bigint))
              AND (CAST(:fromDate AS timestamptz) IS NULL OR block_timestamp >= CAST(:fromDate AS timestamptz))
              AND (CAST(:toDate AS timestamptz) IS NULL OR block_timestamp <= CAST(:toDate AS timestamptz))
        """,
        nativeQuery = true,
    )
    fun searchByWalletAddress(
        @Param("walletAddress") walletAddress: String,
        @Param("contractAddress") contractAddress: String?,
        @Param("eventName") eventName: String?,
        @Param("fromBlock") fromBlock: Long?,
        @Param("toBlock") toBlock: Long?,
        @Param("fromDate") fromDate: Instant?,
        @Param("toDate") toDate: Instant?,
        pageable: Pageable,
    ): Page<BlockchainEventEntity>

    /**
     * Discovery ciente de proxy (DEC-104), passo 1: `Upgraded` cujo emissor está em [addresses] ou cujo
     * `topic1` (implementação) está em [addressTopics] (mesmos endereços já no formato topic de 32 bytes,
     * lowercase). Uma linha por (emissor, topic1) com o maior (block_number, log_index).
     *
     * O `topic0` é **literal interpolado** (`const val` de [UpgradedEvent.TOPIC0]), não bind: o predicado
     * dos índices parciais da `V6` é um literal, e com bind o plano genérico do Postgres não prova a
     * implicação e perde o índice. A expressão `lower(raw_topics->>1)` deve ser idêntica à do índice.
     * Retorna linhas `[contract_address, topic1, block_number, log_index]`.
     */
    @Query(
        value = """
            SELECT DISTINCT ON (contract_address, lower(raw_topics->>1))
                   contract_address, lower(raw_topics->>1) AS topic1, block_number, log_index
            FROM blockchain_event
            WHERE topic0 = '${UpgradedEvent.TOPIC0}'
              AND (contract_address IN (:addresses) OR lower(raw_topics->>1) IN (:addressTopics))
            ORDER BY contract_address, lower(raw_topics->>1), block_number DESC, log_index DESC
        """,
        nativeQuery = true,
    )
    fun findUpgradedInvolving(
        @Param("addresses") addresses: Collection<String>,
        @Param("addressTopics") addressTopics: Collection<String>,
    ): List<Array<Any?>>

    /** Discovery ciente de proxy (DEC-104), passo 2: último `Upgraded` de cada proxy em [proxyAddresses]. */
    @Query(
        value = """
            SELECT DISTINCT ON (contract_address)
                   contract_address, lower(raw_topics->>1) AS topic1, block_number, log_index
            FROM blockchain_event
            WHERE topic0 = '${UpgradedEvent.TOPIC0}'
              AND contract_address IN (:proxyAddresses)
            ORDER BY contract_address, block_number DESC, log_index DESC
        """,
        nativeQuery = true,
    )
    fun findLatestUpgradedByProxy(@Param("proxyAddresses") proxyAddresses: Collection<String>): List<Array<Any?>>

    /**
     * DEC-105: histórico de `Upgraded` emitidos por [proxy], ordem crescente. Retorna linhas
     * `[contract_address, topic1, block_number, log_index, tx_hash]`. `topic0` literal (mesma razão do
     * passo 1 do discovery: índice parcial da V6).
     */
    @Query(
        value = """
            SELECT contract_address, lower(raw_topics->>1) AS topic1, block_number, log_index, tx_hash
            FROM blockchain_event
            WHERE topic0 = '${UpgradedEvent.TOPIC0}'
              AND contract_address = :proxy
            ORDER BY block_number ASC, log_index ASC
        """,
        nativeQuery = true,
    )
    fun findUpgradeHistoryByProxy(@Param("proxy") proxy: String): List<Array<Any?>>
}
