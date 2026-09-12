import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../../test/renderWithProviders'
import { UpgradeabilityBanner } from './UpgradeabilityBanner'

const { useContractUpgradeability } = vi.hoisted(() => ({
  useContractUpgradeability: vi.fn(),
}))

vi.mock('./useContracts', () => ({ useContractUpgradeability }))

const ADDRESS = '0xabc0000000000000000000000000000000000001'

describe('UpgradeabilityBanner', () => {
  beforeEach(() => {
    useContractUpgradeability.mockReset()
  })

  it('não renderiza nada enquanto a consulta está pendente', () => {
    useContractUpgradeability.mockReturnValue({ data: undefined, isPending: true, isError: false })

    const { container } = renderWithProviders(<UpgradeabilityBanner address={ADDRESS} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('não renderiza nada quando a consulta falha', () => {
    useContractUpgradeability.mockReturnValue({ data: undefined, isPending: false, isError: true })

    const { container } = renderWithProviders(<UpgradeabilityBanner address={ADDRESS} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('não renderiza nada quando o contrato não é upgradeável', () => {
    useContractUpgradeability.mockReturnValue({
      data: { isUpgradeable: false, availableUpgradeMethods: [] },
      isPending: false,
      isError: false,
    })

    const { container } = renderWithProviders(<UpgradeabilityBanner address={ADDRESS} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('mostra o banner quando isUpgradeable=true, sem métodos de upgrade disponíveis', () => {
    useContractUpgradeability.mockReturnValue({
      data: { isUpgradeable: true, availableUpgradeMethods: [] },
      isPending: false,
      isError: false,
    })

    renderWithProviders(<UpgradeabilityBanner address={ADDRESS} />)

    expect(screen.getByText('Contrato upgradeável (UUPS/ERC-1822)')).toBeInTheDocument()
    expect(screen.queryByText('Métodos de upgrade declarados no ABI:')).not.toBeInTheDocument()
    expect(
      screen.getByText('Isto é só informativo — o Evex não executa nem oferece um jeito de chamar o upgrade.'),
    ).toBeInTheDocument()
  })

  it('lista os métodos de upgrade disponíveis quando isUpgradeable=true', () => {
    useContractUpgradeability.mockReturnValue({
      data: { isUpgradeable: true, availableUpgradeMethods: ['upgradeTo(address)', 'upgradeToAndCall(address,bytes)'] },
      isPending: false,
      isError: false,
    })

    renderWithProviders(<UpgradeabilityBanner address={ADDRESS} />)

    expect(screen.getByText('Contrato upgradeável (UUPS/ERC-1822)')).toBeInTheDocument()
    expect(screen.getByText('Métodos de upgrade declarados no ABI:')).toBeInTheDocument()
    expect(screen.getByText('upgradeTo(address)')).toBeInTheDocument()
    expect(screen.getByText('upgradeToAndCall(address,bytes)')).toBeInTheDocument()
  })
})
