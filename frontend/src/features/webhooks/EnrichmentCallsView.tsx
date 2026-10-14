import { Alert, AlertTitle, Box, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import type { EnrichmentCallResult, EnrichmentDiagnosis, EnrichmentFailureSummary } from '../../api/api'
import { probeText, verdictText } from './resendUtils'

const MONO = { fontFamily: 'monospace', fontSize: '0.8125rem', wordBreak: 'break-word' } as const

function statusChip(status: string) {
  // Status extensível: OK/ERROR conhecidos; qualquer outro aparece cru, sem cor especial.
  if (status === 'OK') return <Chip size="small" color="success" label="OK" />
  if (status === 'ERROR') return <Chip size="small" color="error" label="ERRO" />
  return <Chip size="small" label={status} />
}

/** Falha de UMA chamada: tudo o que o backend devolveu, inclusive a mensagem BRUTA da rede. */
function CallError({ call }: { call: EnrichmentCallResult }) {
  return (
    <Stack spacing={0.5}>
      <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
        {call.code && <Chip size="small" variant="outlined" color="error" label={`code: ${call.code}`} />}
        {call.rpcCode !== undefined && call.rpcCode !== null && (
          <Chip size="small" variant="outlined" label={`rpcCode: ${call.rpcCode}`} />
        )}
        {call.classification && <Chip size="small" variant="outlined" label={`classificação: ${call.classification}`} />}
        {call.blockUsed && <Chip size="small" variant="outlined" label={`bloco: ${call.blockUsed}`} />}
      </Stack>
      {call.message && (
        <Typography variant="body2" sx={MONO}>
          {call.message}
        </Typography>
      )}
      {call.rpcData && (
        <Typography variant="body2" sx={MONO}>
          rpcData: {call.rpcData}
        </Typography>
      )}
    </Stack>
  )
}

/** Tabela por chamada de enriquecimento (preview e histórico de tentativas). */
export function EnrichmentCallsTable({ calls }: { calls: EnrichmentCallResult[] }) {
  if (calls.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Nenhuma chamada de enriquecimento.
      </Typography>
    )
  }
  return (
    <Table size="small" aria-label="chamadas de enriquecimento">
      <TableHead>
        <TableRow>
          <TableCell>Método</TableCell>
          <TableCell>Contrato</TableCell>
          <TableCell>Status</TableCell>
          <TableCell>Resultado / erro</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {calls.map((call, index) => (
          <TableRow key={`${call.profileId}-${call.methodName}-${index}`}>
            <TableCell sx={MONO}>{call.methodName}</TableCell>
            <TableCell sx={MONO}>{call.targetContractAddress}</TableCell>
            <TableCell>{statusChip(call.status)}</TableCell>
            <TableCell>
              {call.status === 'OK' && (
                <Box sx={MONO}>
                  {call.outputs && Object.keys(call.outputs).length > 0
                    ? Object.entries(call.outputs).map(([key, value]) => (
                        <div key={key}>
                          {key} = {value}
                        </div>
                      ))
                    : '—'}
                  {call.blockUsed && <div>bloco: {call.blockUsed}</div>}
                </Box>
              )}
              {call.status !== 'OK' && <CallError call={call} />}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** Falhas agregadas por (contrato, método, bloco, código). */
export function FailuresSummaryList({ failures }: { failures: EnrichmentFailureSummary[] | undefined }) {
  if (!failures || failures.length === 0) return null
  return (
    <Alert severity="error" sx={{ mt: 1 }}>
      <AlertTitle>Falhas de enriquecimento (agregadas)</AlertTitle>
      <Box component="ul" sx={{ m: 0, pl: 2 }}>
        {failures.map((failure, index) => (
          <li key={index}>
            <span style={MONO}>
              {failure.contract} · {failure.method} · bloco {failure.block ?? '—'} · {failure.code}
              {failure.rpcCode !== undefined && failure.rpcCode !== null ? ` (rpcCode ${failure.rpcCode})` : ''}
            </span>{' '}
            × {failure.count}
            {failure.message && (
              <Typography variant="body2" sx={MONO}>
                {failure.message}
              </Typography>
            )}
          </li>
        ))}
      </Box>
    </Alert>
  )
}

/** Diagnóstico comparativo — SEMPRE apresentado como indício, nunca como causa. */
export function DiagnosesList({ diagnoses }: { diagnoses: EnrichmentDiagnosis[] | undefined }) {
  if (!diagnoses || diagnoses.length === 0) return null
  return (
    <Alert severity="info" sx={{ mt: 1 }}>
      <AlertTitle>Diagnóstico (INDÍCIO, não é causa comprovada)</AlertTitle>
      {diagnoses.map((diagnosis, index) => (
        <Box key={index} sx={{ mb: 1 }}>
          <Typography variant="body2" sx={MONO}>
            {diagnosis.targetContractAddress} · {diagnosis.methodName}
          </Typography>
          <Chip size="small" label={`Indício: ${diagnosis.verdict}`} sx={{ mr: 1 }} />
          <Typography variant="body2" component="span">
            {verdictText(diagnosis.verdict)}
          </Typography>
          <Typography variant="body2" sx={MONO}>
            {diagnosis.atLatest && `latest: ${diagnosis.atLatest.ok ? 'OK' : `falhou${diagnosis.atLatest.code ? ` (${diagnosis.atLatest.code})` : ''}`}`}
            {diagnosis.atLatest && diagnosis.atEventBlock && ' · '}
            {diagnosis.atEventBlock &&
              `bloco ${diagnosis.atEventBlock.block}: ${diagnosis.atEventBlock.ok ? 'OK' : `falhou${diagnosis.atEventBlock.code ? ` (${diagnosis.atEventBlock.code})` : ''}`}`}
          </Typography>
          {diagnosis.probe && (
            <Typography variant="caption" color="text.secondary">
              {diagnosis.probe}
              {probeText(diagnosis.probe) !== diagnosis.probe && ` — ${probeText(diagnosis.probe)}`}
            </Typography>
          )}
        </Box>
      ))}
    </Alert>
  )
}
