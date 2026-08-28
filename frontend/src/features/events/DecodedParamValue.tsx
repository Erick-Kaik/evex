import { Box, Chip, Stack, Typography } from '@mui/material'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import CancelIcon from '@mui/icons-material/Cancel'
import { CopyableHash } from '../../components/CopyableHash'

const ADDRESS_PATTERN = /^0x[a-fA-F0-9]{40}$/
const HEX_PATTERN = /^0x[a-fA-F0-9]+$/
const DIGITS_PATTERN = /^-?\d+$/

/**
 * Insere separador de milhar (`.`, padrão pt-BR) numa string só de dígitos, sem passar por
 * `Number`/`parseInt` — uint256/int256 decodificados podem exceder `Number.MAX_SAFE_INTEGER`
 * (mesmo cuidado já documentado em `BalanceQuerySection` pro campo `balance`).
 */
function formatDigitGroups(digits: string): string {
  const negative = digits.startsWith('-')
  const unsigned = negative ? digits.slice(1) : digits
  const grouped = unsigned.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  return negative ? `-${grouped}` : grouped
}

/**
 * Formata um parâmetro decodificado (`Event.decodedParameters`, `{ [key: string]: any }` no
 * schema — sem tipo por-campo no OpenAPI, ver nota em `EventsPage.tsx`) pelo **tipo em runtime** do
 * valor recebido: endereço (hex 40 chars) truncado/copiável, número com separador de milhar,
 * booleano como chip, `bytes`/hex genérico truncado/copiável, array recursivo, string direto.
 */
export function DecodedParamValue({ value }: { value: unknown }) {
  if (value === null || value === undefined) {
    return (
      <Typography component="span" variant="body2" color="text.secondary">
        —
      </Typography>
    )
  }

  if (typeof value === 'boolean') {
    return (
      <Chip
        size="small"
        label={value ? 'Sim' : 'Não'}
        color={value ? 'success' : 'default'}
        icon={value ? <CheckCircleIcon /> : <CancelIcon />}
      />
    )
  }

  if (typeof value === 'number') {
    return <Typography component="span" variant="body2">{new Intl.NumberFormat('pt-BR').format(value)}</Typography>
  }

  if (typeof value === 'string') {
    if (ADDRESS_PATTERN.test(value) || (HEX_PATTERN.test(value) && value.length > 20)) {
      return <CopyableHash value={value} />
    }
    if (DIGITS_PATTERN.test(value)) {
      return (
        <Typography component="span" variant="body2" sx={{ fontFamily: 'monospace' }}>
          {formatDigitGroups(value)}
        </Typography>
      )
    }
    return <Typography component="span" variant="body2">{value}</Typography>
  }

  if (Array.isArray(value)) {
    return (
      <Stack direction="row" component="span" sx={{ flexWrap: 'wrap', gap: 0.5 }}>
        {value.map((element, index) => (
          <Box key={index} component="span">
            <DecodedParamValue value={element} />
            {index < value.length - 1 && ', '}
          </Box>
        ))}
      </Stack>
    )
  }

  return <Typography component="span" variant="body2">{JSON.stringify(value)}</Typography>
}
