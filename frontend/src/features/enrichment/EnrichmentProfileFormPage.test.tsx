import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { EnrichmentProfileFormPage } from './EnrichmentProfileFormPage'

const { listMonitoredContracts, listContractMethods, createEnrichmentProfile } = vi.hoisted(() => ({
  listMonitoredContracts: vi.fn(),
  listContractMethods: vi.fn(),
  createEnrichmentProfile: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts, listContractMethods })),
    EnrichmentProfilesApi: vi.fn(() => ({ createEnrichmentProfile })),
  }
})

const CONTRACT = {
  id: '1',
  address: '0xaaa0000000000000000000000000000000000001',
  name: 'Token A',
  network: 'TESTNET',
  status: 'ACTIVE',
}

const METHOD = {
  name: 'balanceOf',
  inputs: [{ name: 'account', type: 'address' }],
  outputs: [{ type: 'uint256' }],
  validFromBlock: 0,
  validToBlock: null,
}

describe('EnrichmentProfileFormPage (create)', () => {
  beforeEach(() => {
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 1, totalPages: 1, content: [CONTRACT] },
    })
    listContractMethods.mockResolvedValue({ data: [METHOD] })
    createEnrichmentProfile.mockResolvedValue({
      data: { id: 'ep1', name: 'Perfil Saldo', network: 'TESTNET', status: 'ACTIVE', calls: [], createdAt: '2026-01-01T00:00:00Z' },
    })
  })

  it('monta o CreateEnrichmentProfilePayload com 1 chamada de leitura configurada', async () => {
    const user = userEvent.setup()
    renderWithProviders(<EnrichmentProfileFormPage mode="create" />)

    await user.type(screen.getByRole('textbox', { name: 'Nome' }), 'Perfil Saldo')
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'TESTNET' }))

    await user.click(screen.getByRole('button', { name: /adicionar chamada/i }))

    await user.click(screen.getByRole('combobox', { name: 'Contrato-alvo' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))

    await user.click(screen.getByRole('combobox', { name: 'Método' }))
    await user.click(await screen.findByRole('option', { name: 'balanceOf' }))

    // user-event trata `{` como início de tecla especial (`{enter}` etc.) — `{{` escapa pra um `{`
    // literal; `}` não precisa de escape.
    await user.type(screen.getByRole('textbox', { name: 'account (address)' }), '{{{{decodedParameters.from}}')

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(createEnrichmentProfile).toHaveBeenCalledTimes(1)
    expect(createEnrichmentProfile.mock.calls[0][0]).toMatchObject({
      name: 'Perfil Saldo',
      network: 'TESTNET',
      calls: [
        {
          targetContractAddress: CONTRACT.address,
          methodName: 'balanceOf',
          args: ['{{decodedParameters.from}}'],
          outputs: [{ outputIndex: 0, placeholder: 'output0' }],
        },
      ],
    })
  })
})
