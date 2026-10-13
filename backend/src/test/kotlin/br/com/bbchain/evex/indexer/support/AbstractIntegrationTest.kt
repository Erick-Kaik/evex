package br.com.bbchain.evex.indexer.support

import br.com.bbchain.evex.indexer.config.FlywayMigrationOrderingConfig
import br.com.bbchain.evex.indexer.config.PostSchemaFlywayMigrator
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base for integration tests that need a real Postgres — DEC-009 (JUnit5 + MockK + Testcontainers,
 * not H2). The container is a **singleton** started once (via the eager `.also { it.start() }` in
 * the companion object) and shared across every test class that extends this one, for the whole JVM
 * run of the test suite — never stopped explicitly, Testcontainers' Ryuk reaper cleans it up when the
 * JVM exits. Avoids paying container startup cost per test class.
 *
 * `@Import(FlywayMigrationOrderingConfig::class, PostSchemaFlywayMigrator::class)` (achado desta
 * sessão, complemento à DEC-041): `@DataJpaTest` faz slice do contexto e exclui `@Configuration`/
 * `@Component` "normais" (`TypeExcludeFilter`, mesmo mecanismo de `@WebMvcTest`/`@JsonTest`) — sem
 * este `@Import` explícito, a reordenação Flyway-depois-do-Hibernate de `PostSchemaFlywayMigrator`
 * nunca entra no contexto de um `@DataJpaTest`, e o `FlywayMigrationInitializer` padrão do Spring Boot
 * volta a rodar **antes** do `ddl-auto: update` criar o schema. Contra um container Postgres
 * realmente vazio (1ª classe de teste a tocar o banco na execução da suíte), isso derruba a
 * `V1__contract_abis_unique_open_version.sql` com "relation contract_abis does not exist" — reproduzido
 * de forma **intermitente** nesta sessão (a ordem de descoberta de classes de teste do JUnit5/Gradle
 * não é garantida entre execuções; quando por acaso um `@SpringBootTest` de contexto completo — que
 * inclui `PostSchemaFlywayMigrator` naturalmente via component scan — roda antes de qualquer
 * `@DataJpaTest` no mesmo container compartilhado, o schema já existe e o `@DataJpaTest` nem percebe o
 * problema; quando não, quebra). `@Import` explícito na base comum resolve de vez, independente de
 * ordem — idempotente também para os testes que já herdam contexto completo (`@SpringBootTest`), o
 * Spring apenas ignora o `@Import` duplicado.
 */
@Import(FlywayMigrationOrderingConfig::class, PostSchemaFlywayMigrator::class)
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
            // Cada contexto Spring em cache segura um pool Hikari (padrão 10 conexões ociosas); com ~10 contextos
            // distintos na suíte o Postgres do Testcontainers (max_connections=100) estourava ("too many clients").
            // Pool pequeno por contexto mantém o total folgado mesmo com mais contextos (E33 adicionou um).
            registry.add("spring.datasource.hikari.maximum-pool-size") { "5" }
            // Flyway aponta pro mesmo datasource; sem migrations ainda (ver application.yml) — só o
            // schema history table é criado, o resto do schema vem de ddl-auto: update (DEC-006).

            // E4: desliga todo gatilho de ingestão (ScheduledPollTrigger/WebSocketPollTrigger,
            // @ConditionalOnProperty) em todo teste que estenda esta classe — @SpringBootTest não deve
            // tentar falar com um RPC de verdade (E12/DEC-010 ainda bloqueado). Registrado aqui (não em
            // src/test/resources/application.yml) de propósito: um `application.yml` em
            // src/test/resources **substitui** (não mescla com) o de src/main/resources no classpath de
            // teste — `build/resources/test` vem antes de `build/resources/main` na ordem de resolução
            // do Gradle, então `ddl-auto: update` e todo o resto de `src/main/resources/application.yml`
            // some silenciosamente pros testes. Só percebido porque `JpaIngestionCursorAdapterTest`/etc.
            // começaram a falhar com "relation does not exist" sem nenhuma DDL sendo executada — ver
            // docs/decisions.md.
            //
            // E26/DEC-091: `indexer.blockchain.transport=NONE` substitui a antiga
            // `indexer.blockchain.polling-enabled=false` — `NONE` desliga os 2 gatilhos possíveis de
            // uma vez (nenhum dos dois `@ConditionalOnProperty` bate `POLLING`/`WEBSOCKET`), sem precisar
            // de 2 properties diferentes pra desligar 2 mecanismos diferentes.
            registry.add("indexer.blockchain.transport") { "NONE" }
        }
    }
}
