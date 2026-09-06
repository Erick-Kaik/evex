import { Autocomplete, Box, Chip, TextField, Typography } from '@mui/material'
import type { Network, Wallet } from '../api/api'
import { useMonitoredWalletsList } from './walletCatalog'

/** Carteira resolvida pelo autocomplete — endereço + rede herdada (mesma forma de `ContractRef`). */
export interface WalletRef {
  address: string
  network: Network
}

interface WalletAutocompleteProps {
  value: WalletRef | null
  onChange: (value: WalletRef | null) => void
  /** Filtra as opções por rede (opcional). */
  network?: Network
  label?: string
  required?: boolean
  size?: 'small' | 'medium'
  /** Largura mínima do campo. */
  sx?: object
}

function toRef(wallet: Wallet): WalletRef {
  return { address: wallet.address, network: wallet.network }
}

/**
 * `Autocomplete` sobre a lista de carteiras cadastradas (`GET /v1/wallets`, via React Query) — cópia
 * estrutural de `<ContractAutocomplete>` (E16) para o domínio de Carteiras (E22). Mostra label +
 * endereço (mono) + chip de rede; `onChange` devolve `{ address, network }`. Como carteira nunca é
 * obrigatória — sempre existe a opção de digitar um endereço livre — os pontos de uso (listagem de
 * filtros, formulário de webhook) combinam este componente com um `TextField` manual por trás de um
 * toggle "digitar manualmente" (mesmo padrão dual já usado por `<ContractAutocomplete>` em
 * `BalanceQuerySection`/`EventsPage`), em vez deste componente aceitar texto livre por si.
 */
export function WalletAutocomplete({
  value,
  onChange,
  network,
  label = 'Carteira',
  required,
  size = 'small',
  sx,
}: WalletAutocompleteProps) {
  const { data, isPending, isError } = useMonitoredWalletsList(network)
  const options = data ?? []

  // O `value` pode referir uma carteira que não está (ainda) na lista carregada — reconstrói uma
  // `Wallet` mínima pra o Autocomplete não reclamar de valor fora das opções.
  const selected: Wallet | null = value
    ? (options.find((w) => w.address === value.address && w.network === value.network) ??
      ({
        id: value.address,
        address: value.address,
        label: value.address,
        network: value.network,
        status: 'ACTIVE',
        looksLikeContract: false,
      } as Wallet))
    : null

  return (
    <Autocomplete<Wallet>
      options={options}
      value={selected}
      loading={isPending}
      size={size}
      sx={{ minWidth: 280, ...sx }}
      getOptionLabel={(option) => option.label}
      isOptionEqualToValue={(option, val) =>
        option.address === val.address && option.network === val.network
      }
      onChange={(_event, option) => onChange(option ? toRef(option) : null)}
      renderOption={(props, option) => {
        const { key, ...rest } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...rest}>
            <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minWidth: 0 }}>
              <Typography variant="body2">{option.label}</Typography>
              <Typography variant="caption" sx={{ fontFamily: 'monospace' }} color="text.secondary">
                {option.address}
              </Typography>
            </Box>
            <Chip size="small" label={option.network} sx={{ ml: 1 }} />
          </Box>
        )
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          label={label}
          required={required}
          helperText={isError ? 'Não foi possível carregar a lista de carteiras.' : undefined}
          error={isError}
        />
      )}
    />
  )
}
