import { Autocomplete, Chip, TextField } from '@mui/material'
import type { EntityModel, Network } from '../../api/api'
import { EntityModelStatus } from '../../api/api'
import { ENTITY_MODEL_CATALOG_PAGE_SIZE, useEntityModels } from '../models/useEntityModels'

interface EntityModelMultiSelectProps {
  network: Network | ''
  /** Contrato/evento do próprio webhook — só modelos com um contribuidor que bate podem ser referenciados (DEC-082/083). */
  contractAddress: string
  eventName: string
  value: string[]
  onChange: (ids: string[]) => void
}

function contributesToEvent(model: EntityModel, contractAddress: string, eventName: string): boolean {
  if (!contractAddress || !eventName) {
    return false
  }
  return model.contributors.some(
    (contributor) =>
      contributor.contractAddress.toLowerCase() === contractAddress.toLowerCase() &&
      contributor.eventName === eventName,
  )
}

/**
 * Seleção múltipla de Modelos de Entidade (E19/DEC-082/083) pra `WebhookConfig.entityModelIds`
 * (DEC-084) — o backend só aceita um modelo cujo `(contractAddress, eventName)` bata com o do próprio
 * webhook (regra "só pode ler um modelo do qual o próprio evento também participa", `WebhookConfigService`);
 * a UI aplica o mesmo filtro nas opções **novas** pra não deixar o usuário escolher algo que o
 * `POST/PUT` vai rejeitar. Sem contrato/evento escolhido ainda, a lista de opções novas fica vazia
 * (mesmo espírito não-bloqueante de `<EventPicker>` sem endereço) — mas um id já referenciado
 * continua selecionado mesmo que o contrato/evento do webhook tenha mudado depois.
 */
export function EntityModelMultiSelect({
  network,
  contractAddress,
  eventName,
  value,
  onChange,
}: EntityModelMultiSelectProps) {
  const { data, isPending, isError } = useEntityModels(
    network === '' ? undefined : network,
    0,
    ENTITY_MODEL_CATALOG_PAGE_SIZE,
  )
  const models = data?.content ?? []

  const options = models.filter(
    (model) =>
      (model.status === EntityModelStatus.Active && contributesToEvent(model, contractAddress, eventName)) ||
      value.includes(model.id),
  )
  for (const id of value) {
    if (!options.some((model) => model.id === id)) {
      options.push({
        id,
        name: id,
        network: (network || 'MAINNET') as Network,
        status: EntityModelStatus.Archived,
        contributors: [],
        createdAt: '',
      })
    }
  }

  const selected = options.filter((model) => value.includes(model.id))
  const noEventSelected = !contractAddress || !eventName

  return (
    <Autocomplete
      multiple
      options={options}
      value={selected}
      loading={isPending}
      size="small"
      getOptionLabel={(option) => option.name}
      isOptionEqualToValue={(option, val) => option.id === val.id}
      onChange={(_event, values) => onChange(values.map((v) => v.id))}
      renderValue={(selectedValue: EntityModel[], getItemProps) =>
        selectedValue.map((option, index) => {
          const { key, ...rest } = getItemProps({ index })
          return <Chip key={key} size="small" label={option.name} {...rest} />
        })
      }
      renderInput={(params) => (
        <TextField
          {...params}
          label="Modelos de Entidade (opcional)"
          placeholder="adicionar modelo"
          helperText={
            isError
              ? 'Não foi possível carregar a lista de modelos.'
              : noEventSelected
                ? 'Informe contrato e evento para ver os modelos disponíveis (só modelos cujo evento contribuinte bate com este webhook).'
                : 'Habilita {{model.<campo>}} no template, com os campos acumulados desta entidade.'
          }
          error={isError}
        />
      )}
    />
  )
}
