-- E33/DEC-113 a DEC-117: reenvio manual de webhooks + resultado de enriquecimento por tentativa.
--
-- Mesmo racional da V2/V4/V7 (DEC-041/PostSchemaFlywayMigrator): num banco novo o Hibernate
-- (ddl-auto: update, DEC-006) já cria as colunas/índices a partir do mapeamento das entidades ANTES desta
-- migration rodar (IF NOT EXISTS vira no-op). Num banco preexistente com linhas, o Hibernate não consegue
-- adicionar coluna NOT NULL sem default — aí é esta migration, com DEFAULT 'MATCH', que efetiva a coluna
-- (as entregas já gravadas continuam sendo "disparo normal").

-- webhook_deliveries: origem (código extensível, não enum) + parâmetros do reenvio.
ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS origin VARCHAR(32) NOT NULL DEFAULT 'MATCH';
ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS enrichment_block_mode VARCHAR(32);
ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS enrichment_block_number BIGINT;
ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS target_url_override TEXT;
ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS model_state_at TIMESTAMPTZ;

-- webhook_delivery_attempts: a Fase 2 (enriquecimento) roda a CADA tentativa, então o resultado por
-- chamada, o bloco usado e o payload efetivamente enviado são por tentativa.
ALTER TABLE webhook_delivery_attempts
    ADD COLUMN IF NOT EXISTS enrichment_results JSONB;
ALTER TABLE webhook_delivery_attempts
    ADD COLUMN IF NOT EXISTS enrichment_block_used VARCHAR(32);
ALTER TABLE webhook_delivery_attempts
    ADD COLUMN IF NOT EXISTS sent_payload TEXT;

-- Guarda contra duplo clique do reenvio (existe entrega pendente pra (webhook, evento)?).
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_config_event
    ON webhook_deliveries (webhook_config_id, blockchain_event_id);

-- Conversão data -> bloco e filtro de data de eventos (block_timestamp não tinha índice).
CREATE INDEX IF NOT EXISTS idx_blockchain_event_block_timestamp
    ON blockchain_event (block_timestamp, block_number);

-- Garantia no BANCO contra reenvios concorrentes do mesmo (webhook, evento): no máximo UMA entrega de reenvio
-- PENDING por par. Não restringe disparos normais (origin = 'MATCH') nem reenvios já concluídos (DELIVERED/FAILED).
-- Índice parcial não é expressável em @Index (mesmo caso da V6/DEC-104): só esta migration o cria. `origin` é
-- NOT NULL e o legado é 'MATCH', então nenhuma linha existente viola o índice. O predicado deve ser IDÊNTICO ao do
-- `ON CONFLICT` de `WebhookDeliveryJpaRepository.insertResendIfAbsent`.
CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_deliveries_pending_resend
    ON webhook_deliveries (webhook_config_id, blockchain_event_id)
    WHERE status = 'PENDING' AND origin <> 'MATCH';
