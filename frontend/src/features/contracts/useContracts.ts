import { isAxiosError } from 'axios'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ContractsApi, DiscoveryApi } from '../../api/api'
import type {
  AbiHistoryImportPayload,
  AbiHistorySource,
  BlockscoutContract,
  CreateContractPayload,
  RegisterAbiPayload,
  UpdateContractPayload,
  UpdateContractStatusPayload,
} from '../../api/api'
import type { Network } from '../../api/api'
import { apiConfiguration } from '../../api/config'
import { redecodeStatusKey } from './useRedecodeStatus'

const contractsApi = new ContractsApi(apiConfiguration)
const discoveryApi = new DiscoveryApi(apiConfiguration)

/** Lista contratos monitorados, com filtro opcional de rede e paginação (`GET /v1/contracts`). */
export function useContracts(network: Network | undefined, page: number, size: number) {
  return useQuery({
    queryKey: ['contracts', network, page, size],
    queryFn: async () => (await contractsApi.listMonitoredContracts(network, page, size)).data,
  })
}

/** Consulta um contrato monitorado pelo endereço (`GET /v1/contracts/{address}`). */
export function useContract(address: string | undefined) {
  return useQuery({
    queryKey: ['contract', address],
    queryFn: async () => (await contractsApi.getMonitoredContract(address as string)).data,
    enabled: Boolean(address),
  })
}

/** Cadastra um novo contrato monitorado (`POST /v1/contracts`); invalida a listagem. */
export function useCreateContract() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: CreateContractPayload) =>
      (await contractsApi.createMonitoredContract(payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contracts'] })
    },
  })
}

/** Atualiza nome/descrição/versão de um contrato monitorado (`PUT /v1/contracts/{address}`). */
export function useUpdateContract(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateContractPayload) =>
      (await contractsApi.updateMonitoredContract(address, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contract', address] })
      void queryClient.invalidateQueries({ queryKey: ['contracts'] })
    },
  })
}

/** Ativa/pausa/arquiva um contrato monitorado (`PATCH /v1/contracts/{address}/status`). */
export function useUpdateContractStatus(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateContractStatusPayload) =>
      (await contractsApi.updateContractStatus(address, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contract', address] })
      void queryClient.invalidateQueries({ queryKey: ['contracts'] })
    },
  })
}

/** Lista as versões de ABI cadastradas para um contrato (`GET /v1/contracts/{address}/abi`). */
export function useContractAbiVersions(address: string | undefined) {
  return useQuery({
    queryKey: ['contract-abi-versions', address],
    queryFn: async () => (await contractsApi.listContractAbiVersions(address as string)).data,
    enabled: Boolean(address),
  })
}

/**
 * Cadastra uma nova versão de ABI (`POST /v1/contracts/{address}/abi`); invalida versões + detalhe.
 *
 * Também invalida `redecodeStatusKey`: o backend pode disparar um redecode do histórico já indexado
 * desse contrato como efeito colateral deste cadastro (`AbiRegistrationService.register()`) — se
 * `useRedecodeStatus` estiver em polling degradado e ocioso (`stopPollingWhenIdle`, achado de
 * 2026-09-07), essa invalidação acorda o polling na hora em vez de esperar o componente remontar.
 */
export function useRegisterAbi(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: RegisterAbiPayload) =>
      (await contractsApi.registerContractAbi(address, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contract-abi-versions', address] })
      void queryClient.invalidateQueries({ queryKey: ['contract', address] })
      void queryClient.invalidateQueries({ queryKey: redecodeStatusKey })
    },
  })
}

/**
 * Catálogo completo do ABI de um contrato — funções de qualquer `stateMutability`, eventos, erros
 * customizados e constructor/fallback/receive (`GET /v1/contracts/{address}/abi/entries`, E24).
 * Diferente de `useContractAbiVersions` (metadados da versão), aqui cada item já vem "achatado" com
 * a faixa de blocos da versão que o declara — o mesmo item pode repetir entre versões distintas
 * (proxy upgradável, DEC-007); não é de-duplicado aqui, a tela decide como apresentar.
 */
export function useContractAbiEntries(address: string | undefined) {
  return useQuery({
    queryKey: ['contract-abi-entries', address],
    queryFn: async () => (await contractsApi.listContractAbiEntries(address as string)).data,
    enabled: Boolean(address),
  })
}

/**
 * Linhagem de "contratos redeployados" (E29/DEC-096/DEC-097) que este contrato pertence, inclusive
 * ele mesmo (`GET /v1/contracts/{address}/lineage`) — lista simples, sem paginação, ordenada por
 * data de cadastro. Um contrato sem `lineageId`/nunca envolvido num redeploy devolve uma lista de
 * 1 item (ele mesmo) — a tela decide não mostrar nada nesse caso (`ContractLineageSection`).
 */
