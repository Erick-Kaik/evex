import {
  Box,
  Checkbox,
  FormControlLabel,
  IconButton,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import DeleteIcon from '@mui/icons-material/Delete'
import AddIcon from '@mui/icons-material/Add'
import type { EnrichmentCall, Network } from '../../api/api'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { MethodPicker } from '../../components/MethodPicker'
import type { CatalogMethod } from '../../components/contractCatalog'
import { useContractMethodCatalog } from '../../components/contractCatalog'

interface EnrichmentCallRowProps {
  index: number
  call: EnrichmentCall
  /** Rede do Perfil de Enriquecimento — filtra o `<ContractAutocomplete>` do contrato-alvo. */
  network: Network
  onChange: (index: number, next: EnrichmentCall) => void
  onRemove: (index: number) => void
}

/**
 * Uma linha do builder de `calls` de um Perfil de Enriquecimento (E19/DEC-082/083): contrato-alvo
 * (`<ContractAutocomplete>`, não precisa ser o contrato do evento — DEC-082), método `view`/`pure`
 * (`<MethodPicker>`, catálogo `GET /v1/contracts/{address}/methods`), um `TextField` de argumento por
 * entrada do método (aceita literal, `{{decodedParameters.x}}` ou `{{enriched.y}}` de uma chamada
 * anterior) e a lista de saídas mapeadas (`outputIndex` → `placeholder`).
 *
 * Quando o método é escolhido/trocado pelo usuário, `args`/`outputs` são re-populados do zero a partir
 * do catálogo (mesmo racional de `handleContractRefChange` em `WebhookForm`/`BalanceMonitorForm`: trocar
 * a fonte de dados invalida os campos derivados dela) — `outputs` pré-marcado com todas as saídas do
 * método, `placeholder` sugerido a partir do nome Solidity (`outputN` se anônimo). O usuário pode
 * desmarcar saídas que não quer expor e renomear o `placeholder` de qualquer uma.
 *
 * Quando o método da chamada não está (mais) no catálogo carregado (ABI trocada, ou catálogo ainda
 * carregando durante a edição de um perfil existente), cai num editor livre de `args`/`outputs` — não
 * bloqueia a edição do resto do perfil por causa disso (mesmo espírito não-bloqueante de `<EventPicker>`).
 */
export function EnrichmentCallRow({ index, call, network, onChange, onRemove }: EnrichmentCallRowProps) {
  const { methodsByName } = useContractMethodCatalog(call.targetContractAddress || undefined)
  const method = methodsByName.get(call.methodName)

  const contractRef: ContractRef | null = call.targetContractAddress
    ? { address: call.targetContractAddress, network }
    : null

  function handleContractChange(next: ContractRef | null) {
    onChange(index, {
      ...call,
      targetContractAddress: next?.address ?? '',
      methodName: '',
      args: [],
      outputs: [],
    })
  }

  function handleMethodChange(methodName: string, catalogMethod: CatalogMethod | undefined) {
    onChange(index, {
      ...call,
      methodName,
      args: catalogMethod ? catalogMethod.inputs.map(() => '') : [],
      outputs: catalogMethod
        ? catalogMethod.outputs.map((output, outputIndex) => ({
            outputIndex,
            placeholder: output.name ?? `output${outputIndex}`,
          }))
        : [],
    })
  }

  function updateArg(argIndex: number, value: string) {
    const nextArgs = [...call.args]
    nextArgs[argIndex] = value
    onChange(index, { ...call, args: nextArgs })
  }

  function toggleOutput(outputIndex: number, included: boolean, defaultPlaceholder: string) {
    if (included) {
      onChange(index, {
        ...call,
        outputs: [...call.outputs, { outputIndex, placeholder: defaultPlaceholder }].sort(
          (a, b) => a.outputIndex - b.outputIndex,
        ),
      })
    } else {
      onChange(index, { ...call, outputs: call.outputs.filter((o) => o.outputIndex !== outputIndex) })
    }
  }

  function updateOutputPlaceholder(outputIndex: number, placeholder: string) {
    onChange(index, {
      ...call,
      outputs: call.outputs.map((o) => (o.outputIndex === outputIndex ? { ...o, placeholder } : o)),
    })
  }

  function addFreeformOutput() {
    onChange(index, { ...call, outputs: [...call.outputs, { outputIndex: call.outputs.length, placeholder: '' }] })
  }

  function updateFreeformOutput(outputArrayIndex: number, patch: Partial<{ outputIndex: number; placeholder: string }>) {
    onChange(index, {
      ...call,
      outputs: call.outputs.map((o, i) => (i === outputArrayIndex ? { ...o, ...patch } : o)),
    })
  }

  function removeFreeformOutput(outputArrayIndex: number) {
    onChange(index, { ...call, outputs: call.outputs.filter((_, i) => i !== outputArrayIndex) })
  }

  const argCount = method ? method.inputs.length : call.args.length

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="subtitle2">Chamada {index + 1}</Typography>
        <IconButton size="small" color="error" onClick={() => onRemove(index)} aria-label="remover chamada">
          <DeleteIcon fontSize="small" />
        </IconButton>
      </Stack>

      <Stack sx={{ gap: 2 }}>
        <ContractAutocomplete
          value={contractRef}
          onChange={handleContractChange}
          network={network}
          label="Contrato-alvo"
          required
        />

        <MethodPicker
          address={call.targetContractAddress || undefined}
          value={call.methodName}
          onChange={handleMethodChange}
          required
        />

        {argCount > 0 && (
          <Box>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
              Argumentos (literal, {'{{decodedParameters.x}}'} ou {'{{enriched.y}}'} de uma chamada anterior)
            </Typography>
            <Stack sx={{ gap: 1 }}>
              {Array.from({ length: argCount }).map((_, argIndex) => {
                const input = method?.inputs[argIndex]
                const isArrayInput = input ? /\[\d*]$/.test(input.type) : false
                return (
                  <TextField
                    key={argIndex}
                    size="small"
                    fullWidth
                    label={input ? `${input.name} (${input.type})` : `arg ${argIndex + 1}`}
                    value={call.args[argIndex] ?? ''}
                    onChange={(event) => updateArg(argIndex, event.target.value)}
                    placeholder={
                      isArrayInput
                        ? 'JSON array de strings, ex. ["1","2"] — ou {{decodedParameters.x}}'
                        : 'literal ou {{decodedParameters.x}}'
                    }
                    helperText={isArrayInput ? 'Tipo array (DEC-090): cada elemento como string dentro de um JSON array' : undefined}
                  />
                )
              })}
            </Stack>
          </Box>
        )}

        <Box>
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
            Saídas expostas como {'{{enriched.<placeholder>}}'}
          </Typography>

          {method ? (
            <Stack sx={{ gap: 1 }}>
              {method.outputs.length === 0 && (
                <Typography variant="caption" color="text.secondary">
                  Este método não declara saídas.
                </Typography>
              )}
              {method.outputs.map((output, outputIndex) => {
                const mapping = call.outputs.find((o) => o.outputIndex === outputIndex)
                const included = Boolean(mapping)
                const defaultPlaceholder = output.name ?? `output${outputIndex}`
                return (
                  <Stack key={outputIndex} direction="row" sx={{ alignItems: 'center', gap: 1 }}>
                    <FormControlLabel
                      sx={{ minWidth: 160 }}
                      control={
                        <Checkbox
                          size="small"
                          checked={included}
                          onChange={(event) => toggleOutput(outputIndex, event.target.checked, defaultPlaceholder)}
                        />
                      }
                      label={`saída ${outputIndex} (${output.type})`}
                    />
                    <TextField
                      size="small"
                      label="placeholder"
                      disabled={!included}
                      value={mapping?.placeholder ?? ''}
                      onChange={(event) => updateOutputPlaceholder(outputIndex, event.target.value)}
                      sx={{ flexGrow: 1 }}
                    />
                  </Stack>
                )
              })}
            </Stack>
          ) : (
            <Stack sx={{ gap: 1 }}>
              {call.outputs.map((output, outputArrayIndex) => (
                <Stack key={outputArrayIndex} direction="row" sx={{ alignItems: 'center', gap: 1 }}>
                  <TextField
                    size="small"
                    type="number"
                    label="índice"
                    value={output.outputIndex}
                    onChange={(event) =>
                      updateFreeformOutput(outputArrayIndex, { outputIndex: Number(event.target.value) })
                    }
                    sx={{ width: 100 }}
                  />
                  <TextField
                    size="small"
                    label="placeholder"
                    value={output.placeholder}
                    onChange={(event) => updateFreeformOutput(outputArrayIndex, { placeholder: event.target.value })}
                    sx={{ flexGrow: 1 }}
                  />
                  <IconButton
                    size="small"
                    color="error"
                    onClick={() => removeFreeformOutput(outputArrayIndex)}
                    aria-label="remover saída"
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Stack>
              ))}
              <Box>
                <IconButton size="small" onClick={addFreeformOutput} aria-label="adicionar saída">
                  <AddIcon fontSize="small" />
                </IconButton>
                <Typography variant="caption" color="text.secondary">
                  {call.targetContractAddress && call.methodName
                    ? 'Método fora do catálogo (ABI antiga ou ainda carregando) — informe índice/placeholder manualmente.'
                    : 'Escolha o contrato e o método para pré-popular as saídas automaticamente.'}
                </Typography>
              </Box>
            </Stack>
          )}
        </Box>
      </Stack>
    </Paper>
  )
}
