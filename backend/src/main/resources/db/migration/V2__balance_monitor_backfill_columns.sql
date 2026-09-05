-- E14/DEC-066: backfill de saldo sob demanda. 3 colunas novas em `balance_monitor_configs`:
--  - backfill_watermark_block      — bloco do cursor de ingestão no instante do register() da config
--                                    (imutável); o backfill só reprojeta eventos com
--                                    block_number <= este valor, o resto é da ingestão ao vivo.
--  - backfill_cursor_block         — progresso resumível do backfill (bloco do último evento
--                                    reprojetado), avançado na mesma transação dos deltas do lote.
--  - backfill_cursor_log_index     — idem, log_index, pra desempatar dentro do mesmo bloco.
-- -1 = nada reprojetado ainda / cursor de ingestão ainda em -1 na criação.
--
-- `ADD COLUMN IF NOT EXISTS`: num banco novo o Hibernate (ddl-auto: update, DEC-006) já cria as
-- colunas a partir do mapeamento da entidade ANTES desta migration rodar (PostSchemaFlywayMigrator,
-- DEC-041) — o IF NOT EXISTS torna a migration um no-op nesse caso. Num banco preexistente com
-- linhas, o Hibernate não consegue adicionar coluna NOT NULL sem default e falha silenciosamente
-- (SchemaUpdate loga e segue) — aí é esta migration, com DEFAULT -1, que efetiva as colunas.
ALTER TABLE balance_monitor_configs
    ADD COLUMN IF NOT EXISTS backfill_watermark_block BIGINT NOT NULL DEFAULT -1;
ALTER TABLE balance_monitor_configs
    ADD COLUMN IF NOT EXISTS backfill_cursor_block BIGINT NOT NULL DEFAULT -1;
ALTER TABLE balance_monitor_configs
    ADD COLUMN IF NOT EXISTS backfill_cursor_log_index BIGINT NOT NULL DEFAULT -1;
