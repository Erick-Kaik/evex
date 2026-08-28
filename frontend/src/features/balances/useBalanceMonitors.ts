import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BalanceMonitorsApi } from '../../api/api'
import type {
  CreateBalanceMonitorPayload,
  UpdateBalanceMonitorPayload,
  UpdateBalanceMonitorStatusPayload,
} from '../../api/api'
import { apiConfiguration } from '../../api/config'

const balanceMonitorsApi = new BalanceMonitorsApi(apiConfiguration)

/** Lista configurações de monitoramento de saldo (`GET /v1/balance-monitors`). */
export function useBalanceMonitors(page: number, size: number) {
  return useQuery({
    queryKey: ['balance-monitors', page, size],
    queryFn: async () => (await balanceMonitorsApi.listBalanceMonitors(page, size)).data,
  })
}

/** Cadastra uma nova configuração de monitoramento de saldo (`POST /v1/balance-monitors`). */
export function useCreateBalanceMonitor() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: CreateBalanceMonitorPayload) =>
      (await balanceMonitorsApi.createBalanceMonitor(payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['balance-monitors'] })
    },
  })
}

/** Atualiza uma configuração de monitoramento de saldo (`PUT /v1/balance-monitors/{id}`). */
export function useUpdateBalanceMonitor(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateBalanceMonitorPayload) =>
      (await balanceMonitorsApi.updateBalanceMonitor(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['balance-monitors'] })
    },
  })
}

/** Ativa/pausa uma configuração de monitoramento de saldo (`PATCH /v1/balance-monitors/{id}/status`). */
export function useUpdateBalanceMonitorStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateBalanceMonitorStatusPayload) =>
      (await balanceMonitorsApi.updateBalanceMonitorStatus(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['balance-monitors'] })
    },
  })
}
