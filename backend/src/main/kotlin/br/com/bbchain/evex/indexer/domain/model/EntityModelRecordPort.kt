package br.com.bbchain.evex.indexer.domain.model

import br.com.bbchain.evex.indexer.domain.common.Network
import java.util.UUID

/**
 * Porta de saída para o registro acumulado ([EntityModelRecord]) — **não** é CRUD (esse é
 * [EntityModelPort], pro agregado [EntityModel] em si).
 *
 * **Decisão de design (E19/DEC-082)**: [mergeFields] expressa merge **atômico** no nível da porta — a
 * implementação Postgres (`adapters/outbound/persistence/JpaEntityModelRecordAdapter`) faz um
 * `INSERT ... ON CONFLICT (model_id, correlation_value, network) DO UPDATE SET data = jsonb ||
 * excluded.jsonb, updated_at = ...` numa única query nativa — **não** um "ler linha existente, mesclar
 * `Map` em memória na `application`, escrever de volta" (rejeitado): 2 eventos do mesmo lote de
 * ingestão tocando a mesma entidade (ex.: `Created` e `Approved` chegando na mesma janela de blocos)
 * já são resolvidos sequencialmente dentro da mesma transação sem problema, mas um
 * ler-modifica-escreve em 2 passos abriria uma janela de corrida desnecessária entre uma transação de
 * ingestão e uma de redecode tocando a mesma entidade concorrentemente — o upsert atômico do Postgres
 * elimina essa classe de problema de graça, sem precisar de lock explícito.
 */
interface EntityModelRecordPort {
    /**
     * Funde [fields] no registro `(modelId, correlationValue, network)` — cria se não existir, faz
     * merge raso (`jsonb ||`, chave por chave — um `ModelFieldMapping.modelField` repetido num
     * contribuinte posterior sobrescreve o valor anterior daquela chave, nunca o registro inteiro) se
     * já existir.
     */
    fun mergeFields(modelId: UUID, correlationValue: String, network: Network, fields: Map<String, Any?>)

    /** O registro acumulado até agora, se já houver ao menos 1 contribuinte processado. */
    fun findRecord(modelId: UUID, correlationValue: String, network: Network): EntityModelRecord?
}
