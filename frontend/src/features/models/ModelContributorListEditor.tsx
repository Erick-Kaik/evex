import { Box, Button, Stack, Typography } from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import type { ModelContributor, Network } from '../../api/api'
import { ModelContributorRow } from './ModelContributorRow'
import { validateCorrelationKeys } from './correlationKey'

interface ModelContributorListEditorProps {
  contributors: ModelContributor[]
  network: Network
  onChange: (contributors: ModelContributor[]) => void
}

const EMPTY_CONTRIBUTOR: ModelContributor = {
  contractAddress: '',
  eventName: '',
  correlationKey: [{ type: 'PARAM', arg: '' }],
  fieldMappings: [],
}

/**
 * Lista de `ModelContributor` de um Modelo de Entidade (E19/DEC-082) — cada evento (possivelmente de
 * contratos diferentes) que contribui campos para a mesma entidade lógica ao longo do tempo.
 */
export function ModelContributorListEditor({ contributors, network, onChange }: ModelContributorListEditorProps) {
  function addContributor() {
    onChange([...contributors, { ...EMPTY_CONTRIBUTOR, correlationKey: [{ type: 'PARAM', arg: '' }], fieldMappings: [] }])
  }

  function updateContributor(index: number, next: ModelContributor) {
    onChange(contributors.map((contributor, i) => (i === index ? next : contributor)))
  }

  function removeContributor(index: number) {
    onChange(contributors.filter((_, i) => i !== index))
  }

  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="subtitle2">Eventos contribuintes</Typography>
        <Button size="small" startIcon={<AddIcon />} onClick={addContributor}>
          adicionar evento
        </Button>
      </Stack>

      {contributors.length === 0 && (
        <Typography variant="caption" color="text.secondary">
          Nenhum evento contribuinte configurado — adicione ao menos um.
        </Typography>
      )}

      <Stack sx={{ gap: 2, mt: 1 }}>
        {contributors.map((contributor, index) => (
          <ModelContributorRow
            key={index}
            index={index}
            contributor={contributor}
            network={network}
            onChange={updateContributor}
            onRemove={removeContributor}
          />
        ))}
      </Stack>

      {validateCorrelationKeys(contributors).map((issue) => (
        <Typography key={issue} variant="caption" color="error" component="div" sx={{ mt: 0.5 }}>
          {issue}
        </Typography>
      ))}
    </Box>
  )
}
