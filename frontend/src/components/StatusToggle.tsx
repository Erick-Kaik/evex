import { useState } from 'react'
import type { MouseEvent } from 'react'
import { Chip, Menu, MenuItem } from '@mui/material'
import type { ChipProps } from '@mui/material'

export interface StatusOption<T> {
  value: T
  label: string
  color: ChipProps['color']
}

interface StatusToggleProps<T> {
  value: T
  options: StatusOption<T>[]
  onChange: (value: T) => void
  disabled?: boolean
  size?: 'small' | 'medium'
}

/**
 * Ícone de status clicável (padrão E18) — um `Chip` colorido conforme o valor atual que abre um menu
 * com as opções possíveis; escolher uma diferente da atual dispara `onChange` (toggle rápido, sem
 * navegar pra outra tela). Serve tanto o enum de 3 estados de Contrato (`ACTIVE`/`PAUSED`/`ARCHIVED`)
 * quanto o booleano `active` de Configuração de saldo/Webhook (2 opções) — só o número de opções
 * muda entre os módulos, a mecânica é a mesma. Usado na listagem (coluna Status) e no detalhe.
 */
export function StatusToggle<T>({ value, options, onChange, disabled, size = 'small' }: StatusToggleProps<T>) {
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null)
  const current = options.find((option) => option.value === value)

  function handleOpen(event: MouseEvent<HTMLElement>) {
    event.stopPropagation()
    if (!disabled) {
      setAnchorEl(event.currentTarget)
    }
  }

  function handleClose() {
    setAnchorEl(null)
  }

  function handleSelect(next: T) {
    handleClose()
    if (next !== value) {
      onChange(next)
    }
  }

  return (
    <>
      <Chip
        size={size}
        label={current?.label ?? String(value)}
        color={current?.color ?? 'default'}
        onClick={handleOpen}
        disabled={disabled}
      />
      <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={handleClose}>
        {options.map((option) => (
          <MenuItem
            key={String(option.value)}
            selected={option.value === value}
            onClick={() => handleSelect(option.value)}
          >
            {option.label}
          </MenuItem>
        ))}
      </Menu>
    </>
  )
}
