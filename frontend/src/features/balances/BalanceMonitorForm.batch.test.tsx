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
  address: '0xbbbb000000000000000000000000000000000002',
  name: 'Multi Token',
  network: 'TESTNET',
  status: 'ACTIVE',
}

const baseProps = { submitLabel: 'Cadastrar', isPending: false, isError: false, error: null }

async function pickContractAndEvent(user: ReturnType<typeof userEvent.setup>, eventName: string) {
  await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
  await user.click(await screen.findByRole('option', { name: /Multi Token/ }))
  await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
  await user.click(await screen.findByRole('option', { name: eventName }))
}

describe('BalanceMonitorForm — batch (DEC-110)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 1, totalPages: 1, content: [CONTRACT] },
    })
    listContractEvents.mockResolvedValue({
      data: [
        {
          name: 'TransferSingle',
          inputs: [
            { name: 'from', type: 'address', indexed: true },
            { name: 'to', type: 'address', indexed: true },
            { name: 'id', type: 'uint256', indexed: false },
            { name: 'value', type: 'uint256', indexed: false },
          ],
          validFromBlock: 0,
          validToBlock: null,
        },
        {
          name: 'TransferBatch',
          inputs: [
            { name: 'from', type: 'address', indexed: true },
            { name: 'to', type: 'address', indexed: true },
            { name: 'ids', type: 'uint256[]', indexed: false },
            { name: 'values', type: 'uint256[]', indexed: false },
          ],
          validFromBlock: 0,
          validToBlock: null,
        },
      ],
    })
  })

  it('guiado: evento só com arrays sugere batch, lista só arrays e envia batch=true', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={onSubmit} />)

    await pickContractAndEvent(user, 'TransferBatch')

    expect(screen.getByRole('checkbox', { name: /Lote/ })).toBeChecked()

    await user.click(screen.getByRole('combobox', { name: 'Parâmetro da carteira' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'to (address)' }))

    await user.click(screen.getByRole('combobox', { name: 'Parâmetro do montante/delta (array)' }))
    const options = within(await screen.findByRole('listbox')).getAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['ids (uint256[])', 'values (uint256[])'])
    await user.click(options[1])

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ eventName: 'TransferBatch', valueParam: 'values', batch: true })
  })

  it('guiado: evento escalar não marca batch, lista só escalares; o usuário pode desmarcar a sugestão', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={onSubmit} />)

    await pickContractAndEvent(user, 'TransferSingle')
    expect(screen.getByRole('checkbox', { name: /Lote/ })).not.toBeChecked()

    await user.click(screen.getByRole('combobox', { name: 'Parâmetro do montante/delta' }))
    const options = within(await screen.findByRole('listbox')).getAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['id (uint256)', 'value (uint256)'])
    await user.keyboard('{Escape}')

    // troca para o evento em lote (sugere) e desmarca manualmente: a escolha do usuário prevalece
    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'TransferBatch' }))
    expect(screen.getByRole('checkbox', { name: /Lote/ })).toBeChecked()
    await user.click(screen.getByRole('checkbox', { name: /Lote/ }))
    expect(screen.getByRole('checkbox', { name: /Lote/ })).not.toBeChecked()
    expect(screen.getAllByText(/Nenhum parâmetro deste evento tem o tipo esperado/).length).toBeGreaterThan(0)
  })

  it('visual: vincular array ao montante sugere batch e os slots ganham o sufixo "(array)"', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Visual' }))
    await pickContractAndEvent(user, 'TransferBatch')
    // o evento só com arrays já sugere batch → slots com sufixo
    expect(await screen.findByRole('button', { name: /Campo Montante\/delta \(array\)/ })).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: /Lote/ })) // usuário desmarca
    expect(screen.queryByRole('button', { name: /Campo Montante\/delta \(array\)/ })).not.toBeInTheDocument()

    // vincular um array com batch desmarcado pelo usuário NÃO religa sozinho (escolha manual prevalece)
    await user.click(screen.getByRole('button', { name: 'Parâmetro values, tipo uint256[]' }))
    await user.click(screen.getByRole('button', { name: /Campo Montante\/delta/ }))
    expect(screen.getByRole('checkbox', { name: /Lote/ })).not.toBeChecked()
    // ...e a validação de tipos avisa
    expect(screen.getByText(/é um array; marque "Lote"/)).toBeInTheDocument()
  })

  it('manual: checkbox simples envia batch no body', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<BalanceMonitorForm mode="create" {...baseProps} onSubmit={onSubmit} />)
    await user.click(screen.getByRole('button', { name: 'Manual' }))

    await user.type(screen.getByRole('textbox', { name: 'Contrato' }), CONTRACT.address)
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'TESTNET' }))
    await user.type(screen.getByRole('textbox', { name: 'Nome do evento' }), 'TransferBatch')
    await user.type(screen.getByRole('textbox', { name: 'Parâmetro da carteira' }), 'to')
    await user.type(screen.getByRole('textbox', { name: 'Parâmetro do montante/delta' }), 'values')
    await user.click(screen.getByRole('checkbox', { name: /Lote/ }))
    expect(screen.getByText(/ERC-1155 precisa de 2 configurações por contrato/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ valueParam: 'values', batch: true })
  })

  it('edição com batch=false e parâmetro array: avisa; marcar Lote resolve e permite enviar', async () => {
    const onSubmit = vi.fn()
    renderWithProviders(
      <BalanceMonitorForm
        mode="edit"
        {...baseProps}
        submitLabel="Salvar"
        initialValues={{
          contractAddress: CONTRACT.address,
          network: 'TESTNET',
          eventName: 'TransferBatch',
          behavior: 'CREDIT',
          walletParam: 'to',
          valueParam: 'values',
          batch: false,
        }}
        onSubmit={onSubmit}
      />,
    )
    expect(await screen.findByText(/"values" \(uint256\[\]\) é um array/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /Lote/ }))
    expect(screen.queryByText(/é um array/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ valueParam: 'values', batch: true })
  })

  it('view: mostra o chip "Lote" quando batch=true', () => {
    renderWithProviders(
      <BalanceMonitorForm
        mode="view"
        {...baseProps}
        initialValues={{
          contractAddress: CONTRACT.address,
          network: 'TESTNET',
          eventName: 'TransferBatch',
          behavior: 'TRANSFER',
          fromParam: 'from',
          toParam: 'to',
          valueParam: 'values',
          batch: true,
        }}
        onSubmit={vi.fn()}
      />,
    )
    expect(screen.getByText('Lote')).toBeInTheDocument()
  })
})
