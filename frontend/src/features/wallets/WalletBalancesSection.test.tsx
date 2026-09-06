import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WalletBalancesSection } from './WalletBalancesSection'

const { useBalancesMock, listMonitoredContracts } = vi.hoisted(() => ({
  useBalancesMock: vi.fn(),
  listMonitoredContracts: vi.fn(),
}))

vi.mock('../balances/useBalances', () => ({
  useBalances: useBalancesMock,
}))

// `AddressCell kind="contract"` (E22) consulta o catálogo de contratos pra resolver nome — sem
// contrato nenhum cadastrado aqui, cai no fallback de endereço abreviado.
vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts })),
  }
})

const ADDRESS = '0xabc0000000000000000000000000000000000001'

describe('WalletBalancesSection', () => {
  beforeEach(() => {
    listMonitoredContracts.mockReset()
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 0, totalPages: 0, content: [] },
    })
  })

  it('mostra o aviso de "nenhum saldo" quando a consulta vem vazia', () => {
    useBalancesMock.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletBalancesSection address={ADDRESS} />)

    expect(screen.getByText(/Nenhum saldo projetado ainda/i)).toBeInTheDocument()
    expect(useBalancesMock).toHaveBeenCalledWith({ walletAddress: ADDRESS }, 0, 10)
  })

  it('lista contrato/tokenId/saldo quando a consulta traz resultado', () => {
    useBalancesMock.mockReturnValue({
      data: {
        content: [
          { walletAddress: ADDRESS, contractAddress: '0xcontract1', tokenId: '1', balance: '1000' },
        ],
        page: 0,
        totalPages: 1,
        totalElements: 1,
      },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletBalancesSection address={ADDRESS} />)

    expect(screen.getByText('0xcontract1')).toBeInTheDocument()
    expect(screen.getByText('1000')).toBeInTheDocument()
    expect(screen.queryByText(/Nenhum saldo projetado/i)).not.toBeInTheDocument()
  })
})
