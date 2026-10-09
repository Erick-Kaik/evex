import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { BlockscoutContract } from '../../api/api'
import { renderWithProviders } from '../../test/renderWithProviders'
import { BlockscoutImportPanel } from './BlockscoutImportPanel'

const { useVerifiedContracts } = vi.hoisted(() => ({ useVerifiedContracts: vi.fn() }))
vi.mock('./useContracts', () => ({ useVerifiedContracts }))

const IMPL = '0x22220000000000000000000000000000000000a1'
const PROXY_1 = '0x33330000000000000000000000000000000000b1'
const PROXY_2 = '0x33330000000000000000000000000000000000b2'
const PROXY_WARNING = 'Endereço correto para cadastrar.'
const WARNING = 'Este endereço é a IMPLEMENTAÇÃO de um proxy: cadastre o proxy.'

function mockContracts(contracts: BlockscoutContract[]) {
  useVerifiedContracts.mockReturnValue({
    data: { contracts, unavailable: false },
    isPending: false,
    isError: false,
    error: undefined,
  })
}

describe('BlockscoutImportPanel', () => {
  const onPick = vi.fn()

  beforeEach(() => {
    onPick.mockReset()
  })

  it('renderiza chip de kind e warnings como vieram; STANDALONE sem warnings não mostra nada extra', () => {
    mockContracts([
      { address: IMPL, name: 'ClassImpl', kind: 'IMPLEMENTATION', proxyAddresses: [PROXY_1], warnings: [WARNING] },
      { address: PROXY_2, name: 'TokenProxy', kind: 'PROXY', warnings: [PROXY_WARNING] },
      { address: '0x5555000000000000000000000000000000000001', name: 'Plain', kind: 'STANDALONE' },
      { address: '0x5555000000000000000000000000000000000002', name: 'Unknown', kind: 'FUTURE' as never },
    ])
    renderWithProviders(<BlockscoutImportPanel onPick={onPick} />)

    expect(screen.getByText('Implementação')).toBeInTheDocument()
    expect(screen.getByText(WARNING)).toBeInTheDocument()
    expect(screen.getByText('Proxy')).toBeInTheDocument()
    // só o warning do backend (sem texto hard-coded duplicado)
    expect(screen.getAllByText(/Endereço correto para cadastrar/)).toHaveLength(1)
    // STANDALONE e kind desconhecido: sem chip e sem alert próprio
    expect(screen.getAllByRole('alert')).toHaveLength(2)
  })

  it('implementação com 1 proxy pré-preenche o proxy e explica', async () => {
    mockContracts([{ address: IMPL, name: 'ClassImpl', kind: 'IMPLEMENTATION', proxyAddresses: [PROXY_1] }])
    renderWithProviders(<BlockscoutImportPanel onPick={onPick} />)

    await userEvent.setup().click(screen.getByRole('button', { name: /ClassImpl/ }))

    expect(onPick).toHaveBeenCalledWith(PROXY_1, 'ClassImpl')
    expect(screen.getByRole('status')).toHaveTextContent(PROXY_1)
  })

  it('implementação com 2+ proxies não pré-preenche: o usuário escolhe o proxy', async () => {
    mockContracts([
      { address: IMPL, name: 'ClassImpl', kind: 'IMPLEMENTATION', proxyAddresses: [PROXY_1, PROXY_2] },
    ])
    renderWithProviders(<BlockscoutImportPanel onPick={onPick} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /ClassImpl/ }))
    expect(onPick).not.toHaveBeenCalled()

    const group = screen.getByRole('group', { name: /Escolha o proxy/ })
    expect(group).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: PROXY_2 }))

    expect(onPick).toHaveBeenCalledWith(PROXY_2, 'ClassImpl')
  })

  it('implementação sem proxies e STANDALONE mantêm o comportamento atual (endereço do item)', async () => {
    mockContracts([
      { address: IMPL, name: 'ClassImpl', kind: 'IMPLEMENTATION', proxyAddresses: [] },
      { address: PROXY_2, name: 'Plain' },
    ])
    renderWithProviders(<BlockscoutImportPanel onPick={onPick} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /ClassImpl/ }))
    expect(onPick).toHaveBeenLastCalledWith(IMPL, 'ClassImpl')

    await user.click(screen.getByRole('button', { name: /Plain/ }))
    expect(onPick).toHaveBeenLastCalledWith(PROXY_2, 'Plain')
  })

  it('mostra aviso quando o discovery está indisponível', () => {
    useVerifiedContracts.mockReturnValue({ data: { contracts: [], unavailable: true }, isPending: false, isError: false })
    renderWithProviders(<BlockscoutImportPanel onPick={onPick} />)

    expect(screen.getByText(/Discovery via Blockscout não está disponível/)).toBeInTheDocument()
  })
})
