import { Box, Button, IconButton, Paper, Stack, TextField, Typography } from '@mui/material'
import DeleteIcon from '@mui/icons-material/Delete'
import AddIcon from '@mui/icons-material/Add'
import type { ModelContributor } from '../../api/api'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { EventPicker } from '../../components/EventPicker'
import { EventParamSelect } from '../../components/EventParamSelect'
import { useContractEventCatalog } from '../../components/contractCatalog'
import type { Network } from '../../api/api'

interface ModelContributorRowProps {
  index: number
  contributor: ModelContributor
  network: Network
  onChange: (index: number, next: ModelContributor) => void
  onRemove: (index: number) => void
}

/**
 * Uma linha do builder de `contributors` de um Modelo de Entidade (E19/DEC-082/083): contrato +
 * evento (catálogo `GET /v1/contracts/{address}/events`, mesmo componente `<EventPicker>` da E16),
 * `correlationParam` (qual campo decodificado deste evento identifica a entidade — `<EventParamSelect
 * kind="any">`) e a lista de `fieldMappings` (campo decodificado → nome do campo no modelo acumulado).
 */
export function ModelContributorRow({ index, contributor, network, onChange, onRemove }: ModelContributorRowProps) {
  const { inputsByEvent } = useContractEventCatalog(contributor.contractAddress || undefined)
  const eventInputs = inputsByEvent.get(contributor.eventName) ?? []

  const contractRef: ContractRef | null = contributor.contractAddress
    ? { address: contributor.contractAddress, network }
    : null

  function handleContractChange(next: ContractRef | null) {
    onChange(index, {
      ...contributor,
      contractAddress: next?.address ?? '',
      eventName: '',
      correlationParam: '',
      fieldMappings: [],
    })
  }

  function handleEventChange(eventName: string) {
    onChange(index, { ...contributor, eventName, correlationParam: '', fieldMappings: [] })
  }

  function addFieldMapping() {
    onChange(index, {
      ...contributor,
      fieldMappings: [...contributor.fieldMappings, { decodedParam: '', modelField: '' }],
    })
  }

  function updateFieldMapping(mappingIndex: number, patch: Partial<{ decodedParam: string; modelField: string }>) {
    onChange(index, {
      ...contributor,
      fieldMappings: contributor.fieldMappings.map((mapping, i) =>
        i === mappingIndex ? { ...mapping, ...patch } : mapping,
      ),
    })
  }

  function removeFieldMapping(mappingIndex: number) {
    onChange(index, {
      ...contributor,
      fieldMappings: contributor.fieldMappings.filter((_, i) => i !== mappingIndex),
    })
  }

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="subtitle2">Evento contribuinte {index + 1}</Typography>
        <IconButton size="small" color="error" onClick={() => onRemove(index)} aria-label="remover contribuidor">
          <DeleteIcon fontSize="small" />
        </IconButton>
      </Stack>

      <Stack sx={{ gap: 2 }}>
        <ContractAutocomplete value={contractRef} onChange={handleContractChange} network={network} required />

        <EventPicker
          address={contributor.contractAddress || undefined}
          value={contributor.eventName}
          onChange={(name) => handleEventChange(name)}
          required
        />

        <EventParamSelect
          kind="any"
          inputs={eventInputs}
          value={contributor.correlationParam}
          onChange={(paramName) => onChange(index, { ...contributor, correlationParam: paramName })}
          label="Parâmetro de correlação (identifica a entidade)"
          required
        />

        <Box>
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
            <Typography variant="caption" color="text.secondary">
              Campos que entram no modelo acumulado
            </Typography>
            <Button size="small" startIcon={<AddIcon />} onClick={addFieldMapping} disabled={eventInputs.length === 0}>
              adicionar campo
            </Button>
          </Stack>

          {contributor.fieldMappings.length === 0 && (
            <Typography variant="caption" color="text.secondary" component="div">
              Nenhum campo mapeado ainda.
            </Typography>
          )}

          <Stack sx={{ gap: 1, mt: 1 }}>
            {contributor.fieldMappings.map((mapping, mappingIndex) => (
              <Stack key={mappingIndex} direction="row" sx={{ alignItems: 'center', gap: 1 }}>
                <EventParamSelect
                  kind="any"
                  inputs={eventInputs}
                  value={mapping.decodedParam}
                  onChange={(paramName) => updateFieldMapping(mappingIndex, { decodedParam: paramName })}
                  label="parâmetro do evento"
                  required
                  sx={{ width: 220 }}
                />
                <TextField
                  size="small"
                  required
                  label="nome no modelo"
                  value={mapping.modelField}
                  onChange={(event) => updateFieldMapping(mappingIndex, { modelField: event.target.value })}
                  sx={{ width: 200 }}
                />
                <IconButton
                  size="small"
                  color="error"
                  onClick={() => removeFieldMapping(mappingIndex)}
                  aria-label="remover campo"
                >
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </Stack>
            ))}
          </Stack>
        </Box>
      </Stack>
    </Paper>
  )
}
