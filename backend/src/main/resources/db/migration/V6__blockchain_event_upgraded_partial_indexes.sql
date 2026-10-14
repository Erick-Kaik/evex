-- DEC-104: índices parciais para o discovery ciente de proxy (consulta de `Upgraded(address)` já
-- ingerido). `ddl-auto: update` (DEC-006) não cria índice parcial/de expressão a partir de `@Index`,
-- então este é o único caminho; `PostSchemaFlywayMigrator` (DEC-041) garante que `blockchain_event`
-- já existe quando a migration roda. `Upgraded` é raro: os índices ficam minúsculos e não oneram a
-- ingestão dos demais logs.
--
-- O predicado `topic0 = '<literal>'` deve ser IDÊNTICO ao literal da query nativa
-- (`UpgradedEvent.TOPIC0`, conferido contra o EventEncoder do Web3j em `UpgradedEventTest`), e a
-- expressão `lower(raw_topics->>1)` idêntica à da `@Query` de `BlockchainEventJpaRepository`.
CREATE INDEX IF NOT EXISTS idx_blockchain_event_upgraded_emitter
    ON blockchain_event (contract_address, block_number DESC, log_index DESC)
    WHERE topic0 = '0xbc7cd75a20ee27fd9adebab32041f755214dbc6bffa90cc0225b39da2e5c2d3b';

CREATE INDEX IF NOT EXISTS idx_blockchain_event_upgraded_implementation
    ON blockchain_event ((lower(raw_topics->>1)), block_number DESC, log_index DESC)
    WHERE topic0 = '0xbc7cd75a20ee27fd9adebab32041f755214dbc6bffa90cc0225b39da2e5c2d3b';
