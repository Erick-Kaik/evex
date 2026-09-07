-- E19/DEC-082/DEC-083: enrichmentProfileIds/entityModelIds em WebhookConfig — JSONB NOT NULL DEFAULT
-- '[]'. Mesmo racional exato da V2 (DEC-041/PostSchemaFlywayMigrator): num banco novo o Hibernate
-- (ddl-auto: update, DEC-006) já cria as colunas certas a partir do mapeamento da entidade ANTES desta
-- migration rodar (IF NOT EXISTS vira no-op nesse caso). Num banco preexistente com linhas em
-- webhook_configs (qualquer instância que já usava webhooks antes da E19), o Hibernate não consegue
-- adicionar coluna NOT NULL sem default e falha (SchemaUpdate loga e segue, sem derrubar o boot) — aí
-- é esta migration, com DEFAULT '[]'::jsonb, que efetiva as colunas pras linhas já existentes.
--
-- Achado real (2026-09-06, sessão principal, rodando o cenário de validação da E19 contra um banco
-- local com webhooks pré-existentes de etapas anteriores): sem esta migration, todo POST/PUT
-- /v1/webhooks passava a devolver 500 (`column "enrichment_profile_ids" of relation "webhook_configs"
-- does not exist`) em qualquer banco que já tivesse `webhook_configs` populada antes da E19 — o
-- `./gradlew clean build` da E19 não pegou isso porque Testcontainers sobe um banco vazio a cada
-- rodada (ddl-auto: update sempre tem sucesso contra tabela sem linha nenhuma).
ALTER TABLE webhook_configs
    ADD COLUMN IF NOT EXISTS enrichment_profile_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE webhook_configs
    ADD COLUMN IF NOT EXISTS entity_model_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
