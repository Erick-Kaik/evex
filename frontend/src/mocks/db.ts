/**
 * Base de dados em memória do modo mock (`npm run dev:mock`). É **mutável** — os handlers do MSW
 * alteram estes arrays (cadastrar contrato, cadastrar ABI, pausar config…) para o front parecer vivo
 * numa demo sem backend. Recarregar a página zera tudo de volta pro seed.
 *
 * Não faz parte do produto. Só é importado quando `import.meta.env.VITE_MOCK === '1'`.
 */
import type {
  AssetBalance,
  BalanceMonitorConfig,
  BlockscoutContract,
  Contract,
  ContractAbi,
  ContractEvent,
  Event as IndexedEvent,
  InterfaceSuggestion,
  Wallet,
  WebhookConfig,
  WebhookDelivery,
} from '../api/api'

const NOW = '2026-09-02T20:00:00Z'
const uid = (() => {
  let n = 1000
  return () => `mock-${(n += 1)}`
})()

// ---------- Endereços fixos (batem com os contratos de teste do local-env) ----------

export const ADDR = {
  erc20: '0x5fbdb2315678afecb367f032d93f642f64180aa3',
  widgets: '0xcf7ed3acca5a467e9e704c703e8d87f634fb0fc9',
  erc1155: '0x9fe46736679d2d9a65f0992f2272de9f3c7fa6e0',
  proxy: '0x1111000000000000000000000000000000009999',
  /** Proxy com versão de ABI legada (BLOCKSCOUT sem implementação, from=0) — cenário do opt-in "substituir legado" (DEC-105). */
  legacyProxy: '0x1111000000000000000000000000000000008888',
} as const

const WALLET = {
  alice: '0x70997970c51812dc3a010c7d01b50e0d17dc79c8',
  bob: '0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc',
  carol: '0x90f79bf6eb2c4f870365e785982e1f101e93b906',
  zero: '0x0000000000000000000000000000000000000000',
} as const

// ---------- Estado mutável ----------

export const db = {
  contracts: [] as Contract[],
  abis: [] as ContractAbi[],
  events: [] as IndexedEvent[],
  monitors: [] as BalanceMonitorConfig[],
  balances: [] as AssetBalance[],
  webhooks: [] as WebhookConfig[],
  deliveries: [] as WebhookDelivery[],
  wallets: [] as Wallet[],
}

// ---------- Catálogos de eventos (GET /v1/contracts/{address}/events) ----------

const input = (name: string, type: string, indexed = false) => ({ name, type, indexed })

export const eventCatalogs: Record<string, ContractEvent[]> = {
  [ADDR.erc20]: [
    {
      name: 'Transfer',
      inputs: [input('from', 'address', true), input('to', 'address', true), input('value', 'uint256')],
      validFromBlock: 0,
    },
    {
      name: 'Approval',
      inputs: [input('owner', 'address', true), input('spender', 'address', true), input('value', 'uint256')],
      validFromBlock: 0,
    },
  ],
  [ADDR.widgets]: [
    {
      name: 'WidgetCreated',
      inputs: [input('id', 'uint256', true), input('creator', 'address', true), input('name', 'string'), input('value', 'uint256')],
      validFromBlock: 0,
    },
    {
      name: 'WidgetStatusChanged',
      inputs: [input('id', 'uint256', true), input('previousStatus', 'uint8'), input('newStatus', 'uint8'), input('flagged', 'bool')],
      validFromBlock: 0,
    },
    {
      name: 'BatchProcessed',
      inputs: [input('operator', 'address', true), input('ids', 'uint256[]'), input('totalValue', 'uint256')],
      validFromBlock: 0,
    },
  ],
  // Proxy: `Withdrawal` ganhou o parâmetro `fee` a partir do bloco 150000 (2 faixas).
  [ADDR.proxy]: [
    { name: 'Deposit', inputs: [input('account', 'address', true), input('amount', 'uint256')], validFromBlock: 0 },
    {
      name: 'Withdrawal',
      inputs: [input('account', 'address', true), input('amount', 'uint256')],
      validFromBlock: 0,
      validToBlock: 149999,
    },
    {
      name: 'Withdrawal',
      inputs: [input('account', 'address', true), input('amount', 'uint256'), input('fee', 'uint256')],
      validFromBlock: 150000,
    },
  ],
  // ERC1155 (ADDR.erc1155) fica de fora de propósito: contrato sem ABI → catálogo vazio.
}

