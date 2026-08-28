import { Box, Pagination as MuiPagination, Typography } from '@mui/material'

interface PaginationProps {
  page: number
  totalPages: number
  totalElements: number
  onPageChange: (page: number) => void
}

/**
 * Paginação genérica (page 0-indexed, `MuiPagination` é 1-indexed) reaproveitada em toda lista
 * paginada (contratos, eventos, saldos, webhooks, entregas).
 */
export function Pagination({ page, totalPages, totalElements, onPageChange }: PaginationProps) {
  if (totalPages <= 1) {
    return null
  }

  return (
    <Box sx={{ mt: 2, display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
      <Typography variant="body2" color="text.secondary">
        Página {page + 1} de {totalPages} · {totalElements} {totalElements === 1 ? 'item' : 'itens'}
      </Typography>
      <MuiPagination
        page={page + 1}
        count={totalPages}
        onChange={(_event, value) => onPageChange(value - 1)}
        color="primary"
        size="small"
      />
    </Box>
  )
}
