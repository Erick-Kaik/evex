import { useState } from 'react'
import type { FormEvent } from 'react'
import {
  Box,
  Button,
  IconButton,
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

export type WebhookFormValues = CreateWebhookPayload

interface WebhookFormProps {
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
 * Form de cadastro/edição de webhook (doc §4.6) — `filters` como lista dinâmica de
 * `{paramName, operator, value}`, `payloadTemplate` opcional com placeholder mostrando o formato
 * `{{campo}}`/`{{decodedParameters.nome}}`. Reaproveitado no cadastro (`WebhooksListPage`) e na
 * edição (`WebhookDetailPage`).
 */
export function WebhookForm({
  initialValues,
  submitLabel,
  isPending,
  isError,
  error,
  isSuccess,
  successMessage,
  onSubmit,
}: WebhookFormProps) {
  const [contractAddress, setContractAddress] = useState(initialValues?.contractAddress ?? '')
  const [network, setNetwork] = useState<Network | ''>(initialValues?.network ?? '')
  const [eventName, setEventName] = useState(initialValues?.eventName ?? '')
  const [url, setUrl] = useState(initialValues?.url ?? '')
  const [filters, setFilters] = useState<FilterCondition[]>(initialValues?.filters ?? [])
  const [payloadTemplate, setPayloadTemplate] = useState(initialValues?.payloadTemplate ?? '')

  function addFilter() {
    setFilters([...filters, { paramName: '', operator: FilterOperator.Eq, value: '' }])
  }

  function updateFilter(index: number, patch: Partial<FilterCondition>) {
    setFilters(filters.map((filter, i) => (i === index ? { ...filter, ...patch } : filter)))
  }

  function removeFilter(index: number) {
    setFilters(filters.filter((_, i) => i !== index))
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (network === '') {
      return
    }

    onSubmit({
      contractAddress,
      network,
      eventName,
      url,
      filters: filters.length > 0 ? filters : undefined,
      payloadTemplate: payloadTemplate || undefined,
    })
  }

  return (
    <Box component="form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 560 }}>
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

        {filters.map((filter, index) => (
          <Stack key={index} direction="row" sx={{ alignItems: 'center', gap: 1, mt: 1 }}>
            <TextField
              required
              size="small"
              value={filter.paramName}
              onChange={(event) => updateFilter(index, { paramName: event.target.value })}
              placeholder="parâmetro"
              sx={{ width: 140 }}
            />
            <TextField
              select
              size="small"
              value={filter.operator}
              onChange={(event) => updateFilter(index, { operator: event.target.value as FilterOperator })}
              sx={{ width: 100 }}
            >
              {(Object.values(FilterOperator) as FilterOperator[]).map((operator) => (
                <MenuItem key={operator} value={operator}>
                  {operator}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              required
              size="small"
              value={filter.value}
              onChange={(event) => updateFilter(index, { value: event.target.value })}
              placeholder="valor"
              sx={{ width: 140 }}
            />
            <IconButton size="small" color="error" onClick={() => removeFilter(index)} aria-label="remover filtro">
              <DeleteIcon fontSize="small" />
            </IconButton>
          </Stack>
        ))}
      </Box>

      <TextField
        label="Template de payload (opcional)"
        multiline
        minRows={3}
        fullWidth
        value={payloadTemplate}
        onChange={(event) => setPayloadTemplate(event.target.value)}
        placeholder='{"evento": "{{eventName}}", "valor": "{{decodedParameters.value}}"}'
        helperText="Vazio = usa o envelope JSON padrão (evento completo)."
        slotProps={{ htmlInput: { style: { fontFamily: 'monospace', fontSize: '0.75rem' } } }}
      />

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
