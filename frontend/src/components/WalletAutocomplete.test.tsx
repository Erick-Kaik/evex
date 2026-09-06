import { useState } from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/renderWithProviders'
import { WalletAutocomplete } from './WalletAutocomplete'
import type { WalletRef } from './WalletAutocomplete'
import { WALLET_CATALOG_PAGE_SIZE } from './walletCatalog'

const { listWallets } = vi.hoisted(() => ({ listWallets: vi.fn() }))

vi.mock('../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/api')>()
  return {
    ...actual,
    WalletsApi: vi.fn(() => ({ listWallets })),
  }
})

const wallets = [
  {
    id: '1',
    address: '0xaaa0000000000000000000000000000000000001',
    label: 'Carteira A',
    network: 'TESTNET',
    status: 'ACTIVE',
    looksLikeContract: false,
  },
  {
    id: '2',
    address: '0xbbb0000000000000000000000000000000000002',
    label: 'Carteira B',
    network: 'MAINNET',
    status: 'ACTIVE',
    looksLikeContract: false,
  },
]

function Harness() {
  const [value, setValue] = useState<WalletRef | null>(null)
  return (
    <>
      <span data-testid="value">{value ? `${value.address}|${value.network}` : 'none'}</span>
      <WalletAutocomplete value={value} onChange={setValue} />
    </>
  )
}

describe('WalletAutocomplete', () => {
  beforeEach(() => {
    listWallets.mockReset()
    listWallets.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 2, totalPages: 1, content: wallets },
    })
  })

  it('lista as carteiras com label, endereço e rede', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(await screen.findByRole('combobox', { name: 'Carteira' }))

    const optionA = await screen.findByRole('option', { name: /Carteira A/ })
    expect(optionA).toHaveTextContent('0xaaa0000000000000000000000000000000000001')
    expect(optionA).toHaveTextContent('TESTNET')
    expect(screen.getByRole('option', { name: /Carteira B/ })).toHaveTextContent('MAINNET')
  })

  it('resolve para { address, network } ao selecionar', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(await screen.findByRole('combobox', { name: 'Carteira' }))
    await user.click(await screen.findByRole('option', { name: /Carteira B/ }))

    expect(screen.getByTestId('value')).toHaveTextContent(
      '0xbbb0000000000000000000000000000000000002|MAINNET',
    )
  })

  it('pede a listagem com o teto de `size` do backend (sem paginar na PoC)', async () => {
    renderWithProviders(<Harness />)
    await screen.findByRole('combobox', { name: 'Carteira' })
    expect(listWallets).toHaveBeenCalledWith(undefined, 0, WALLET_CATALOG_PAGE_SIZE)
    expect(WALLET_CATALOG_PAGE_SIZE).toBeLessThanOrEqual(200)
  })
})
