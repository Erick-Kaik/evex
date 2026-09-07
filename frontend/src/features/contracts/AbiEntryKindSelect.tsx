import { FormControl, InputLabel, MenuItem, Select } from '@mui/material'
import type { SelectChangeEvent } from '@mui/material'
import { AbiEntryKind } from '../../api/api'

interface AbiEntryKindSelectProps {
  value: AbiEntryKind | ''
  onChange: (value: AbiEntryKind | '') => void
  id?: string
  label?: string
  size?: 'small' | 'medium'
}

const KIND_OPTIONS: AbiEntryKind[] = [
  AbiEntryKind.Function,
  AbiEntryKind.Event,
  AbiEntryKind.Error,
  AbiEntryKind.Constructor,
  AbiEntryKind.Fallback,
  AbiEntryKind.Receive,
]

/**
 * `<Select>` de filtro por `kind` do Explorador de ABI (E24) — mesmo padrão do `<NetworkSelect>`
 * (combobox simples com opção "Todos"), específico o bastante (enum de 1 endpoint só) para não
 * justificar promoção a `components/` ainda (DEC-084, alternativa 4 rejeitada por falta de 2º
 * consumidor — mesmo racional aqui).
 */
export function AbiEntryKindSelect({
  value,
  onChange,
  id = 'abi-entry-kind-select',
  label = 'Tipo',
  size = 'small',
}: AbiEntryKindSelectProps) {
  function handleChange(event: SelectChangeEvent) {
    onChange(event.target.value as AbiEntryKind | '')
  }

  return (
    <FormControl size={size} sx={{ minWidth: 160 }}>
      <InputLabel id={`${id}-label`}>{label}</InputLabel>
      <Select labelId={`${id}-label`} id={id} label={label} value={value} onChange={handleChange}>
        <MenuItem value="">Todos</MenuItem>
        {KIND_OPTIONS.map((kind) => (
          <MenuItem key={kind} value={kind}>
            {kind}
          </MenuItem>
        ))}
      </Select>
    </FormControl>
  )
}
