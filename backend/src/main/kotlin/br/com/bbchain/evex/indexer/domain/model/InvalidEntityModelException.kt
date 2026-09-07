package br.com.bbchain.evex.indexer.domain.model

/**
 * Cadastro/edição de [EntityModel] inválido — "falhar cedo" (mesmo princípio do resto da API):
 * `ModelContributor.(contractAddress, eventName)` que não existe no catálogo de eventos do contrato
 * ([br.com.bbchain.evex.indexer.application.abi.ContractEventCatalogService], reaproveitado), ou
 * `correlationParam`/`ModelFieldMapping.decodedParam` que não é um parâmetro real daquele evento.
 */
class InvalidEntityModelException(message: String) : RuntimeException(message)
