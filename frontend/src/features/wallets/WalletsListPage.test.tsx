import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WalletsListPage } from './WalletsListPage'

const { useWallets, useWalletSuggestions, useUpdateWalletStatus, navigateMock } = vi.hoisted(() => ({
  useWallets: vi.fn(),
  useWalletSuggestions: vi.fn(),
  useUpdateWalletStatus: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('./useWallets', () => ({ useWallets, useWalletSuggestions, useUpdateWalletStatus }))

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock }
})

const WALLET = {
  id: 'w1',
  address: '0xaaa0000000000000000000000000000000000001',
  label: 'Carteira A',
  network: 'TESTNET',
  status: 'ACTIVE',
  looksLikeContract: false,
}

describe('WalletsListPage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    useUpdateWalletStatus.mockReturnValue({ isPending: false, mutate: vi.fn() })
    useWalletSuggestions.mockReturnValue({ isPending: false, isError: false, data: [] })
  })

  it('mostra o aviso de "nenhuma carteira" quando a listagem vem vazia', () => {
    useWallets.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletsListPage />)

    expect(screen.getByText(/Nenhuma carteira cadastrada ainda/i)).toBeInTheDocument()
  })

  it('lista carteiras cadastradas com endereço, label, rede e status', () => {
    useWallets.mockReturnValue({
      data: { content: [WALLET], page: 0, totalPages: 1, totalElements: 1 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletsListPage />)

    expect(screen.getByText('Carteira A')).toBeInTheDocument()
    expect(screen.getByText('TESTNET')).toBeInTheDocument()
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
  })

  it('navega para /wallets/new ao clicar em "Cadastrar"', async () => {
    const user = userEvent.setup()
    useWallets.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletsListPage />)

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))
    expect(navigateMock).toHaveBeenCalledWith('/wallets/new')
  })

  it('card "Carteiras sugeridas": lista endereços observados e navega com prefill (E22)', async () => {
    const user = userEvent.setup()
    useWallets.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })
    const suggested = '0xbbb0000000000000000000000000000000000002'
    useWalletSuggestions.mockReturnValue({ isPending: false, isError: false, data: [suggested] })

    renderWithProviders(<WalletsListPage />)

    expect(screen.getByText('Carteiras sugeridas')).toBeInTheDocument()
    expect(screen.getByText(suggested)).toBeInTheDocument()

    // 2 botões "Cadastrar" na tela: o do topo (cadastro em branco) e o da linha da sugestão.
    const cadastrarButtons = screen.getAllByRole('button', { name: 'Cadastrar' })
    await user.click(cadastrarButtons[cadastrarButtons.length - 1])
    expect(navigateMock).toHaveBeenCalledWith(`/wallets/new?address=${suggested}`)
  })

  it('não mostra o card de sugestões quando não há candidatos', () => {
    useWallets.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })
    useWalletSuggestions.mockReturnValue({ isPending: false, isError: false, data: [] })

    renderWithProviders(<WalletsListPage />)

    expect(screen.queryByText('Carteiras sugeridas')).not.toBeInTheDocument()
  })
})
