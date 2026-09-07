import { Link as RouterLink } from 'react-router'
import { Autocomplete, Link, TextField, Typography } from '@mui/material'
import type { CatalogMethod } from './contractCatalog'
import { useContractMethodCatalog } from './contractCatalog'

interface MethodPickerProps {
  /** Endereço do contrato-alvo. Sem endereço → campo desabilitado. */
  address: string | undefined
  /** Nome do método selecionado. */
  value: string
  /** Recebe o nome do método e o método completo (`inputs`/`outputs`) para o form pai montar `args`/`outputs`. */
  onChange: (methodName: string, method: CatalogMethod | undefined) => void
  label?: string
  required?: boolean
  size?: 'small' | 'medium'
  sx?: object
}

/**
 * `Autocomplete` dos nomes de método `view`/`pure` decodificáveis de um contrato (catálogo E19/
 * DEC-082/083, `GET /v1/contracts/{address}/methods`) — mirror exato de `<EventPicker>` trocando
 * evento por método. Usado no builder de `EnrichmentCall` (Perfis de Enriquecimento) para escolher a
 * função a chamar via `eth_call`.
 * - sem `address` → desabilitado;
 * - catálogo vazio (200, contrato sem ABI) **e** sem valor atual → mensagem "cadastre a ABI" com link;
 * - proxy (mesmo método em faixas de bloco diferentes) → nomes de-duplicados;
 * - `value` fora do catálogo → entra como opção sintética, sem esvaziar o campo (mesmo não-bloqueante
 *   de `<EventPicker>` — comum ao editar um perfil cuja ABI mudou desde o cadastro).
 */
export function MethodPicker({
  address,
  value,
  onChange,
  label = 'Método',
  required,
  size = 'small',
  sx,
}: MethodPickerProps) {
  const { query, methodNames, methodsByName, hasNoAbi } = useContractMethodCatalog(address)

  const valueOutsideCatalog = Boolean(value) && !methodNames.includes(value)
  const options = valueOutsideCatalog ? [...methodNames, value] : methodNames

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
            helperText="Selecione um contrato-alvo primeiro."
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
      onChange={(_event, name) => onChange(name ?? '', name ? methodsByName.get(name) : undefined)}
      renderInput={(params) => (
        <TextField
          {...params}
          label={label}
          required={required}
          error={query.isError}
          helperText={
            query.isError
              ? 'Não foi possível carregar os métodos do contrato.'
              : valueOutsideCatalog
                ? `Método atual: ${value} — não está no catálogo do contrato (ABI antiga ou ainda carregando).`
                : undefined
          }
        />
      )}
    />
  )
}
