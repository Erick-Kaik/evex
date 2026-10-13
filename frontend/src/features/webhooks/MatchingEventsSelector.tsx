import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
import type { Event as IndexedEvent } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { useResolveBlockByDate } from './useWebhookResend'
import type { CapabilityLimits, MatchingEventsFilter } from './useWebhookResend'
import { summarizeParams, timezoneLabel, toIsoWithOffset, translateWarning } from './resendUtils'

type FilterMode = 'date' | 'block'

interface MatchingEventsSelectorProps {
  events: IndexedEvent[]
  isPending: boolean
  error: unknown
  hasNextPage: boolean
  isFetchingNextPage: boolean
  onLoadMore: () => void
  /** Total de eventos examinados nas páginas carregadas. */
  scanned: number
  /** A última página carregada parou no teto de varredura. */
  scanTruncated: boolean
  warnings: string[]
  onApplyFilter: (filter: MatchingEventsFilter) => void
  selected: ReadonlySet<string>
  onSelectedChange: (selected: Set<string>) => void
  limits: CapabilityLimits
}

function parseBlock(text: string): number | undefined | null {
  const trimmed = text.trim()
  if (trimmed === '') return undefined
  return /^\d+$/.test(trimmed) ? Number(trimmed) : null
}

/**
 * Seletor dos eventos que CASAM com o webhook (`matching-events`): filtro por data OU por bloco,
 * paginação por cursor ("carregar mais"), seleção múltipla e contador respeitando os limites de
 * `capabilities`. A busca em si (`useInfiniteQuery`) vive no componente pai.
 */
