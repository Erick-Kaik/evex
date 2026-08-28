package br.com.bbchain.evex.indexer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

// @EnableScheduling adicionado na E4 — BlockchainPoller.poll() usa @Scheduled(fixedDelayString = ...).
// @EnableAsync adicionado na E5 — RedecodeRunner.run() usa @Async (primeira etapa a usar; convive com
// @EnableScheduling sem conflito).
//
// E8.1: UserDetailsServiceAutoConfiguration excluída — spring-boot-starter-security (novo, pra CORS
// via SecurityConfig) dispara essa autoconfig independentemente de já existir um SecurityFilterChain
// explícito (é uma autoconfig diferente da que decide o filtro default), gerando um usuário/senha
// aleatória no log a cada boot ("Using generated security password..."). Sem efeito funcional real
// (o SecurityFilterChain de SecurityConfig é 100% permitAll(), essa credencial nunca é exigida em
// lugar nenhum) — excluída só pra não deixar ruído de log que pareça sugerir auth ativa, quando não
// está (mesmo espírito do "ponto de atenção crítico" da E8.1: não confundir operador/dev futuro).
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@EnableScheduling
@EnableAsync
class EvexIndexerApplication

fun main(args: Array<String>) {
    runApplication<EvexIndexerApplication>(*args)
}