export function useContractLineage(address: string | undefined) {
  return useQuery({
    queryKey: ['contract-lineage', address],
    queryFn: async () => (await contractsApi.listContractLineage(address as string)).data,
    enabled: Boolean(address),
  })
}

/**
 * Detecção de proxy upgradável UUPS/ERC-1822 (E28/DEC-099) a partir do ABI vigente do contrato
 * (`GET /v1/contracts/{address}/upgradeability`) — só leitura, o Evex não executa upgrade nenhum.
 * `availableUpgradeMethods` pode vir vazio mesmo com `isUpgradeable = true` (ABI só com
 * `proxiableUUID()`, sem os métodos de upgrade em si) — a tela decide como exibir.
 */
export function useContractUpgradeability(address: string | undefined) {
  return useQuery({
    queryKey: ['contract-upgradeability', address],
    queryFn: async () => (await contractsApi.getContractUpgradeability(address as string)).data,
    enabled: Boolean(address),
  })
}

/** Prefixo da chave do plano de importação de histórico de ABI (DEC-105) — invalida todas as fontes. */
export const abiHistoryPlanKey = (address: string) => ['contract-abi-history-plan', address] as const

/**
 * Dry-run da importação do histórico de versões de ABI de um proxy
 * (`GET /v1/contracts/{address}/abi/history-plan`, DEC-105) — não grava nada. `enabled` deixa o
 * chamador só consultar quando o diálogo está aberto (a consulta pode ir ao Blockscout, é cara).
 * `replaceBlockscoutLegacy` entra na chave: o plano com a flag é outro plano (fingerprint próprio).
 */
export function useAbiHistoryPlan(
  address: string,
  historySource: AbiHistorySource,
  replaceBlockscoutLegacy: boolean,
  enabled: boolean,
) {
  return useQuery({
    queryKey: [...abiHistoryPlanKey(address), historySource, replaceBlockscoutLegacy],
    queryFn: async () =>
      (await contractsApi.getContractAbiHistoryPlan(address, historySource, replaceBlockscoutLegacy)).data,
    enabled,
    retry: false,
  })
}

/**
 * Aplica o plano (`POST /v1/contracts/{address}/abi/history-import`, DEC-105). O backend dispara UM
 * redecode ao final, então além de ABI/versões/eventos invalida `redecodeStatusKey` (acorda o polling
 * degradado, como `useRegisterAbi`) e o plano (o que foi aplicado vira `SKIP_ALREADY_IMPORTED`).
 */
export function useImportAbiHistory(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: AbiHistoryImportPayload) =>
      (await contractsApi.importContractAbiHistory(address, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contract-abi-versions', address] })
      void queryClient.invalidateQueries({ queryKey: ['contract-abi-entries', address] })
      void queryClient.invalidateQueries({ queryKey: ['contract-upgradeability', address] })
      void queryClient.invalidateQueries({ queryKey: ['contract', address] })
      void queryClient.invalidateQueries({ queryKey: ['events'] })
      void queryClient.invalidateQueries({ queryKey: redecodeStatusKey })
      void queryClient.invalidateQueries({ queryKey: abiHistoryPlanKey(address) })
    },
  })
}

/**
 * Sugere interfaces padrão (ERC20/ERC721/ERC1155) pra um contrato — sob demanda (botão), não
 * automático no carregamento da tela (`GET /v1/contracts/{address}/abi/suggestions`, via `mutate()`
 * sem parâmetros).
 */
export function useInterfaceSuggestions(address: string) {
  return useMutation({
    mutationFn: async () => (await contractsApi.suggestInterface(address)).data,
  })
}

interface VerifiedContractsResult {
  contracts: BlockscoutContract[]
  /** `true` quando o Blockscout não está configurado/disponível (503) — não é uma lista vazia real. */
  unavailable: boolean
}

/**
 * Lista contratos verificados no Blockscout configurado, pra atalho de importação no cadastro de
 * contrato. Sem instância Blockscout real disponível na PoC (E6/DEC-010), trata 503 como estado
 * vazio + aviso, não como erro fatal da tela.
 */
export function useVerifiedContracts() {
  return useQuery({
    queryKey: ['blockscout-verified-contracts'],
    queryFn: async (): Promise<VerifiedContractsResult> => {
      try {
        const { data } = await discoveryApi.listVerifiedContracts()
        return { contracts: data, unavailable: false }
      } catch (error) {
        if (isAxiosError(error) && error.response?.status === 503) {
          return { contracts: [], unavailable: true }
        }
        throw error
      }
    },
    retry: false,
  })
}
