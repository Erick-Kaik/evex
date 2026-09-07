import { Box, Button, Stack, Typography } from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import type { EnrichmentCall, Network } from '../../api/api'
import { EnrichmentCallRow } from './EnrichmentCallRow'

interface EnrichmentCallListEditorProps {
  calls: EnrichmentCall[]
  network: Network
  onChange: (calls: EnrichmentCall[]) => void
}

const EMPTY_CALL: EnrichmentCall = { targetContractAddress: '', methodName: '', args: [], outputs: [] }

/**
 * Lista ordenada de `EnrichmentCall` de um Perfil de Enriquecimento (E19/DEC-082) — a ordem importa
 * (encadeamento entre chamadas via `{{enriched.y}}`, resolvido sequencialmente no dispatch).
 */
export function EnrichmentCallListEditor({ calls, network, onChange }: EnrichmentCallListEditorProps) {
  function addCall() {
    onChange([...calls, { ...EMPTY_CALL }])
  }

  function updateCall(index: number, next: EnrichmentCall) {
    onChange(calls.map((call, i) => (i === index ? next : call)))
  }

  function removeCall(index: number) {
    onChange(calls.filter((_, i) => i !== index))
  }

  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="subtitle2">Chamadas de leitura</Typography>
        <Button size="small" startIcon={<AddIcon />} onClick={addCall}>
          adicionar chamada
        </Button>
      </Stack>

      {calls.length === 0 && (
        <Typography variant="caption" color="text.secondary">
          Nenhuma chamada configurada — adicione ao menos uma.
        </Typography>
      )}

      <Stack sx={{ gap: 2, mt: 1 }}>
        {calls.map((call, index) => (
          <EnrichmentCallRow
            key={index}
            index={index}
            call={call}
            network={network}
            onChange={updateCall}
            onRemove={removeCall}
          />
        ))}
      </Stack>
    </Box>
  )
}
