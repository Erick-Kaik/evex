import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../../test/renderWithProviders'
import { EntityModelFormPage } from './EntityModelFormPage'

const { listMonitoredContracts, listContractEvents, createEntityModel, getEntityModel, updateEntityModel } = vi.hoisted(() => ({
  listMonitoredContracts: vi.fn(),
  listContractEvents: vi.fn(),
  createEntityModel: vi.fn(),
  getEntityModel: vi.fn(),
  updateEntityModel: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts, listContractEvents })),
    EntityModelsApi: vi.fn(() => ({ createEntityModel, getEntityModel, updateEntityModel })),
  }
})

const ADDRESS = '0xbbbb000000000000000000000000000000000002'
const CONTRACT = { id: '1', address: ADDRESS, name: 'Registry', network: 'TESTNET', status: 'ACTIVE' }

function model(contributors: unknown[]) {
  return {
    id: 'em1',
    name: 'Modelo',
    network: 'TESTNET',
    status: 'ACTIVE',
    contributors,
    createdAt: '2026-01-01T00:00:00Z',
  }
}

const LEGACY = model([
  { contractAddress: ADDRESS, eventName: 'Created', correlationParam: 'id', fieldMappings: [{ decodedParam: 'owner', modelField: 'owner' }] },
])

function renderEdit(path = '/entity-models/em1/edit') {
  return renderWithProviders(
    <Routes>
      <Route path="/entity-models/:id" element={<EntityModelFormPage mode="view" />} />
      <Route path="/entity-models/:id/edit" element={<EntityModelFormPage mode="edit" />} />
    </Routes>,
    { initialEntries: [path] },
  )
}

