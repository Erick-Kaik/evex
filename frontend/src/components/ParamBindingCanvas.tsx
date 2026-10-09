import { useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
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
import { Box, Chip, IconButton, Paper, Stack, Typography } from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import CheckIcon from '@mui/icons-material/Check'
import type { ContractEventInput } from '../api/api'
import { matchesParamKind } from './contractCatalog'
import type { EventParamKind } from './contractCatalog'

/** Um campo-alvo do canvas: recebe (via drag ou clique) o nome de um parâmetro do evento. */
export interface ParamBindingSlot {
  /** Identificador estável do slot (não exibido). */
  key: string
  /** Rótulo visível. */
  label: string
  /** Tipo aceito: `address`, `numeric` (`uint*`/`int*`) ou `any`. */
  kind: EventParamKind
  /** Nome do parâmetro atualmente vinculado (`''` quando vazio). */
  value: string
}

interface ParamBindingCanvasProps {
  /** Parâmetros do evento escolhido — viram chips arrastáveis. */
  params: ContractEventInput[]
  /** Campos-alvo. */
  slots: ParamBindingSlot[]
  /** Chamado ao vincular (`paramName`) ou desfazer (`null`) um slot. */
  onBind: (slotKey: string, paramName: string | null) => void
  /** Conteúdo extra renderizado ao lado de cada slot (ex.: operador + valor de um filtro de webhook). */
  renderSlotExtra?: (slotKey: string) => ReactNode
}

const KIND_LABEL: Record<EventParamKind, string> = {
  address: 'endereço',
  numeric: 'numérico',
  numericScalar: 'numérico (escalar)',
  numericArray: 'array numérico',
  any: 'qualquer tipo',
}

const PARAM_PREFIX = 'param:'
const SLOT_PREFIX = 'slot:'

/**
 * Canvas de arrastar-para-vincular (DEC-072, modo visual da E16). Compartilhado entre a config de
 * saldo e o form de webhook. Os parâmetros do evento viram chips; cada campo-alvo é um slot tipado.
 * Arrastar um chip para um slot cujo `kind` não casa com o `type` Solidity do parâmetro é recusado
 * (feedback visual, sem `onBind`). Além do drag há um fallback acessível de clique/teclado: clicar
 * (ou `Enter`/`Espaço`) num chip o seleciona, clicar num slot compatível cria o vínculo.
 *
 * Um parâmetro já usado aparece "consumido" (esmaecido + check) mas continua arrastável — a mesma
 * fonte pode alimentar mais de um slot (decisão documentada no complemento da DEC-072).
 */
export function ParamBindingCanvas({ params, slots, onBind, renderSlotExtra }: ParamBindingCanvasProps) {
  const [selectedParam, setSelectedParam] = useState<string | null>(null)
  const [rejectedSlotKey, setRejectedSlotKey] = useState<string | null>(null)

  // `distance` para um clique simples (sem arrasto) não virar drag — deixa o `onClick` do chip
  // (fallback acessível de seleção) funcionar normalmente.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )

  const boundParams = new Set(slots.map((slot) => slot.value).filter(Boolean))

  function tryBind(slotKey: string, paramName: string): boolean {
    const slot = slots.find((candidate) => candidate.key === slotKey)
    const param = params.find((candidate) => candidate.name === paramName)
    if (!slot || !param) {
      return false
    }
    if (!matchesParamKind(param.type, slot.kind)) {
      setRejectedSlotKey(slotKey)
      return false
    }
    setRejectedSlotKey(null)
    onBind(slotKey, paramName)
    return true
  }

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event
    if (!over) {
      return
    }
    const paramName = String(active.id).slice(PARAM_PREFIX.length)
    const slotKey = String(over.id).slice(SLOT_PREFIX.length)
    tryBind(slotKey, paramName)
  }

  function toggleParam(name: string) {
    setRejectedSlotKey(null)
    setSelectedParam((current) => (current === name ? null : name))
  }

  function activateSlot(slotKey: string) {
    if (selectedParam === null) {
      return
    }
    if (tryBind(slotKey, selectedParam)) {
      setSelectedParam(null)
    }
  }

  if (params.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Escolha um contrato e um evento para ver os parâmetros arrastáveis.
      </Typography>
    )
  }

  return (
    <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
      <Stack sx={{ gap: 2 }}>
        <Box>
          <Typography variant="caption" color="text.secondary">
            Parâmetros do evento
          </Typography>
          <Box
            role="list"
            aria-label="Parâmetros do evento"
            sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 0.5 }}
          >
            {params.map((param) => (
              <DraggableParam
                key={param.name}
                input={param}
                consumed={boundParams.has(param.name)}
                selected={selectedParam === param.name}
                onToggle={() => toggleParam(param.name)}
              />
            ))}
          </Box>
          {selectedParam !== null && (
            <Typography variant="caption" color="primary" sx={{ display: 'block', mt: 0.5 }}>
              "{selectedParam}" selecionado — clique num campo compatível para vincular.
            </Typography>
          )}
        </Box>

        <Stack sx={{ gap: 1.5 }}>
          {slots.map((slot) => (
            <DroppableSlot
              key={slot.key}
              slot={slot}
              rejected={rejectedSlotKey === slot.key}
              hasSelection={selectedParam !== null}
              onActivate={() => activateSlot(slot.key)}
              onClear={() => onBind(slot.key, null)}
              extra={renderSlotExtra?.(slot.key)}
            />
          ))}
        </Stack>
      </Stack>
    </DndContext>
  )
}

