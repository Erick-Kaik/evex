import { MenuItem, Stack, TextField, Typography } from '@mui/material'
import type { BlockModeValue } from './blockMode'
import { BLOCK_MODES, BLOCK_MODE_HELP, BLOCK_MODE_LABELS, timezoneLabel } from './resendUtils'
import type { BlockMode } from './resendUtils'

interface EnrichmentBlockModeFieldsProps {
  value: BlockModeValue
  onChange: (value: BlockModeValue) => void
}

/** Modo de bloco do enriquecimento: select + campos condicionais + ajuda honesta sobre cada modo. */
export function EnrichmentBlockModeFields({ value, onChange }: EnrichmentBlockModeFieldsProps) {
  return (
    <Stack spacing={1}>
      <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <TextField
          select
          size="small"
          label="Bloco do enriquecimento"
          value={value.mode}
          onChange={(event) => onChange({ ...value, mode: event.target.value as BlockMode })}
          sx={{ minWidth: 260 }}
        >
          {BLOCK_MODES.map((mode) => (
            <MenuItem key={mode} value={mode}>
              {BLOCK_MODE_LABELS[mode]}
            </MenuItem>
          ))}
        </TextField>
        {value.mode === 'BLOCK_NUMBER' && (
          <TextField
            size="small"
            label="Número do bloco"
            value={value.blockNumber}
            onChange={(event) => onChange({ ...value, blockNumber: event.target.value })}
            slotProps={{ htmlInput: { inputMode: 'numeric' } }}
          />
        )}
        {value.mode === 'BLOCK_AT_DATE' && (
          <TextField
            size="small"
            type="datetime-local"
            label="Data/hora do bloco"
            value={value.blockDate}
            onChange={(event) => onChange({ ...value, blockDate: event.target.value })}
            helperText={`Fuso: ${timezoneLabel()}`}
            slotProps={{ inputLabel: { shrink: true } }}
          />
        )}
      </Stack>
      <Typography variant="caption" color="text.secondary">
        {BLOCK_MODE_HELP[value.mode]}
      </Typography>
    </Stack>
  )
}
