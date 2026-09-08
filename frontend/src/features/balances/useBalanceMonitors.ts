import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BalanceMonitorsApi } from '../../api/api'
import type {
  BalanceBackfillStatus,
  CreateBalanceMonitorPayload,
  UpdateBalanceMonitorPayload,
  UpdateBalanceMonitorStatusPayload,
} from '../../api/api'
import { apiConfiguration } from '../../api/config'
import { useServerSentStatus } from '../../components/useServerSentStatus'
import type { ServerSentStatus } from '../../components/useServerSentStatus'

const balanceMonitorsApi = new BalanceMonitorsApi(apiConfiguration)

/** Lista configurações de monitoramento de saldo (`GET /v1/balance-monitors`). */
export function useBalanceMonitors(page: number, size: number) {
  return useQuery({
    queryKey: ['balance-monitors', page, size],
    queryFn: async () => (await balanceMonitorsApi.listBalanceMonitors(page, size)).data,
  })
}

/** Consulta uma configuração de monitoramento de saldo pelo id (`GET /v1/balance-monitors/{id}`). */
export function useBalanceMonitor(id: string | undefined) {
  return useQuery({
    queryKey: ['balance-monitor', id],
    queryFn: async () => (await balanceMonitorsApi.getBalanceMonitor(id as string)).data,
    enabled: Boolean(id),
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
      void queryClient.invalidateQueries({ queryKey: ['balance-monitor', id] })
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
      void queryClient.invalidateQueries({ queryKey: ['balance-monitor', id] })
      void queryClient.invalidateQueries({ queryKey: ['balance-monitors'] })
    },
  })
}

/**
 * Chave de cache do status do job GLOBAL de backfill (único em toda a instância, DEC-068).
 * Fora do prefixo `['balance-monitors']` de propósito — create/update de config invalida aquele
 * prefixo e não deve arrastar junto um refetch do status de backfill.
 */
const globalBackfillStatusKey = ['balance-backfill-status'] as const

/**
 * Dispara o reprocessamento de histórico de uma configuração de saldo
 * (`POST /v1/balance-monitors/{id}/backfill`). Job único e global — o `202` retornado já traz o
 * `BalanceBackfillStatus` global atual (inclusive quando é no-op por já estar concluído, ou quando
 * outro backfill está em andamento).
 */
export function useBalanceBackfill(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => (await balanceMonitorsApi.triggerBalanceBackfill(id)).data,
    onSuccess: (status) => {
      // O 202 já traz o status global — semeia o cache pra o polling começar sem esperar o GET.
      queryClient.setQueryData(globalBackfillStatusKey, status)
      void queryClient.invalidateQueries({ queryKey: globalBackfillStatusKey })
    },
  })
}

/**
 * Status do job **global** de backfill de saldo (DEC-068). O backfill roda 1 execução por vez em toda
 * a instância, então **uma única fonte** alimenta todas as linhas da tabela de configurações (em vez
 * de uma por linha).
 *
 * Desde a E17 (DEC-074) a fonte primária é o stream SSE
 * `GET /v1/balance-monitors/backfill/status/stream` — o backend empurra cada transição de estado, sem
 * polling. Se o stream cair (backend fora do ar, etc.), `useServerSentStatus` cai sozinho para o
 * polling de `GET /v1/balance-monitors/backfill/status` (comportamento pré-E17), sem regressão. O
 * `configId` do payload continua dizendo qual linha está rodando.
 *
 * `stopPollingWhenIdle` (achado da análise de 2026-09-07): em modo degradado (sem SSE), o polling REST
 * parava sozinho só quando o componente desmontava — mesmo com `running: false`, continuava batendo
 * indefinidamente. `useBalanceBackfill` já invalida `globalBackfillStatusKey` no `onSuccess` (linha
 * acima), então parar o polling ocioso não perde nenhuma transição: disparar um backfill novo acorda
 * o polling na hora, via invalidação, não precisa esperar o próximo tick.
 */
export function useGlobalBalanceBackfillStatus(): ServerSentStatus<BalanceBackfillStatus> {
  return useServerSentStatus<BalanceBackfillStatus>('/v1/balance-monitors/backfill/status/stream', {
    pollQueryKey: globalBackfillStatusKey,
    pollQueryFn: async () => (await balanceMonitorsApi.getBalanceBackfillStatus()).data,
    stopPollingWhenIdle: (status) => status?.running !== true,
  })
}
