import { Box, TextField, Typography } from '@mui/material'
import type { PageMode } from './pageMode'

interface LabeledFieldProps {
  label: string
  value: string
  mode: PageMode
  onChange?: (value: string) => void
  required?: boolean
  multiline?: boolean
  minRows?: number
  placeholder?: string
  /** Fonte monoespaçada — usado para endereços/hashes. */
  monospace?: boolean
  fullWidth?: boolean
}

/**
 * Campo de formulário com 2 aparências (padrão E18, `PageMode`): em `view` mostra rótulo + valor como
 * texto simples (read-only, sem `<TextField>` desabilitado); em `edit`/`create` mostra um
 * `<TextField>` editável de verdade. Usado pelos 3 módulos de cadastro (Contratos, Configurações de
 * saldo, Webhooks) para manter a mesma aparência de campo entre eles.
 */
export function LabeledField({
  label,
  value,
  mode,
  onChange,
  required,
  multiline,
  minRows,
  placeholder,
  monospace,
  fullWidth = true,
}: LabeledFieldProps) {
  if (mode === 'view') {
    return (
      <Box>
        <Typography variant="caption" color="text.secondary" component="div">
          {label}
        </Typography>
        <Typography
          variant="body1"
          sx={{
            fontFamily: monospace ? 'monospace' : undefined,
            whiteSpace: multiline ? 'pre-wrap' : undefined,
          }}
        >
          {value || '—'}
        </Typography>
      </Box>
    )
  }

  return (
    <TextField
      label={label}
      required={required}
      fullWidth={fullWidth}
      multiline={multiline}
      minRows={minRows}
      value={value}
      placeholder={placeholder}
      onChange={(event) => onChange?.(event.target.value)}
      slotProps={monospace ? { htmlInput: { style: { fontFamily: 'monospace' } } } : undefined}
    />
  )
}
