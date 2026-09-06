import { useMemo } from 'react'
import type { Network } from '../api/api'
import { useMonitoredContractsList } from './contractCatalog'
import { useMonitoredWalletsList } from './walletCatalog'

/**
 * Mapa endereço (lowercase) → nome do contrato cadastrado, pra resolver nome+link nas tabelas
 * (`<AddressCell kind="contract">`). Extraído de `EventsPage`/`BalanceQuerySection` (E16/E21), onde
 * a mesma lógica vivia copiada em cada tela — E22.
 */
export function useContractLabels(network?: Network): Map<string, string> {
  const query = useMonitoredContractsList(network)
  return useMemo(() => {
    const map = new Map<string, string>()
    for (const contract of query.data ?? []) {
      map.set(contract.address.toLowerCase(), contract.name)
    }
    return map
  }, [query.data])
}

/** Mesmo princípio de `useContractLabels`, para carteiras cadastradas (`<AddressCell kind="wallet">`). */
export function useWalletLabels(network?: Network): Map<string, string> {
  const query = useMonitoredWalletsList(network)
  return useMemo(() => {
    const map = new Map<string, string>()
    for (const wallet of query.data ?? []) {
      map.set(wallet.address.toLowerCase(), wallet.label)
    }
    return map
  }, [query.data])
}
