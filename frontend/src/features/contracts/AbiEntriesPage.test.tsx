import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { AbiEntriesPage } from './AbiEntriesPage'
import { formatAbiEntryParams } from './abiEntryFormat'

const { useContractAbiEntries, navigateMock, useParamsMock } = vi.hoisted(() => ({
  useContractAbiEntries: vi.fn(),
  navigateMock: vi.fn(),
  useParamsMock: vi.fn(),
}))

vi.mock('./useContracts', () => ({ useContractAbiEntries }))

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock, useParams: useParamsMock }
})

const ADDRESS = '0xabc0000000000000000000000000000000000001'

const FUNCTION_ENTRY = {
  kind: 'FUNCTION',
  name: 'balanceOf',
  stateMutability: 'view',
  inputs: [{ name: 'owner', type: 'address' }],
  outputs: [{ type: 'uint256' }],
  validFromBlock: 0,
  validToBlock: null,
}

const EVENT_ENTRY = {
  kind: 'EVENT',
  name: 'Transfer',
  stateMutability: undefined,
  inputs: [
    { name: 'from', type: 'address', indexed: true },
    { name: 'value', type: 'uint256', indexed: false },
  ],
  outputs: [],
  anonymous: false,
  validFromBlock: 0,
  validToBlock: 100,
}

const ERROR_ENTRY = {
  kind: 'ERROR',
  name: 'InsufficientPower',
  stateMutability: undefined,
  inputs: [
    { name: 'required', type: 'uint256' },
    { name: 'actual', type: 'uint256' },
  ],
  outputs: [],
  validFromBlock: 0,
  validToBlock: null,
}

const CONSTRUCTOR_ENTRY = {
  kind: 'CONSTRUCTOR',
  name: undefined,
  stateMutability: 'nonpayable',
  inputs: [],
  outputs: [],
  validFromBlock: 0,
  validToBlock: null,
}

describe('formatAbiEntryParams', () => {
  it('devolve "—" pra lista vazia', () => {
    expect(formatAbiEntryParams([])).toBe('—')
  })

  it('formata "nome: tipo" por item, separados por vírgula', () => {
    expect(formatAbiEntryParams([{ name: 'owner', type: 'address' }, { type: 'uint256' }])).toBe(
      'owner: address, uint256',
    )
  })

  it('sinaliza inputs indexed', () => {
    expect(formatAbiEntryParams([{ name: 'from', type: 'address', indexed: true }])).toBe(
      'from: address (indexed)',
    )
  })
})

describe('AbiEntriesPage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    useParamsMock.mockReturnValue({ address: ADDRESS })
  })

  it('renderiza a tabela com pelo menos um item de cada tipo', () => {
    useContractAbiEntries.mockReturnValue({
      data: [FUNCTION_ENTRY, EVENT_ENTRY, ERROR_ENTRY, CONSTRUCTOR_ENTRY],
      isPending: false,
      isError: false,
    })

    renderWithProviders(<AbiEntriesPage />)

    expect(screen.getByText('balanceOf')).toBeInTheDocument()
    expect(screen.getByText('Transfer')).toBeInTheDocument()
    expect(screen.getByText('InsufficientPower')).toBeInTheDocument()
    // Constructor não tem nome — pelo menos um "—" na coluna Nome.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)

    expect(screen.getByText('FUNCTION')).toBeInTheDocument()
    expect(screen.getByText('EVENT')).toBeInTheDocument()
    expect(screen.getByText('ERROR')).toBeInTheDocument()
    expect(screen.getByText('CONSTRUCTOR')).toBeInTheDocument()

    expect(screen.getByText('owner: address')).toBeInTheDocument()
    expect(screen.getByText('from: address (indexed), value: uint256')).toBeInTheDocument()
  })

  it('mostra "vigente" pro item com validToBlock nulo', () => {
    useContractAbiEntries.mockReturnValue({
      data: [FUNCTION_ENTRY, EVENT_ENTRY],
      isPending: false,
      isError: false,
    })

    renderWithProviders(<AbiEntriesPage />)

    expect(screen.getByText('vigente')).toBeInTheDocument()
    expect(screen.getByText('100')).toBeInTheDocument()
  })

  it('filtra por Tipo', async () => {
    const user = userEvent.setup()
    useContractAbiEntries.mockReturnValue({
      data: [FUNCTION_ENTRY, EVENT_ENTRY],
      isPending: false,
      isError: false,
    })

    renderWithProviders(<AbiEntriesPage />)

    expect(screen.getByText('balanceOf')).toBeInTheDocument()
    expect(screen.getByText('Transfer')).toBeInTheDocument()

    await user.click(screen.getByLabelText('Tipo'))
    await user.click(screen.getByRole('option', { name: 'EVENT' }))

    expect(screen.queryByText('balanceOf')).not.toBeInTheDocument()
    expect(screen.getByText('Transfer')).toBeInTheDocument()
  })

  it('navega de volta para o detalhe do contrato', async () => {
    const user = userEvent.setup()
    useContractAbiEntries.mockReturnValue({ data: [], isPending: false, isError: false })

    renderWithProviders(<AbiEntriesPage />)

    await user.click(screen.getByRole('button', { name: /voltar/i }))
    expect(navigateMock).toHaveBeenCalledWith(`/contracts/${ADDRESS}`)
  })
})
