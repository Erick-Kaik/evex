import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ContractLineageSection } from './ContractLineageSection'

const { useContractLineage, ADDRESS_V1, ADDRESS_V2 } = vi.hoisted(() => ({
  useContractLineage: vi.fn(),
  ADDRESS_V1: '0xaaa0000000000000000000000000000000000001',
  ADDRESS_V2: '0xbbb0000000000000000000000000000000000002',
}))

vi.mock('./useContracts', () => ({ useContractLineage }))

// `<AddressCell kind="contract">` (link pro detalhe) consulta o catálogo de contratos cadastrados —
// mesmo padrão de mock de `ContractBalancesSection.test.tsx`.
vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({
      listMonitoredContracts: vi.fn().mockResolvedValue({
        data: {
          page: 0,
          size: 200,
          totalElements: 2,
          totalPages: 1,
          content: [
            { id: 'c1', address: ADDRESS_V1, name: 'Token v1', network: 'TESTNET', status: 'ARCHIVED' },
            { id: 'c2', address: ADDRESS_V2, name: 'Token v2', network: 'TESTNET', status: 'ACTIVE' },
          ],
        },
      }),
    })),
  }
})

describe('ContractLineageSection', () => {
  beforeEach(() => {
    useContractLineage.mockReset()
  })

  it('não renderiza nada enquanto a consulta está pendente', () => {
    useContractLineage.mockReturnValue({ data: undefined, isPending: true, isError: false, error: undefined })

    const { container } = renderWithProviders(<ContractLineageSection address={ADDRESS_V2} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('não renderiza a seção quando a linhagem tem só 1 contrato (sem lineage útil)', () => {
    useContractLineage.mockReturnValue({
      data: [{ id: 'c2', address: ADDRESS_V2, name: 'Token v2', network: 'TESTNET', status: 'ACTIVE' }],
      isPending: false,
      isError: false,
      error: undefined,
    })

    const { container } = renderWithProviders(<ContractLineageSection address={ADDRESS_V2} />)

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText('Linhagem')).not.toBeInTheDocument()
  })

  it('renderiza a linhagem com mais de 1 contrato, cada um linkando pro próprio detalhe', async () => {
    useContractLineage.mockReturnValue({
      data: [
        { id: 'c1', address: ADDRESS_V1, name: 'Token v1', network: 'TESTNET', status: 'ARCHIVED' },
        { id: 'c2', address: ADDRESS_V2, name: 'Token v2', network: 'TESTNET', status: 'ACTIVE' },
      ],
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<ContractLineageSection address={ADDRESS_V2} />)

    expect(screen.getByText('Linhagem')).toBeInTheDocument()

    const linkV1 = await screen.findByRole('link', { name: 'Token v1' })
    expect(linkV1).toHaveAttribute('href', `/contracts/${ADDRESS_V1}`)

    const linkV2 = await screen.findByRole('link', { name: 'Token v2' })
    expect(linkV2).toHaveAttribute('href', `/contracts/${ADDRESS_V2}`)

    expect(screen.getByText('Atual')).toBeInTheDocument()
  })

  it('mostra o erro quando a consulta de linhagem falha', () => {
    useContractLineage.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: new Error('falhou'),
    })

    renderWithProviders(<ContractLineageSection address={ADDRESS_V2} />)

    expect(screen.getByText('Linhagem')).toBeInTheDocument()
    expect(screen.getByText('falhou')).toBeInTheDocument()
  })
})
