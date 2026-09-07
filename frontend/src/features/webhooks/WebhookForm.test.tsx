import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WebhookForm } from './WebhookForm'

const {
  listMonitoredContracts,
  listContractEvents,
  listWallets,
  listEnrichmentProfiles,
  listEntityModels,
} = vi.hoisted(() => ({
  listMonitoredContracts: vi.fn(),
  listContractEvents: vi.fn(),
  listWallets: vi.fn(),
  listEnrichmentProfiles: vi.fn(),
  listEntityModels: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts, listContractEvents })),
    WalletsApi: vi.fn(() => ({ listWallets })),
    EnrichmentProfilesApi: vi.fn(() => ({ listEnrichmentProfiles })),
    EntityModelsApi: vi.fn(() => ({ listEntityModels })),
  }
})

const CONTRACT = {
  id: '1',
  address: '0xaaa0000000000000000000000000000000000001',
  name: 'Token A',
  network: 'TESTNET',
  status: 'ACTIVE',
}

const ENRICHMENT_PROFILE = {
  id: 'ep1',
  name: 'Perfil Saldo',
  network: 'TESTNET',
  status: 'ACTIVE',
  calls: [
    {
      targetContractAddress: CONTRACT.address,
      methodName: 'balanceOf',
      args: ['{{decodedParameters.from}}'],
      outputs: [{ outputIndex: 0, placeholder: 'saldo' }],
    },
  ],
  createdAt: '2026-01-01T00:00:00Z',
}

const ENTITY_MODEL = {
  id: 'em1',
  name: 'Modelo X',
  network: 'TESTNET',
  status: 'ACTIVE',
  contributors: [
    {
      contractAddress: CONTRACT.address,
      eventName: 'Transfer',
      correlationParam: 'from',
      fieldMappings: [{ decodedParam: 'value', modelField: 'valor' }],
    },
  ],
  createdAt: '2026-01-01T00:00:00Z',
}

const baseProps = {
  mode: 'create' as const,
  submitLabel: 'Cadastrar',
  isPending: false,
  isError: false,
  error: null,
}

