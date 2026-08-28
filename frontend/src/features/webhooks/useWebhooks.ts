import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { WebhooksApi } from '../../api/api'
import type {
  CreateWebhookPayload,
  UpdateWebhookPayload,
  UpdateWebhookStatusPayload,
} from '../../api/api'
import { apiConfiguration } from '../../api/config'

const webhooksApi = new WebhooksApi(apiConfiguration)

/** Lista configurações de webhook (`GET /v1/webhooks`). */
export function useWebhooks(page: number, size: number) {
  return useQuery({
    queryKey: ['webhooks', page, size],
    queryFn: async () => (await webhooksApi.listWebhooks(page, size)).data,
  })
}

/** Consulta um webhook pelo id (`GET /v1/webhooks/{id}`). */
export function useWebhook(id: string | undefined) {
  return useQuery({
    queryKey: ['webhook', id],
    queryFn: async () => (await webhooksApi.getWebhook(id as string)).data,
    enabled: Boolean(id),
  })
}

/** Cadastra um novo webhook (`POST /v1/webhooks`); invalida a listagem. */
export function useCreateWebhook() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: CreateWebhookPayload) => (await webhooksApi.createWebhook(payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['webhooks'] })
    },
  })
}

/** Atualiza um webhook (`PUT /v1/webhooks/{id}`). */
export function useUpdateWebhook(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateWebhookPayload) => (await webhooksApi.updateWebhook(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['webhook', id] })
      void queryClient.invalidateQueries({ queryKey: ['webhooks'] })
    },
  })
}

/** Ativa/pausa um webhook (`PATCH /v1/webhooks/{id}/status`). */
export function useUpdateWebhookStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateWebhookStatusPayload) =>
      (await webhooksApi.updateWebhookStatus(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['webhook', id] })
      void queryClient.invalidateQueries({ queryKey: ['webhooks'] })
    },
  })
}

/** Lista as entregas (com log de tentativas) de um webhook (`GET /v1/webhooks/{id}/deliveries`). */
export function useWebhookDeliveries(id: string | undefined, page: number, size: number) {
  return useQuery({
    queryKey: ['webhook-deliveries', id, page, size],
    queryFn: async () => (await webhooksApi.listWebhookDeliveries(id as string, page, size)).data,
    enabled: Boolean(id),
  })
}
