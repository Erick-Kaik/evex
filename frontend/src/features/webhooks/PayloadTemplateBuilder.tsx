import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { Box, Chip, Stack, TextField, Typography } from '@mui/material'
import type { ContractEventInput } from '../../api/api'

interface PayloadTemplateBuilderProps {
  /** Parâmetros do evento escolhido — viram tokens `{{decodedParameters.<nome>}}`. */
  params: ContractEventInput[]
  /** Template mustache atual (mesma string que o modo manual edita à mão). */
  value: string
  onChange: (next: string) => void
}

/** Tokens fixos, independentes do evento. */
const FIXED_TOKENS = ['{{eventName}}', '{{contractAddress}}']

const DROP_ID = 'payload-template-drop'

/**
 * Builder do `payloadTemplate` do webhook (DEC-072, modo visual). Arrastar (ou clicar) um token o
 * insere no fim do template — o resultado continua sendo a mesma string mustache que o modo manual
 * edita. Template vazio => envelope JSON padrão (comportamento atual mantido).
 */
export function PayloadTemplateBuilder({ params, value, onChange }: PayloadTemplateBuilderProps) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )
  const tokens = [...FIXED_TOKENS, ...params.map((param) => `{{decodedParameters.${param.name}}}`)]

  function insertToken(token: string) {
    const separator = value.length === 0 || value.endsWith(' ') || value.endsWith('\n') ? '' : ' '
    onChange(`${value}${separator}${token}`)
  }

  function handleDragEnd(event: DragEndEvent) {
    if (event.over?.id === DROP_ID) {
      insertToken(String(event.active.id))
    }
  }

  return (
    <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
      <Stack sx={{ gap: 1 }}>
        <Typography variant="subtitle2">Template de payload (opcional)</Typography>
        <Box role="list" aria-label="Tokens do template" sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
          {tokens.map((token) => (
            <DraggableToken key={token} token={token} onInsert={() => insertToken(token)} />
          ))}
        </Box>
        <TemplateDropField value={value} onChange={onChange} />
      </Stack>
    </DndContext>
  )
}

function DraggableToken({ token, onInsert }: { token: string; onInsert: () => void }) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({ id: token })
  return (
    <Chip
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      onClick={onInsert}
      size="small"
      label={token}
      aria-label={`Inserir ${token}`}
      sx={{
        cursor: 'grab',
        fontFamily: 'monospace',
        opacity: isDragging ? 0.4 : 1,
        transform: transform ? `translate3d(${transform.x}px, ${transform.y}px, 0)` : undefined,
      }}
    />
  )
}

function TemplateDropField({ value, onChange }: { value: string; onChange: (next: string) => void }) {
  const { setNodeRef, isOver } = useDroppable({ id: DROP_ID })
  return (
    <Box ref={setNodeRef}>
      <TextField
        label="Template montado"
        multiline
        minRows={3}
        fullWidth
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder='{"evento": "{{eventName}}", "valor": "{{decodedParameters.value}}"}'
        helperText={
          isOver
            ? 'Solte para inserir o token'
            : 'Arraste ou clique nos tokens acima. Vazio = envelope JSON padrão. Editável à mão.'
        }
        slotProps={{ htmlInput: { style: { fontFamily: 'monospace', fontSize: '0.75rem' } } }}
      />
    </Box>
  )
}
