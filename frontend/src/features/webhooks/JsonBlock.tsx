import { Box, Stack, Typography } from '@mui/material'
import { CopyTextButton } from './CopyTextButton'
import { prettyJson } from './resendUtils'

interface JsonBlockProps {
  title: string
  /** JSON em string; `null`/`undefined` mostra a mensagem de ausência. */
  value: string | null | undefined
  emptyMessage: string
}

/** Payload JSON formatado, em monospace e copiável. */
export function JsonBlock({ title, value, emptyMessage }: JsonBlockProps) {
  const formatted = value ? prettyJson(value) : null
  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="subtitle2">{title}</Typography>
        {formatted && <CopyTextButton text={formatted} />}
      </Stack>
      {formatted ? (
        <Box
          component="pre"
          sx={{ m: 0, p: 1, bgcolor: 'grey.100', borderRadius: 1, fontFamily: 'monospace', fontSize: '0.8125rem', overflow: 'auto', maxHeight: 260 }}
        >
          {formatted}
        </Box>
      ) : (
        <Typography variant="body2" color="text.secondary">
          {emptyMessage}
        </Typography>
      )}
    </Box>
  )
}
