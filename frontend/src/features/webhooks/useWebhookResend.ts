import { useEffect, useState } from 'react'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BlocksApi, CapabilitiesApi, WebhooksApi } from '../../api/api'
import type { WebhookPreviewPayload, WebhookResendPayload } from '../../api/api'
import { apiConfiguration } from '../../api/config'
import { DEFAULT_PREVIEW_MAX_EVENTS, DEFAULT_RESEND_MAX_EVENTS } from './resendUtils'

const webhooksApi = new WebhooksApi(apiConfiguration)
const blocksApi = new BlocksApi(apiConfiguration)
const capabilitiesApi = new CapabilitiesApi(apiConfiguration)

/** Tamanho de página do seletor de eventos (a spec aceita 1..100). */
export const MATCHING_EVENTS_PAGE_SIZE = 20

export interface CapabilityLimits {
  previewMaxEvents: number
  resendMaxEvents: number
  urlOverrideEnabled: boolean
  /** `true` quando os valores são os defaults conservadores (a chamada falhou ou ainda carrega). */
  isFallback: boolean
}

/** Capacidades/limites efetivos (`GET /v1/capabilities`). */
export function useCapabilities() {
  return useQuery({
    queryKey: ['capabilities'],
    queryFn: async () => (await capabilitiesApi.getCapabilities()).data,
    staleTime: 60_000,
    retry: 1,
  })
}

/** Limites a usar na tela: os do backend ou, se a chamada falhou, defaults conservadores. */
export function useCapabilityLimits(): CapabilityLimits {
  const { data } = useCapabilities()
  return {
    previewMaxEvents: data?.webhookPreviewMaxEvents ?? DEFAULT_PREVIEW_MAX_EVENTS,
    resendMaxEvents: data?.webhookResendMaxEventsPerRequest ?? DEFAULT_RESEND_MAX_EVENTS,
    urlOverrideEnabled: data?.webhookUrlOverrideEnabled === true,
    isFallback: !data,
  }
}

/** Filtro aplicado ao seletor: janela por bloco OU por data (ISO-8601 com offset). */
export interface MatchingEventsFilter {
  fromBlock?: number
  toBlock?: number
  fromDate?: string
  toDate?: string
}

/** Eventos que casam com o webhook, paginados por cursor (`GET /v1/webhooks/{id}/matching-events`). */
export function useMatchingEvents(webhookId: string, filter: MatchingEventsFilter, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: ['webhook-matching-events', webhookId, filter],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      (
        await webhooksApi.listWebhookMatchingEvents(
          webhookId,
          filter.fromBlock,
          filter.toBlock,
          filter.fromDate,
          filter.toDate,
          pageParam,
          MATCHING_EVENTS_PAGE_SIZE,
        )
      ).data,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled,
  })
}

/** Pré-visualização (`POST /v1/webhooks/{id}/preview`) — dry-run, não grava nada. */
export function usePreviewWebhook(webhookId: string) {
  return useMutation({
    mutationFn: async (payload: WebhookPreviewPayload) => (await webhooksApi.previewWebhook(webhookId, payload)).data,
  })
}

/** Reenvio (`POST /v1/webhooks/{id}/resend`, 202); invalida o histórico de entregas. */
export function useResendWebhook(webhookId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: WebhookResendPayload) => (await webhooksApi.resendWebhook(webhookId, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['webhook-deliveries', webhookId] })
    },
  })
}

/** Data -> bloco (`GET /v1/blocks/by-date`), sob demanda. */
export function useResolveBlockByDate() {
  return useMutation({
    mutationFn: async (params: { date: string; direction: 'FLOOR' | 'CEILING' }) =>
      (await blocksApi.resolveBlockByDate(params.date, params.direction)).data,
  })
}

/**
 * Espera do `Retry-After` (429): `start(segundos)` arma a contagem; `seconds` é o restante (0 =
 * liberado). O `useEffect` só gere o timer local do relógio — não busca dado de API.
 */
export function useRetryWait(): { seconds: number; start: (seconds: number) => void } {
  const [clock, setClock] = useState<{ deadline: number; now: number } | null>(null)
  const active = clock !== null
  useEffect(() => {
    if (!active) return undefined
    const timer = setInterval(() => setClock((current) => (current ? { ...current, now: Date.now() } : current)), 500)
    return () => clearInterval(timer)
  }, [active])
  return {
    seconds: clock ? Math.max(0, Math.ceil((clock.deadline - clock.now) / 1000)) : 0,
    start: (seconds) => {
      const now = Date.now()
      setClock({ deadline: now + seconds * 1000, now })
    },
  }
}
