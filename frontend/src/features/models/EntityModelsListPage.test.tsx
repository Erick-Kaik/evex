import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { EntityModelsListPage } from './EntityModelsListPage'

const { useEntityModels, useUpdateEntityModelStatus, navigateMock } = vi.hoisted(() => ({
  useEntityModels: vi.fn(),
  useUpdateEntityModelStatus: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('./useEntityModels', () => ({ useEntityModels, useUpdateEntityModelStatus }))

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock }
})

const MODEL = {
  id: 'em1',
  name: 'Modelo X',
  network: 'TESTNET',
  status: 'ACTIVE',
  contributors: [
    {
      contractAddress: '0xaaa0000000000000000000000000000000000001',
      eventName: 'Transfer',
      correlationParam: 'from',
      fieldMappings: [{ decodedParam: 'value', modelField: 'valor' }],
    },
  ],
  createdAt: '2026-01-01T00:00:00Z',
}

describe('EntityModelsListPage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    useUpdateEntityModelStatus.mockReturnValue({ isPending: false, mutate: vi.fn() })
  })

  it('mostra o aviso de "nenhum modelo" quando a listagem vem vazia', () => {
    useEntityModels.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<EntityModelsListPage />)

    expect(screen.getByText(/Nenhum Modelo de Entidade cadastrado ainda/i)).toBeInTheDocument()
  })

  it('lista modelos cadastrados com nome, rede, número de contribuidores e status', () => {
    useEntityModels.mockReturnValue({
      data: { content: [MODEL], page: 0, totalPages: 1, totalElements: 1 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<EntityModelsListPage />)

    expect(screen.getByText('Modelo X')).toBeInTheDocument()
    expect(screen.getByText('TESTNET')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
  })

  it('navega para /entity-models/new ao clicar em "Cadastrar"', async () => {
    const user = userEvent.setup()
    useEntityModels.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<EntityModelsListPage />)

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))
    expect(navigateMock).toHaveBeenCalledWith('/entity-models/new')
  })
})
