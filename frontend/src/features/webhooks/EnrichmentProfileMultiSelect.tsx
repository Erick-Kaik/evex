import { Autocomplete, Chip, TextField } from '@mui/material'
import type { EnrichmentProfile, Network } from '../../api/api'
import { EnrichmentProfileStatus } from '../../api/api'
import { ENRICHMENT_PROFILE_CATALOG_PAGE_SIZE, useEnrichmentProfiles } from '../enrichment/useEnrichmentProfiles'

interface EnrichmentProfileMultiSelectProps {
  network: Network | ''
  value: string[]
  onChange: (ids: string[]) => void
}

/**
 * Seleção múltipla de Perfis de Enriquecimento (E19/DEC-082/083) pra `WebhookConfig.enrichmentProfileIds`
 * (DEC-084) — só oferece perfis `ACTIVE` como opção **nova**, mas mantém selecionado um perfil
 * `ARCHIVED` já referenciado (mesmo espírito não-bloqueante do resto da API pra status arquivado: o
 * perfil arquivado continua resolvível no dispatch, DEC-082 item 3 — só não aparece pra quem está
 * montando uma seleção nova). Resolução ao vivo no dispatch (DEC-082) — editar um perfil depois muda
 * imediatamente todos os webhooks que o referenciam, mesmo entregas de retry pendentes.
 */
export function EnrichmentProfileMultiSelect({ network, value, onChange }: EnrichmentProfileMultiSelectProps) {
  const { data, isPending, isError } = useEnrichmentProfiles(
    network === '' ? undefined : network,
    0,
    ENRICHMENT_PROFILE_CATALOG_PAGE_SIZE,
  )
  const profiles = data?.content ?? []

  const options = profiles.filter((p) => p.status === EnrichmentProfileStatus.Active || value.includes(p.id))
  // ids referenciados que não vieram na página carregada (perfil arquivado fora da 1ª página, ou
  // ainda carregando) — placeholder mínimo pra não esvaziar a seleção do usuário.
  for (const id of value) {
    if (!options.some((p) => p.id === id)) {
      options.push({
        id,
        name: id,
        network: (network || 'MAINNET') as Network,
        status: EnrichmentProfileStatus.Archived,
        calls: [],
        createdAt: '',
      })
    }
  }

  const selected = options.filter((p) => value.includes(p.id))

  return (
    <Autocomplete
      multiple
      options={options}
      value={selected}
      loading={isPending}
      size="small"
      getOptionLabel={(option) => option.name}
      isOptionEqualToValue={(option, val) => option.id === val.id}
      onChange={(_event, values) => onChange(values.map((v) => v.id))}
      renderValue={(selectedValue: EnrichmentProfile[], getItemProps) =>
        selectedValue.map((option, index) => {
          const { key, ...rest } = getItemProps({ index })
          return <Chip key={key} size="small" label={option.name} {...rest} />
        })
      }
      renderInput={(params) => (
        <TextField
          {...params}
          label="Perfis de Enriquecimento (opcional)"
          placeholder="adicionar perfil"
          helperText={
            isError
              ? 'Não foi possível carregar a lista de perfis.'
              : 'Chamadas on-chain resolvidas no disparo — habilita {{enriched.<placeholder>}} no template.'
          }
          error={isError}
        />
      )}
    />
  )
}
