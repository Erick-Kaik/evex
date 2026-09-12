-- E29/DEC-096/DEC-097: campo aditivo `lineageId` em `MonitoredContract` (redeploy != upgrade) +
-- `createdAt`, necessário para ordenar a linhagem inteira (`findByLineageIdOrderByCreatedAt`).
--
-- `lineage_id` é NULLABLE, sem valor default nenhum: contratos já cadastrados antes desta etapa ficam
-- com `lineage_id = NULL` de propósito (sem backfill retroativo — não há como inferir a linhagem
-- histórica deles). Num banco novo o Hibernate (ddl-auto: update, DEC-006) já cria a coluna a partir do
-- mapeamento da entidade antes desta migration rodar (PostSchemaFlywayMigrator, DEC-041) — `IF NOT
-- EXISTS` torna a migration um no-op nesse caso.
--
-- `created_at` é NOT NULL — mesmo gotcha já documentado na DEC-041/DEC-083 (Postgres recusa `ALTER
-- TABLE ... ADD COLUMN ... NOT NULL` sem default contra uma tabela com linhas). Diferente daquelas
-- migrations (DEFAULT era um valor de "sentinela" com significado de domínio, ex. `-1`/`'[]'::jsonb`),
-- aqui não existe um valor "sem significado" possível para uma data de criação — `DEFAULT now()`
-- carimba as linhas pré-existentes com o instante em que esta migration rodou (aproximação aceitável:
-- essas linhas já eram "contrato sem lineage" mesmo, `created_at` delas não é usado por nenhuma regra
-- de negócio, só serve para ordenar a linhagem de contratos cadastrados dali em diante).
ALTER TABLE monitored_contracts
    ADD COLUMN IF NOT EXISTS lineage_id UUID NULL;
ALTER TABLE monitored_contracts
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT now();
