import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ContractFormPage } from './ContractFormPage'

const {
  useContract,
  useCreateContract,
  useUpdateContract,
  useUpdateContractStatus,
  useVerifiedContracts,
  navigateMock,
  useParamsMock,
  listMonitoredContracts,
} = vi.hoisted(() => ({
  useContract: vi.fn(),
  useCreateContract: vi.fn(),
  useUpdateContract: vi.fn(),
  useUpdateContractStatus: vi.fn(),
  useVerifiedContracts: vi.fn(),
  navigateMock: vi.fn(),
  useParamsMock: vi.fn(),
  listMonitoredContracts: vi.fn(),
}))

vi.mock('./useContracts', () => ({
  useContract,
  useCreateContract,
  useUpdateContract,
  useUpdateContractStatus,
  useVerifiedContracts,
}))

// Sub-seções da tela de detalhe (ABI, saldos, linhagem) têm suas próprias suítes — aqui elas só
// precisam não quebrar o render de `ContractViewEditForm` nos testes de `edit`/`view`.
vi.mock('./AbiSection', () => ({ AbiSection: () => null }))
vi.mock('./ContractBalancesSection', () => ({ ContractBalancesSection: () => null }))
vi.mock('./ContractLineageSection', () => ({ ContractLineageSection: () => null }))

// `<ContractAutocomplete>` (usado só quando "é um redeploy" é marcado, no modo `create`) consulta
// `GET /v1/contracts` via `contractCatalog.ts`, que instancia seu próprio `ContractsApi` — mesmo
// padrão de mock de `ContractAutocomplete.test.tsx`/`ContractBalancesSection.test.tsx`.
vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts })),
  }
})

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock, useParams: useParamsMock }
})

const ADDRESS = '0xaaa0000000000000000000000000000000000001'
const PREDECESSOR_ADDRESS = '0xbbb0000000000000000000000000000000000002'

const CONTRACT = {
  id: 'c1',
  address: ADDRESS,
  name: 'Token A',
  description: undefined,
  version: undefined,
  network: 'TESTNET',
  status: 'ACTIVE',
  lineageId: null,
}

describe('ContractFormPage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    useVerifiedContracts.mockReturnValue({
      data: { contracts: [], unavailable: true },
      isPending: false,
      isError: false,
      error: undefined,
    })
    listMonitoredContracts.mockReset()
    listMonitoredContracts.mockResolvedValue({
      data: {
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
        content: [
          { id: 'c0', address: PREDECESSOR_ADDRESS, name: 'Token A v1', network: 'TESTNET', status: 'ACTIVE' },
        ],
      },
    })
  })

  describe('modo create', () => {
    beforeEach(() => {
      useParamsMock.mockReturnValue({})
    })

    it('mostra o campo de redeploy, escondido por padrão', () => {
      useCreateContract.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })

      renderWithProviders(<ContractFormPage mode="create" />)

      expect(screen.getByText('Isto é um redeploy de um contrato existente?')).toBeInTheDocument()
      expect(screen.queryByRole('combobox', { name: 'Contrato predecessor' })).not.toBeInTheDocument()
    })

    it('ao marcar o checkbox, mostra o autocomplete de predecessor e manda predecessorAddress no payload', async () => {
      const mutate = vi.fn((_payload, options) => options?.onSuccess?.(CONTRACT))
      useCreateContract.mockReturnValue({ isPending: false, isError: false, mutate })

      const user = userEvent.setup()
      renderWithProviders(<ContractFormPage mode="create" />)

      await user.type(screen.getByRole('textbox', { name: 'Endereço' }), ADDRESS)
      await user.type(screen.getByRole('textbox', { name: 'Nome' }), 'Token A')
      await user.click(screen.getByRole('combobox', { name: 'Rede' }))
      await user.click(await screen.findByRole('option', { name: 'TESTNET' }))

      await user.click(screen.getByRole('checkbox', { name: /Isto é um redeploy/i }))
      const predecessorField = await screen.findByRole('combobox', { name: 'Contrato predecessor' })
      await user.click(predecessorField)
      await user.click(await screen.findByRole('option', { name: /Token A v1/ }))

      await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

      expect(mutate).toHaveBeenCalledWith(
        expect.objectContaining({
          address: ADDRESS,
          name: 'Token A',
          network: 'TESTNET',
          predecessorAddress: PREDECESSOR_ADDRESS,
        }),
        expect.objectContaining({ onSuccess: expect.any(Function) }),
      )
    })

    it('sem marcar o checkbox, não manda predecessorAddress no payload', async () => {
      const mutate = vi.fn((_payload, options) => options?.onSuccess?.(CONTRACT))
      useCreateContract.mockReturnValue({ isPending: false, isError: false, mutate })

      const user = userEvent.setup()
      renderWithProviders(<ContractFormPage mode="create" />)

      await user.type(screen.getByRole('textbox', { name: 'Endereço' }), ADDRESS)
      await user.type(screen.getByRole('textbox', { name: 'Nome' }), 'Token A')
      await user.click(screen.getByRole('combobox', { name: 'Rede' }))
      await user.click(await screen.findByRole('option', { name: 'TESTNET' }))
      await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

      expect(mutate).toHaveBeenCalledWith(
        expect.objectContaining({ predecessorAddress: undefined }),
        expect.objectContaining({ onSuccess: expect.any(Function) }),
      )
    })

    it('mostra mensagem clara quando o backend recusa por incompatibilidade de rede (400 LINEAGE_NETWORK_MISMATCH)', () => {
      useCreateContract.mockReturnValue({
        isPending: false,
        isError: true,
        error: {
          isAxiosError: true,
          response: {
            data: {
              code: 'LINEAGE_NETWORK_MISMATCH',
              message: 'O predecessor informado pertence a uma rede diferente do contrato novo',
              timestamp: '2026-09-12T00:00:00Z',
            },
          },
        },
        mutate: vi.fn(),
      })

      renderWithProviders(<ContractFormPage mode="create" />)

      expect(screen.getByText(/pertence a uma rede diferente do contrato novo/i)).toBeInTheDocument()
    })
  })

  describe('modo edit/view: não mostra o campo de redeploy (decisão tomada só no cadastro)', () => {
    beforeEach(() => {
      useParamsMock.mockReturnValue({ address: ADDRESS })
      useCreateContract.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
      useUpdateContractStatus.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
      useUpdateContract.mockReturnValue({ isPending: false, isError: false, mutate: vi.fn() })
      useContract.mockReturnValue({ data: CONTRACT, isPending: false, isError: false, error: undefined })
    })

    it('modo edit', async () => {
      renderWithProviders(<ContractFormPage mode="edit" />)

      expect(await screen.findByRole('heading', { name: 'Token A' })).toBeInTheDocument()
      expect(screen.queryByText(/Isto é um redeploy/i)).not.toBeInTheDocument()
    })

    it('modo view', async () => {
      renderWithProviders(<ContractFormPage mode="view" />)

      expect(await screen.findByRole('heading', { name: 'Token A' })).toBeInTheDocument()
      expect(screen.queryByText(/Isto é um redeploy/i)).not.toBeInTheDocument()
    })
  })
})
