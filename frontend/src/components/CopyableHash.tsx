import { useState } from 'react'
import { IconButton, Stack, Tooltip, Typography } from '@mui/material'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import CheckIcon from '@mui/icons-material/Check'

interface CopyableHashProps {
  value: string
  /** Quantos chars manter no início/fim ao truncar (default 10/8, ex.: endereço 0x...). */
  headLength?: number
  tailLength?: number
}

function truncate(value: string, headLength: number, tailLength: number): string {
  if (value.length <= headLength + tailLength + 1) {
    return value
  }
  return `${value.slice(0, headLength)}…${value.slice(-tailLength)}`
}

/**
 * Endereço/hash truncado, com tooltip mostrando o valor completo e botão de copiar — reaproveitado
 * na lista/detalhe de contratos e nos parâmetros decodificados de eventos (endereços, `bytes`).
 * Não é chamada de API: `navigator.clipboard` é efeito colateral local disparado por clique, não
 * dado buscado do backend — `useState` aqui é só feedback visual momentâneo do ícone.
 */
export function CopyableHash({ value, headLength = 10, tailLength = 8 }: CopyableHashProps) {
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    await navigator.clipboard.writeText(value)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <Stack direction="row" spacing={0.5} component="span" sx={{ alignItems: 'center' }}>
      <Tooltip title={value}>
        <Typography component="span" sx={{ fontFamily: 'monospace', fontSize: '0.8125rem' }}>
          {truncate(value, headLength, tailLength)}
        </Typography>
      </Tooltip>
      <Tooltip title={copied ? 'Copiado!' : 'Copiar'}>
        <IconButton size="small" onClick={() => void handleCopy()} aria-label="copiar valor completo">
          {copied ? <CheckIcon fontSize="inherit" color="success" /> : <ContentCopyIcon fontSize="inherit" />}
        </IconButton>
      </Tooltip>
    </Stack>
  )
}
