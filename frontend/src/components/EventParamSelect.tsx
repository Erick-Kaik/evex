import { MenuItem, TextField } from '@mui/material'
import type { ContractEventInput } from '../api/api'
import { filterInputsByKind } from './contractCatalog'
import type { EventParamKind } from './contractCatalog'

interface EventParamSelectProps {
  /** Que tipo de parâmetro este slot aceita: `address`, `uint*`/`int*` (numeric) ou qualquer. */
  kind: EventParamKind
  /** `inputs` do evento selecionado (vindo do `<EventPicker>`). */
  inputs: ContractEventInput[]
  /** Nome do parâmetro escolhido. */
  value: string
  onChange: (paramName: string) => void
  label: string
  required?: boolean
  size?: 'small' | 'medium'
  disabled?: boolean
  sx?: object
}

/**
 * `Select` dos `inputs` de um evento cujo `type` Solidity casa com o `kind` do slot
 * (`address` → `address`; `numeric` → `/^u?int(\d+)?(\[\])?$/`; `any` → todos). O valor emitido é o
 * nome do parâmetro. Usado no modo guiado dos formulários de configuração (DEC-072).
 */
export function EventParamSelect({
  kind,
  inputs,
  value,
  onChange,
  label,
  required,
  size = 'small',
  disabled,
  sx,
}: EventParamSelectProps) {
  const options = filterInputsByKind(inputs, kind)
  const noMatch = inputs.length > 0 && options.length === 0

  return (
    <TextField
      select
      label={label}
      required={required}
      size={size}
      fullWidth
      sx={sx}
      disabled={disabled || inputs.length === 0}
      value={options.some((input) => input.name === value) ? value : ''}
      onChange={(event) => onChange(event.target.value)}
      helperText={
        noMatch ? 'Nenhum parâmetro deste evento tem o tipo esperado para este campo.' : undefined
      }
      error={noMatch}
    >
      {options.map((input) => (
        <MenuItem key={input.name} value={input.name}>
          {input.name} ({input.type})
        </MenuItem>
      ))}
    </TextField>
  )
}