export const interfaceSuggestions: Record<string, InterfaceSuggestion[]> = {
  [ADDR.erc1155]: [
    {
      templateName: 'ERC1155',
      level: 'FULL',
      matchedTopic0s: [
        '0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62',
        '0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb',
      ],
      missingTopic0s: [],
    },
  ],
}

// ---------- Seed ----------

const LEGACY_ABI_ID = 'a0000000-0000-4000-8000-0000000000e1'

function seed(): void {
  db.contracts = [
    { id: uid(), address: ADDR.erc20, name: 'Token BRLX (ERC20)', description: 'Stablecoin de teste', version: '1.0.0', network: 'TESTNET', status: 'ACTIVE' },
    { id: uid(), address: ADDR.widgets, name: 'Registro de Widgets', description: 'Contrato de eventos variados', network: 'TESTNET', status: 'ACTIVE' },
    { id: uid(), address: ADDR.erc1155, name: 'Coleção NFT (ERC1155)', description: 'Ainda sem ABI cadastrada', network: 'TESTNET', status: 'ACTIVE' },
    { id: uid(), address: ADDR.proxy, name: 'Cofre (proxy upgradável)', description: 'ERC1967Proxy — ABI versionada por faixa de blocos', version: '2.1.0', network: 'MAINNET', status: 'ACTIVE' },
    { id: uid(), address: ADDR.legacyProxy, name: 'Proxy com ABI legada do Blockscout', description: 'Demonstra a importação de histórico com versão legada a substituir', network: 'TESTNET', status: 'ACTIVE' },
  ]

  db.abis = [
    { id: uid(), contractAddress: ADDR.erc20, network: 'TESTNET', source: 'UPLOAD_TEXT', storageType: 'DATABASE', validFromBlock: 0, createdAt: NOW },
    { id: uid(), contractAddress: ADDR.widgets, network: 'TESTNET', source: 'BLOCKSCOUT', storageType: 'DATABASE', validFromBlock: 0, createdAt: NOW },
    { id: uid(), contractAddress: ADDR.proxy, network: 'MAINNET', source: 'AUTO_DETECTED', storageType: 'DATABASE', validFromBlock: 0, validToBlock: 149999, createdAt: '2026-08-01T10:00:00Z' },
    { id: LEGACY_ABI_ID, contractAddress: ADDR.legacyProxy, network: 'TESTNET', source: 'BLOCKSCOUT', storageType: 'DATABASE', validFromBlock: 0, createdAt: NOW },
    { id: uid(), contractAddress: ADDR.proxy, network: 'MAINNET', source: 'UPLOAD_TEXT', storageType: 'DATABASE', validFromBlock: 150000, createdAt: NOW },
  ]

  const ev = (
    over: Partial<IndexedEvent> & Pick<IndexedEvent, 'contractAddress' | 'blockNumber' | 'decoded'>,
  ): IndexedEvent => ({
    id: uid(),
    network: over.contractAddress === ADDR.proxy ? 'MAINNET' : 'TESTNET',
    blockHash: '0x' + 'ab'.repeat(32),
    txHash: '0x' + over.blockNumber.toString(16).padStart(6, '0') + 'cdef'.repeat(14),
    logIndex: 0,
    topic0: '0x' + 'dd'.repeat(32),
    blockTimestamp: `2026-09-02T${String(19 + Math.floor(over.blockNumber / 60) % 5).padStart(2, '0')}:${String(over.blockNumber % 60).padStart(2, '0')}:00Z`,
    createdAt: NOW,
    ...over,
  })

  db.events = [
    // ERC20 — decodificados
    ev({ contractAddress: ADDR.erc20, blockNumber: 180, decoded: true, eventName: 'Transfer', decodedParameters: { from: WALLET.zero, to: WALLET.alice, value: '250000' } }),
    ev({ contractAddress: ADDR.erc20, blockNumber: 178, decoded: true, eventName: 'Transfer', decodedParameters: { from: WALLET.alice, to: WALLET.bob, value: '40000' } }),
    ev({ contractAddress: ADDR.erc20, blockNumber: 171, decoded: true, eventName: 'Approval', decodedParameters: { owner: WALLET.alice, spender: WALLET.carol, value: '1000000' } }),
    ev({ contractAddress: ADDR.erc20, blockNumber: 160, decoded: true, eventName: 'Transfer', decodedParameters: { from: WALLET.zero, to: WALLET.bob, value: '90000' } }),
    // Widgets — decodificados
    ev({ contractAddress: ADDR.widgets, blockNumber: 176, decoded: true, eventName: 'WidgetCreated', decodedParameters: { id: '7', creator: WALLET.bob, name: 'widget-alpha', value: '1200' } }),
    ev({ contractAddress: ADDR.widgets, blockNumber: 175, decoded: true, eventName: 'WidgetStatusChanged', decodedParameters: { id: '7', previousStatus: '0', newStatus: '1', flagged: false } }),
    ev({ contractAddress: ADDR.widgets, blockNumber: 168, decoded: true, eventName: 'BatchProcessed', decodedParameters: { operator: WALLET.carol, ids: ['1', '2', '3'], totalValue: '5400' } }),
    // ERC1155 — SEM ABI (raw)
    ev({ contractAddress: ADDR.erc1155, blockNumber: 179, decoded: false }),
    ev({ contractAddress: ADDR.erc1155, blockNumber: 172, decoded: false }),
    ev({ contractAddress: ADDR.erc1155, blockNumber: 165, decoded: false }),
    ev({ contractAddress: ADDR.erc1155, blockNumber: 151, decoded: false }),
    // Proxy — decodificados (faixas diferentes)
    ev({ contractAddress: ADDR.proxy, blockNumber: 210000, decoded: true, eventName: 'Withdrawal', decodedParameters: { account: WALLET.alice, amount: '5000', fee: '25' } }),
    ev({ contractAddress: ADDR.proxy, blockNumber: 120000, decoded: true, eventName: 'Withdrawal', decodedParameters: { account: WALLET.bob, amount: '3000' } }),
    ev({ contractAddress: ADDR.proxy, blockNumber: 90000, decoded: true, eventName: 'Deposit', decodedParameters: { account: WALLET.alice, amount: '8000' } }),
  ]

  db.monitors = [
    { id: uid(), contractAddress: ADDR.erc20, network: 'TESTNET', eventName: 'Transfer', behavior: 'TRANSFER', fromParam: 'from', toParam: 'to', valueParam: 'value', active: true, createdAt: NOW },
    { id: uid(), contractAddress: ADDR.widgets, network: 'TESTNET', eventName: 'WidgetCreated', behavior: 'CREDIT', walletParam: 'creator', valueParam: 'value', active: false, createdAt: NOW },
  ]

  db.balances = [
    { walletAddress: WALLET.alice, contractAddress: ADDR.erc20, network: 'TESTNET', balance: '210000', updatedAt: NOW },
    { walletAddress: WALLET.bob, contractAddress: ADDR.erc20, network: 'TESTNET', balance: '130000', updatedAt: NOW },
    { walletAddress: WALLET.bob, contractAddress: ADDR.widgets, network: 'TESTNET', balance: '1200', updatedAt: NOW },
  ]

  // `WALLET.bob` fica de propósito FORA de `db.wallets` (já tem saldo em `db.balances` acima) — é o
  // candidato que `GET /v1/wallets/suggestions` deve sugerir. O 2º item demonstra `looksLikeContract`
  // (endereço cadastrado como "carteira" que na verdade é um dos contratos do seed).
  db.wallets = [
    { id: uid(), address: WALLET.alice, label: 'Alice (holder)', description: 'Carteira de teste com saldo em BRLX', network: 'TESTNET', status: 'ACTIVE', looksLikeContract: false, looksLikeContractAcknowledged: false },
    // `looksLikeContractAcknowledged: false` de propósito — demonstra o aviso (com o botão "Ciente,
    // não mostrar de novo") ao abrir o detalhe desta carteira no modo mock.
    { id: uid(), address: ADDR.erc1155, label: 'Endereço suspeito', description: 'Cadastrado de propósito pra demonstrar o aviso looksLikeContract — na verdade é o endereço do contrato NFT (ERC1155) do seed.', network: 'TESTNET', status: 'ACTIVE', looksLikeContract: true, looksLikeContractAcknowledged: false },
  ]

  db.webhooks = [
    { id: uid(), contractAddress: ADDR.erc20, network: 'TESTNET', eventName: 'Transfer', url: 'https://example-webhook.site/brlx-transfers', filters: [{ paramName: 'value', operator: 'GT', value: '100000' }], payloadTemplate: '{"evento":{{eventName}},"de":{{decodedParameters.from}},"para":{{decodedParameters.to}},"valor":{{decodedParameters.value}}}', active: true, createdAt: NOW },
    { id: uid(), contractAddress: ADDR.widgets, network: 'TESTNET', eventName: 'WidgetStatusChanged', url: 'https://example-webhook.site/widget-status', filters: [], active: false, createdAt: NOW },
  ]

  const w1 = db.webhooks[0].id
  db.deliveries = [
    {
      id: uid(), webhookConfigId: w1, blockchainEventId: db.events[0].id,
      payload: '{"evento":"Transfer","de":"0x0000…0000","para":"0x7099…79c8","valor":"250000"}',
      status: 'DELIVERED', attemptCount: 1, createdAt: NOW,
      attempts: [{ id: uid(), deliveryId: 'd1', attemptNumber: 1, attemptedAt: NOW, success: true, httpStatus: 200, responseBody: 'OK' }],
    },
    {
      id: uid(), webhookConfigId: w1, blockchainEventId: db.events[3].id,
      payload: '{"evento":"Transfer","de":"0x0000…0000","para":"0x3c44…93bc","valor":"90000"}',
      status: 'FAILED', attemptCount: 3, createdAt: NOW,
      attempts: [
        { id: uid(), deliveryId: 'd2', attemptNumber: 1, attemptedAt: NOW, success: false, errorMessage: 'connect ETIMEDOUT' },
        { id: uid(), deliveryId: 'd2', attemptNumber: 2, attemptedAt: NOW, success: false, httpStatus: 503, responseBody: 'Service Unavailable' },
        { id: uid(), deliveryId: 'd2', attemptNumber: 3, attemptedAt: NOW, success: false, httpStatus: 503, responseBody: 'Service Unavailable' },
      ],
    },
  ]
}

