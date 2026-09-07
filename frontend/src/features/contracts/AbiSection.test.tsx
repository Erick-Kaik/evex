import { describe, expect, it, vi, beforeEach } from 'vitest'
import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { FakeEventSource } from '../../test/fakeEventSource'
import { AbiSection } from './AbiSection'

const { useContractAbiVersions, navigateMock } = vi.hoisted(() => ({
  useContractAbiVersions: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('./useContracts', () => ({
  useContractAbiVersions,
  useRegisterAbi: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
  useInterfaceSuggestions: () => ({ mutate: vi.fn(), isIdle: true, isPending: false, isError: false }),
}))

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock }
})

const ADDRESS = '0xabc0000000000000000000000000000000000001'

beforeEach(() => {
  navigateMock.mockReset()
  useContractAbiVersions.mockReturnValue({ data: [], isPending: false, isError: false })
})

describe('AbiSection — indicador de redecode via SSE', () => {
  it('mostra o indicador quando o stream reporta running: true e esconde quando volta a false', () => {
    renderWithProviders(<AbiSection address={ADDRESS} />)

    expect(screen.queryByText(/Decodificando o histórico/i)).not.toBeInTheDocument()

    act(() => {
      FakeEventSource.last().emitStatus({
        running: true,
        processedCount: 12,
        decodedCount: 4,
        remainingCount: 30,
      })
    })

    expect(screen.getByText(/Decodificando o histórico… \(12 processados\)/i)).toBeInTheDocument()

    act(() => {
      FakeEventSource.last().emitStatus({
        running: false,
        processedCount: 42,
        decodedCount: 40,
        remainingCount: 0,
      })
    })

    expect(screen.queryByText(/Decodificando o histórico/i)).not.toBeInTheDocument()
  })
})

describe('AbiSection — botão "Ver métodos e eventos" (E24)', () => {
  it('navega para o Explorador de ABI do contrato ao clicar no botão da linha', async () => {
    const user = userEvent.setup()
    useContractAbiVersions.mockReturnValue({
      data: [
        {
          id: 'v1',
          validFromBlock: 0,
          validToBlock: null,
          source: 'MANUAL',
          storageType: 'DATABASE',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      isPending: false,
      isError: false,
    })

    renderWithProviders(<AbiSection address={ADDRESS} />)

    await user.click(screen.getByRole('button', { name: 'ver métodos e eventos' }))
    expect(navigateMock).toHaveBeenCalledWith(`/contracts/${ADDRESS}/abi-entries`)
  })
})
