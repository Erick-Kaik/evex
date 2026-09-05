import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { BalanceMonitorForm } from './BalanceMonitorForm'

const { listMonitoredContracts, listContractEvents } = vi.hoisted(() => ({
  listMonitoredContracts: vi.fn(),
  listContractEvents: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts, listContractEvents })),
  }
})

const CONTRACT = {
  id: '1',
  address: '0xaaa0000000000000000000000000000000000001',
  name: 'Token A',
  network: 'TESTNET',
  status: 'ACTIVE',
}

const baseProps = {
  submitLabel: 'Cadastrar',
  isPending: false,
  isError: false,
  error: null,
}

describe('BalanceMonitorForm', () => {
  beforeEach(() => {
    window.localStorage.clear()
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 500, totalElements: 1, totalPages: 1, content: [CONTRACT] },
    })
    listContractEvents.mockResolvedValue({
      data: [
        {
          name: 'Transfer',
          inputs: [
            { name: 'from', type: 'address', indexed: true },
            { name: 'to', type: 'address', indexed: true },
            { name: 'value', type: 'uint256', indexed: false },
          ],
          validFromBlock: 0,
          validToBlock: null,
        },
      ],
    })
  })

  it('modo manual: mantém o form de texto livre + rede e monta o payload (sem regressão)', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('button', { name: 'Manual' }))

    await user.type(screen.getByRole('textbox', { name: 'Contrato' }), '0xdeadbeef00000000000000000000000000000001')

    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'TESTNET' }))

    await user.type(screen.getByRole('textbox', { name: 'Nome do evento' }), 'Transfer')
    await user.type(screen.getByRole('textbox', { name: 'Parâmetro da carteira' }), 'to')
    await user.type(screen.getByRole('textbox', { name: 'Parâmetro do montante/delta' }), 'value')

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: '0xdeadbeef00000000000000000000000000000001',
      network: 'TESTNET',
      eventName: 'Transfer',
      behavior: 'CREDIT',
      walletParam: 'to',
      valueParam: 'value',
    })
  })

  it('modo visual: canvas de arrastar-para-vincular monta o mesmo payload', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('button', { name: 'Visual' }))
    expect(screen.queryByRole('combobox', { name: 'Rede' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))

    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))

    // vincula via fallback de clique: seleciona o chip, clica no slot compatível
    await user.click(await screen.findByRole('button', { name: 'Parâmetro to, tipo address' }))
    await user.click(screen.getByRole('button', { name: /Campo Carteira/ }))

    await user.click(screen.getByRole('button', { name: 'Parâmetro value, tipo uint256' }))
    await user.click(screen.getByRole('button', { name: /Campo Montante\/delta/ }))

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: CONTRACT.address,
      network: 'TESTNET',
      eventName: 'Transfer',
      behavior: 'CREDIT',
      walletParam: 'to',
      valueParam: 'value',
    })
  })

  it('honra o modo "visual" persistido em localStorage ao montar (DEC-072)', async () => {
    window.localStorage.setItem('formMode:balance-monitor', 'visual')
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={vi.fn()} />)

    expect(await screen.findByRole('button', { name: 'Visual', pressed: true })).toBeInTheDocument()

    // seleciona contrato + evento e confirma que o canvas (exclusivo do modo visual) renderiza
    await userEvent.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await userEvent.click(await screen.findByRole('option', { name: /Token A/ }))
    await userEvent.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Transfer' }))
    expect(await screen.findByRole('button', { name: /Campo Carteira/ })).toBeInTheDocument()
  })

  it('modo guiado: contrato→evento→parâmetros em cascata, sem <NetworkSelect>, mesmo payload', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={onSubmit} />)

    // guiado é o default
    expect(screen.queryByRole('combobox', { name: 'Rede' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))

    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))

    await user.click(screen.getByRole('combobox', { name: 'Parâmetro da carteira' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'to (address)' }))

    await user.click(screen.getByRole('combobox', { name: 'Parâmetro do montante/delta' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'value (uint256)' }))

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: CONTRACT.address,
      network: 'TESTNET',
      eventName: 'Transfer',
      behavior: 'CREDIT',
      walletParam: 'to',
      valueParam: 'value',
    })
  })

  it('modo view: mostra os campos como texto, sem controles editáveis nem botão de salvar', () => {
    renderWithProviders(
      <BalanceMonitorForm
        mode="view"
        {...baseProps}
        initialValues={{
          contractAddress: CONTRACT.address,
          network: 'TESTNET',
          eventName: 'Transfer',
          behavior: 'CREDIT',
          walletParam: 'to',
          valueParam: 'value',
        }}
        onSubmit={vi.fn()}
      />,
    )

    expect(screen.getByText('Contrato')).toBeInTheDocument()
    expect(screen.getByText(CONTRACT.address)).toBeInTheDocument()
    expect(screen.getByText('Transfer')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cadastrar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Salvar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})
