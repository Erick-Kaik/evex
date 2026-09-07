import { useState } from 'react'
import type { FormEvent } from 'react'
import {
  Box,
  Button,
  IconButton,
  Link,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import DeleteIcon from '@mui/icons-material/Delete'
import AddIcon from '@mui/icons-material/Add'
import { FilterOperator } from '../../api/api'
import type { CreateWebhookPayload, FilterCondition, Network } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { WalletAutocomplete } from '../../components/WalletAutocomplete'
import type { WalletRef } from '../../components/WalletAutocomplete'
import { EventPicker } from '../../components/EventPicker'
import { EventParamSelect } from '../../components/EventParamSelect'
import { ParamBindingCanvas } from '../../components/ParamBindingCanvas'
import { FormModeToggle } from '../../components/FormModeToggle'
import { readFormMode } from '../../components/formMode'
import type { FormMode } from '../../components/formMode'
import { LabeledField } from '../../components/LabeledField'
import type { PageMode } from '../../components/pageMode'
import { matchesParamKind, useContractEventCatalog } from '../../components/contractCatalog'
import { ENRICHMENT_PROFILE_CATALOG_PAGE_SIZE, useEnrichmentProfiles } from '../enrichment/useEnrichmentProfiles'
import { ENTITY_MODEL_CATALOG_PAGE_SIZE, useEntityModels } from '../models/useEntityModels'
import { EnrichmentProfileMultiSelect } from './EnrichmentProfileMultiSelect'
import { EntityModelMultiSelect } from './EntityModelMultiSelect'
import { PayloadTemplateBuilder } from './PayloadTemplateBuilder'

export type WebhookFormValues = CreateWebhookPayload

const FORM_MODE_KEY = 'webhook-form'
const FORM_MODES: FormMode[] = ['guided', 'visual', 'manual']

interface WebhookFormProps {
  /** `view` (só leitura), `edit` (edição de webhook existente) ou `create` (padrão E18). */
  mode: PageMode
  initialValues?: Partial<WebhookFormValues>
  submitLabel: string
  isPending: boolean
  isError: boolean
  error: unknown
  isSuccess?: boolean
  successMessage?: string
  onSubmit: (payload: WebhookFormValues) => void
}

/**
 * Form de cadastro/edição/visualização de webhook (doc §4.6, padrão E18/DEC-079) — `filters` como
 * lista dinâmica de `{paramName, operator, value}`, `payloadTemplate` opcional. Componente único
 * reaproveitado nos 3 modos: `create` (`/webhooks/new`), `view` (`/webhooks/:id`, campos só leitura)
 * e `edit` (`/webhooks/:id/edit`).
 *
 * Em `edit`/`create`, três modos de preenchimento (DEC-072): **guiado** (contrato/evento sobre os
 * dados cadastrados, rede herdada, cada `filter.paramName` num `<EventParamSelect kind="any">`),
 * **visual** (`<ParamBindingCanvas>` para os filtros — arrasta o parâmetro, escolhe operador/valor ao
 * lado — e `<PayloadTemplateBuilder>` para montar o `payloadTemplate`) e **manual** (texto livre +
 * `<NetworkSelect>`, comportamento histórico). Os três montam o mesmo `CreateWebhookPayload`. Em
 * `view` os 3 modos não se aplicam — os campos aparecem como texto (`<LabeledField mode="view">`).
 */
export function WebhookForm({
  mode,
  initialValues,
  submitLabel,
  isPending,
  isError,
  error,
  isSuccess,
  successMessage,
  onSubmit,
}: WebhookFormProps) {
  const [formMode, setFormMode] = useState<FormMode>(() => readFormMode(FORM_MODE_KEY, FORM_MODES))

  const [contractAddress, setContractAddress] = useState(initialValues?.contractAddress ?? '')
  const [network, setNetwork] = useState<Network | ''>(initialValues?.network ?? '')
  const [contractRef, setContractRef] = useState<ContractRef | null>(
    initialValues?.contractAddress && initialValues?.network
      ? { address: initialValues.contractAddress, network: initialValues.network }
      : null,
  )
  const [eventName, setEventName] = useState(initialValues?.eventName ?? '')
  const [url, setUrl] = useState(initialValues?.url ?? '')
  const [filters, setFilters] = useState<FilterCondition[]>(initialValues?.filters ?? [])
  const [payloadTemplate, setPayloadTemplate] = useState(initialValues?.payloadTemplate ?? '')
  const [enrichmentProfileIds, setEnrichmentProfileIds] = useState<string[]>(
    initialValues?.enrichmentProfileIds ?? [],
  )
  const [entityModelIds, setEntityModelIds] = useState<string[]>(initialValues?.entityModelIds ?? [])

  const isGuided = formMode === 'guided'
  const isVisual = formMode === 'visual'
  // Guiado e visual compartilham a cascata contrato→evento no topo (rede herdada do contrato).
  const usesContractRef = isGuided || isVisual
  const effectiveAddress = usesContractRef ? (contractRef?.address ?? '') : contractAddress
  const effectiveNetwork: Network | '' = usesContractRef ? (contractRef?.network ?? '') : network

  // Catálogo do contrato-âncora — usado nos modos guiado/visual tanto pra o `<EventPicker>` quanto
  // pra resolver os `inputs` do evento já selecionado (edição de um webhook existente).
  const catalog = useContractEventCatalog(usesContractRef ? effectiveAddress || undefined : undefined)
  const eventInputs = catalog.inputsByEvent.get(eventName) ?? []

  // Tokens `{{enriched.*}}`/`{{model.*}}` disponíveis a mais no `payloadTemplate` (E19/DEC-082/083,
  // DEC-084) — derivados dos Perfis/Modelos já referenciados por este webhook. As mesmas consultas já
  // rodam dentro de `<EnrichmentProfileMultiSelect>`/`<EntityModelMultiSelect>` (React Query deduplica
  // por `queryKey`, sem round-trip extra).
  const enrichmentProfilesQuery = useEnrichmentProfiles(
    effectiveNetwork === '' ? undefined : effectiveNetwork,
    0,
    ENRICHMENT_PROFILE_CATALOG_PAGE_SIZE,
  )
  const entityModelsQuery = useEntityModels(
    effectiveNetwork === '' ? undefined : effectiveNetwork,
    0,
    ENTITY_MODEL_CATALOG_PAGE_SIZE,
  )
  const enrichedTokens = (enrichmentProfilesQuery.data?.content ?? [])
    .filter((profile) => enrichmentProfileIds.includes(profile.id))
    .flatMap((profile) => profile.calls.flatMap((call) => call.outputs.map((output) => `{{enriched.${output.placeholder}}}`)))
  const modelTokens = (entityModelsQuery.data?.content ?? [])
    .filter((model) => entityModelIds.includes(model.id))
    .flatMap((model) =>
      model.contributors
        .filter(
          (contributor) =>
            contributor.contractAddress.toLowerCase() === effectiveAddress.toLowerCase() &&
            contributor.eventName === eventName,
        )
        .flatMap((contributor) => contributor.fieldMappings.map((mapping) => `{{model.${mapping.modelField}}}`)),
    )
  const enrichmentTemplateTokens = [...enrichedTokens, ...modelTokens]

  function addFilter() {
    setFilters([...filters, { paramName: '', operator: FilterOperator.Eq, value: '' }])
  }

  function updateFilter(index: number, patch: Partial<FilterCondition>) {
    setFilters(filters.map((filter, i) => (i === index ? { ...filter, ...patch } : filter)))
  }

  function removeFilter(index: number) {
    setFilters(filters.filter((_, i) => i !== index))
  }

  /**
   * Operador + valor + remover de um filtro — igual nos 3 modos, só o campo de parâmetro muda. E22:
   * quando o `paramName` escolhido é de um evento com esse parâmetro do tipo Solidity `address`
   * (`eventInputs`/`matchesParamKind`, só disponível nos modos guiado/visual, que têm catálogo), o
   * campo de valor oferece `<WalletAutocomplete>` como alternativa ao texto livre, atrás do mesmo
   * toggle "digitar manualmente" usado em outros lugares (DEC-081) — escolher uma carteira já
   * conhecida em vez de colar o endereço de novo.
   */
  function renderFilterControls(index: number) {
    const filter = filters[index]
    if (!filter) {
      return null
    }
    const paramType = eventInputs.find((input) => input.name === filter.paramName)?.type
    const isAddressValue = paramType !== undefined && matchesParamKind(paramType, 'address')

    return (
      <>
        <TextField
          select
          size="small"
          label="operador"
          value={filter.operator}
          onChange={(event) => updateFilter(index, { operator: event.target.value as FilterOperator })}
          sx={{ width: 110 }}
        >
          {(Object.values(FilterOperator) as FilterOperator[]).map((operator) => (
            <MenuItem key={operator} value={operator}>
              {operator}
            </MenuItem>
          ))}
        </TextField>
        {isAddressValue ? (
          <FilterAddressValueField
            value={filter.value}
            network={effectiveNetwork || undefined}
            onChange={(value) => updateFilter(index, { value })}
          />
        ) : (
          <TextField
            required
            size="small"
            label="valor"
            value={filter.value}
            onChange={(event) => updateFilter(index, { value: event.target.value })}
            placeholder="valor"
            sx={{ width: 140 }}
          />
        )}
        <IconButton size="small" color="error" onClick={() => removeFilter(index)} aria-label="remover filtro">
          <DeleteIcon fontSize="small" />
        </IconButton>
      </>
    )
  }

  function handleContractRefChange(next: ContractRef | null) {
    setContractRef(next)
    setEventName('')
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (effectiveNetwork === '') {
      return
    }

    onSubmit({
      contractAddress: effectiveAddress,
      network: effectiveNetwork,
      eventName,
      url,
      filters: filters.length > 0 ? filters : undefined,
      payloadTemplate: payloadTemplate || undefined,
      enrichmentProfileIds: enrichmentProfileIds.length > 0 ? enrichmentProfileIds : undefined,
      entityModelIds: entityModelIds.length > 0 ? entityModelIds : undefined,
    })
  }

  if (mode === 'view') {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 560 }}>
        <LabeledField label="Contrato" mode="view" value={contractAddress} monospace />
        <LabeledField label="Rede" mode="view" value={network} />
        <LabeledField label="Nome do evento" mode="view" value={eventName} />
        <LabeledField label="URL de destino" mode="view" value={url} />
        <Box>
          <Typography variant="subtitle2">Filtros</Typography>
          {filters.length === 0 ? (
            <Typography variant="caption" color="text.secondary">
              Sem filtros — sempre dispara.
            </Typography>
          ) : (
            <Stack sx={{ gap: 0.5, mt: 0.5 }}>
              {filters.map((filter, index) => (
                <Typography key={index} variant="body2">
                  {filter.paramName} {filter.operator} {filter.value}
                </Typography>
              ))}
            </Stack>
          )}
        </Box>
        <LabeledField
          label="Template de payload"
          mode="view"
          value={payloadTemplate || 'Vazio — usa o envelope JSON padrão (evento completo).'}
          multiline
          monospace
        />
        <Box>
          <Typography variant="caption" color="text.secondary" component="div">
            Perfis de Enriquecimento referenciados
          </Typography>
          <Typography variant="body2">
            {enrichmentProfileIds.length === 0
              ? '—'
              : enrichmentProfileIds
                  .map((id) => enrichmentProfilesQuery.data?.content.find((p) => p.id === id)?.name ?? id)
                  .join(', ')}
          </Typography>
        </Box>
        <Box>
          <Typography variant="caption" color="text.secondary" component="div">
            Modelos de Entidade referenciados
          </Typography>
          <Typography variant="body2">
            {entityModelIds.length === 0
              ? '—'
              : entityModelIds
                  .map((id) => entityModelsQuery.data?.content.find((m) => m.id === id)?.name ?? id)
                  .join(', ')}
          </Typography>
        </Box>
      </Box>
    )
  }

  return (
    <Box component="form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 560 }}>
      <FormModeToggle
        storageKey={FORM_MODE_KEY}
        modes={FORM_MODES}
        value={formMode}
        onChange={setFormMode}
      />

      {usesContractRef ? (
        <>
          <ContractAutocomplete value={contractRef} onChange={handleContractRefChange} required />
          <EventPicker
            address={effectiveAddress || undefined}
            value={eventName}
            onChange={(name) => setEventName(name)}
            required
          />
        </>
      ) : (
        <>
          <TextField
            label="Contrato"
            required
            fullWidth
            value={contractAddress}
            onChange={(event) => setContractAddress(event.target.value)}
            placeholder="0x..."
            slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
          />
          <NetworkSelect required value={network} onChange={setNetwork} />
          <TextField
            label="Nome do evento"
            required
            fullWidth
            value={eventName}
            onChange={(event) => setEventName(event.target.value)}
            placeholder="ex.: Transfer"
          />
        </>
      )}

      <TextField
        label="URL de destino"
        type="url"
        required
        fullWidth
        value={url}
        onChange={(event) => setUrl(event.target.value)}
        placeholder="https://..."
      />

      <Box>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography variant="subtitle2">Filtros (opcional)</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addFilter}>
            adicionar filtro
          </Button>
        </Stack>

        {filters.length === 0 && (
          <Typography variant="caption" color="text.secondary">
            Sem filtros — sempre dispara.
          </Typography>
        )}

        {isVisual ? (
          <Box sx={{ mt: 1 }}>
            <ParamBindingCanvas
              params={eventInputs}
              slots={filters.map((filter, index) => ({
                key: String(index),
                label: `Filtro ${index + 1}`,
                kind: 'any',
                value: filter.paramName,
              }))}
              onBind={(slotKey, paramName) =>
                updateFilter(Number(slotKey), { paramName: paramName ?? '' })
              }
              renderSlotExtra={(slotKey) => renderFilterControls(Number(slotKey))}
            />
          </Box>
        ) : (
          filters.map((filter, index) => (
            <Stack key={index} direction="row" sx={{ alignItems: 'center', gap: 1, mt: 1 }}>
              {isGuided ? (
                <EventParamSelect
                  kind="any"
                  inputs={eventInputs}
                  value={filter.paramName}
                  onChange={(paramName) => updateFilter(index, { paramName })}
                  label="parâmetro"
                  required
                  sx={{ width: 160 }}
                />
              ) : (
                <TextField
                  required
                  size="small"
                  value={filter.paramName}
                  onChange={(event) => updateFilter(index, { paramName: event.target.value })}
                  placeholder="parâmetro"
                  sx={{ width: 140 }}
                />
              )}
              {renderFilterControls(index)}
            </Stack>
          ))
        )}
      </Box>

      <Box>
        <Typography variant="subtitle2" gutterBottom>
          Enriquecimento do payload (opcional, E19)
        </Typography>
        <Stack sx={{ gap: 2 }}>
          <EnrichmentProfileMultiSelect
            network={effectiveNetwork}
            value={enrichmentProfileIds}
            onChange={setEnrichmentProfileIds}
          />
          <EntityModelMultiSelect
            network={effectiveNetwork}
            contractAddress={effectiveAddress}
            eventName={eventName}
            value={entityModelIds}
            onChange={setEntityModelIds}
          />
        </Stack>
      </Box>

      {isVisual ? (
        <PayloadTemplateBuilder
          params={eventInputs}
          extraTokens={enrichmentTemplateTokens}
          value={payloadTemplate}
          onChange={setPayloadTemplate}
        />
      ) : (
        <TextField
          label="Template de payload (opcional)"
          multiline
          minRows={3}
          fullWidth
          value={payloadTemplate}
          onChange={(event) => setPayloadTemplate(event.target.value)}
          placeholder='{"evento": "{{eventName}}", "valor": "{{decodedParameters.value}}"}'
          helperText={
            enrichmentTemplateTokens.length > 0
              ? `Vazio = usa o envelope JSON padrão (evento completo). Também disponíveis: ${enrichmentTemplateTokens.join(', ')}.`
              : 'Vazio = usa o envelope JSON padrão (evento completo). Perfis/Modelos referenciados acima habilitam {{enriched.*}}/{{model.*}}.'
          }
          slotProps={{ htmlInput: { style: { fontFamily: 'monospace', fontSize: '0.75rem' } } }}
        />
      )}

      {isError && <ApiErrorAlert error={error} />}
      {isSuccess && successMessage && (
        <Typography variant="body2" color="success.main">
          {successMessage}
        </Typography>
      )}

      <Button type="submit" variant="contained" disabled={isPending} sx={{ alignSelf: 'flex-start' }}>
        {isPending ? 'Salvando...' : submitLabel}
      </Button>
    </Box>
  )
}

