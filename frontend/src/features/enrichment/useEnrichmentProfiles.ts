import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { EnrichmentProfilesApi } from '../../api/api'
import type {
  CreateEnrichmentProfilePayload,
  Network,
  UpdateEnrichmentProfilePayload,
  UpdateEnrichmentProfileStatusPayload,
} from '../../api/api'
import { apiConfiguration } from '../../api/config'

const enrichmentProfilesApi = new EnrichmentProfilesApi(apiConfiguration)

/**
 * Tamanho de página usado pra carregar a lista inteira de Perfis de Enriquecimento de uma vez só
 * (autocomplete multi-seleção em `WebhookForm`) — mesmo teto do parâmetro `size` de
 * `GET /v1/enrichment-profiles` (`maximum: 200` na spec, mesmo valor de `CONTRACT_CATALOG_PAGE_SIZE`).
 */
export const ENRICHMENT_PROFILE_CATALOG_PAGE_SIZE = 200

/** Lista Perfis de Enriquecimento cadastrados, com filtro opcional de rede e paginação (`GET /v1/enrichment-profiles`). */
export function useEnrichmentProfiles(network: Network | undefined, page: number, size: number) {
  return useQuery({
    queryKey: ['enrichment-profiles', network, page, size],
    queryFn: async () => (await enrichmentProfilesApi.listEnrichmentProfiles(network, page, size)).data,
  })
}

/** Consulta um Perfil de Enriquecimento pelo id (`GET /v1/enrichment-profiles/{id}`). */
export function useEnrichmentProfile(id: string | undefined) {
  return useQuery({
    queryKey: ['enrichment-profile', id],
    queryFn: async () => (await enrichmentProfilesApi.getEnrichmentProfile(id as string)).data,
    enabled: Boolean(id),
  })
}

/** Cadastra um novo Perfil de Enriquecimento (`POST /v1/enrichment-profiles`); invalida a listagem. */
export function useCreateEnrichmentProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: CreateEnrichmentProfilePayload) =>
      (await enrichmentProfilesApi.createEnrichmentProfile(payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['enrichment-profiles'] })
    },
  })
}

/** Atualiza nome/descrição/chamadas de um Perfil de Enriquecimento (`PUT /v1/enrichment-profiles/{id}`). */
export function useUpdateEnrichmentProfile(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateEnrichmentProfilePayload) =>
      (await enrichmentProfilesApi.updateEnrichmentProfile(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['enrichment-profile', id] })
      void queryClient.invalidateQueries({ queryKey: ['enrichment-profiles'] })
    },
  })
}

/** Ativa/arquiva um Perfil de Enriquecimento (`PATCH /v1/enrichment-profiles/{id}/status`). */
export function useUpdateEnrichmentProfileStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (payload: UpdateEnrichmentProfileStatusPayload) =>
      (await enrichmentProfilesApi.updateEnrichmentProfileStatus(id, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['enrichment-profile', id] })
      void queryClient.invalidateQueries({ queryKey: ['enrichment-profiles'] })
    },
  })
}
