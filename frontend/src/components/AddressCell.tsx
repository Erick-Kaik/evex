import { Box, Link, Tooltip, Typography } from '@mui/material'
import { Link as RouterLink } from 'react-router'
import { shortenAddress } from './formatAddress'
import { useContractLabels, useWalletLabels } from './addressLabels'

export type AddressKind = 'contract' | 'wallet'

interface AddressCellProps {
  address: string
  kind: AddressKind
}

/**
 * Célula de tabela para um endereço de contrato/carteira (E22, pedido explícito do usuário). Quando
 * o endereço está cadastrado, mostra **nome (com link pro detalhe) + endereço abreviado logo
 * abaixo** — mesmo layout de 2 linhas que `<ContractAutocomplete>`/`<WalletAutocomplete>` já usam no
 * `renderOption` do dropdown, só que fora do dropdown, numa célula de tabela. Quando não está
 * cadastrado, mantém o comportamento anterior (endereço abreviado + tooltip com o endereço completo,
 * sem nome) — antes isso vivia como uma coluna "só nome, só quando cadastrado" (E16/E21); esta troca
 * evita, na mesma tabela, uma coluna nome+endereço ao lado de outra só-endereço.
 *
 * `kind` decide qual catálogo consultar (`useContractLabels`/`useWalletLabels`) e pra onde o link
 * aponta (`/contracts/:address` ou `/wallets/:address`) — dois componentes internos por `kind` em vez
 * de uma chamada condicional de hook, pra não violar as regras de hooks.
 */
export function AddressCell({ address, kind }: AddressCellProps) {
  return kind === 'contract' ? (
    <ContractAddressCell address={address} />
  ) : (
    <WalletAddressCell address={address} />
  )
}

function ContractAddressCell({ address }: { address: string }) {
  const labels = useContractLabels()
  return (
    <AddressCellView
      address={address}
      name={labels.get(address.toLowerCase())}
      detailPath={`/contracts/${address}`}
    />
  )
}

function WalletAddressCell({ address }: { address: string }) {
  const labels = useWalletLabels()
  return (
    <AddressCellView
      address={address}
      name={labels.get(address.toLowerCase())}
      detailPath={`/wallets/${address}`}
    />
  )
}

interface AddressCellViewProps {
  address: string
  name: string | undefined
  detailPath: string
}

function AddressCellView({ address, name, detailPath }: AddressCellViewProps) {
  if (!name) {
    return (
      <Tooltip title={address}>
        <Box component="span" sx={{ fontFamily: 'monospace' }}>
          {shortenAddress(address)}
        </Box>
      </Tooltip>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
      <Link component={RouterLink} to={detailPath} variant="body2">
        {name}
      </Link>
      <Typography variant="caption" sx={{ fontFamily: 'monospace' }} color="text.secondary">
        {shortenAddress(address)}
      </Typography>
    </Box>
  )
}
