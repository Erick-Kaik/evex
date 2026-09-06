import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WalletFormPage } from './WalletFormPage'

const {
  useWallet,
  useCreateWallet,
  useUpdateWallet,
  useUpdateWalletStatus,
  useAcknowledgeContractWarning,
  useBalancesMock,
  navigateMock,
  useParamsMock,
} = vi.hoisted(() => ({
  useWallet: vi.fn(),
  useCreateWallet: vi.fn(),
  useUpdateWallet: vi.fn(),
  useUpdateWalletStatus: vi.fn(),
  useAcknowledgeContractWarning: vi.fn(),
  useBalancesMock: vi.fn(),
  navigateMock: vi.fn(),
  useParamsMock: vi.fn(),
}))

vi.mock('./useWallets', () => ({
  useWallet,
  useCreateWallet,
  useUpdateWallet,
  useUpdateWalletStatus,
  useAcknowledgeContractWarning,
}))

vi.mock('../balances/useBalances', () => ({ useBalances: useBalancesMock }))

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock, useParams: useParamsMock }
})

const WALLET = {
  id: 'w1',
  address: '0xaaa0000000000000000000000000000000000001',
  label: 'Carteira A',
  description: 'uma descrição',
  network: 'TESTNET',
  status: 'ACTIVE',
  looksLikeContract: false,
  looksLikeContractAcknowledged: false,
}

describe('WalletFormPage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    useBalancesMock.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })
    useUpdateWalletStatus.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
    useUpdateWallet.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
    useAcknowledgeContractWarning.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
  })

  it('modo create: monta o CreateWalletPayload e navega pro detalhe ao cadastrar', async () => {
    useParamsMock.mockReturnValue({})
    const mutate = vi.fn((_payload, options) => options?.onSuccess?.(WALLET))
    useCreateWallet.mockReturnValue({ isPending: false, isError: false, mutate })

    const user = userEvent.setup()
    renderWithProviders(<WalletFormPage mode="create" />)

    await user.type(screen.getByRole('textbox', { name: 'Endereço' }), WALLET.address)
    await user.type(screen.getByRole('textbox', { name: 'Label' }), WALLET.label)
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'TESTNET' }))
    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(mutate).toHaveBeenCalledWith(
      { address: WALLET.address, label: WALLET.label, description: undefined, network: 'TESTNET' },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    )
    expect(navigateMock).toHaveBeenCalledWith(`/wallets/${WALLET.address}`)
  })

  it('modo view: mostra dados da carteira e o aviso looksLikeContract quando aplicável', async () => {
    useParamsMock.mockReturnValue({ address: WALLET.address })
    useCreateWallet.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
    useWallet.mockReturnValue({
      data: { ...WALLET, looksLikeContract: true },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletFormPage mode="view" />)

    expect(await screen.findByRole('heading', { name: 'Carteira A' })).toBeInTheDocument()
    expect(screen.getByText(/tem código de contrato/i)).toBeInTheDocument()
  })

  it('modo view: o botão "Ciente, não mostrar de novo" confirma o aviso', async () => {
    useParamsMock.mockReturnValue({ address: WALLET.address })
    useCreateWallet.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
    useWallet.mockReturnValue({
      data: { ...WALLET, looksLikeContract: true },
      isPending: false,
      isError: false,
      error: undefined,
    })
    const mutate = vi.fn()
    useAcknowledgeContractWarning.mockReturnValue({ isPending: false, isError: false, mutate })

    const user = userEvent.setup()
    renderWithProviders(<WalletFormPage mode="view" />)

    await user.click(await screen.findByRole('button', { name: /ciente, não mostrar de novo/i }))

    expect(mutate).toHaveBeenCalled()
  })

  it('modo view: não mostra o aviso quando já foi confirmado (looksLikeContractAcknowledged)', async () => {
    useParamsMock.mockReturnValue({ address: WALLET.address })
    useCreateWallet.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
    useWallet.mockReturnValue({
      data: { ...WALLET, looksLikeContract: true, looksLikeContractAcknowledged: true },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletFormPage mode="view" />)

    expect(await screen.findByRole('heading', { name: 'Carteira A' })).toBeInTheDocument()
    expect(screen.queryByText(/tem código de contrato/i)).not.toBeInTheDocument()
  })

  it('modo view: não mostra o aviso quando looksLikeContract é falso', async () => {
    useParamsMock.mockReturnValue({ address: WALLET.address })
    useCreateWallet.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
    useWallet.mockReturnValue({
      data: WALLET,
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<WalletFormPage mode="view" />)

    expect(await screen.findByRole('heading', { name: 'Carteira A' })).toBeInTheDocument()
    expect(screen.queryByText(/tem código de contrato/i)).not.toBeInTheDocument()
  })

  it('modo edit: envia label/descrição atualizados', async () => {
    useParamsMock.mockReturnValue({ address: WALLET.address })
    useCreateWallet.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
    useWallet.mockReturnValue({
      data: WALLET,
      isPending: false,
      isError: false,
      error: undefined,
    })
    const mutate = vi.fn((_payload, options) => options?.onSuccess?.())
    useUpdateWallet.mockReturnValue({ isPending: false, isError: false, mutate })

    const user = userEvent.setup()
    renderWithProviders(<WalletFormPage mode="edit" />)

    const labelField = await screen.findByRole('textbox', { name: 'Label' })
    await user.clear(labelField)
    await user.type(labelField, 'Carteira Renomeada')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(mutate).toHaveBeenCalledWith(
      { label: 'Carteira Renomeada', description: WALLET.description },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    )
    expect(navigateMock).toHaveBeenCalledWith(`/wallets/${WALLET.address}`)
  })
})
