import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'
import { CssBaseline, ThemeProvider } from '@mui/material'
import './index.css'
import { router } from './routes/router'
import { theme } from './theme'

const queryClient = new QueryClient()

const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error('#root element not found')
}

/**
 * Modo mock (`npm run dev:mock`): sobe o Service Worker do MSW com dados estáticos antes de renderizar,
 * pra rodar o front sem backend. O import é dinâmico pra o código de mock nunca entrar no bundle real.
 */
async function bootstrap(): Promise<void> {
  if (import.meta.env.VITE_MOCK === '1') {
    const { startMockWorker } = await import('./mocks/browser')
    await startMockWorker()
  }

  createRoot(rootElement!).render(
    <StrictMode>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </ThemeProvider>
    </StrictMode>,
  )
}

void bootstrap()
