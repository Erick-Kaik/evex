import { useQuery } from '@tanstack/react-query'
import { ContractsApi } from '../api/api'
import type { Contract, ContractEvent, ContractEventInput, Network } from '../api/api'
import { apiConfiguration } from '../api/config'

const contractsApi = new ContractsApi(apiConfiguration)

/**
 * Tamanho de página usado pelo `<ContractAutocomplete>` para carregar a lista de contratos
 * monitorados de uma vez só. A PoC não pagina o autocomplete (DEC-072, complemento da passada A do
 * frontend): uma instância do Evex é por rede/cliente e a lista de contratos cadastrados é curta.
 * O valor é o **teto do parâmetro `size`** de `GET /v1/contracts` (`maximum: 200` na spec — pedir
 * mais devolve 400 `VALIDATION_ERROR`). Se algum dia estourar 200, o backend já pagina
 * (`GET /v1/contracts?page&size`) e o componente passa a um modelo com busca server-side.
 */
export const CONTRACT_CATALOG_PAGE_SIZE = 200

/** Lista (não paginada, ver `CONTRACT_CATALOG_PAGE_SIZE`) de contratos monitorados para os selects/autocomplete. */
export function useMonitoredContractsList(network?: Network) {
  return useQuery({
    queryKey: ['contracts', 'catalog', network ?? null],
    queryFn: async (): Promise<Contract[]> =>
      (await contractsApi.listMonitoredContracts(network, 0, CONTRACT_CATALOG_PAGE_SIZE)).data.content,
  })
}

/** Um evento do catálogo com os `inputs` já mesclados entre faixas de bloco (proxy, DEC-076). */
export interface CatalogEvent {
  name: string
  inputs: ContractEventInput[]
}

interface ContractEventCatalog {
  /** Resultado bruto do React Query (`isPending`/`isError`/`data`...). */
  query: ReturnType<typeof useContractEventCatalogQuery>
  /** Nomes de evento de-duplicados, na ordem em que aparecem. */
  eventNames: string[]
  /** `inputs` do evento pelo nome — já mesclados entre versões de ABI. */
  inputsByEvent: Map<string, ContractEventInput[]>
  /** `true` quando o contrato respondeu 200 com catálogo vazio (sem ABI cadastrada — não é erro). */
  hasNoAbi: boolean
}

function useContractEventCatalogQuery(address: string | undefined) {
  return useQuery({
    queryKey: ['contract-event-catalog', address?.toLowerCase() ?? null],
    queryFn: async (): Promise<ContractEvent[]> =>
      (await contractsApi.listContractEvents(address as string)).data,
    enabled: Boolean(address),
  })
}

/**
 * Catálogo de eventos decodificáveis de um contrato (`GET /v1/contracts/{address}/events`, DEC-073).
 * O mesmo `name` pode vir N vezes (uma por faixa de validade de ABI, proxy upgradável — DEC-076):
 * de-duplica por nome e mescla os `inputs`. Na prática os `inputs` são idênticos entre faixas; se
 * divergirem, prevalece a faixa mais recente (a de `validToBlock` nulo, senão a de maior
 * `validFromBlock`).
 */
export function useContractEventCatalog(address: string | undefined): ContractEventCatalog {
  const query = useContractEventCatalogQuery(address)
  const events = query.data ?? []

  const inputsByEvent = new Map<string, ContractEventInput[]>()
  const recencyByEvent = new Map<string, number>()
  const eventNames: string[] = []

  for (const event of events) {
    if (!inputsByEvent.has(event.name)) {
      eventNames.push(event.name)
    }
    // Faixa aberta (`validToBlock` nulo) é sempre a mais recente; senão usa `validFromBlock`.
    const recency = event.validToBlock == null ? Number.MAX_SAFE_INTEGER : event.validFromBlock
    const previous = recencyByEvent.get(event.name)
    if (previous === undefined || recency >= previous) {
      recencyByEvent.set(event.name, recency)
      inputsByEvent.set(event.name, event.inputs)
    }
  }

  return {
    query,
    eventNames,
    inputsByEvent,
    hasNoAbi: query.isSuccess && events.length === 0,
  }
}

/** Filtro de `inputs` de evento por tipo Solidity, compartilhado entre `<EventParamSelect>` e testes. */
export type EventParamKind = 'address' | 'numeric' | 'any'

const NUMERIC_TYPE = /^u?int(\d+)?(\[\])?$/

export function matchesParamKind(type: string, kind: EventParamKind): boolean {
  switch (kind) {
    case 'address':
      return type === 'address'
    case 'numeric':
      return NUMERIC_TYPE.test(type)
    case 'any':
      return true
  }
}

export function filterInputsByKind(
  inputs: ContractEventInput[],
  kind: EventParamKind,
): ContractEventInput[] {
  return inputs.filter((input) => matchesParamKind(input.type, kind))
}
