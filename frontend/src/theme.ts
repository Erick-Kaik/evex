import { createTheme } from '@mui/material/styles'

/** Tema MUI único do projeto (DEC-060) — paleta neutra, sem customização visual elaborada (PoC). */
export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#1e293b',
    },
  },
  shape: {
    borderRadius: 8,
  },
})
