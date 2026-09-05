import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { EventsPage } from './EventsPage'

const { useEvents, listMonitoredContracts, listContractEvents } = vi.hoisted(() => ({
  useEvents: vi.fn(),
  listMonitoredContracts: vi.fn(),
  listContractEvents: vi.fn(),
}))

vi.mock('./useEvents', () => ({ useEvents }))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts, listContractEvents })),
  }
})

const KNOWN = '0xaaa0000000000000000000000000000000000001'
const UNKNOWN = '0xbbb0000000000000000000000000000000000002'

const events = [
  {
    id: 'e1',
    contractAddress: KNOWN,
    network: 'TESTNET',
    blockNumber: 10,
    blockHash: '0xhash',
    txHash: '0xtx1',
    logIndex: 0,
    decoded: true,
    eventName: 'Transfer',
    blockTimestamp: null,
    decodedParameters: {},
  },
  {
    id: 'e2',
    contractAddress: UNKNOWN,
    network: 'TESTNET',
    blockNumber: 11,
    blockHash: '0xhash',
    txHash: '0xtx2',
    logIndex: 1,
    decoded: false,
    blockTimestamp: null,
  },
]

describe('EventsPage', () => {
  beforeEach(() => {
    useEvents.mockReturnValue({
      data: { page: 0, size: 20, totalElements: 2, totalPages: 1, content: events },
      isPending: false,
      isError: false,
    })
    listMonitoredContracts.mockResolvedValue({
      data: {
        page: 0,
        size: 500,
        totalElements: 1,
        totalPages: 1,
        content: [{ id: '1', address: KNOWN, name: 'Token A', network: 'TESTNET', status: 'ACTIVE' }],
      },
    })
  })

  it('mostra a coluna "Contrato" com nome+link pros monitorados e endereço curto pros demais', async () => {
    renderWithProviders(<EventsPage />)

    const headerRow = screen.getAllByRole('row')[0]
    expect(within(headerRow).getByText('Contrato')).toBeInTheDocument()

    const link = await screen.findByRole('link', { name: 'Token A' })
    expect(link).toHaveAttribute('href', `/contracts/${KNOWN}`)

    // contrato não monitorado → endereço encurtado
    expect(screen.getByText('0xbbb000…0002')).toBeInTheDocument()
  })

  it('rotula evento raw como "sem ABI" com tooltip explicativo', async () => {
    const user = userEvent.setup()
    renderWithProviders(<EventsPage />)

    const chip = await screen.findByText('sem ABI')
    expect(chip).toBeInTheDocument()

    await user.hover(chip)
    expect(await screen.findByText(/ainda não decodificado — cadastre a ABI/i)).toBeInTheDocument()
  })

  it('permite digitar um endereço de contrato fora da lista de monitorados (não-bloqueante 1)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<EventsPage />)

    await user.click(screen.getByRole('button', { name: /digitar endereço manualmente/i }))

    const addressField = screen.getByRole('textbox', { name: 'Contrato' })
    await user.type(addressField, UNKNOWN)
    // segue sendo texto livre pro nome do evento (sem catálogo pra endereço não monitorado)
    expect(screen.getByRole('textbox', { name: 'Nome do evento' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Nome do evento' })).not.toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: 'Nome do evento' }), 'Transfer')
    await user.click(screen.getByRole('button', { name: 'Filtrar' }))

    expect(useEvents).toHaveBeenLastCalledWith(
      expect.objectContaining({ contractAddress: UNKNOWN, eventName: 'Transfer' }),
      0,
      20,
    )
  })

  it('usa <EventPicker> quando há contrato selecionado no filtro', async () => {
    listContractEvents.mockResolvedValue({
      data: [{ name: 'Transfer', inputs: [], validFromBlock: 0, validToBlock: null }],
    })
    const user = userEvent.setup()
    renderWithProviders(<EventsPage />)

    // sem contrato: campo de texto livre
    expect(screen.getByRole('textbox', { name: 'Nome do evento' })).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))

    // agora o nome do evento é um combobox (EventPicker)
    expect(await screen.findByRole('combobox', { name: 'Nome do evento' })).toBeInTheDocument()
    expect(listContractEvents).toHaveBeenCalledWith(KNOWN)
  })
})
