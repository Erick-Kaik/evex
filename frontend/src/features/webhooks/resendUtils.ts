import { isAxiosError } from 'axios'
import type { ApiError } from '../../api/api'

/**
 * Funções puras da pré-visualização/reenvio de webhooks (E33): tradução de códigos extensíveis para
 * texto PT-BR e conversões de data. Princípio: **valor desconhecido = texto cru** (o backend pode
 * acrescentar códigos sem quebrar a UI).
 */

/** Limites conservadores usados quando `GET /v1/capabilities` falha. */
export const DEFAULT_PREVIEW_MAX_EVENTS = 5
export const DEFAULT_RESEND_MAX_EVENTS = 50

export const BLOCK_MODES = ['EVENT_BLOCK', 'LATEST', 'BLOCK_NUMBER', 'BLOCK_AT_DATE'] as const
export type BlockMode = (typeof BLOCK_MODES)[number]

export const BLOCK_MODE_LABELS: Record<BlockMode, string> = {
  EVENT_BLOCK: 'Bloco do evento (padrão)',
  LATEST: 'Estado atual (latest)',
  BLOCK_NUMBER: 'Bloco específico',
  BLOCK_AT_DATE: 'Bloco de uma data',
}

export const BLOCK_MODE_HELP: Record<BlockMode, string> = {
  EVENT_BLOCK:
    'Lê o estado no bloco do evento e exige que o nó guarde estado histórico. Um erro genérico (ex.: -32603 "Internal error") não prova que o nó não guarda histórico: compare com "Estado atual" ou use "Diagnosticar falhas".',
  LATEST: 'Lê o estado atual da cadeia (reavaliado a cada tentativa de entrega).',
  BLOCK_NUMBER: 'Lê o estado em um bloco fixo que você informa.',
  BLOCK_AT_DATE:
    'O servidor converte a data no último bloco até ela (arredondamento para baixo). A resolução é aproximada: só eventos ingeridos têm timestamp.',
}

/** Rótulo de um modo (string extensível; desconhecido = texto cru). */
export function blockModeLabel(mode: string | undefined): string {
  if (!mode) return BLOCK_MODE_LABELS.EVENT_BLOCK
  return (BLOCK_MODE_LABELS as Record<string, string>)[mode] ?? mode
}

/** Separa `CODE:n` em código e contagem. */
function splitWarning(warning: string): { code: string; count?: string } {
  const index = warning.indexOf(':')
  if (index < 0) return { code: warning }
  return { code: warning.slice(0, index), count: warning.slice(index + 1) }
}

/** Traduz um aviso extensível (`CODE` ou `CODE:contagem`); código desconhecido = texto cru. */
export function translateWarning(warning: string): string {
  const { code, count } = splitWarning(warning)
  switch (code) {
    case 'DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP':
      return `${count ?? 'Alguns'} evento(s) sem timestamp de bloco ficaram de fora do filtro por data (só eventos ingeridos têm timestamp).`
    case 'MODEL_STATE_NEWER_THAN_EVENT':
      return 'O payload usa o estado ATUAL do modelo, que avançou desde o evento.'
    case 'RANGE_TRUNCATED':
      return 'O intervalo tinha mais eventos que o limite por reenvio: só os primeiros foram enfileirados. Refine o intervalo e reenvie o restante.'
    case 'SCAN_TRUNCATED':
      return 'A varredura atingiu o limite de eventos examinados: refine o filtro ou continue.'
    case 'DATE_BEFORE_GENESIS':
      return 'A data é anterior ao bloco gênesis.'
    case 'DATE_AFTER_LATEST':
      return 'A data é posterior ao bloco mais recente.'
    case 'RPC_BUDGET_EXHAUSTED':
      return 'O limite de consultas ao nó foi atingido: o bloco resolvido é aproximado.'
    default:
      return warning
  }
}

const SKIP_REASONS: Record<string, string> = {
  NOT_MATCHING: 'não casa com o webhook (contrato, evento ou filtros)',
  ALREADY_PENDING: 'já existe um reenvio pendente para este evento',
  EVENT_NOT_DECODED: 'evento ainda não decodificado',
  EVENT_NOT_FOUND: 'evento não encontrado',
  PAYLOAD_RENDER_FAILED: 'falha ao renderizar o payload',
}

const NOT_MATCHING_REASONS: Record<string, string> = {
  CONTRACT_MISMATCH: 'contrato diferente do configurado',
  EVENT_NAME_MISMATCH: 'nome do evento diferente do configurado',
  FILTER_NOT_MET: 'filtros do webhook não atendidos',
  EVENT_NOT_DECODED: 'evento ainda não decodificado',
  EVENT_NOT_FOUND: 'evento não encontrado',
  PAYLOAD_RENDER_FAILED: 'falha ao renderizar o payload',
}

export function translateSkipReason(reason: string): string {
  return SKIP_REASONS[reason] ?? reason
}

export function translateNotMatchingReason(reason: string): string {
  return NOT_MATCHING_REASONS[reason] ?? SKIP_REASONS[reason] ?? reason
}

