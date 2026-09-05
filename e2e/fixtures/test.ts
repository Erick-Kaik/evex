/**
 * `test` estendido com os Page Objects já instanciados por spec, e um `evexApi` (helpers de espera
 * determinística — ver `helpers/evex-api.ts`). Importar daqui, não de `@playwright/test`.
 */
import { test as base } from '@playwright/test'
import {
  AppNav,
  BalancesPage,
  ContractDetailPage,
  ContractsPage,
  DemoPage,
  EventsPage,
  WebhooksPage,
} from './pages'
import * as evexApi from '../helpers/evex-api'

type Fixtures = {
  nav: AppNav
  contractsPage: ContractsPage
  contractDetailPage: ContractDetailPage
  eventsPage: EventsPage
  balancesPage: BalancesPage
  webhooksPage: WebhooksPage
  demoPage: DemoPage
  evexApi: typeof evexApi
}

export const test = base.extend<Fixtures>({
  nav: async ({ page }, use) => use(new AppNav(page)),
  contractsPage: async ({ page }, use) => use(new ContractsPage(page)),
  contractDetailPage: async ({ page }, use) => use(new ContractDetailPage(page)),
  eventsPage: async ({ page }, use) => use(new EventsPage(page)),
  balancesPage: async ({ page }, use) => use(new BalancesPage(page)),
  webhooksPage: async ({ page }, use) => use(new WebhooksPage(page)),
  demoPage: async ({ page }, use) => use(new DemoPage(page)),
  evexApi: async ({}, use) => use(evexApi),
})

export { expect } from '@playwright/test'
