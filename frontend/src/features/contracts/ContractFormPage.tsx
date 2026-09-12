import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  Chip,
  Divider,
  FormControlLabel,
  Grid,
  Link as MuiLink,
  List,
  ListItemButton,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import type { Contract, Network } from '../../api/api'
import { ContractStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { CopyableHash } from '../../components/CopyableHash'
import { NetworkSelect } from '../../components/NetworkSelect'
import { LabeledField } from '../../components/LabeledField'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import type { PageMode } from '../../components/pageMode'
import { AbiSection } from './AbiSection'
import { ContractBalancesSection } from './ContractBalancesSection'
import { ContractLineageSection } from './ContractLineageSection'
import {
  useContract,
  useCreateContract,
  useUpdateContract,
  useUpdateContractStatus,
  useVerifiedContracts,
} from './useContracts'

const STATUS_OPTIONS: StatusOption<ContractStatus>[] = [
  { value: ContractStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: ContractStatus.Paused, label: 'PAUSED', color: 'warning' },
  { value: ContractStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

interface ContractFormPageProps {
  mode: PageMode
}

/**
 * Página de formulário de contrato monitorado, nos 3 modos do padrão E18 (mesmo componente para os
 * 3, ver DEC-079): `create` (`/contracts/new`, com atalho de importação via Blockscout — doc §7),
 * `view` (`/contracts/:address`, campos só leitura) e `edit` (`/contracts/:address/edit`, campos
 * editáveis). O status (toggle rápido) e a seção de ABI (sub-recurso versionado, fora deste padrão —
 * ver E18 §5) só aparecem em `view`/`edit`, quando já existe um contrato cadastrado.
 */
export function ContractFormPage({ mode }: ContractFormPageProps) {
  const { address } = useParams<{ address: string }>()

  if (mode === 'create') {
    return <ContractCreateForm />
  }

  if (!address) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Endereço do contrato ausente na URL.')} />
      </Box>
    )
  }

  return <ContractViewEditForm address={address} mode={mode} />
}

function ContractCreateForm() {
  const navigate = useNavigate()
  const createContract = useCreateContract()
  const verifiedContracts = useVerifiedContracts()

  const [address, setAddress] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [version, setVersion] = useState('')
  const [network, setNetwork] = useState<Network | ''>('')
  const [isRedeploy, setIsRedeploy] = useState(false)
  const [predecessor, setPredecessor] = useState<ContractRef | null>(null)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (network === '') {
      return
    }

    createContract.mutate(
      {
        address,
        name,
        description: description || undefined,
        version: version || undefined,
        network,
        predecessorAddress: isRedeploy ? predecessor?.address || undefined : undefined,
      },
      { onSuccess: (contract) => navigate(`/contracts/${contract.address}`) },
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/contracts')} sx={{ mb: 2 }}>
        Voltar
      </Button>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 3 }}>
        Novo contrato monitorado
      </Typography>

      <Grid container spacing={4}>
        <Grid size={{ xs: 12, md: 7 }}>
          <Box
            component="form"
            id="contract-form"
            onSubmit={handleSubmit}
            sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}
          >
            <LabeledField
              label="Endereço"
              mode="edit"
              required
              value={address}
              onChange={setAddress}
              placeholder="0x..."
              monospace
            />
            <LabeledField label="Nome" mode="edit" required value={name} onChange={setName} />
            <LabeledField
              label="Descrição (opcional)"
              mode="edit"
              value={description}
              onChange={setDescription}
              multiline
              minRows={2}
            />
            <LabeledField
              label="Versão (opcional)"
              mode="edit"
              value={version}
              onChange={setVersion}
              placeholder="ex.: 1.0.0"
            />
            <NetworkSelect required value={network} onChange={setNetwork} />

            <FormControlLabel
              control={
                <Checkbox
                  checked={isRedeploy}
                  onChange={(event) => {
                    setIsRedeploy(event.target.checked)
                    if (!event.target.checked) {
                      setPredecessor(null)
                    }
                  }}
                />
              }
              label="Isto é um redeploy de um contrato existente?"
            />
            {isRedeploy && (
              <ContractAutocomplete
                label="Contrato predecessor"
                required
                value={predecessor}
                onChange={setPredecessor}
              />
            )}

            {createContract.isError && <ApiErrorAlert error={createContract.error} />}

            <Button type="submit" variant="contained" disabled={createContract.isPending} sx={{ alignSelf: 'flex-start' }}>
              {createContract.isPending ? 'Cadastrando...' : 'Cadastrar'}
            </Button>
          </Box>
        </Grid>

        <Grid size={{ xs: 12, md: 5 }}>
          <Typography variant="subtitle2" gutterBottom>
            Importar do Blockscout (opcional)
          </Typography>
          <Divider sx={{ mb: 2 }} />

          {verifiedContracts.isPending && (
            <Typography variant="body2" color="text.secondary">
              Consultando Blockscout...
            </Typography>
          )}

          {verifiedContracts.isError && <ApiErrorAlert error={verifiedContracts.error} />}

          {verifiedContracts.data?.unavailable && (
            <Alert severity="warning">
              Discovery via Blockscout não está disponível nesta instância (nenhum Blockscout
              configurado, ou fora do ar). Preencha o formulário manualmente.
            </Alert>
          )}

          {verifiedContracts.data &&
            !verifiedContracts.data.unavailable &&
            verifiedContracts.data.contracts.length === 0 && (
              <Alert severity="info">
                Nenhum contrato verificado encontrado no Blockscout. Isso é esperado: o discovery só
                lista contratos <strong>verificados</strong> — não é um erro. Em ambiente local, rode{' '}
                <MuiLink
                  href="https://github.com"
                  onClick={(event) => event.preventDefault()}
                  sx={{ cursor: 'default', textDecoration: 'none' }}
                >
                  <Typography component="code" variant="body2" sx={{ fontFamily: 'monospace' }}>
                    local-env/blockscout/verify-contracts.ts
                  </Typography>
                </MuiLink>{' '}
                (via <code>npx tsx blockscout/verify-contracts.ts</code> de dentro de{' '}
                <code>local-env/</code>) para verificar os contratos de teste antes de tentar de novo.
              </Alert>
            )}

          {verifiedContracts.data && verifiedContracts.data.contracts.length > 0 && (
            <List dense disablePadding>
              {verifiedContracts.data.contracts.map((contract) => (
                <ListItemButton
                  key={contract.address}
                  onClick={() => {
                    setAddress(contract.address)
                    setName(contract.name ?? contract.address)
                  }}
                  sx={{ border: 1, borderColor: 'divider', borderRadius: 1, mb: 1 }}
                >
                  <ListItemText
                    primary={contract.name ?? contract.address}
                    secondary={contract.address}
                    slotProps={{
                      primary: { sx: { fontWeight: 500 } },
                      secondary: { sx: { fontFamily: 'monospace', fontSize: '0.75rem' } },
                    }}
                  />
                </ListItemButton>
              ))}
            </List>
          )}
        </Grid>
      </Grid>
    </Box>
  )
}

