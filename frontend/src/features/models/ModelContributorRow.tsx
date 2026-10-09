import { Box, Button, IconButton, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material'
import DeleteIcon from '@mui/icons-material/Delete'
import AddIcon from '@mui/icons-material/Add'
import type { ModelContributor, ModelFieldMapping } from '../../api/api'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { EventPicker } from '../../components/EventPicker'
import { EventParamSelect } from '../../components/EventParamSelect'
import { useContractEventCatalog } from '../../components/contractCatalog'
import type { Network } from '../../api/api'
import { CorrelationKeyBuilder } from './CorrelationKeyBuilder'
import { FIELD_SOURCES, effectiveKeyParts, isLegacyKey, withKeyParts } from './correlationKey'

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
 * chave de correlação (`<CorrelationKeyBuilder>`, DEC-111: partes PARAM/EMITTER_ADDRESS/TX_HASH/
 * BLOCK_NUMBER/LITERAL; modelo legado só com `correlationParam` aparece como 1 parte PARAM e é enviado
 * intacto se não for alterado) e a lista de `fieldMappings` (fonte `source` + parâmetro decodificado,
 * só para PARAM → nome do campo no modelo acumulado).
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
      correlationParam: undefined,
      correlationKey: [{ type: 'PARAM', arg: '' }],
      fieldMappings: [],
    })
  }

  function handleEventChange(eventName: string) {
    // Trocar o evento invalida os parâmetros escolhidos: mantém a forma da chave (nº de partes e tipos),
    // zerando só os `arg` de PARAM — não migra um modelo legado sem o usuário mexer no evento.
    const parts = effectiveKeyParts(contributor)
    const nextParts = parts.length === 0 ? [{ type: 'PARAM', arg: '' }] : parts.map((p) => (p.type === 'PARAM' ? { ...p, arg: '' } : p))
    onChange(index, { ...withKeyParts(contributor, nextParts), eventName, fieldMappings: [] })
  }

  function addFieldMapping() {
    onChange(index, {
      ...contributor,
      fieldMappings: [...contributor.fieldMappings, { decodedParam: '', modelField: '', source: 'PARAM' }],
    })
  }

  function updateFieldMapping(mappingIndex: number, patch: Partial<ModelFieldMapping>) {
    onChange(index, {
      ...contributor,
      fieldMappings: contributor.fieldMappings.map((mapping, i) =>
        i === mappingIndex ? { ...mapping, ...patch } : mapping,
      ),
    })
  }

  function changeSource(mappingIndex: number, source: string) {
    // `decodedParam` só vale para PARAM: as demais fontes o descartam.
    updateFieldMapping(mappingIndex, { source, decodedParam: source === 'PARAM' ? '' : undefined })
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

        <CorrelationKeyBuilder
          parts={effectiveKeyParts(contributor)}
          eventInputs={eventInputs}
          legacy={isLegacyKey(contributor)}
          onChange={(parts) => onChange(index, withKeyParts(contributor, parts))}
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
            {contributor.fieldMappings.map((mapping, mappingIndex) => {
              const source = mapping.source ?? 'PARAM'
              const knownSource = (FIELD_SOURCES as readonly string[]).includes(source)
              return (
              <Stack key={mappingIndex} direction="row" sx={{ alignItems: 'center', gap: 1 }}>
                <TextField
                  select
                  size="small"
                  label="fonte"
                  value={source}
                  onChange={(event) => changeSource(mappingIndex, event.target.value)}
                  helperText={source === 'BLOCK_TIMESTAMP' ? 'Exige o timestamp já gravado no evento.' : undefined}
                  sx={{ width: 190 }}
                >
                  {FIELD_SOURCES.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                  {!knownSource && <MenuItem value={source}>{source}</MenuItem>}
                </TextField>
                {source === 'PARAM' && (
                  <EventParamSelect
                    kind="any"
                    inputs={eventInputs}
                    value={mapping.decodedParam ?? ''}
                    onChange={(paramName) => updateFieldMapping(mappingIndex, { decodedParam: paramName })}
                    label="parâmetro do evento"
                    required
                    sx={{ width: 220 }}
                  />
                )}
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
              )
            })}
          </Stack>
        </Box>
      </Stack>
    </Paper>
  )
}