function DraggableParam({
  input,
  consumed,
  selected,
  onToggle,
}: {
  input: ContractEventInput
  consumed: boolean
  selected: boolean
  onToggle: () => void
}) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: `${PARAM_PREFIX}${input.name}`,
  })

  return (
    <Chip
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      onClick={onToggle}
      aria-pressed={selected}
      aria-label={`Parâmetro ${input.name}, tipo ${input.type}`}
      color={selected ? 'primary' : 'default'}
      variant={consumed ? 'outlined' : 'filled'}
      icon={consumed ? <CheckIcon /> : undefined}
      label={`${input.name} (${input.type})`}
      sx={{
        cursor: 'grab',
        opacity: isDragging ? 0.4 : consumed ? 0.65 : 1,
        transform: transform ? `translate3d(${transform.x}px, ${transform.y}px, 0)` : undefined,
      }}
    />
  )
}

function DroppableSlot({
  slot,
  rejected,
  hasSelection,
  onActivate,
  onClear,
  extra,
}: {
  slot: ParamBindingSlot
  rejected: boolean
  hasSelection: boolean
  onActivate: () => void
  onClear: () => void
  extra: ReactNode
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `${SLOT_PREFIX}${slot.key}` })

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onActivate()
    }
  }

  const filled = Boolean(slot.value)

  return (
    <Paper
      ref={setNodeRef}
      variant="outlined"
      role="button"
      tabIndex={0}
      aria-label={`Campo ${slot.label} (${KIND_LABEL[slot.kind]})${filled ? `, vinculado a ${slot.value}` : ', vazio'}`}
      onClick={hasSelection ? onActivate : undefined}
      onKeyDown={handleKeyDown}
      sx={{
        p: 1.5,
        borderStyle: rejected ? 'solid' : 'dashed',
        borderColor: rejected ? 'error.main' : isOver ? 'primary.main' : 'divider',
        cursor: hasSelection ? 'pointer' : 'default',
      }}
    >
      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <Box sx={{ minWidth: 150 }}>
          <Typography variant="body2" sx={{ fontWeight: 500 }}>
            {slot.label}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            aceita: {KIND_LABEL[slot.kind]}
          </Typography>
        </Box>

        {filled ? (
          <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5 }}>
            <Chip label={slot.value} size="small" color="primary" variant="outlined" />
            <IconButton
              size="small"
              aria-label={`Remover vínculo de ${slot.label}`}
              onClick={(event) => {
                event.stopPropagation()
                onClear()
              }}
            >
              <CloseIcon fontSize="small" />
            </IconButton>
          </Stack>
        ) : (
          <Typography variant="caption" color="text.secondary">
            arraste um parâmetro aqui
            {hasSelection ? ' ou clique para vincular o parâmetro selecionado' : ''}
          </Typography>
        )}

        {extra}
      </Stack>

      {rejected && (
        <Typography variant="caption" color="error" sx={{ display: 'block', mt: 0.5 }}>
          Tipo incompatível — este campo aceita {KIND_LABEL[slot.kind]}.
        </Typography>
      )}
    </Paper>
  )
}
