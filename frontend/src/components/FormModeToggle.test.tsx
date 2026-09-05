import { useState } from 'react'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FormModeToggle } from './FormModeToggle'
import { readFormMode } from './formMode'
import type { FormMode } from './formMode'

function Harness({ storageKey, modes }: { storageKey: string; modes?: FormMode[] }) {
  const [mode, setMode] = useState<FormMode>(() => readFormMode(storageKey, modes))
  return (
    <>
      <span data-testid="mode">{mode}</span>
      <FormModeToggle storageKey={storageKey} value={mode} onChange={setMode} modes={modes} />
    </>
  )
}

describe('FormModeToggle', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renderiza os modos default (Guiado / Manual)', () => {
    render(<Harness storageKey="k1" />)
    expect(screen.getByRole('button', { name: 'Guiado' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Manual' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Visual' })).not.toBeInTheDocument()
  })

  it('aceita uma lista de modos custom (passada B adiciona "visual")', () => {
    render(<Harness storageKey="k2" modes={['guided', 'manual', 'visual']} />)
    expect(screen.getByRole('button', { name: 'Visual' })).toBeInTheDocument()
  })

  it('persiste a escolha em localStorage com a chave formMode:<key>', async () => {
    const user = userEvent.setup()
    render(<Harness storageKey="balance-monitor" />)

    await user.click(screen.getByRole('button', { name: 'Manual' }))

    expect(screen.getByTestId('mode')).toHaveTextContent('manual')
    expect(window.localStorage.getItem('formMode:balance-monitor')).toBe('manual')
  })

  it('readFormMode lê o valor persistido e ignora valor inválido', () => {
    window.localStorage.setItem('formMode:x', 'manual')
    expect(readFormMode('x')).toBe('manual')

    window.localStorage.setItem('formMode:x', 'lixo')
    expect(readFormMode('x')).toBe('guided')

    expect(readFormMode('nunca-salvo')).toBe('guided')
  })

  it('tolera localStorage lançando (setItem e getItem)', async () => {
    const user = userEvent.setup()
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })

    expect(readFormMode('k3')).toBe('guided')

    render(<Harness storageKey="k3" />)
    await user.click(screen.getByRole('button', { name: 'Manual' }))

    // não lançou; o modo mudou em memória mesmo sem conseguir persistir
    expect(screen.getByTestId('mode')).toHaveTextContent('manual')
    expect(setItem).toHaveBeenCalled()
    getItem.mockRestore()
  })
})