interface FilterAddressValueFieldProps {
  value: string
  network?: Network
  onChange: (value: string) => void
}

/**
 * Campo de valor de um filtro sobre um parâmetro `address` (E22) — por padrão oferece
 * `<WalletAutocomplete>` (escolher uma carteira já cadastrada), com um toggle "digitar manualmente"
 * pra texto livre (mesmo padrão dual de `<ContractAutocomplete>` em `BalanceQuerySection`/
 * `EventsPage`). Estado de toggle local ao componente — como a lista de filtros é renderizada com
 * `key={index}` (mesmo índice da posição na lista), remover/adicionar filtros já remonta a instância
 * corretamente, sem precisar sincronizar um mapa de índice→modo no componente pai.
 */
function FilterAddressValueField({ value, network, onChange }: FilterAddressValueFieldProps) {
  const [manual, setManual] = useState(false)
  const walletRef: WalletRef | null = value && network ? { address: value, network } : null

  return (
    <Stack sx={{ gap: 0.25 }}>
      {manual ? (
        <TextField
          required
          size="small"
          label="valor"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder="valor"
          sx={{ width: 180 }}
        />
      ) : (
        <WalletAutocomplete
          value={walletRef}
          network={network}
          label="valor"
          size="small"
          sx={{ width: 220 }}
          onChange={(ref) => onChange(ref?.address ?? '')}
        />
      )}
      <Link component="button" type="button" variant="caption" onClick={() => setManual((current) => !current)}>
        {manual ? 'escolher carteira cadastrada' : 'digitar manualmente'}
      </Link>
    </Stack>
  )
}
