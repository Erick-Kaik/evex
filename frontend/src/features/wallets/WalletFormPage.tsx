import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { Alert, Box, Button, Card, CardContent, Chip, Stack, Typography } from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import type { Network, Wallet } from '../../api/api'
import { WalletStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { NetworkSelect } from '../../components/NetworkSelect'
import { LabeledField } from '../../components/LabeledField'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import type { PageMode } from '../../components/pageMode'
import { WalletBalancesSection } from './WalletBalancesSection'
import {
  useAcknowledgeContractWarning,
  useCreateWallet,
  useUpdateWallet,
  useUpdateWalletStatus,
  useWallet,
} from './useWallets'

const STATUS_OPTIONS: StatusOption<WalletStatus>[] = [
  { value: WalletStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: WalletStatus.Paused, label: 'PAUSED', color: 'warning' },
  { value: WalletStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

/**
 * Aviso não-bloqueante quando `looksLikeContract === true` (E22, `eth_getCode` no cadastro) — o
 * endereço tem bytecode publicado, o que é um indício (não uma certeza) de que não é um EOA. Não
 * impede o cadastro: pode ser proposital (ex. carteira multisig tipo Gnosis Safe).
 *
 * Some assim que `looksLikeContractAcknowledged === true` (complemento à DEC-080, feedback de teste
 * manual) — sem isso o aviso reaparecia toda vez que a tela de detalhe era aberta, mesmo já visto.
 * O botão "Ciente, não mostrar de novo" chama `PATCH /v1/wallets/{address}/contract-warning`
 * (`useAcknowledgeContractWarning`) — decisão unidirecional, sem caminho de volta.
 */
const LOOKS_LIKE_CONTRACT_WARNING =
  'Esse endereço tem código de contrato — confirme se não era pra cadastrar em Contratos (pode ser proposital, ex. carteira multisig).'

interface WalletFormPageProps {
  mode: PageMode
}

/**
 * Página de formulário de carteira cadastrada, nos 3 modos do padrão E18 — cópia estrutural de
 * `ContractFormPage` (E16/E18) pro domínio de Carteiras (E22), sem o campo "Versão" (carteira não
 * tem) e sem a seção de ABI (não se aplica); ganha, a mais, o aviso `looksLikeContract`.
 */
export function WalletFormPage({ mode }: WalletFormPageProps) {
  const { address } = useParams<{ address: string }>()

  if (mode === 'create') {
    return <WalletCreateForm />
  }

  if (!address) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Endereço da carteira ausente na URL.')} />
      </Box>
    )
  }

  return <WalletViewEditForm address={address} mode={mode} />
}

function WalletCreateForm() {
  const navigate = useNavigate()
  // Prefill vindo do card "Carteiras sugeridas" (`WalletsListPage`, `/wallets/new?address=0x...`).
  const [searchParams] = useSearchParams()
  const createWallet = useCreateWallet()

  const [address, setAddress] = useState(searchParams.get('address') ?? '')
  const [label, setLabel] = useState('')
  const [description, setDescription] = useState('')
  const [network, setNetwork] = useState<Network | ''>('')

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (network === '') {
      return
    }

    createWallet.mutate(
      { address, label, description: description || undefined, network },
      { onSuccess: (wallet) => navigate(`/wallets/${wallet.address}`) },
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/wallets')} sx={{ mb: 2 }}>
        Voltar
      </Button>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 3 }}>
        Nova carteira
      </Typography>

      <Box
        component="form"
        onSubmit={handleSubmit}
        sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 480 }}
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
        <LabeledField label="Label" mode="edit" required value={label} onChange={setLabel} />
        <LabeledField
          label="Descrição (opcional)"
          mode="edit"
          value={description}
          onChange={setDescription}
          multiline
          minRows={2}
        />
        <NetworkSelect required value={network} onChange={setNetwork} />

        {createWallet.isError && <ApiErrorAlert error={createWallet.error} />}

        <Button
          type="submit"
          variant="contained"
          disabled={createWallet.isPending}
          sx={{ alignSelf: 'flex-start' }}
        >
          {createWallet.isPending ? 'Cadastrando...' : 'Cadastrar'}
        </Button>
      </Box>
    </Box>
  )
}

