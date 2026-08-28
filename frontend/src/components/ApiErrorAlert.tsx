import { isAxiosError } from 'axios'
import { Alert } from '@mui/material'
import type { ApiError } from '../api/api'

interface ApiErrorAlertProps {
  error: unknown
}

/**
 * Extrai `error.response?.data` (schema `ApiError`: `code`/`message`/`timestamp`) de erros do
 * axios e mostra `message`; fallback genérico se o formato não bater (rede fora do ar, erro
 * inesperado do axios, etc.). Reaproveitado em toda mutation/query com estado de erro.
 */
function extractMessage(error: unknown): string {
  if (isAxiosError<ApiError>(error)) {
    const data = error.response?.data
    if (data && typeof data.message === 'string') {
      return data.message
    }
    return error.message
  }

  if (error instanceof Error) {
    return error.message
  }

  return 'Erro inesperado.'
}

export function ApiErrorAlert({ error }: ApiErrorAlertProps) {
  return (
    <Alert severity="error" sx={{ mt: 2 }}>
      {extractMessage(error)}
    </Alert>
  )
}