const VERDICT_TEXTS: Record<string, string> = {
  FAILS_EVERYWHERE: 'A chamada falha também no estado atual e no bloco do evento (indício de problema na chamada ou no contrato, não no histórico).',
  FAILS_ONLY_AT_BLOCK: 'A chamada falha no bloco do evento mas funciona no estado atual (indício, não prova, de estado histórico indisponível ou contrato inexistente nesse bloco).',
  OK_AT_LATEST_ONLY: 'A chamada funciona só no estado atual (indício de que o nó não serve estado nesse bloco; considere o modo "Estado atual").',
  INCONCLUSIVE: 'Não foi possível concluir (falha de transporte ou resultados contraditórios nas reexecuções).',
}

export function verdictText(verdict: string): string {
  return VERDICT_TEXTS[verdict] ?? verdict
}

const PROBE_TEXTS: Record<string, string> = {
  PROBE_STATE_OK: 'sonda: o nó respondeu com código do contrato nesse bloco',
  PROBE_STATE_UNAVAILABLE: 'sonda: o nó não devolveu estado nesse bloco',
}

export function probeText(probe: string): string {
  return PROBE_TEXTS[probe] ?? probe
}

/** Formata um JSON (string) com indentação; se não for JSON válido, devolve o texto como veio. */
export function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** Resumo curto `chave=valor` dos parâmetros decodificados de um evento. */
export function summarizeParams(params: { [key: string]: unknown } | undefined): string {
  if (!params) return '—'
  const entries = Object.entries(params)
  if (entries.length === 0) return '—'
  return entries
    .map(([key, value]) => `${key}=${typeof value === 'string' ? value : JSON.stringify(value)}`)
    .join(', ')
}

// ---------------- Datas ----------------

function pad(value: number): string {
  return String(Math.abs(value)).padStart(2, '0')
}

/** Offset do fuso local no formato `+03:00`/`-03:00`, para a data informada. */
function offsetOf(date: Date): string {
  const minutes = -date.getTimezoneOffset()
  const sign = minutes >= 0 ? '+' : '-'
  return `${sign}${pad(Math.trunc(Math.abs(minutes) / 60))}:${pad(Math.abs(minutes) % 60)}`
}

/**
 * Converte o valor de um `<input type="datetime-local">` (`2026-02-01T10:30`, sem fuso) para
 * ISO-8601 com o offset do usuário. Vazio/inválido = `undefined`.
 */
export function toIsoWithOffset(local: string): string | undefined {
  if (!local) return undefined
  const date = new Date(local)
  if (Number.isNaN(date.getTime())) return undefined
  const withSeconds = local.length === 16 ? `${local}:00` : local
  return `${withSeconds}${offsetOf(date)}`
}

/** Fuso do usuário para exibição, ex.: `America/Sao_Paulo (UTC-03:00)`. */
export function timezoneLabel(): string {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  const offset = `UTC${offsetOf(new Date())}`
  return zone ? `${zone} (${offset})` : offset
}

// ---------------- Erros ----------------

export interface ParsedApiError {
  status?: number
  code?: string
  message: string
  /** Segundos de espera sugeridos (cabeçalho `Retry-After` ou, na falta, a mensagem do backend). */
  retryAfterSeconds?: number
}

function readHeader(headers: unknown, name: string): string | undefined {
  if (!headers || typeof headers !== 'object') return undefined
  const record = headers as Record<string, unknown> & { get?: (key: string) => unknown }
  const direct = record[name] ?? record[name.toLowerCase()]
  const value = direct ?? (typeof record.get === 'function' ? record.get(name) : undefined)
  return typeof value === 'string' || typeof value === 'number' ? String(value) : undefined
}

/** Extrai status, `code`, mensagem e `Retry-After` de um erro do axios. */
export function parseApiError(error: unknown): ParsedApiError {
  if (!isAxiosError<ApiError>(error)) {
    return { message: error instanceof Error ? error.message : 'Erro inesperado.' }
  }
  const data = error.response?.data
  const message = data && typeof data.message === 'string' ? data.message : error.message
  let retryAfterSeconds: number | undefined
  if (error.response?.status === 429) {
    const header = Number(readHeader(error.response.headers, 'Retry-After'))
    if (Number.isFinite(header) && header > 0) {
      retryAfterSeconds = Math.ceil(header)
    } else {
      // O cabeçalho pode não estar exposto ao navegador (CORS); a mensagem do backend repete o valor.
      const match = /(\d+)\s*segundo/i.exec(message)
      retryAfterSeconds = match ? Number(match[1]) : undefined
    }
  }
  return { status: error.response?.status, code: data?.code, message, retryAfterSeconds }
}

/** Dica adicional por código de erro, além da mensagem do backend. */
export function errorHint(error: ParsedApiError): string | undefined {
  switch (error.code) {
    case 'WEBHOOK_INACTIVE':
      return 'Ative o webhook ou informe uma URL de teste válida.'
    case 'BLOCK_RESOLUTION_FAILED':
      return 'Não foi possível converter a data em bloco; tente outra data ou informe o número do bloco.'
    default:
      return undefined
  }
}
