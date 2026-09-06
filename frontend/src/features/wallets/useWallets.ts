import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { WalletsApi } from '../../api/api'
import type {
  CreateWalletPayload,
  Network,
  UpdateWalletPayload,
  UpdateWalletStatusPayload,
} from '../../api/api'
import { apiConfiguration } from '../../api/config'

const walletsApi = new WalletsApi(apiConfiguration)

/** Lista carteiras cadastradas, com filtro opcional de rede e paginação (`GET /v1/wallets`). */
export function useWallets(network: Network | undefined, page: number, size: number) {
  return useQuery({
    queryKey: ['wallets', network, page, size],
    queryFn: async () => (await walletsApi.listWallets(network, page, size)).data,
  })
}

/** Consulta uma carteira cadastrada pelo endereço (`GET /v1/wallets/{address}`). */
export function useWallet(address: string | undefined) {
  return useQuery({
    queryKey: ['wallet', address],
    queryFn: async () => (await walletsApi.getWallet(address as string)).data,
    enabled: Boolean(address),
  })
}

/** Cadastra uma nova carteira (`POST /v1/wallets`); invalida a listagem/catálogo. */
export function useCreateWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: CreateWalletPayload) => (await walletsApi.createWallet(payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['wallets'] })
    },
  })
}

/** Atualiza label/descrição de uma carteira cadastrada (`PUT /v1/wallets/{address}`). */
export function useUpdateWallet(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateWalletPayload) =>
      (await walletsApi.updateWallet(address, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['wallet', address] })
      void queryClient.invalidateQueries({ queryKey: ['wallets'] })
    },
  })
}

/** Ativa/pausa/arquiva uma carteira cadastrada (`PATCH /v1/wallets/{address}/status`). */
export function useUpdateWalletStatus(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateWalletStatusPayload) =>
      (await walletsApi.updateWalletStatus(address, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['wallet', address] })
      void queryClient.invalidateQueries({ queryKey: ['wallets'] })
    },
  })
}

/**
 * Confirma ciência do aviso `looksLikeContract` (`PATCH /v1/wallets/{address}/contract-warning`) —
 * some da tela de detalhe depois disso (feedback do usuário: reaparecer toda vez que abre o detalhe
 * era irritante pro caso legítimo de carteira multisig cadastrada de propósito).
 */
export function useAcknowledgeContractWarning(address: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => (await walletsApi.acknowledgeWalletContractWarning(address)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['wallet', address] })
      void queryClient.invalidateQueries({ queryKey: ['wallets'] })
    },
  })
}

/**
 * Descoberta automática de carteiras (`GET /v1/wallets/suggestions`, E22) — endereços já observados
 * em `asset_balances` que ainda não estão cadastrados. Lista sem paginação (mesma lógica de
 * `contract-abi-list.yaml`).
 */
export function useWalletSuggestions(network: Network | undefined) {
  return useQuery({
    queryKey: ['wallet-suggestions', network ?? null],
    queryFn: async () => (await walletsApi.suggestWallets(network)).data,
  })
}
