import { useQuery } from '@tanstack/react-query'
import { BalancesApi } from '../../api/api'
import type { Network } from '../../api/api'
import { apiConfiguration } from '../../api/config'

const balancesApi = new BalancesApi(apiConfiguration)

export interface BalanceFilters {
  walletAddress?: string
  contractAddress?: string
  tokenId?: string
  network?: Network
}

/** Consulta saldos atuais (`GET /v1/balances`). */
export function useBalances(filters: BalanceFilters, page: number, size: number) {
  return useQuery({
    queryKey: ['balances', filters, page, size],
    queryFn: async () =>
      (
        await balancesApi.listBalances(
          filters.walletAddress,
          filters.contractAddress,
          filters.tokenId,
          filters.network,
          page,
          size,
        )
      ).data,
  })
}

/** Consulta saldos consolidados numa data específica (`GET /v1/balances/history`). */
export function useBalanceHistory(
  date: string | undefined,
  filters: BalanceFilters,
  page: number,
  size: number,
) {
  return useQuery({
    queryKey: ['balance-history', date, filters, page, size],
    queryFn: async () =>
      (
        await balancesApi.listBalanceHistory(
          date as string,
          filters.walletAddress,
          filters.contractAddress,
          filters.tokenId,
          filters.network,
          page,
          size,
        )
      ).data,
    enabled: Boolean(date),
  })
}
