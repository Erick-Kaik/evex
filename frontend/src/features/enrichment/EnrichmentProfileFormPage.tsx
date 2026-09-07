import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import { Box, Button, Card, CardContent, Chip, Stack, Typography } from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import type { EnrichmentCall, EnrichmentProfile, Network } from '../../api/api'
import { EnrichmentProfileStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { LabeledField } from '../../components/LabeledField'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import type { PageMode } from '../../components/pageMode'
import { EnrichmentCallListEditor } from './EnrichmentCallListEditor'
import {
  useCreateEnrichmentProfile,
  useEnrichmentProfile,
  useUpdateEnrichmentProfile,
  useUpdateEnrichmentProfileStatus,
} from './useEnrichmentProfiles'

const STATUS_OPTIONS: StatusOption<EnrichmentProfileStatus>[] = [
  { value: EnrichmentProfileStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: EnrichmentProfileStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

interface EnrichmentProfileFormPageProps {
  mode: PageMode
}

/**
 * Página de formulário de Perfil de Enriquecimento (E19/DEC-082/083), nos 3 modos do padrão E18 —
 * cópia estrutural de `ContractFormPage`/`WalletFormPage`: `create` (`/enrichment-profiles/new`),
 * `view` (`/enrichment-profiles/:id`, campos só leitura) e `edit` (`/enrichment-profiles/:id/edit`).
 * `network` é imutável depois de criado (mesmo padrão de Carteira) — não aparece em `UpdateEnrichmentProfilePayload`.
 */
export function EnrichmentProfileFormPage({ mode }: EnrichmentProfileFormPageProps) {
  const { id } = useParams<{ id: string }>()

  if (mode === 'create') {
    return <EnrichmentProfileCreateForm />
  }

  if (!id) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Identificador do perfil ausente na URL.')} />
      </Box>
    )
  }

  return <EnrichmentProfileViewEditForm id={id} mode={mode} />
}

function EnrichmentProfileCreateForm() {
  const navigate = useNavigate()
  const createProfile = useCreateEnrichmentProfile()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [network, setNetwork] = useState<Network | ''>('')
  const [calls, setCalls] = useState<EnrichmentCall[]>([])

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (network === '') {
      return
    }

    createProfile.mutate(
      { name, description: description || undefined, network, calls },
      { onSuccess: (profile) => navigate(`/enrichment-profiles/${profile.id}`) },
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/enrichment-profiles')} sx={{ mb: 2 }}>
        Voltar
      </Button>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 3 }}>
        Novo Perfil de Enriquecimento
      </Typography>

      <Box
        component="form"
        onSubmit={handleSubmit}
        sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 640 }}
      >
        <LabeledField label="Nome" mode="edit" required value={name} onChange={setName} />
        <LabeledField
          label="Descrição (opcional)"
          mode="edit"
          value={description}
          onChange={setDescription}
          multiline
          minRows={2}
        />
        <NetworkSelect required value={network} onChange={setNetwork} />

        {network !== '' && <EnrichmentCallListEditor calls={calls} network={network} onChange={setCalls} />}

        {createProfile.isError && <ApiErrorAlert error={createProfile.error} />}

        <Button
          type="submit"
          variant="contained"
          disabled={createProfile.isPending || calls.length === 0}
          sx={{ alignSelf: 'flex-start' }}
        >
          {createProfile.isPending ? 'Cadastrando...' : 'Cadastrar'}
        </Button>
      </Box>
    </Box>
  )
}

function EnrichmentProfileViewEditForm({ id, mode }: { id: string; mode: PageMode }) {
  const navigate = useNavigate()
  const profile = useEnrichmentProfile(id)
  const updateStatus = useUpdateEnrichmentProfileStatus(id)

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/enrichment-profiles')} sx={{ mb: 2 }}>
        Voltar
      </Button>

      {profile.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando...
        </Typography>
      )}
      {profile.isError && <ApiErrorAlert error={profile.error} />}

      {profile.data && (
        <>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2 }}
          >
            <Box>
              <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
                <Typography variant="h5" sx={{ fontWeight: 600 }}>
                  {profile.data.name}
                </Typography>
                <Chip size="small" label={profile.data.network} />
                <StatusToggle
                  value={profile.data.status}
                  options={STATUS_OPTIONS}
                  disabled={updateStatus.isPending}
                  onChange={(status) => updateStatus.mutate({ status })}
                />
              </Stack>
            </Box>
            {mode === 'view' && (
              <Button
                variant="outlined"
                startIcon={<EditIcon />}
                onClick={() => navigate(`/enrichment-profiles/${id}/edit`)}
              >
                Editar
              </Button>
            )}
          </Stack>
          {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}

          {/* `key` remonta o form ao trocar de perfil — inicializa o estado local a partir dos dados
              já carregados (`useState` lazy), sem precisar de `useEffect` pra sincronizar. */}
          <EnrichmentProfileFieldsForm key={profile.data.id} profile={profile.data} mode={mode} />
        </>
      )}
    </Box>
  )
}

interface EnrichmentProfileFieldsFormProps {
  profile: EnrichmentProfile
  mode: PageMode
}

function EnrichmentProfileFieldsForm({ profile, mode }: EnrichmentProfileFieldsFormProps) {
  const navigate = useNavigate()
  const updateProfile = useUpdateEnrichmentProfile(profile.id)

  const [name, setName] = useState(profile.name)
  const [description, setDescription] = useState(profile.description ?? '')
  const [calls, setCalls] = useState<EnrichmentCall[]>(profile.calls)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    updateProfile.mutate(
      { name, description: description || undefined, calls },
      { onSuccess: () => navigate(`/enrichment-profiles/${profile.id}`) },
    )
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Dados do perfil
        </Typography>
        <Box
          component={mode === 'view' ? 'div' : 'form'}
          onSubmit={mode === 'view' ? undefined : handleSubmit}
          sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 640 }}
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

          {mode === 'view' ? (
            <Box>
              <Typography variant="caption" color="text.secondary" component="div">
                Chamadas de leitura
              </Typography>
              {calls.length === 0 && <Typography variant="body2">—</Typography>}
              {calls.map((call, index) => (
                <Typography key={index} variant="body2">
                  {index + 1}. {call.methodName}({call.args.join(', ')}) em {call.targetContractAddress} →{' '}
                  {call.outputs.map((o) => `{{enriched.${o.placeholder}}}`).join(', ') || 'nenhuma saída mapeada'}
                </Typography>
              ))}
            </Box>
          ) : (
            <EnrichmentCallListEditor calls={calls} network={profile.network} onChange={setCalls} />
          )}

          {mode !== 'view' && updateProfile.isError && <ApiErrorAlert error={updateProfile.error} />}

          {mode !== 'view' && (
            <Button
              type="submit"
              variant="contained"
              disabled={updateProfile.isPending || calls.length === 0}
              sx={{ alignSelf: 'flex-start' }}
            >
              {updateProfile.isPending ? 'Salvando...' : 'Salvar'}
            </Button>
          )}
        </Box>
      </CardContent>
    </Card>
  )
}
