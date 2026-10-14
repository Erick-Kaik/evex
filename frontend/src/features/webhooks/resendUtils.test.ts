import { describe, expect, it } from 'vitest'
import { AxiosError } from 'axios'
import type { AxiosResponse } from 'axios'
import {
  blockModeLabel,
  errorHint,
  parseApiError,
  prettyJson,
  summarizeParams,
  toIsoWithOffset,
  translateNotMatchingReason,
  translateSkipReason,
  translateWarning,
  verdictText,
} from './resendUtils'

function axiosError(status: number, data: unknown, headers: Record<string, string> = {}): AxiosError {
  const response = { status, statusText: '', data, headers, config: {} } as unknown as AxiosResponse
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, response)
}

describe('resendUtils', () => {
  it('traduz avisos com contagem e mantém códigos desconhecidos crus', () => {
    expect(translateWarning('DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP:3')).toContain('3 evento(s) sem timestamp')
    expect(translateWarning('MODEL_STATE_NEWER_THAN_EVENT')).toContain('estado ATUAL do modelo')
    expect(translateWarning('RANGE_TRUNCATED')).toContain('limite por reenvio')
    expect(translateWarning('SOMETHING_NEW:9')).toBe('SOMETHING_NEW:9')
  })

  it('traduz motivos de skip/não-casamento e tolera desconhecidos', () => {
    expect(translateSkipReason('ALREADY_PENDING')).toContain('pendente')
    expect(translateSkipReason('NOVO_MOTIVO')).toBe('NOVO_MOTIVO')
    expect(translateNotMatchingReason('FILTER_NOT_MET')).toContain('filtros')
    expect(translateNotMatchingReason('OUTRO')).toBe('OUTRO')
  })

  it('veredito e modo de bloco desconhecidos viram texto cru', () => {
    expect(verdictText('OK_AT_LATEST_ONLY')).toContain('estado atual')
    expect(verdictText('NOVO')).toBe('NOVO')
    expect(blockModeLabel('MODO_FUTURO')).toBe('MODO_FUTURO')
    expect(blockModeLabel(undefined)).toContain('padrão')
  })

  it('converte datetime-local para ISO-8601 com offset do usuário', () => {
    expect(toIsoWithOffset('2026-02-01T10:30')).toMatch(/^2026-02-01T10:30:00[+-]\d{2}:\d{2}$/)
    expect(toIsoWithOffset('')).toBeUndefined()
  })

  it('formata JSON e resume parâmetros', () => {
    expect(prettyJson('{"a":1}')).toBe('{\n  "a": 1\n}')
    expect(prettyJson('não é json')).toBe('não é json')
    expect(summarizeParams({ value: '1', ids: [1, 2] })).toBe('value=1, ids=[1,2]')
    expect(summarizeParams(undefined)).toBe('—')
  })

  it('lê Retry-After do cabeçalho e, na falta, da mensagem', () => {
    const withHeader = parseApiError(axiosError(429, { code: 'RATE_LIMITED', message: 'x' }, { 'retry-after': '12' }))
    expect(withHeader.retryAfterSeconds).toBe(12)
    const fromMessage = parseApiError(
      axiosError(429, { code: 'RESEND_QUEUE_FULL', message: 'Fila cheia; tente novamente em 30 segundo(s)' }),
    )
    expect(fromMessage.retryAfterSeconds).toBe(30)
    expect(parseApiError(axiosError(409, { code: 'WEBHOOK_INACTIVE', message: 'm' })).retryAfterSeconds).toBeUndefined()
  })

  it('dá dica para WEBHOOK_INACTIVE', () => {
    expect(errorHint({ code: 'WEBHOOK_INACTIVE', message: 'm' })).toContain('Ative o webhook')
    expect(errorHint({ code: 'OUTRO', message: 'm' })).toBeUndefined()
  })
})