function ContractViewEditForm({ address, mode }: { address: string; mode: PageMode }) {
  const navigate = useNavigate()
  const contract = useContract(address)
  const updateStatus = useUpdateContractStatus(address)

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/contracts')} sx={{ mb: 2 }}>
        Voltar
      </Button>

      {contract.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando...
        </Typography>
      )}
      {contract.isError && <ApiErrorAlert error={contract.error} />}

      {contract.data && (
        <>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2 }}
          >
            <Box>
              <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
                <Typography variant="h5" sx={{ fontWeight: 600 }}>
                  {contract.data.name}
                </Typography>
                <Chip size="small" label={contract.data.network} />
                <StatusToggle
                  value={contract.data.status}
                  options={STATUS_OPTIONS}
                  disabled={updateStatus.isPending}
                  onChange={(status) => updateStatus.mutate({ status })}
                />
              </Stack>
              <Box sx={{ mt: 0.5 }}>
                <CopyableHash value={contract.data.address} headLength={42} tailLength={0} />
              </Box>
            </Box>
            {mode === 'view' && (
              <Button
                variant="outlined"
                startIcon={<EditIcon />}
                onClick={() => navigate(`/contracts/${address}/edit`)}
              >
                Editar
              </Button>
            )}
          </Stack>
          {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}

          {/* `key` remonta o form ao trocar de contrato — inicializa o estado local a partir dos
              dados já carregados (`useState` lazy), sem precisar de `useEffect` pra sincronizar. */}
          <ContractFieldsForm key={contract.data.address} contract={contract.data} mode={mode} />

          <AbiSection address={address} />
          <ContractBalancesSection address={address} />
          <ContractLineageSection address={address} />
        </>
      )}
    </Box>
  )
}

interface ContractFieldsFormProps {
  contract: Contract
  mode: PageMode
}

function ContractFieldsForm({ contract, mode }: ContractFieldsFormProps) {
  const navigate = useNavigate()
  const updateContract = useUpdateContract(contract.address)

  const [name, setName] = useState(contract.name)
  const [description, setDescription] = useState(contract.description ?? '')
  const [version, setVersion] = useState(contract.version ?? '')

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    updateContract.mutate(
      { name, description: description || undefined, version: version || undefined },
      { onSuccess: () => navigate(`/contracts/${contract.address}`) },
    )
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Dados do contrato
        </Typography>
        <Box
          component={mode === 'view' ? 'div' : 'form'}
          onSubmit={mode === 'view' ? undefined : handleSubmit}
          sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 480 }}
        >
          <LabeledField label="Nome" mode={mode} required value={name} onChange={setName} />
          <LabeledField
            label="Descrição"
            mode={mode}
            value={description}
            onChange={setDescription}
            multiline
            minRows={3}
          />
          <LabeledField label="Versão" mode={mode} value={version} onChange={setVersion} />

          {mode !== 'view' && updateContract.isError && <ApiErrorAlert error={updateContract.error} />}

          {mode !== 'view' && (
            <Button type="submit" variant="contained" disabled={updateContract.isPending} sx={{ alignSelf: 'flex-start' }}>
              {updateContract.isPending ? 'Salvando...' : 'Salvar'}
            </Button>
          )}
        </Box>
      </CardContent>
    </Card>
  )
}
