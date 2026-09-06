import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ContractBalancesSection } from './ContractBalancesSection'

const { useBalancesMock, listWallets } = vi.hoisted(() => ({
  useBalancesMock: vi.fn(),
  listWallets: vi.fn(),
}))

vi.mock('../balances/useBalances', () => ({
  useBalances: useBalancesMock,
}))

// `AddressCell kind="wallet"` (E22) consulta o catálogo de carteiras pra resolver nome — sem
// carteira nenhuma cadastrada aqui, cai no fallback de endereço abreviado (comportamento anterior).
vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    WalletsApi: vi.fn(() => ({ listWallets })),
  }
})

const ADDRESS = '0xabc0000000000000000000000000000000000001'

describe('ContractBalancesSection', () => {
  beforeEach(() => {
    listWallets.mockReset()
    listWallets.mockResolvedValue({
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

    renderWithProviders(<ContractBalancesSection address={ADDRESS} />)

    expect(screen.getByText(/Nenhum saldo projetado ainda/i)).toBeInTheDocument()
    expect(useBalancesMock).toHaveBeenCalledWith({ contractAddress: ADDRESS }, 0, 10)
  })

  it('lista carteira/tokenId/saldo quando a consulta traz resultado', () => {
    useBalancesMock.mockReturnValue({
      data: {
        content: [
          { walletAddress: '0xwallet1', contractAddress: ADDRESS, tokenId: '1', balance: '1000' },
        ],
        page: 0,
        totalPages: 1,
        totalElements: 1,
      },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<ContractBalancesSection address={ADDRESS} />)

    expect(screen.getByText('0xwallet1')).toBeInTheDocument()
    expect(screen.getByText('1000')).toBeInTheDocument()
    expect(screen.queryByText(/Nenhum saldo projetado/i)).not.toBeInTheDocument()
  })
})
