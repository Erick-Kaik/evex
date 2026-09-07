import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { EntityModelsApi } from '../../api/api'
import type {
  CreateEntityModelPayload,
  Network,
  UpdateEntityModelPayload,
  UpdateEntityModelStatusPayload,
} from '../../api/api'
import { apiConfiguration } from '../../api/config'

const entityModelsApi = new EntityModelsApi(apiConfiguration)

/**
 * Tamanho de página usado pra carregar a lista inteira de Modelos de Entidade de uma vez só
 * (autocomplete multi-seleção em `WebhookForm`) — mesmo teto do parâmetro `size` de
 * `GET /v1/entity-models` (`maximum: 200` na spec).
 */
export const ENTITY_MODEL_CATALOG_PAGE_SIZE = 200

/** Lista Modelos de Entidade cadastrados, com filtro opcional de rede e paginação (`GET /v1/entity-models`). */
export function useEntityModels(network: Network | undefined, page: number, size: number) {
  return useQuery({
    queryKey: ['entity-models', network, page, size],
    queryFn: async () => (await entityModelsApi.listEntityModels(network, page, size)).data,
  })
}

/** Consulta um Modelo de Entidade pelo id (`GET /v1/entity-models/{id}`). */
export function useEntityModel(id: string | undefined) {
  return useQuery({
    queryKey: ['entity-model', id],
    queryFn: async () => (await entityModelsApi.getEntityModel(id as string)).data,
    enabled: Boolean(id),
  })
}

/** Cadastra um novo Modelo de Entidade (`POST /v1/entity-models`); invalida a listagem. */
export function useCreateEntityModel() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: CreateEntityModelPayload) =>
      (await entityModelsApi.createEntityModel(payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['entity-models'] })
    },
  })
}

/** Atualiza nome/descrição/contribuidores de um Modelo de Entidade (`PUT /v1/entity-models/{id}`). */
export function useUpdateEntityModel(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateEntityModelPayload) =>
      (await entityModelsApi.updateEntityModel(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['entity-model', id] })
      void queryClient.invalidateQueries({ queryKey: ['entity-models'] })
    },
  })
}

/** Ativa/arquiva um Modelo de Entidade (`PATCH /v1/entity-models/{id}/status`). */
export function useUpdateEntityModelStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateEntityModelStatusPayload) =>
      (await entityModelsApi.updateEntityModelStatus(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['entity-model', id] })
      void queryClient.invalidateQueries({ queryKey: ['entity-models'] })
    },
  })
}
