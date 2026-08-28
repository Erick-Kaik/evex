import { useState } from 'react'
import type { FormEvent } from 'react'
import { useParams } from 'react-router'
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
import type { Contract } from '../../api/api'
import { ContractStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { AbiSection } from './AbiSection'
import { useContract, useUpdateContract, useUpdateContractStatus } from './useContracts'

const STATUS_OPTIONS = [ContractStatus.Active, ContractStatus.Paused, ContractStatus.Archived]

const STATUS_COLOR: Record<ContractStatus, 'success' | 'default' | 'warning'> = {
  [ContractStatus.Active]: 'success',
  [ContractStatus.Paused]: 'warning',
  [ContractStatus.Archived]: 'default',
}

/** Detalhe de um contrato monitorado: dados + edição + toggle de status + seção de ABI (Cards separados). */
export function ContractDetailPage() {
  const { address } = useParams<{ address: string }>()
  const contract = useContract(address)
  const updateStatus = useUpdateContractStatus(address ?? '')

  if (!address) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Endereço do contrato ausente na URL.')} />
      </Box>
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      {contract.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando...
        </Typography>
      )}
      {contract.isError && <ApiErrorAlert error={contract.error} />}

      {contract.data && (
        <>
          <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
            <Typography variant="h5" sx={{ fontWeight: 600 }}>
              {contract.data.name}
            </Typography>
            <Chip size="small" label={contract.data.network} />
            <Chip size="small" label={contract.data.status} color={STATUS_COLOR[contract.data.status]} />
          </Stack>
          <Box sx={{ mt: 0.5 }}>
            <CopyableHash value={contract.data.address} headLength={42} tailLength={0} />
          </Box>

          <Card variant="outlined" sx={{ mt: 3 }}>
            <CardContent>
              <Typography variant="subtitle2" gutterBottom>
                Status
              </Typography>
              <ToggleButtonGroup
                exclusive
                value={contract.data.status}
                onChange={(_event, value: ContractStatus | null) => {
                  if (value) {
                    updateStatus.mutate({ status: value })
                  }
                }}
                size="small"
                disabled={updateStatus.isPending}
              >
                {STATUS_OPTIONS.map((status) => (
                  <ToggleButton key={status} value={status}>
                    {status}
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
              {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}
            </CardContent>
          </Card>

          {/* `key` remonta o form ao trocar de contrato — inicializa o estado local a partir dos
              dados já carregados (`useState` lazy), sem precisar de `useEffect` pra sincronizar. */}
          <ContractEditForm key={contract.data.address} contract={contract.data} />

          <AbiSection address={address} />
        </>
      )}
    </Box>
  )
}

interface ContractEditFormProps {
  contract: Contract
}

function ContractEditForm({ contract }: ContractEditFormProps) {
  const updateContract = useUpdateContract(contract.address)

  const [name, setName] = useState(contract.name)
  const [description, setDescription] = useState(contract.description ?? '')
  const [version, setVersion] = useState(contract.version ?? '')

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    updateContract.mutate({
      name,
      description: description || undefined,
      version: version || undefined,
    })
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Dados do contrato
        </Typography>
        <Box component="form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 480 }}>
          <TextField label="Nome" required fullWidth value={name} onChange={(event) => setName(event.target.value)} />
          <TextField
            label="Descrição"
            fullWidth
            multiline
            minRows={3}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
          <TextField label="Versão" fullWidth value={version} onChange={(event) => setVersion(event.target.value)} />

          {updateContract.isError && <ApiErrorAlert error={updateContract.error} />}
          {updateContract.isSuccess && (
            <Typography variant="body2" color="success.main">
              Contrato atualizado com sucesso.
            </Typography>
          )}

          <Button type="submit" variant="contained" disabled={updateContract.isPending} sx={{ alignSelf: 'flex-start' }}>
            {updateContract.isPending ? 'Salvando...' : 'Salvar'}
          </Button>
        </Box>
      </CardContent>
    </Card>
  )
}
