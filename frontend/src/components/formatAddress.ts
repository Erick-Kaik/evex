/**
 * Encurta um endereço/hash para exibição em espaço apertado (ex.: `0xabc0000…0001`) — o valor
 * completo continua disponível via tooltip/clique em quem usa (`<CopyableHash>`, `<AddressCell>`).
 * Extraído de `EventsPage`/`BalanceQuerySection` (E16/E21), onde vivia duplicado — E22.
 */
export function shortenAddress(address: string): string {
  return address.length > 12 ? `${address.slice(0, 8)}…${address.slice(-4)}` : address
}