describe('WebhookForm', () => {
  beforeEach(() => {
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 500, totalElements: 1, totalPages: 1, content: [CONTRACT] },
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
    listWallets.mockResolvedValue({
      data: {
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
        content: [
          {
            id: 'w1',
            address: '0xddd0000000000000000000000000000000000004',
            label: 'Carteira D',
            network: 'TESTNET',
            status: 'ACTIVE',
            looksLikeContract: false,
          },
        ],
      },
    })
    listEnrichmentProfiles.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 1, totalPages: 1, content: [ENRICHMENT_PROFILE] },
    })
    listEntityModels.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 1, totalPages: 1, content: [ENTITY_MODEL] },
    })
  })

  it('modo manual: form de texto livre, monta o CreateWebhookPayload (sem regressão)', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<WebhookForm {...baseProps} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('button', { name: 'Manual' }))

    await user.type(screen.getByRole('textbox', { name: 'Contrato' }), '0xfeed000000000000000000000000000000000001')
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'MAINNET' }))
    await user.type(screen.getByRole('textbox', { name: 'Nome do evento' }), 'Transfer')
    await user.type(screen.getByRole('textbox', { name: 'URL de destino' }), 'https://example.test/hook')

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledWith({
      contractAddress: '0xfeed000000000000000000000000000000000001',
      network: 'MAINNET',
      eventName: 'Transfer',
      url: 'https://example.test/hook',
      filters: undefined,
      payloadTemplate: undefined,
    })
  })

  it('modo guiado: contrato + evento em cascata, filtro por <EventParamSelect kind="any">', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<WebhookForm {...baseProps} onSubmit={onSubmit} />)

    expect(screen.queryByRole('combobox', { name: 'Rede' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))

    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))

    await user.type(screen.getByRole('textbox', { name: 'URL de destino' }), 'https://example.test/hook')

    await user.click(screen.getByRole('button', { name: /adicionar filtro/i }))
    await user.click(screen.getByRole('combobox', { name: 'parâmetro' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'value (uint256)' }))
    await user.type(screen.getAllByPlaceholderText('valor')[0], '100')

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: CONTRACT.address,
      network: 'TESTNET',
      eventName: 'Transfer',
      url: 'https://example.test/hook',
      filters: [{ paramName: 'value', operator: 'EQ', value: '100' }],
    })
  })

  it('modo visual: canvas de filtros + builder de payloadTemplate montam o mesmo payload', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<WebhookForm {...baseProps} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('button', { name: 'Visual' }))
    expect(screen.queryByRole('combobox', { name: 'Rede' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))
    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))
    await user.type(screen.getByRole('textbox', { name: 'URL de destino' }), 'https://example.test/hook')

    // filtro: adiciona um slot, vincula o parâmetro pelo fallback de clique, preenche o valor
    await user.click(screen.getByRole('button', { name: /adicionar filtro/i }))
    await user.click(await screen.findByRole('button', { name: 'Parâmetro value, tipo uint256' }))
    await user.click(screen.getByRole('button', { name: /Campo Filtro 1/ }))
    await user.type(screen.getByRole('textbox', { name: 'valor' }), '100')

    // payloadTemplate: insere um token clicando no chip
    await user.click(screen.getByRole('button', { name: 'Inserir {{eventName}}' }))

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: CONTRACT.address,
      network: 'TESTNET',
      eventName: 'Transfer',
      url: 'https://example.test/hook',
      filters: [{ paramName: 'value', operator: 'EQ', value: '100' }],
      payloadTemplate: '{{eventName}}',
    })
  })

  it('edição guiada: aceita eventName fora do catálogo sem esvaziar o campo (não-bloqueante 2)', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(
      <WebhookForm
        {...baseProps}
        mode="edit"
        submitLabel="Salvar"
        initialValues={{
          contractAddress: CONTRACT.address,
          network: 'TESTNET',
          eventName: 'LegacyEvent',
          url: 'https://old.test/hook',
        }}
        onSubmit={onSubmit}
      />,
    )

    // guiado é o default; o evento 'LegacyEvent' não está no catálogo mockado (só 'Transfer')
    const eventField = await screen.findByRole('combobox', { name: 'Nome do evento' })
    expect(eventField).toHaveValue('LegacyEvent')
    expect(screen.getByText(/não está no catálogo do contrato/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: CONTRACT.address,
      eventName: 'LegacyEvent',
    })
  })

  it('edição: pré-carrega os valores no modo manual', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(
      <WebhookForm
        {...baseProps}
        mode="edit"
        submitLabel="Salvar"
        initialValues={{
          contractAddress: '0xfeed000000000000000000000000000000000001',
          network: 'MAINNET',
          eventName: 'Approval',
          url: 'https://old.test/hook',
        }}
        onSubmit={onSubmit}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Manual' }))
    expect(screen.getByRole('textbox', { name: 'Nome do evento' })).toHaveValue('Approval')
    expect(screen.getByRole('textbox', { name: 'URL de destino' })).toHaveValue('https://old.test/hook')

    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: '0xfeed000000000000000000000000000000000001',
      network: 'MAINNET',
      eventName: 'Approval',
    })
  })

  it('modo guiado: filtro sobre parâmetro address oferece <WalletAutocomplete> como valor (E22)', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<WebhookForm {...baseProps} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))
    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))
    await user.type(screen.getByRole('textbox', { name: 'URL de destino' }), 'https://example.test/hook')

    await user.click(screen.getByRole('button', { name: /adicionar filtro/i }))
    await user.click(screen.getByRole('combobox', { name: 'parâmetro' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'from (address)' }))

    // valor do filtro vira um autocomplete de carteira (não um texto livre)
    await user.click(await screen.findByRole('combobox', { name: 'valor' }))
    await user.click(await screen.findByRole('option', { name: /Carteira D/ }))

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      filters: [{ paramName: 'from', operator: 'EQ', value: '0xddd0000000000000000000000000000000000004' }],
    })
  })

  it('modo guiado: filtro sobre parâmetro address permite digitar o valor manualmente', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<WebhookForm {...baseProps} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))
    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))
    await user.type(screen.getByRole('textbox', { name: 'URL de destino' }), 'https://example.test/hook')

    await user.click(screen.getByRole('button', { name: /adicionar filtro/i }))
    await user.click(screen.getByRole('combobox', { name: 'parâmetro' }))
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'from (address)' }))

    await user.click(await screen.findByRole('button', { name: /digitar manualmente/i }))
    await user.type(screen.getByRole('textbox', { name: 'valor' }), '0xfeed000000000000000000000000000000000009')

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      filters: [{ paramName: 'from', operator: 'EQ', value: '0xfeed000000000000000000000000000000000009' }],
    })
  })

  it('E19/DEC-084: seleciona Perfil de Enriquecimento e Modelo de Entidade, envia os ids no payload', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(<WebhookForm {...baseProps} onSubmit={onSubmit} />)

    await user.click(screen.getByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))
    await user.click(screen.getByRole('combobox', { name: 'Nome do evento' }))
    await user.click(await screen.findByRole('option', { name: 'Transfer' }))
    await user.type(screen.getByRole('textbox', { name: 'URL de destino' }), 'https://example.test/hook')

    await user.click(screen.getByRole('combobox', { name: 'Perfis de Enriquecimento (opcional)' }))
    await user.click(await screen.findByRole('option', { name: 'Perfil Saldo' }))

    await user.click(screen.getByRole('combobox', { name: 'Modelos de Entidade (opcional)' }))
    await user.click(await screen.findByRole('option', { name: 'Modelo X' }))

    // os tokens do perfil/modelo selecionados viram hint no template manual
    expect(screen.getByText(/enriched\.saldo/)).toBeInTheDocument()
    expect(screen.getByText(/model\.valor/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      contractAddress: CONTRACT.address,
      network: 'TESTNET',
      eventName: 'Transfer',
      enrichmentProfileIds: ['ep1'],
      entityModelIds: ['em1'],
    })
  })

  it('modo view: mostra os campos como texto, sem controles editáveis nem botão de salvar', () => {
    renderWithProviders(
      <WebhookForm
        {...baseProps}
        mode="view"
        submitLabel="Salvar"
        initialValues={{
          contractAddress: CONTRACT.address,
          network: 'TESTNET',
          eventName: 'Transfer',
          url: 'https://old.test/hook',
        }}
        onSubmit={vi.fn()}
      />,
    )

    expect(screen.getByText('URL de destino')).toBeInTheDocument()
    expect(screen.getByText('https://old.test/hook')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Salvar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})
