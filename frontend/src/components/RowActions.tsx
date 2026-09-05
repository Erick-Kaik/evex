import { IconButton, Stack, Tooltip } from '@mui/material'
import VisibilityIcon from '@mui/icons-material/Visibility'
import EditIcon from '@mui/icons-material/Edit'

interface RowActionsProps {
  onView: () => void
  onEdit: () => void
}

/**
 * Ações padrão de uma linha de listagem (padrão E18) — "Ver" e "Editar", à direita da linha,
 * navegando para as rotas de detalhe/edição do módulo. `stopPropagation` porque algumas tabelas
 * ainda reagem a clique na linha para outras coisas (nenhuma hoje, mas evita regressão silenciosa).
 */
export function RowActions({ onView, onEdit }: RowActionsProps) {
  return (
    <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
      <Tooltip title="Ver">
        <IconButton
          size="small"
          aria-label="ver"
          onClick={(event) => {
            event.stopPropagation()
            onView()
          }}
        >
          <VisibilityIcon fontSize="small" />
        </IconButton>
      </Tooltip>
      <Tooltip title="Editar">
        <IconButton
          size="small"
          aria-label="editar"
          onClick={(event) => {
            event.stopPropagation()
            onEdit()
          }}
        >
          <EditIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </Stack>
  )
}
