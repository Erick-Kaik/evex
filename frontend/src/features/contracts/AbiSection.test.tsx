import { describe, expect, it, vi } from 'vitest'
import { act, screen } from '@testing-library/react'
import { renderWithProviders } from '../../test/renderWithProviders'
import { FakeEventSource } from '../../test/fakeEventSource'
import { AbiSection } from './AbiSection'

vi.mock('./useContracts', () => ({
  useContractAbiVersions: () => ({ data: [], isPending: false, isError: false }),
  useRegisterAbi: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
  useInterfaceSuggestions: () => ({ mutate: vi.fn(), isIdle: true, isPending: false, isError: false }),
}))

const ADDRESS = '0xabc0000000000000000000000000000000000001'

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
