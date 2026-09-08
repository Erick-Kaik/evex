import { Box, Chip, Stack, Typography } from '@mui/material'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import CancelIcon from '@mui/icons-material/Cancel'
import { CopyableHash } from '../../components/CopyableHash'

const ADDRESS_PATTERN = /^0x[a-fA-F0-9]{40}$/
const HEX_PATTERN = /^0x[a-fA-F0-9]+$/

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
    // DEC-063: nenhum ramo especial pra string só-de-dígitos aqui — um `uint256`/`int256` decodificado
    // nunca chega como `string` (o backend serializa BigInteger como literal JSON numérico, o axios já
    // converte pra `number` do JS antes deste componente rodar, caindo no ramo `number` acima; a perda
    // de precisão de um uint256 grande, se houver, já aconteceu antes daqui). Uma `string` que chega
    // até este ponto é sempre um parâmetro Solidity `string`/`bytes` de verdade — aplicar separador de
    // milhar formataria como quantidade algo que pode ser um ID/código, semanticamente não-numérico.
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
