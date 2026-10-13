import { Alert, Typography } from '@mui/material'
import type { ResolvedEnrichmentBlock } from '../../api/api'
import { blockModeLabel, translateWarning } from './resendUtils'

/** Bloco de enriquecimento efetivamente usado (modo, bloco fixo, data resolvida, origem/aproximação, avisos). */
export function ResolvedBlockInfo({ block }: { block: ResolvedEnrichmentBlock | undefined }) {
  if (!block) return null
  const warnings = block.warnings ?? []
  return (
    <Alert severity={warnings.length > 0 || block.approximate ? 'warning' : 'info'} sx={{ mt: 1 }}>
      <Typography variant="body2">
        Modo de bloco: <strong>{blockModeLabel(block.mode)}</strong>
        {block.blockNumber !== undefined && block.blockNumber !== null && <> · bloco usado: {block.blockNumber}</>}
        {block.resolvedFromDate && <> · resolvido a partir de {new Date(block.resolvedFromDate).toLocaleString('pt-BR')}</>}
        {block.source && <> · origem: {block.source}</>}
        {block.approximate && <> · resolução aproximada</>}
      </Typography>
      {warnings.map((warning) => (
        <Typography key={warning} variant="body2">
          {translateWarning(warning)}
        </Typography>
      ))}
    </Alert>
  )
}
