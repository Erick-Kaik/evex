import type { MouseEvent } from 'react'
import { ToggleButton, ToggleButtonGroup } from '@mui/material'
import { DEFAULT_FORM_MODES, FORM_MODE_LABELS, persistFormMode } from './formMode'
import type { FormMode } from './formMode'

interface FormModeToggleProps {
  /** Chave de persistência (`formMode:<storageKey>` no `localStorage`). */
  storageKey: string
  value: FormMode
  onChange: (mode: FormMode) => void
  /** Modos oferecidos, na ordem. Default `['guided','manual']` — a passada B acrescenta `'visual'`. */
  modes?: FormMode[]
}

/**
 * `ToggleButtonGroup` "Guiado / Manual" (/ "Visual" na passada B). Persiste a escolha em
 * `localStorage` a cada troca. O valor inicial deve vir de `readFormMode(storageKey, modes)` no
 * `useState` do pai — assim o componente fica controlado e o pai já nasce com o modo certo.
 */
export function FormModeToggle({
  storageKey,
  value,
  onChange,
  modes = DEFAULT_FORM_MODES,
}: FormModeToggleProps) {
  function handleChange(_event: MouseEvent<HTMLElement>, next: FormMode | null) {
    if (next === null) {
      return
    }
    persistFormMode(storageKey, next)
    onChange(next)
  }

  return (
    <ToggleButtonGroup exclusive size="small" value={value} onChange={handleChange} aria-label="Modo do formulário">
      {modes.map((mode) => (
        <ToggleButton key={mode} value={mode}>
          {FORM_MODE_LABELS[mode]}
        </ToggleButton>
      ))}
    </ToggleButtonGroup>
  )
}
