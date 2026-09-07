import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import { Box, Button, Card, CardContent, Chip, Stack, Typography } from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import type { EntityModel, ModelContributor, Network } from '../../api/api'
import { EntityModelStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { LabeledField } from '../../components/LabeledField'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import type { PageMode } from '../../components/pageMode'
import { ModelContributorListEditor } from './ModelContributorListEditor'
import {
  useCreateEntityModel,
  useEntityModel,
  useUpdateEntityModel,
  useUpdateEntityModelStatus,
} from './useEntityModels'

const STATUS_OPTIONS: StatusOption<EntityModelStatus>[] = [
  { value: EntityModelStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: EntityModelStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

interface EntityModelFormPageProps {
  mode: PageMode
}

/**
 * Página de formulário de Modelo de Entidade (E19/DEC-082/083), nos 3 modos do padrão E18 — cópia
 * estrutural de `EnrichmentProfileFormPage`: `create` (`/entity-models/new`), `view`
 * (`/entity-models/:id`) e `edit` (`/entity-models/:id/edit`). `network` é imutável depois de criado.
 */
export function EntityModelFormPage({ mode }: EntityModelFormPageProps) {
  const { id } = useParams<{ id: string }>()

  if (mode === 'create') {
    return <EntityModelCreateForm />
  }

  if (!id) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Identificador do modelo ausente na URL.')} />
      </Box>
    )
  }

  return <EntityModelViewEditForm id={id} mode={mode} />
}

function EntityModelCreateForm() {
  const navigate = useNavigate()
  const createModel = useCreateEntityModel()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [network, setNetwork] = useState<Network | ''>('')
  const [contributors, setContributors] = useState<ModelContributor[]>([])

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (network === '') {
      return
    }

    createModel.mutate(
      { name, description: description || undefined, network, contributors },
      { onSuccess: (model) => navigate(`/entity-models/${model.id}`) },
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/entity-models')} sx={{ mb: 2 }}>
        Voltar
      </Button>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 3 }}>
        Novo Modelo de Entidade
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

        {network !== '' && (
          <ModelContributorListEditor contributors={contributors} network={network} onChange={setContributors} />
        )}

        {createModel.isError && <ApiErrorAlert error={createModel.error} />}

        <Button
          type="submit"
          variant="contained"
          disabled={createModel.isPending || contributors.length === 0}
          sx={{ alignSelf: 'flex-start' }}
        >
          {createModel.isPending ? 'Cadastrando...' : 'Cadastrar'}
        </Button>
      </Box>
    </Box>
  )
}

function EntityModelViewEditForm({ id, mode }: { id: string; mode: PageMode }) {
  const navigate = useNavigate()
  const model = useEntityModel(id)
  const updateStatus = useUpdateEntityModelStatus(id)

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/entity-models')} sx={{ mb: 2 }}>
        Voltar
      </Button>

      {model.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando...
        </Typography>
      )}
      {model.isError && <ApiErrorAlert error={model.error} />}

      {model.data && (
        <>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2 }}
          >
            <Box>
              <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
                <Typography variant="h5" sx={{ fontWeight: 600 }}>
                  {model.data.name}
                </Typography>
                <Chip size="small" label={model.data.network} />
                <StatusToggle
                  value={model.data.status}
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
                onClick={() => navigate(`/entity-models/${id}/edit`)}
              >
                Editar
              </Button>
            )}
          </Stack>
          {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}

          {/* `key` remonta o form ao trocar de modelo — inicializa o estado local a partir dos dados
              já carregados (`useState` lazy), sem precisar de `useEffect` pra sincronizar. */}
          <EntityModelFieldsForm key={model.data.id} model={model.data} mode={mode} />
        </>
      )}
    </Box>
  )
}

interface EntityModelFieldsFormProps {
  model: EntityModel
  mode: PageMode
}

function EntityModelFieldsForm({ model, mode }: EntityModelFieldsFormProps) {
  const navigate = useNavigate()
  const updateModel = useUpdateEntityModel(model.id)

  const [name, setName] = useState(model.name)
  const [description, setDescription] = useState(model.description ?? '')
  const [contributors, setContributors] = useState<ModelContributor[]>(model.contributors)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    updateModel.mutate(
      { name, description: description || undefined, contributors },
      { onSuccess: () => navigate(`/entity-models/${model.id}`) },
    )
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Dados do modelo
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
                Eventos contribuintes
              </Typography>
              {contributors.length === 0 && <Typography variant="body2">—</Typography>}
              {contributors.map((contributor, index) => (
                <Typography key={index} variant="body2">
                  {index + 1}. {contributor.eventName} em {contributor.contractAddress} (correlação:{' '}
                  {contributor.correlationParam}) →{' '}
                  {contributor.fieldMappings.map((m) => `${m.decodedParam}→{{model.${m.modelField}}}`).join(', ') ||
                    'nenhum campo mapeado'}
                </Typography>
              ))}
            </Box>
          ) : (
            <ModelContributorListEditor contributors={contributors} network={model.network} onChange={setContributors} />
          )}

          {mode !== 'view' && updateModel.isError && <ApiErrorAlert error={updateModel.error} />}

          {mode !== 'view' && (
            <Button
              type="submit"
              variant="contained"
              disabled={updateModel.isPending || contributors.length === 0}
              sx={{ alignSelf: 'flex-start' }}
            >
              {updateModel.isPending ? 'Salvando...' : 'Salvar'}
            </Button>
          )}
        </Box>
      </CardContent>
    </Card>
  )
}
