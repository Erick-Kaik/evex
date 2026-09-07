import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { EnrichmentProfilesListPage } from './EnrichmentProfilesListPage'

const { useEnrichmentProfiles, useUpdateEnrichmentProfileStatus, navigateMock } = vi.hoisted(() => ({
  useEnrichmentProfiles: vi.fn(),
  useUpdateEnrichmentProfileStatus: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('./useEnrichmentProfiles', () => ({ useEnrichmentProfiles, useUpdateEnrichmentProfileStatus }))

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock }
})

const PROFILE = {
  id: 'ep1',
  name: 'Perfil Saldo',
  network: 'TESTNET',
  status: 'ACTIVE',
  calls: [
    {
      targetContractAddress: '0xaaa0000000000000000000000000000000000001',
      methodName: 'balanceOf',
      args: ['{{decodedParameters.from}}'],
      outputs: [{ outputIndex: 0, placeholder: 'saldo' }],
    },
  ],
  createdAt: '2026-01-01T00:00:00Z',
}

describe('EnrichmentProfilesListPage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    useUpdateEnrichmentProfileStatus.mockReturnValue({ isPending: false, mutate: vi.fn() })
  })

  it('mostra o aviso de "nenhum perfil" quando a listagem vem vazia', () => {
    useEnrichmentProfiles.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<EnrichmentProfilesListPage />)

    expect(screen.getByText(/Nenhum Perfil de Enriquecimento cadastrado ainda/i)).toBeInTheDocument()
  })

  it('lista perfis cadastrados com nome, rede, número de chamadas e status', () => {
    useEnrichmentProfiles.mockReturnValue({
      data: { content: [PROFILE], page: 0, totalPages: 1, totalElements: 1 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<EnrichmentProfilesListPage />)

    expect(screen.getByText('Perfil Saldo')).toBeInTheDocument()
    expect(screen.getByText('TESTNET')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
  })

  it('navega para /enrichment-profiles/new ao clicar em "Cadastrar"', async () => {
    const user = userEvent.setup()
    useEnrichmentProfiles.mockReturnValue({
      data: { content: [], page: 0, totalPages: 0, totalElements: 0 },
      isPending: false,
      isError: false,
      error: undefined,
    })

    renderWithProviders(<EnrichmentProfilesListPage />)

    await user.click(screen.getByRole('button', { name: 'Cadastrar' }))
    expect(navigateMock).toHaveBeenCalledWith('/enrichment-profiles/new')
  })
})
