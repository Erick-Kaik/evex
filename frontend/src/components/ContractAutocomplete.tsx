import { Autocomplete, Box, Chip, TextField, Typography } from '@mui/material'
import type { Contract, Network } from '../api/api'
import { useMonitoredContractsList } from './contractCatalog'

/** Contrato resolvido pelo autocomplete — endereço + rede herdada (a rede não é mais pedida à parte). */
export interface ContractRef {
  address: string
  network: Network
}

interface ContractAutocompleteProps {
  value: ContractRef | null
  onChange: (value: ContractRef | null) => void
  /** Filtra as opções por rede (opcional — em geral não é usado, a rede vem do contrato). */
  network?: Network
  label?: string
  required?: boolean
  size?: 'small' | 'medium'
  /** Largura mínima do campo. */
  sx?: object
}

function toRef(contract: Contract): ContractRef {
  return { address: contract.address, network: contract.network }
}

/**
 * `Autocomplete` sobre a lista de contratos monitorados (`GET /v1/contracts`, via React Query).
 * Mostra nome + endereço (mono) + chip de rede; `onChange` devolve `{ address, network }` — a rede
 * é herdada do contrato (DEC-072), por isso as telas que usam este componente removem o
 * `<NetworkSelect>`.
 */
export function ContractAutocomplete({
  value,
  onChange,
  network,
  label = 'Contrato',
  required,
  size = 'small',
  sx,
}: ContractAutocompleteProps) {
  const { data, isPending, isError } = useMonitoredContractsList(network)
  const options = data ?? []

  // O `value` pode referir um contrato que não está (ainda) na lista carregada — reconstrói um
  // `Contract` mínimo pra o Autocomplete não reclamar de valor fora das opções.
  const selected: Contract | null = value
    ? (options.find((c) => c.address === value.address && c.network === value.network) ??
      ({
        id: value.address,
        address: value.address,
        name: value.address,
        network: value.network,
        status: 'ACTIVE',
      } as Contract))
    : null

  return (
    <Autocomplete<Contract>
      options={options}
      value={selected}
      loading={isPending}
      size={size}
      sx={{ minWidth: 280, ...sx }}
      getOptionLabel={(option) => option.name}
      isOptionEqualToValue={(option, val) =>
        option.address === val.address && option.network === val.network
      }
      onChange={(_event, option) => onChange(option ? toRef(option) : null)}
      renderOption={(props, option) => {
        const { key, ...rest } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...rest}>
            <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minWidth: 0 }}>
              <Typography variant="body2">{option.name}</Typography>
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
          helperText={isError ? 'Não foi possível carregar a lista de contratos.' : undefined}
          error={isError}
        />
      )}
    />
  )
}
