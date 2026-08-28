import { useState } from 'react'
import type { ChangeEvent, FormEvent } from 'react'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  FormLabel,
  Radio,
  RadioGroup,
  TextField,
  Typography,
} from '@mui/material'
import { AbiSource, DetectionLevel } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { useInterfaceSuggestions, useRegisterAbi } from './useContracts'

interface AbiRegisterDialogProps {
  address: string
  open: boolean
  onClose: () => void
}

const SOURCE_OPTIONS = [
  { value: AbiSource.UploadText, label: 'Colar texto' },
  { value: AbiSource.UploadFile, label: 'Enviar arquivo (.abi)' },
  { value: AbiSource.Blockscout, label: 'Buscar no Blockscout' },
  { value: AbiSource.AutoDetected, label: 'Detectar automaticamente' },
] as const

/**
 * Cadastro de nova versão de ABI em modal (E13/DEC-060) — mesmas 4 origens de antes (colar
 * texto/arquivo/Blockscout/detecção automática), agora em `Dialog` em vez de inline na página.
 */
export function AbiRegisterDialog({ address, open, onClose }: AbiRegisterDialogProps) {
  const registerAbi = useRegisterAbi(address)
  const suggestions = useInterfaceSuggestions(address)

  const [source, setSource] = useState<AbiSource>(AbiSource.UploadText)
  const [abiContent, setAbiContent] = useState('')
  const [fileName, setFileName] = useState('')
  const [validFromBlock, setValidFromBlock] = useState('')

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    setFileName(file.name)
    setAbiContent(await file.text())
  }

  const hasFullSuggestion = suggestions.data?.some((s) => s.level === DetectionLevel.Full) ?? false

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    registerAbi.mutate(
      {
        abi: source === AbiSource.UploadText || source === AbiSource.UploadFile ? abiContent : undefined,
        source,
        validFromBlock: validFromBlock === '' ? undefined : Number(validFromBlock),
      },
      {
        onSuccess: () => {
          setAbiContent('')
          setFileName('')
          setValidFromBlock('')
          suggestions.reset()
          onClose()
        },
      },
    )
  }

  const canSubmit =
    (source === AbiSource.UploadText && abiContent.trim().length > 0) ||
    (source === AbiSource.UploadFile && abiContent.trim().length > 0) ||
    source === AbiSource.Blockscout ||
    (source === AbiSource.AutoDetected && hasFullSuggestion)

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Cadastrar nova versão de ABI</DialogTitle>
      <DialogContent dividers>
        <Box component="form" id="abi-register-form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <FormControl>
            <FormLabel id="abi-source-label">Origem</FormLabel>
            <RadioGroup
              aria-labelledby="abi-source-label"
              value={source}
              onChange={(event) => setSource(event.target.value as AbiSource)}
            >
              {SOURCE_OPTIONS.map((option) => (
                <FormControlLabel key={option.value} value={option.value} control={<Radio size="small" />} label={option.label} />
              ))}
            </RadioGroup>
          </FormControl>

          {source === AbiSource.UploadText && (
            <TextField
              label="Conteúdo do ABI (JSON)"
              multiline
              minRows={6}
              value={abiContent}
              onChange={(event) => setAbiContent(event.target.value)}
              placeholder='[{"type":"event","name":"Transfer", ...}]'
              slotProps={{ htmlInput: { style: { fontFamily: 'monospace', fontSize: '0.75rem' } } }}
            />
          )}

          {source === AbiSource.UploadFile && (
            <Box>
              <Button variant="outlined" component="label">
                Selecionar arquivo .abi/.json
                <input type="file" hidden accept=".abi,.json,application/json" onChange={(event) => void handleFileChange(event)} />
              </Button>
              {fileName && (
                <Typography variant="caption" sx={{ display: 'block', mt: 1 }}>
                  Selecionado: {fileName}
                </Typography>
              )}
            </Box>
          )}

          {source === AbiSource.Blockscout && (
            <Alert severity="info">A ABI será buscada no Blockscout configurado no momento do cadastro.</Alert>
          )}

          {source === AbiSource.AutoDetected && (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              <Button variant="outlined" size="small" onClick={() => suggestions.mutate()} disabled={suggestions.isPending} sx={{ alignSelf: 'flex-start' }}>
                {suggestions.isPending ? 'Detectando...' : 'Detectar interfaces'}
              </Button>

              {suggestions.isError && <ApiErrorAlert error={suggestions.error} />}

              {suggestions.data && suggestions.data.length === 0 && (
                <Alert severity="info">Nenhuma interface padrão reconhecida.</Alert>
              )}

              {suggestions.data && suggestions.data.length > 0 && (
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                  {suggestions.data.map((suggestion) => (
                    <Typography key={suggestion.templateName} variant="body2">
                      <strong>{suggestion.templateName}</strong> —{' '}
                      <Box
                        component="span"
                        sx={{ color: suggestion.level === DetectionLevel.Full ? 'success.main' : 'warning.main' }}
                      >
                        {suggestion.level}
                      </Box>
                    </Typography>
                  ))}
                </Box>
              )}

              {suggestions.data && !hasFullSuggestion && (
                <Alert severity="warning">
                  Nenhuma sugestão com match total (FULL) — não é possível confirmar como detecção
                  automática. Cadastre manualmente (colar texto/arquivo) ou tente o Blockscout.
                </Alert>
              )}
            </Box>
          )}

          <TextField
            label="Bloco inicial de validade (opcional, default 0)"
            type="number"
            slotProps={{ htmlInput: { min: 0 } }}
            sx={{ maxWidth: 260 }}
            value={validFromBlock}
            onChange={(event) => setValidFromBlock(event.target.value)}
          />

          {registerAbi.isError && <ApiErrorAlert error={registerAbi.error} />}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancelar</Button>
        <Button type="submit" form="abi-register-form" variant="contained" disabled={!canSubmit || registerAbi.isPending}>
          {registerAbi.isPending ? 'Cadastrando...' : 'Cadastrar versão de ABI'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
