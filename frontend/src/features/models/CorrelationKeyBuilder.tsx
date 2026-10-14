import { Box, Button, IconButton, MenuItem, Stack, TextField, Typography } from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import DeleteIcon from '@mui/icons-material/Delete'
import type { ContractEventInput, ModelCorrelationKeyPart } from '../../api/api'
import { EventParamSelect } from '../../components/EventParamSelect'
import { KEY_PART_TYPES, keyPartNeedsArg, previewKey } from './correlationKey'

interface CorrelationKeyBuilderProps {
  parts: ModelCorrelationKeyPart[]
  /** `inputs` do evento escolhido — alimentam o select das partes `PARAM`. */
  eventInputs: ContractEventInput[]
  /** `true` quando a chave vem só do atalho legado `correlationParam` (exibida como 1 parte PARAM). */
  legacy: boolean
  onChange: (parts: ModelCorrelationKeyPart[]) => void
}

/**
 * Construtor da chave de correlação de um contribuinte (DEC-111): lista ordenada de partes
 * (adicionar/remover/reordenar), cada uma com tipo e `arg` condicional (PARAM → parâmetro do evento;
 * LITERAL → texto; demais sem `arg`). O tipo é string extensível: um tipo desconhecido (vindo do
 * backend) aparece como opção extra com o texto cru e o `arg`, se houver, fica editável como texto.
 */
export function CorrelationKeyBuilder({ parts, eventInputs, legacy, onChange }: CorrelationKeyBuilderProps) {
  function update(index: number, patch: ModelCorrelationKeyPart) {
    onChange(parts.map((part, i) => (i === index ? patch : part)))
  }

  function changeType(index: number, type: string) {
    // Trocar o tipo descarta o `arg` anterior (não faz sentido entre PARAM e LITERAL).
    update(index, keyPartNeedsArg(type) ? { type, arg: '' } : { type })
  }

  function move(index: number, delta: -1 | 1) {
    const target = index + delta
    if (target < 0 || target >= parts.length) return
    const next = [...parts]
    ;[next[index], next[target]] = [next[target], next[index]]
    onChange(next)
  }

  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="caption" color="text.secondary">
          Chave de correlação (identifica a entidade)
        </Typography>
        <Button
          size="small"
          startIcon={<AddIcon />}
          onClick={() => onChange([...parts, { type: 'PARAM', arg: '' }])}
        >
          adicionar parte
        </Button>
      </Stack>

      {legacy && (
        <Typography variant="caption" color="text.secondary" component="div">
          Modelo legado: a chave vem do atalho de parâmetro único. Se você não alterar, ele é enviado como está.
        </Typography>
      )}

      <Stack sx={{ gap: 1, mt: 1 }}>
        {parts.map((part, index) => {
          const knownType = (KEY_PART_TYPES as readonly string[]).includes(part.type)
          return (
            <Stack key={index} direction="row" sx={{ alignItems: 'center', gap: 1 }}>
              <TextField
                select
                size="small"
                label={`Tipo da parte ${index + 1}`}
                value={part.type}
                onChange={(event) => changeType(index, event.target.value)}
                sx={{ width: 220 }}
              >
                {KEY_PART_TYPES.map((type) => (
                  <MenuItem key={type} value={type}>
                    {type}
                  </MenuItem>
                ))}
                {!knownType && <MenuItem value={part.type}>{part.type}</MenuItem>}
              </TextField>

              {part.type === 'PARAM' && (
                <EventParamSelect
                  kind="any"
                  inputs={eventInputs}
                  value={part.arg ?? ''}
                  onChange={(arg) => update(index, { type: 'PARAM', arg })}
                  label={`Parâmetro da parte ${index + 1}`}
                  sx={{ width: 220 }}
                />
              )}
              {part.type === 'LITERAL' && (
                <TextField
                  size="small"
                  label={`Valor da parte ${index + 1}`}
                  value={part.arg ?? ''}
                  onChange={(event) => update(index, { type: 'LITERAL', arg: event.target.value })}
                  sx={{ width: 220 }}
                />
              )}
              {!knownType && (
                <TextField
                  size="small"
                  label={`Argumento da parte ${index + 1}`}
                  value={part.arg ?? ''}
                  onChange={(event) => update(index, { type: part.type, arg: event.target.value || undefined })}
                  sx={{ width: 220 }}
                />
              )}

              <IconButton
                size="small"
                aria-label={`subir parte ${index + 1}`}
                disabled={index === 0}
                onClick={() => move(index, -1)}
              >
                <ArrowUpwardIcon fontSize="small" />
              </IconButton>
              <IconButton
                size="small"
                aria-label={`descer parte ${index + 1}`}
                disabled={index === parts.length - 1}
                onClick={() => move(index, 1)}
              >
                <ArrowDownwardIcon fontSize="small" />
              </IconButton>
              <IconButton
                size="small"
                color="error"
                aria-label={`remover parte ${index + 1}`}
                onClick={() => onChange(parts.filter((_, i) => i !== index))}
              >
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Stack>
          )
        })}
      </Stack>

      <Typography variant="body2" sx={{ mt: 1 }}>
        Chave: <code>{previewKey(parts)}</code>
      </Typography>
      <Typography variant="caption" color="text.secondary" component="div">
        EMITTER_ADDRESS só correlaciona eventos do mesmo emissor (use com PARAM quando o mesmo id se repete em contratos
        diferentes). Para correlacionar entre contratos, use partes que valham igual nos dois eventos (um PARAM com
        endereço, TX_HASH ou LITERAL); eventos que não carregam a chave não correlacionam entre contratos. Todos os
        contribuintes do modelo precisam ter o mesmo número de partes.
      </Typography>
    </Box>
  )
}
