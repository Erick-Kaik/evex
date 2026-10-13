import { useState } from 'react'
import { Button } from '@mui/material'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import CheckIcon from '@mui/icons-material/Check'

/** Botão "Copiar" de um texto longo (ex.: payload JSON). O `useState` é só feedback visual do clique. */
export function CopyTextButton({ text, label = 'Copiar' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <Button
      size="small"
      startIcon={copied ? <CheckIcon /> : <ContentCopyIcon />}
      onClick={() => void handleCopy()}
    >
      {copied ? 'Copiado!' : label}
    </Button>
  )
}
