package br.com.bbchain.evex.indexer.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base for integration tests that need a real Postgres — DEC-009 (JUnit5 + MockK + Testcontainers,
 * not H2). The container is a **singleton** started once (via the eager `.also { it.start() }` in
 * the companion object) and shared across every test class that extends this one, for the whole JVM
 * run of the test suite — never stopped explicitly, Testcontainers' Ryuk reaper cleans it up when the
 * JVM exits. Avoids paying container startup cost per test class.
 */
abstract class AbstractIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("evex_test")
                .withUsername("evex_test")
                .withPassword("evex_test")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerDatasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // Flyway aponta pro mesmo datasource; sem migrations ainda (ver application.yml) — só o
            // schema history table é criado, o resto do schema vem de ddl-auto: update (DEC-006).

            // E4: desliga o BlockchainPoller (@ConditionalOnProperty) em todo teste que estenda esta
            // classe — @SpringBootTest não deve tentar falar com um RPC de verdade (E12/DEC-010 ainda
            // bloqueado). Registrado aqui (não em src/test/resources/application.yml) de propósito: um
            // `application.yml` em src/test/resources **substitui** (não mescla com) o de
            // src/main/resources no classpath de teste — `build/resources/test` vem antes de
            // `build/resources/main` na ordem de resolução do Gradle, então `ddl-auto: update` e todo
            // o resto de `src/main/resources/application.yml` some silenciosamente pros testes. Só
            // percebido porque `JpaIngestionCursorAdapterTest`/etc. começaram a falhar com "relation
            // does not exist" sem nenhuma DDL sendo executada — ver docs/decisions.md.
            registry.add("indexer.blockchain.polling-enabled") { "false" }
        }
    }
}