function WalletViewEditForm({ address, mode }: { address: string; mode: PageMode }) {
  const navigate = useNavigate()
  const wallet = useWallet(address)
  const updateStatus = useUpdateWalletStatus(address)
  const acknowledgeContractWarning = useAcknowledgeContractWarning(address)

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/wallets')} sx={{ mb: 2 }}>
        Voltar
      </Button>

      {wallet.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando...
        </Typography>
      )}
      {wallet.isError && <ApiErrorAlert error={wallet.error} />}

      {wallet.data && (
        <>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2 }}
          >
            <Box>
              <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
                <Typography variant="h5" sx={{ fontWeight: 600 }}>
                  {wallet.data.label}
                </Typography>
                <Chip size="small" label={wallet.data.network} />
                <StatusToggle
                  value={wallet.data.status}
                  options={STATUS_OPTIONS}
                  disabled={updateStatus.isPending}
                  onChange={(status) => updateStatus.mutate({ status })}
                />
              </Stack>
              <Box sx={{ mt: 0.5 }}>
                <CopyableHash value={wallet.data.address} headLength={42} tailLength={0} />
              </Box>
            </Box>
            {mode === 'view' && (
              <Button
                variant="outlined"
                startIcon={<EditIcon />}
                onClick={() => navigate(`/wallets/${address}/edit`)}
              >
                Editar
              </Button>
            )}
          </Stack>
          {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}

          {wallet.data.looksLikeContract && !wallet.data.looksLikeContractAcknowledged && (
            <Alert
              severity="warning"
              sx={{ mt: 2 }}
              action={
                <Button
                  color="warning"
                  size="small"
                  disabled={acknowledgeContractWarning.isPending}
                  onClick={() => acknowledgeContractWarning.mutate()}
                >
                  Ciente, não mostrar de novo
                </Button>
              }
            >
              {LOOKS_LIKE_CONTRACT_WARNING}
            </Alert>
          )}
          {acknowledgeContractWarning.isError && <ApiErrorAlert error={acknowledgeContractWarning.error} />}

          {/* `key` remonta o form ao trocar de carteira — inicializa o estado local a partir dos
              dados já carregados (`useState` lazy), sem precisar de `useEffect` pra sincronizar. */}
          <WalletFieldsForm key={wallet.data.address} wallet={wallet.data} mode={mode} />

          <WalletBalancesSection address={address} />
        </>
      )}
    </Box>
  )
}

interface WalletFieldsFormProps {
  wallet: Wallet
  mode: PageMode
}

function WalletFieldsForm({ wallet, mode }: WalletFieldsFormProps) {
  const navigate = useNavigate()
  const updateWallet = useUpdateWallet(wallet.address)

  const [label, setLabel] = useState(wallet.label)
  const [description, setDescription] = useState(wallet.description ?? '')

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    updateWallet.mutate(
      { label, description: description || undefined },
      { onSuccess: () => navigate(`/wallets/${wallet.address}`) },
    )
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Dados da carteira
        </Typography>
        <Box
          component={mode === 'view' ? 'div' : 'form'}
          onSubmit={mode === 'view' ? undefined : handleSubmit}
          sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 480 }}
        >
          <LabeledField label="Label" mode={mode} required value={label} onChange={setLabel} />
          <LabeledField
            label="Descrição"
            mode={mode}
            value={description}
            onChange={setDescription}
            multiline
            minRows={3}
          />

          {mode !== 'view' && updateWallet.isError && <ApiErrorAlert error={updateWallet.error} />}

          {mode !== 'view' && (
            <Button
              type="submit"
              variant="contained"
              disabled={updateWallet.isPending}
              sx={{ alignSelf: 'flex-start' }}
            >
              {updateWallet.isPending ? 'Salvando...' : 'Salvar'}
            </Button>
          )}
        </Box>
      </CardContent>
    </Card>
  )
}
