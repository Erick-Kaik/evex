import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../../test/renderWithProviders'
import { BalanceMonitorFormPage } from './BalanceMonitorFormPage'

const { createBalanceMonitor, getBalanceMonitor, listMonitoredContracts, listContractEvents } = vi.hoisted(() => ({
  createBalanceMonitor: vi.fn(),
  getBalanceMonitor: vi.fn(),
  listMonitoredContracts: vi.fn(),
  listContractEvents: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    BalanceMonitorsApi: vi.fn(() => ({
      createBalanceMonitor,
      getBalanceMonitor,
      getGlobalBalanceBackfillStatus: vi.fn().mockResolvedValue({ data: { running: false } }),
    })),
    ContractsApi: vi.fn(() => ({ listMonitoredContracts, listContractEvents })),
  }
})

const CONFIG = {
  id: 'c1',
  contractAddress: '0xbbbb000000000000000000000000000000000002',
  network: 'TESTNET',
  eventName: 'TransferBatch',
  behavior: 'TRANSFER',
  fromParam: 'from',
  toParam: 'to',
  valueParam: 'values',
  tokenIdParam: 'ids',
  batch: true,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
}
const WARNING = "A flag batch=true é incompatível com a ABI das versões nas faixas de blocos 0..99 deste contrato; nessas faixas o evento 'TransferBatch' será descartado com WARN."

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/balances/monitors/new" element={<BalanceMonitorFormPage mode="create" />} />
      <Route path="/balances/monitors/:id" element={<BalanceMonitorFormPage mode="view" />} />
    </Routes>,
    { initialEntries: ['/balances/monitors/new'] },
  )
}

describe('BalanceMonitorFormPage — warnings e erro 400 (DEC-110)', () => {
  beforeEach(() => {
    window.localStorage.setItem('formMode:balance-monitor', 'manual')
    listMonitoredContracts.mockResolvedValue({ data: { page: 0, size: 200, totalElements: 0, totalPages: 1, content: [] } })
    listContractEvents.mockResolvedValue({ data: [] })
    getBalanceMonitor.mockResolvedValue({ data: CONFIG })
    createBalanceMonitor.mockReset()
  })

  async function fillAndSubmit() {
    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: 'Contrato' }), CONFIG.contractAddress)
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'TESTNET' }))
    await user.type(screen.getByRole('textbox', { name: 'Nome do evento' }), 'TransferBatch')
    await user.type(screen.getByRole('textbox', { name: 'Parâmetro da carteira' }), 'to')
    await user.type(screen.getByRole('textbox', { name: 'Parâmetro do montante/delta' }), 'values')
    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))
  }

  it('após o create, navega para o detalhe e exibe os warnings', async () => {
    createBalanceMonitor.mockResolvedValue({ data: { ...CONFIG, warnings: [WARNING] } })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Manual' }))
    await fillAndSubmit()

    expect(await screen.findByText('Configuração salva com avisos')).toBeInTheDocument()
    expect(screen.getByText(WARNING)).toBeInTheDocument()
    expect(screen.getByText('Configuração de saldo')).toBeInTheDocument()
  })

  it('sem warnings: navega sem alerta', async () => {
    createBalanceMonitor.mockResolvedValue({ data: CONFIG })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Manual' }))
    await fillAndSubmit()

    expect(await screen.findByText('Configuração de saldo')).toBeInTheDocument()
    expect(screen.queryByText('Configuração salva com avisos')).not.toBeInTheDocument()
  })

  it('400 INVALID_BALANCE_MONITOR_CONFIG: mostra a mensagem do backend e permanece no formulário', async () => {
    createBalanceMonitor.mockRejectedValue({
      isAxiosError: true,
      message: 'Request failed',
      response: { status: 400, data: { code: 'INVALID_BALANCE_MONITOR_CONFIG', message: 'batch=true incompatível com todas as versões da ABI.' } },
    })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Manual' }))
    await fillAndSubmit()

    expect(await screen.findByText('batch=true incompatível com todas as versões da ABI.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cadastrar' })).toBeInTheDocument()
  })
})
