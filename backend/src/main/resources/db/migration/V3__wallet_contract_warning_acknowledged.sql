-- Complemento à DEC-080 (E22): `looks_like_contract_acknowledged` em `monitored_wallets` — sem isso o
-- aviso "este endereço parece um contrato" reaparecia toda vez que a tela de detalhe da carteira era
-- aberta, mesmo já tendo sido visto (feedback do usuário em teste manual).
--
-- `ADD COLUMN IF NOT EXISTS ... DEFAULT false`: mesmo raciocínio da V2 — num banco novo o Hibernate
-- (ddl-auto: update, DEC-006) já cria a coluna a partir do mapeamento da entidade ANTES desta migration
-- rodar (PostSchemaFlywayMigrator, DEC-041), então o IF NOT EXISTS torna isto um no-op nesse caso. Num
-- banco com `monitored_wallets` já populada (linhas cadastradas antes desta etapa), o Hibernate não
-- consegue adicionar uma coluna NOT NULL sem default e falha silenciosamente — aí é esta migration,
-- com DEFAULT false, que efetiva a coluna sem quebrar as linhas existentes (nenhuma carteira antiga
-- tinha o aviso confirmado, `false` é o valor correto pra elas).
ALTER TABLE monitored_wallets
    ADD COLUMN IF NOT EXISTS looks_like_contract_acknowledged BOOLEAN NOT NULL DEFAULT false;
