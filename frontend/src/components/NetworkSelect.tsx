import { FormControl, InputLabel, MenuItem, Select } from '@mui/material'
import type { SelectChangeEvent } from '@mui/material'
import { Network } from '../api/api'

interface NetworkSelectProps {
  value: Network | ''
  onChange: (value: Network | '') => void
  /** `true` = inclui a opção "Todas" (uso em filtro); `false` = seleção obrigatória (uso em formulário). */
  allowEmpty?: boolean
  id?: string
  label?: string
  required?: boolean
  size?: 'small' | 'medium'
}

/** `<Select>` MAINNET/TESTNET reaproveitado em filtros e formulários. */
export function NetworkSelect({
  value,
  onChange,
  allowEmpty = false,
  id = 'network-select',
  label = 'Rede',
  required,
  size = 'small',
}: NetworkSelectProps) {
  function handleChange(event: SelectChangeEvent) {
    onChange(event.target.value as Network | '')
  }

  return (
    <FormControl size={size} required={required} sx={{ minWidth: 160 }}>
      <InputLabel id={`${id}-label`}>{label}</InputLabel>
      <Select labelId={`${id}-label`} id={id} label={label} value={value} onChange={handleChange}>
        {allowEmpty && <MenuItem value="">Todas</MenuItem>}
        <MenuItem value={Network.Mainnet}>MAINNET</MenuItem>
        <MenuItem value={Network.Testnet}>TESTNET</MenuItem>
      </Select>
    </FormControl>
  )
}