seed()

/**
 * Discovery com `kind`/proxy (DEC-104): cobre os 4 casos — IMPLEMENTATION com 2 proxies (caso
 * "ClassANBIMA": dois proxies, mesma implementação), IMPLEMENTATION com 1 proxy (pré-preenche o proxy),
 * PROXY e STANDALONE. Endereços fictícios.
 */
export const blockscoutVerified: BlockscoutContract[] = [
  { address: ADDR.erc20, name: 'TestERC20', kind: 'STANDALONE' },
  { address: ADDR.widgets, name: 'TestEventEmitter' },
  {
    address: '0x22220000000000000000000000000000000000a1',
    name: 'ClassImpl',
    kind: 'IMPLEMENTATION',
    proxyAddresses: ['0x33330000000000000000000000000000000000b1', '0x33330000000000000000000000000000000000b2'],
    lastUpgradeBlock: 12,
    warnings: [
      'Este endereço é a IMPLEMENTAÇÃO de um proxy: os eventos são emitidos pelo proxy. Cadastre o endereço do proxy (proxyAddresses), não este.',
      'Esta implementação é usada por 2 proxies; cada proxy emite seus próprios eventos.',
    ],
  },
  {
    address: '0x2222000000000000000000000000000000000c01',
    name: 'VaultImpl',
    kind: 'IMPLEMENTATION',
    proxyAddresses: ['0x3333000000000000000000000000000000000d01'],
    lastUpgradeBlock: 40,
    warnings: [
      'Este endereço é a IMPLEMENTAÇÃO de um proxy: os eventos são emitidos pelo proxy. Cadastre o endereço do proxy (proxyAddresses), não este.',
    ],
  },
  {
    address: '0x3333000000000000000000000000000000000e01',
    name: 'TokenProxy',
    kind: 'PROXY',
    implementationAddress: '0x2222000000000000000000000000000000000f01',
    lastUpgradeBlock: 300,
    warnings: [],
  },
]

export const helpers = { uid, NOW, WALLET }
