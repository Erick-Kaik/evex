import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { EntityModelFormPage } from './EntityModelFormPage'

const { listMonitoredContracts, listContractEvents, createEntityModel } = vi.hoisted(() => ({
  listMonitoredContracts: vi.fn(),
  listContractEvents: vi.fn(),
  createEntityModel: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts, listContractEvents })),
    EntityModelsApi: vi.fn(() => ({ createEntityModel })),
  }
})

const CONTRACT = {
  id: '1',
  address: '0xaaa0000000000000000000000000000000000001',
  name: 'Token A',
  network: 'TESTNET',
  status: 'ACTIVE',
}

describe('EntityModelFormPage (create)', () => {
  beforeEach(() => {
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 1, totalPages: 1, content: [CONTRACT] },
    })
    listContractEvents.mockResolvedValue({
      data: [
        {
          name: 'Transfer',
          inputs: [
            { name: 'from', type: 'address', indexed: true },
            { name: 'value', type: 'uint256', indexed: false },
          ],
          validFromBlock: 0,
          validToBlock: null,
        },
      ],
    })
    createEntityModel.mockResolvedValue({
      data: { id: 'em1', name: 'Modelo X', network: 'TESTNET', status: 'ACTIVE', contributors: [], createdAt: '2026-01-01T00:00:00Z' },
    })
  })

  it('monta o CreateEntityModelPayload com 1 evento contribuinte e 1 campo mapeado', async () => {
    const user = userEvent.setup()
    renderWithProviders(<EntityModelFormPage mode="create" />)

    await user.type(screen.getByRole('textbox', { name: 'Nome' }), 'Modelo X')
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'TESTNET' }))

    await user.click(screen.getByRole('button', { name: /adicionar evento/i }))

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))

    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))

    await user.click(screen.getByRole('combobox', { name: 'Parâmetro da parte 1' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'from (address)' }))

    await user.click(screen.getByRole('button', { name: /adicionar campo/i }))
    await user.click(screen.getByRole('combobox', { name: 'parâmetro do evento' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'value (uint256)' }))
    await user.type(screen.getByRole('textbox', { name: 'nome no modelo' }), 'valor')

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(createEntityModel).toHaveBeenCalledTimes(1)
    expect(createEntityModel.mock.calls[0][0]).toMatchObject({
      name: 'Modelo X',
      network: 'TESTNET',
      contributors: [
        {
          contractAddress: CONTRACT.address,
          eventName: 'Transfer',
          correlationKey: [{ type: 'PARAM', arg: 'from' }],
          fieldMappings: [{ decodedParam: 'value', modelField: 'valor', source: 'PARAM' }],
        },
      ],
    })
  })
})
