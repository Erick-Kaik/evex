import { isAxiosError } from 'axios'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ContractsApi, DiscoveryApi } from '../../api/api'
import type {
  BlockscoutContract,
  CreateContractPayload,
  RegisterAbiPayload,
  UpdateContractPayload,
  UpdateContractStatusPayload,
} from '../../api/api'
import type { Network } from '../../api/api'
import { apiConfiguration } from '../../api/config'

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

/** Cadastra uma nova versão de ABI (`POST /v1/contracts/{address}/abi`); invalida versões + detalhe. */
export function useRegisterAbi(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: RegisterAbiPayload) =>
      (await contractsApi.registerContractAbi(address, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contract-abi-versions', address] })
      void queryClient.invalidateQueries({ queryKey: ['contract', address] })
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
