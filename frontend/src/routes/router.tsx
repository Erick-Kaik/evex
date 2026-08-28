import { createBrowserRouter, Navigate } from 'react-router'
import { RootLayout } from './RootLayout'
import { ContractsListPage } from '../features/contracts/ContractsListPage'
import { ContractDetailPage } from '../features/contracts/ContractDetailPage'
import { EventsPage } from '../features/events/EventsPage'
import { BalancesPage } from '../features/balances/BalancesPage'
import { WebhooksListPage } from '../features/webhooks/WebhooksListPage'
import { WebhookDetailPage } from '../features/webhooks/WebhookDetailPage'

/**
 * Roteamento das 4 telas da UI mínima da PoC (doc §6) — contratos (lista com cadastro em modal,
 * ver DEC-060/E13 + detalhe/ABI), eventos, saldos (consulta + configuração de monitoramento),
 * webhooks (lista/cadastro/detalhe).
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <RootLayout />,
    children: [
      { index: true, element: <Navigate to="/contracts" replace /> },
      { path: 'contracts', element: <ContractsListPage /> },
      { path: 'contracts/:address', element: <ContractDetailPage /> },
      { path: 'events', element: <EventsPage /> },
      { path: 'balances', element: <BalancesPage /> },
      { path: 'webhooks', element: <WebhooksListPage /> },
      { path: 'webhooks/:id', element: <WebhookDetailPage /> },
    ],
  },
])
