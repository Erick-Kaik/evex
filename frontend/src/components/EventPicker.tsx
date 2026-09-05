import { Link as RouterLink } from 'react-router'
import { Autocomplete, Link, TextField, Typography } from '@mui/material'
import type { ContractEventInput } from '../api/api'
import { useContractEventCatalog } from './contractCatalog'

interface EventPickerProps {
  /** Endereço do contrato-âncora. Sem endereço → campo desabilitado. */
  address: string | undefined
  /** Nome do evento selecionado. */
  value: string
  /** Recebe o nome do evento e os `inputs` mesclados (para o `<EventParamSelect>` do pai). */
  onChange: (eventName: string, inputs: ContractEventInput[]) => void
  label?: string
  required?: boolean
  size?: 'small' | 'medium'
  sx?: object
}

/**
 * `Autocomplete` dos nomes de evento decodificáveis de um contrato (catálogo da DEC-073).
 * - sem `address` → desabilitado;
 * - catálogo vazio (200, contrato sem ABI) **e** sem valor atual → mensagem "cadastre a ABI" com link pro detalhe (não é erro);
 * - proxy (mesmo evento em faixas de bloco diferentes) → nomes de-duplicados (DEC-076);
 * - `value` fora do catálogo (evento de uma ABI antiga, ou catálogo ainda carregando na edição de um
 *   webhook) → o valor entra como opção sintética, sem warning do MUI e sem esvaziar o campo.
 * Os `inputs` do evento selecionado são entregues via `onChange`.
 */
export function EventPicker({
  address,
  value,
  onChange,
  label = 'Nome do evento',
  required,
  size = 'small',
  sx,
}: EventPickerProps) {
  const { query, eventNames, inputsByEvent, hasNoAbi } = useContractEventCatalog(address)

  // O `value` pode referir um evento que não está no catálogo carregado (ABI antiga substituída, ou
  // catálogo ainda carregando durante a edição de um webhook). Entra como opção sintética para o
  // Autocomplete não emitir o warning "none of the options match" nem limpar o campo.
  const valueOutsideCatalog = Boolean(value) && !eventNames.includes(value)
  const options = valueOutsideCatalog ? [...eventNames, value] : eventNames

  if (!address) {
    return (
      <Autocomplete<string>
        options={[]}
        value={null}
        disabled
        size={size}
        sx={{ minWidth: 220, ...sx }}
        renderInput={(params) => (
          <TextField
            {...params}
            label={label}
            required={required}
            helperText="Selecione um contrato primeiro."
          />
        )}
      />
    )
  }

  if (hasNoAbi && !valueOutsideCatalog) {
    return (
      <div>
        <Autocomplete<string>
          options={[]}
          value={null}
          disabled
          size={size}
          sx={{ minWidth: 220, ...sx }}
          renderInput={(params) => <TextField {...params} label={label} required={required} />}
        />
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
          Este contrato não tem ABI cadastrada.{' '}
          <Link component={RouterLink} to={`/contracts/${address}`}>
            Cadastrar ABI
          </Link>
        </Typography>
      </div>
    )
  }

  return (
    <Autocomplete<string>
      options={options}
      value={value || null}
      loading={query.isPending}
      size={size}
      sx={{ minWidth: 220, ...sx }}
      isOptionEqualToValue={(option, selected) => option === selected}
      onChange={(_event, name) => onChange(name ?? '', name ? (inputsByEvent.get(name) ?? []) : [])}
      renderInput={(params) => (
        <TextField
          {...params}
          label={label}
          required={required}
          error={query.isError}
          helperText={
            query.isError
              ? 'Não foi possível carregar os eventos do contrato.'
              : valueOutsideCatalog
                ? `Evento atual: ${value} — não está no catálogo do contrato (ABI antiga ou ainda carregando).`
                : undefined
          }
        />
      )}
    />
  )
}
