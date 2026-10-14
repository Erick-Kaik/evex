-- DEC-110: flag `batch` na configuração de saldo (parâmetros valor/tokenId do tipo array, ex.: ERC-1155
-- TransferBatch). Default false = comportamento escalar anterior para todas as configs já gravadas.
--
-- `ADD COLUMN IF NOT EXISTS`: num banco novo o Hibernate (ddl-auto: update, DEC-006) já cria a coluna a
-- partir do mapeamento da entidade ANTES desta migration rodar (PostSchemaFlywayMigrator, DEC-041) — o
-- IF NOT EXISTS torna a migration um no-op nesse caso. Num banco preexistente com linhas, o Hibernate
-- não consegue adicionar coluna NOT NULL sem default (SchemaUpdate loga e segue) — aí é esta migration,
-- com DEFAULT false, que efetiva a coluna (mesmo padrão da V2).
ALTER TABLE balance_monitor_configs
    ADD COLUMN IF NOT EXISTS batch BOOLEAN NOT NULL DEFAULT false;
