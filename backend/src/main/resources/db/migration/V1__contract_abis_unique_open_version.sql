-- DEC-022: garante em banco o invariante "no máximo 1 versão aberta (valid_to_block IS NULL) por
-- contract_address" que AbiRegistrationService.register()/ContractAbiPort.findCurrentOpenVersion já
-- assumiam sem nenhuma constraint por trás. Índice único PARCIAL (só considera linhas com
-- valid_to_block IS NULL) — não pode ser um unique index simples em contract_address, porque um
-- endereço legitimamente acumula várias versões FECHADAS ao longo do tempo (DEC-007, proxy
-- upgradável); só a versão aberta precisa ser única.
--
-- 1ª migration Flyway real do projeto (db/migration estava vazio até agora, ver
-- backend/docs/performance-and-ops-notes.md) — spring.flyway.baseline-on-migrate/baseline-version
-- (application.yml) garantem que esta migration roda de fato mesmo com o schema já existente via
-- ddl-auto: update (DEC-006).
CREATE UNIQUE INDEX contract_abis_unique_open_version
    ON contract_abis (contract_address)
    WHERE valid_to_block IS NULL;
