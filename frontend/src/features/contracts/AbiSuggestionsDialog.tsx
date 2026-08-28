import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from '@mui/material'
import { DetectionLevel } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { useInterfaceSuggestions } from './useContracts'

interface AbiSuggestionsDialogProps {
  address: string
  open: boolean
  onClose: () => void
}

/**
 * Preview somente-leitura das sugestões de detecção automática (doc §4.5) — separado do modal de
 * cadastro de ABI (`AbiRegisterDialog`) pra permitir só "espiar" o resultado sem já ter decidido
 * cadastrar. Dispara a consulta ao abrir (chamada no `onClick` do botão que abre o modal, não em
 * `useEffect` — a consulta em si é uma mutation sob demanda, mesma porta usada em `AbiRegisterDialog`).
 */
export function AbiSuggestionsDialog({ address, open, onClose }: AbiSuggestionsDialogProps) {
  const suggestions = useInterfaceSuggestions(address)

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Sugestões de detecção automática</DialogTitle>
      <DialogContent dividers>
        {suggestions.isIdle && (
          <Button variant="outlined" onClick={() => suggestions.mutate()}>
            Detectar interfaces
          </Button>
        )}

        {suggestions.isPending && <Typography color="text.secondary">Detectando...</Typography>}

        {suggestions.isError && <ApiErrorAlert error={suggestions.error} />}

        {suggestions.data && suggestions.data.length === 0 && (
          <Alert severity="info">Nenhuma interface padrão (ERC20/ERC721/ERC1155) reconhecida para este contrato.</Alert>
        )}

        {suggestions.data && suggestions.data.length > 0 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
            {suggestions.data.map((suggestion) => (
              <Typography key={suggestion.templateName} variant="body2">
                <strong>{suggestion.templateName}</strong> —{' '}
                <Box component="span" sx={{ color: suggestion.level === DetectionLevel.Full ? 'success.main' : 'warning.main' }}>
                  {suggestion.level}
                </Box>
              </Typography>
            ))}
            <Typography variant="caption" color="text.secondary">
              FULL = match total (pode ser cadastrado automaticamente). PARTIAL = só sugestão, não
              confirma a interface sozinho. Use "Cadastrar nova versão" com origem "Detectar
              automaticamente" para aplicar uma sugestão FULL.
            </Typography>
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Fechar</Button>
      </DialogActions>
    </Dialog>
  )
}
