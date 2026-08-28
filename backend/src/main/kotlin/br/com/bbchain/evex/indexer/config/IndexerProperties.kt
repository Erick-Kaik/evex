package br.com.bbchain.evex.indexer.config

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.network` (`application.yml`) — tag de rede aplicada a **todo** [br.com.bbchain.evex.indexer
 * .domain.event.BlockchainEvent] ingerido por esta instância. Decisão tomada com o usuário no
 * planejamento da E4: não deriva de
 * [br.com.bbchain.evex.indexer.domain.contract.MonitoredContract] porque a ingestão é da rede inteira
 * (DEC-001, sem filtro de endereço) — a maioria dos logs é de endereços não cadastrados. Consistente
 * com "instância por rede" (`CLAUDE.md` raiz: 1 instância do Evex por cliente/ambiente, segregação por
 * `network`).
 */
@Component
@ConfigurationProperties(prefix = "indexer")
class IndexerProperties {
    var network: Network = Network.TESTNET

    /**
     * `indexer.warn-log-dedupe-interval` — intervalo mínimo entre 2 logs `WARN` consecutivos pra uma
     * mesma `BalanceMonitorConfig`/`WebhookConfig` mal configurada
     * ([br.com.bbchain.evex.indexer.application.common.WarnLogDeduper], E11 — resíduo de DEC-035).
     * Default de 5 minutos: baixo o bastante pra não esconder o problema por muito tempo, alto o
     * bastante pra não virar 1 linha de log por evento numa rede com tráfego real.
     */
    var warnLogDedupeInterval: Duration = Duration.ofMinutes(5)
}