describe('EntityModelFormPage — chave de correlação e source (DEC-111)', () => {
  beforeEach(() => {
    listMonitoredContracts.mockResolvedValue({ data: { page: 0, size: 200, totalElements: 1, totalPages: 1, content: [CONTRACT] } })
    listContractEvents.mockResolvedValue({
      data: [
        {
          name: 'Created',
          inputs: [
            { name: 'id', type: 'uint256', indexed: true },
            { name: 'owner', type: 'address', indexed: false },
          ],
          validFromBlock: 0,
          validToBlock: null,
        },
      ],
    })
    createEntityModel.mockReset()
    updateEntityModel.mockReset()
    updateEntityModel.mockResolvedValue({ data: LEGACY })
  })

  it('modelo legado: exibe 1 parte PARAM e, sem alterar a chave, envia correlationParam intacto', async () => {
    getEntityModel.mockResolvedValue({ data: LEGACY })
    renderEdit()
    expect(await screen.findByText(/Modelo legado/)).toBeInTheDocument()
    expect(screen.getByText('PARAM(id)')).toBeInTheDocument()
    expect(screen.queryByText(/ficam órfãos/)).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    const body = updateEntityModel.mock.calls[0][1]
    expect(body.contributors[0].correlationParam).toBe('id')
    expect(body.contributors[0].correlationKey).toBeUndefined()
  })

  it('alterar a chave de modelo existente: alerta de órfãos e envia correlationKey (sem correlationParam)', async () => {
    getEntityModel.mockResolvedValue({ data: LEGACY })
    const user = userEvent.setup()
    renderEdit()
    await screen.findByText(/Modelo legado/)

    await user.click(screen.getByRole('button', { name: /adicionar parte/i }))
    await user.click(screen.getByRole('combobox', { name: 'Tipo da parte 2' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'TX_HASH' }))

    expect(await screen.findByText(/registros já gravados com a chave antiga ficam órfãos/)).toBeInTheDocument()
    expect(screen.getByText('PARAM(id) | TX_HASH')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    const contributor = updateEntityModel.mock.calls[0][1].contributors[0]
    expect(contributor.correlationKey).toEqual([{ type: 'PARAM', arg: 'id' }, { type: 'TX_HASH' }])
    expect(contributor.correlationParam).toBeUndefined()
  })

  it('reordenar e remover partes atualiza a pré-visualização', async () => {
    getEntityModel.mockResolvedValue({
      data: model([
        {
          contractAddress: ADDRESS,
          eventName: 'Created',
          correlationKey: [{ type: 'EMITTER_ADDRESS' }, { type: 'PARAM', arg: 'id' }],
          fieldMappings: [],
        },
      ]),
    })
    const user = userEvent.setup()
    renderEdit()
    expect(await screen.findByText('EMITTER_ADDRESS | PARAM(id)')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'subir parte 2' }))
    expect(screen.getByText('PARAM(id) | EMITTER_ADDRESS')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'remover parte 1' }))
    expect(screen.getByText('EMITTER_ADDRESS', { selector: 'code' })).toBeInTheDocument()
  })

  it('LITERAL mostra campo de texto; arg faltando bloqueia o envio com mensagem', async () => {
    getEntityModel.mockResolvedValue({
      data: model([{ contractAddress: ADDRESS, eventName: 'Created', correlationKey: [{ type: 'TX_HASH' }], fieldMappings: [] }]),
    })
    const user = userEvent.setup()
    renderEdit()
    await screen.findByText('TX_HASH', { selector: 'code' })

    await user.click(screen.getByRole('combobox', { name: 'Tipo da parte 1' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'LITERAL' }))
    expect(screen.getByText(/LITERAL exige um valor/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled()

    await user.type(screen.getByRole('textbox', { name: 'Valor da parte 1' }), 'x')
    expect(screen.getByText('LITERAL("x")')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeEnabled()
  })

  it('aridade diferente entre contribuintes: mensagem e envio bloqueado', async () => {
    getEntityModel.mockResolvedValue({
      data: model([
        { contractAddress: ADDRESS, eventName: 'Created', correlationKey: [{ type: 'TX_HASH' }], fieldMappings: [] },
        { contractAddress: ADDRESS, eventName: 'Created', correlationKey: [{ type: 'EMITTER_ADDRESS' }, { type: 'TX_HASH' }], fieldMappings: [] },
      ]),
    })
    renderEdit()
    expect(
      await screen.findByText('Todos os eventos contribuintes precisam ter o mesmo número de partes na chave de correlação.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled()
  })

  it('tipo de parte e source desconhecidos são tolerados (texto cru) e preservados no envio', async () => {
    getEntityModel.mockResolvedValue({
      data: model([
        {
          contractAddress: ADDRESS,
          eventName: 'Created',
          correlationKey: [{ type: 'FUTURE_PART', arg: 'k' }],
          fieldMappings: [{ modelField: 'f', source: 'FUTURE_SOURCE' }],
        },
      ]),
    })
    renderEdit()
    expect(await screen.findByText('FUTURE_PART(k)')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Tipo da parte 1' })).toHaveTextContent('FUTURE_PART')
    expect(screen.getByRole('combobox', { name: 'fonte' })).toHaveTextContent('FUTURE_SOURCE')

    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    const contributor = updateEntityModel.mock.calls[0][1].contributors[0]
    expect(contributor.correlationKey).toEqual([{ type: 'FUTURE_PART', arg: 'k' }])
    expect(contributor.fieldMappings).toEqual([{ modelField: 'f', source: 'FUTURE_SOURCE' }])
  })

  it('field mapping: decodedParam só para PARAM; outras fontes ocultam o select e descartam o valor', async () => {
    getEntityModel.mockResolvedValue({
      data: model([
        {
          contractAddress: ADDRESS,
          eventName: 'Created',
          correlationKey: [{ type: 'TX_HASH' }],
          fieldMappings: [{ decodedParam: 'owner', modelField: 'owner' }],
        },
      ]),
    })
    const user = userEvent.setup()
    renderEdit()
    expect(await screen.findByRole('combobox', { name: 'parâmetro do evento' })).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'fonte' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'BLOCK_TIMESTAMP' }))
    expect(screen.queryByRole('combobox', { name: 'parâmetro do evento' })).not.toBeInTheDocument()
    expect(screen.getByText(/timestamp já gravado/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(updateEntityModel.mock.calls[0][1].contributors[0].fieldMappings).toEqual([
      { modelField: 'owner', source: 'BLOCK_TIMESTAMP' },
    ])
  })

  it('modo leitura mostra a chave e a fonte dos campos', async () => {
    getEntityModel.mockResolvedValue({
      data: model([
        {
          contractAddress: ADDRESS,
          eventName: 'Created',
          correlationKey: [{ type: 'EMITTER_ADDRESS' }, { type: 'PARAM', arg: 'id' }],
          fieldMappings: [{ decodedParam: 'owner', modelField: 'owner' }, { modelField: 'emitter', source: 'EMITTER_ADDRESS' }],
        },
      ]),
    })
    renderEdit('/entity-models/em1')
    expect(await screen.findByText(/chave: EMITTER_ADDRESS \| PARAM\(id\)/)).toBeInTheDocument()
    expect(screen.getByText(/EMITTER_ADDRESS→\{\{model\.emitter\}\}/)).toBeInTheDocument()
  })

  it('criação: monta correlationKey composta com partes PARAM + EMITTER_ADDRESS', async () => {
    createEntityModel.mockResolvedValue({ data: model([]) })
    const user = userEvent.setup()
    renderWithProviders(<EntityModelFormPage mode="create" />)

    await user.type(screen.getByRole('textbox', { name: 'Nome' }), 'M')
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'TESTNET' }))
    await user.click(screen.getByRole('button', { name: /adicionar evento/i }))
    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Registry/ }))
    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Created' }))

    await user.click(screen.getByRole('combobox', { name: 'Parâmetro da parte 1' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'id (uint256)' }))
    await user.click(screen.getByRole('button', { name: /adicionar parte/i }))
    await user.click(screen.getByRole('combobox', { name: 'Tipo da parte 2' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'EMITTER_ADDRESS' }))
    expect(screen.getByText('PARAM(id) | EMITTER_ADDRESS')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))
    expect(createEntityModel.mock.calls[0][0].contributors[0].correlationKey).toEqual([
      { type: 'PARAM', arg: 'id' },
      { type: 'EMITTER_ADDRESS' },
    ])
  })
})