export function MatchingEventsSelector(props: MatchingEventsSelectorProps) {
  const { events, selected, onSelectedChange, limits } = props
  const [mode, setMode] = useState<FilterMode>('date')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [fromBlock, setFromBlock] = useState('')
  const [toBlock, setToBlock] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const [resolveDate, setResolveDate] = useState('')
  const [direction, setDirection] = useState<'FLOOR' | 'CEILING'>('FLOOR')
  const resolve = useResolveBlockByDate()

  function applyFilter() {
    if (mode === 'date') {
      const from = toIsoWithOffset(fromDate)
      const to = toIsoWithOffset(toDate)
      if (from && to && new Date(from) > new Date(to)) {
        setFormError('A data inicial deve ser anterior ou igual à final.')
        return
      }
      setFormError(null)
      props.onApplyFilter({ fromDate: from, toDate: to })
      return
    }
    const from = parseBlock(fromBlock)
    const to = parseBlock(toBlock)
    if (from === null || to === null) {
      setFormError('Os blocos devem ser inteiros não negativos.')
      return
    }
    if (from !== undefined && to !== undefined && from > to) {
      setFormError('O bloco inicial deve ser menor ou igual ao final.')
      return
    }
    setFormError(null)
    props.onApplyFilter({ fromBlock: from, toBlock: to })
  }

  function clearFilter() {
    setFromDate('')
    setToDate('')
    setFromBlock('')
    setToBlock('')
    setFormError(null)
    props.onApplyFilter({})
  }

  function handleResolve() {
    const date = toIsoWithOffset(resolveDate)
    if (date) resolve.mutate({ date, direction })
  }

  const allSelected = events.length > 0 && events.every((event) => selected.has(event.id))

  function toggleAll(checked: boolean) {
    const next = new Set(selected)
    for (const event of events) {
      if (checked) next.add(event.id)
      else next.delete(event.id)
    }
    onSelectedChange(next)
  }

  function toggleOne(id: string, checked: boolean) {
    const next = new Set(selected)
    if (checked) next.add(id)
    else next.delete(id)
    onSelectedChange(next)
  }

  const resolved = resolve.data
  const overPreview = selected.size > limits.previewMaxEvents
  const overResend = selected.size > limits.resendMaxEvents

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={mode}
          onChange={(_, value: FilterMode | null) => value && setMode(value)}
          aria-label="Tipo de filtro"
        >
          <ToggleButton value="date">Por data</ToggleButton>
          <ToggleButton value="block">Por bloco</ToggleButton>
        </ToggleButtonGroup>

        {mode === 'date' ? (
          <>
            <TextField size="small" type="datetime-local" label="De" value={fromDate} onChange={(e) => setFromDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField
              size="small"
              type="datetime-local"
              label="Até"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
              helperText={`Fuso: ${timezoneLabel()}`}
              slotProps={{ inputLabel: { shrink: true } }}
            />
          </>
        ) : (
          <>
            <TextField size="small" label="Bloco inicial" value={fromBlock} onChange={(e) => setFromBlock(e.target.value)} slotProps={{ htmlInput: { inputMode: 'numeric' } }} />
            <TextField size="small" label="Bloco final" value={toBlock} onChange={(e) => setToBlock(e.target.value)} slotProps={{ htmlInput: { inputMode: 'numeric' } }} />
          </>
        )}
        <Button variant="outlined" onClick={applyFilter}>
          Buscar
        </Button>
        <Button onClick={clearFilter}>Limpar filtro</Button>
      </Stack>
      {formError && <Alert severity="warning">{formError}</Alert>}

      {mode === 'block' && (
        <Box sx={{ p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>
            Resolver data em bloco
          </Typography>
          <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap', alignItems: 'flex-start' }}>
            <TextField
              size="small"
              type="datetime-local"
              label="Data"
              value={resolveDate}
              onChange={(e) => setResolveDate(e.target.value)}
              helperText={`Fuso: ${timezoneLabel()}`}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField select size="small" label="Direção" value={direction} onChange={(e) => setDirection(e.target.value as 'FLOOR' | 'CEILING')} sx={{ minWidth: 230 }}>
              <MenuItem value="FLOOR">Último bloco até a data</MenuItem>
              <MenuItem value="CEILING">Primeiro bloco a partir da data</MenuItem>
            </TextField>
            <Button variant="outlined" onClick={handleResolve} disabled={!toIsoWithOffset(resolveDate) || resolve.isPending}>
              Resolver data → bloco
            </Button>
          </Stack>
          {resolve.isError && <ApiErrorAlert error={resolve.error} />}
          {resolved && (
            <Box sx={{ mt: 1 }}>
              <Typography variant="body2">
                {resolved.blockNumber !== undefined && resolved.blockNumber !== null
                  ? `Bloco ${resolved.blockNumber}`
                  : 'Nenhum bloco encontrado para a data'}
                {' · origem: '}
                {resolved.source}
                {resolved.approximate && ' · aproximado'}
              </Typography>
              {(resolved.warnings ?? []).map((warning) => (
                <Typography key={warning} variant="caption" color="text.secondary" component="div">
                  {translateWarning(warning)}
                </Typography>
              ))}
              {resolved.blockNumber !== undefined && resolved.blockNumber !== null && (
                <Stack direction="row" sx={{ gap: 1, mt: 0.5 }}>
                  <Button size="small" onClick={() => setFromBlock(String(resolved.blockNumber))}>
                    Usar como bloco inicial
                  </Button>
                  <Button size="small" onClick={() => setToBlock(String(resolved.blockNumber))}>
                    Usar como bloco final
                  </Button>
                </Stack>
              )}
            </Box>
          )}
        </Box>
      )}

      {props.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando eventos...
        </Typography>
      )}
      {props.error != null && <ApiErrorAlert error={props.error} />}

      {props.warnings.map((warning) => (
        <Alert key={warning} severity="info">
          {translateWarning(warning)}
        </Alert>
      ))}
      {props.scanTruncated && (
        <Alert severity="warning">
          Busca limitada a {props.scanned} eventos varridos; refine o filtro ou continue ("carregar mais").
        </Alert>
      )}

      {!props.isPending && props.error == null && events.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          Nenhum evento que case com este webhook nessa janela.
        </Typography>
      )}

      {events.length > 0 && (
        <>
          <TableContainer sx={{ maxHeight: 320, border: 1, borderColor: 'divider', borderRadius: 1 }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={allSelected}
                      indeterminate={!allSelected && events.some((event) => selected.has(event.id))}
                      onChange={(e) => toggleAll(e.target.checked)}
                      slotProps={{ input: { 'aria-label': 'selecionar todos os eventos carregados' } }}
                    />
                  </TableCell>
                  <TableCell>Bloco</TableCell>
                  <TableCell>logIndex</TableCell>
                  <TableCell>Evento</TableCell>
                  <TableCell>Timestamp</TableCell>
                  <TableCell>Parâmetros</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {events.map((event) => (
                  <TableRow key={event.id} hover selected={selected.has(event.id)}>
                    <TableCell padding="checkbox">
                      <Checkbox
                        checked={selected.has(event.id)}
                        onChange={(e) => toggleOne(event.id, e.target.checked)}
                        slotProps={{ input: { 'aria-label': `selecionar evento do bloco ${event.blockNumber} log ${event.logIndex}` } }}
                      />
                    </TableCell>
                    <TableCell>{event.blockNumber}</TableCell>
                    <TableCell>{event.logIndex}</TableCell>
                    <TableCell>{event.eventName ?? '—'}</TableCell>
                    <TableCell>{event.blockTimestamp ? new Date(event.blockTimestamp).toLocaleString('pt-BR') : '—'}</TableCell>
                    <TableCell sx={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontFamily: 'monospace', fontSize: '0.75rem' }} title={summarizeParams(event.decodedParameters)}>
                      {summarizeParams(event.decodedParameters)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
            <Chip label={`${selected.size} selecionado(s)`} color={selected.size > 0 ? 'primary' : 'default'} />
            <Typography variant="caption" color="text.secondary">
              {events.length} carregado(s) · {props.scanned} examinado(s)
            </Typography>
            {props.hasNextPage && (
              <Button size="small" onClick={props.onLoadMore} disabled={props.isFetchingNextPage}>
                {props.isFetchingNextPage ? 'Carregando...' : 'Carregar mais'}
              </Button>
            )}
          </Stack>
        </>
      )}

      <Typography variant="caption" color="text.secondary">
        Limites efetivos: pré-visualizar até {limits.previewMaxEvents} evento(s); reenviar até {limits.resendMaxEvents} por requisição
        {limits.isFallback && ' (valores padrão conservadores: não foi possível consultar as capacidades do servidor)'}.
      </Typography>
      {selected.size > 0 && overPreview && (
        <Alert severity="warning">
          Para pré-visualizar, selecione no máximo {limits.previewMaxEvents} evento(s) (selecionados: {selected.size}).
        </Alert>
      )}
      {selected.size > 0 && overResend && (
        <Alert severity="warning">
          Para reenviar, selecione no máximo {limits.resendMaxEvents} evento(s) (selecionados: {selected.size}).
        </Alert>
      )}
    </Stack>
  )
}
