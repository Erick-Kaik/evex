import { useQuery } from '@tanstack/react-query'
import { WalletsApi } from '../api/api'
import type { Network, Wallet } from '../api/api'
import { apiConfiguration } from '../api/config'

const walletsApi = new WalletsApi(apiConfiguration)

/**
 * Tamanho de página usado pelo `<WalletAutocomplete>`/`useWalletLabels` para carregar a lista de
 * carteiras cadastradas de uma vez só — mesmo princípio de `CONTRACT_CATALOG_PAGE_SIZE`
 * (`contractCatalog.ts`, DEC-072): uma instância do Evex é por rede/cliente e a lista de carteiras
 * cadastradas é curta por natureza. O valor é o teto do parâmetro `size` de `GET /v1/wallets`
 * (`maximum: 200` na spec, igual `/v1/contracts`) — pedir mais devolve 400 `VALIDATION_ERROR`.
 */
export const WALLET_CATALOG_PAGE_SIZE = 200

/** Lista (não paginada, ver `WALLET_CATALOG_PAGE_SIZE`) de carteiras cadastradas para selects/autocomplete. */
export function useMonitoredWalletsList(network?: Network) {
  return useQuery({
    queryKey: ['wallets', 'catalog', network ?? null],
    queryFn: async (): Promise<Wallet[]> =>
      (await walletsApi.listWallets(network, 0, WALLET_CATALOG_PAGE_SIZE)).data.content,
  })
}
