import { useQuery } from '@tanstack/react-query'
import { ContractsApi } from '../api/api'
import type {
  Contract,
  ContractEvent,
  ContractEventInput,
  ContractMethod,
  ContractMethodInput,
  ContractMethodOutput,
  Network,
} from '../api/api'
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

/** Um método do catálogo com `inputs`/`outputs` já mesclados entre faixas de bloco (proxy, E19/DEC-082). */
export interface CatalogMethod {
  name: string
  inputs: ContractMethodInput[]
  outputs: ContractMethodOutput[]
}

interface ContractMethodCatalog {
  /** Resultado bruto do React Query (`isPending`/`isError`/`data`...). */
  query: ReturnType<typeof useContractMethodCatalogQuery>
  /** Nomes de método de-duplicados, na ordem em que aparecem. */
  methodNames: string[]
  /** Método pelo nome — já mesclado entre versões de ABI. */
  methodsByName: Map<string, CatalogMethod>
  /** `true` quando o contrato respondeu 200 com catálogo vazio (sem ABI cadastrada — não é erro). */
  hasNoAbi: boolean
}

function useContractMethodCatalogQuery(address: string | undefined) {
  return useQuery({
    queryKey: ['contract-method-catalog', address?.toLowerCase() ?? null],
    queryFn: async (): Promise<ContractMethod[]> =>
      (await contractsApi.listContractMethods(address as string)).data,
    enabled: Boolean(address),
  })
}

/**
 * Catálogo de métodos `view`/`pure` decodificáveis de um contrato (`GET /v1/contracts/{address}/methods`,
 * E19/DEC-082/083) — mirror exato de `useContractEventCatalog`, trocando evento por método. O mesmo
 * `name` pode vir N vezes (uma por faixa de validade de ABI, proxy upgradável): de-duplica por nome e
 * mescla `inputs`/`outputs`, prevalecendo a faixa mais recente.
 */
export function useContractMethodCatalog(address: string | undefined): ContractMethodCatalog {
  const query = useContractMethodCatalogQuery(address)
  const methods = query.data ?? []

  const methodsByName = new Map<string, CatalogMethod>()
  const recencyByMethod = new Map<string, number>()
  const methodNames: string[] = []

  for (const method of methods) {
    if (!methodsByName.has(method.name)) {
      methodNames.push(method.name)
    }
    const recency = method.validToBlock == null ? Number.MAX_SAFE_INTEGER : method.validFromBlock
    const previous = recencyByMethod.get(method.name)
    if (previous === undefined || recency >= previous) {
      recencyByMethod.set(method.name, recency)
      methodsByName.set(method.name, { name: method.name, inputs: method.inputs, outputs: method.outputs })
    }
  }

  return {
    query,
    methodNames,
    methodsByName,
    hasNoAbi: query.isSuccess && methods.length === 0,
  }
}

/** Filtro de `inputs` de evento por tipo Solidity, compartilhado entre `<EventParamSelect>` e testes. */
export type EventParamKind = 'address' | 'numeric' | 'numericScalar' | 'numericArray' | 'any'

const NUMERIC_TYPE = /^u?int(\d+)?(\[\])?$/
const NUMERIC_SCALAR_TYPE = /^u?int(\d+)?$/
/** Mesma regra do backend para `batch=true` (DEC-110): array numérico, de tamanho fixo ou dinâmico. */
const NUMERIC_ARRAY_TYPE = /^u?int\d*\[\d*\]$/

/** `true` quando o tipo Solidity é um array (termina em `]`). */
export function isArrayType(type: string): boolean {
  return type.endsWith(']')
}

/** `true` quando o tipo é um array numérico (`uint256[]`, `int8[3]`...) — o que `batch=true` exige. */
export function isNumericArrayType(type: string): boolean {
  return NUMERIC_ARRAY_TYPE.test(type)
}

export function matchesParamKind(type: string, kind: EventParamKind): boolean {
  switch (kind) {
    case 'address':
      return type === 'address'
    case 'numeric':
      return NUMERIC_TYPE.test(type)
    case 'numericScalar':
      return NUMERIC_SCALAR_TYPE.test(type)
    case 'numericArray':
      return NUMERIC_ARRAY_TYPE.test(type)
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
