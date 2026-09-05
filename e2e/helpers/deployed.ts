/**
 * Lê `packages/nextjs/contracts/deployedContracts.ts` do clone do scaffold-eth-2 (gerado por
 * `yarn deploy`) pra pegar endereço + ABI dos 4 contratos de teste do local-env. Mesmo loader que
 * `local-env/seed/register-contracts.ts` usa — não reimplementa nada, só reaproveita o artefato.
 */
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { config, scaffoldEthDir } from './config'

const execFileAsync = promisify(execFile)

export type DeployedContract = { address: string; abi: unknown[] }
export type DeployedContracts = Record<string, Record<string, DeployedContract>>

/** Nomes dos contratos de teste do local-env (ver local-env/contracts/). */
export const TEST_CONTRACT_NAMES = ['TestERC20', 'TestERC721', 'TestERC1155', 'TestEventEmitter'] as const
export type TestContractName = (typeof TEST_CONTRACT_NAMES)[number]

let cache: Record<TestContractName, DeployedContract> | null = null

export async function loadDeployedContracts(): Promise<Record<TestContractName, DeployedContract>> {
  if (cache) return cache

  const path = resolve(scaffoldEthDir, 'packages/nextjs/contracts/deployedContracts.ts')
  if (!existsSync(path)) {
    throw new Error(
      `Não achei ${path}. Rode o deploy dos contratos de teste primeiro:\n` +
        `  cd local-env && ./scripts/up.sh   (ou: cd local-env/local-env-scaffold-eth-2 && yarn deploy --tags TestContracts)`,
    )
  }

  // `deployedContracts.ts` é um arquivo `.ts` — o runner do Playwright não carrega `import()`
  // dinâmico de `.ts` externo, então avaliamos num subprocesso `tsx` (ver `dump-deployed.ts`).
  const e2eDir = new URL('..', import.meta.url).pathname
  const { stdout } = await execFileAsync('npx', ['--no-install', 'tsx', 'helpers/dump-deployed.ts'], {
    cwd: e2eDir,
    timeout: 60_000,
  })
  const parsed = JSON.parse(stdout) as DeployedContracts
  const onChain = parsed[config.chainId]
  if (!onChain) {
    throw new Error(`deployedContracts.ts não tem nada pro chainId ${config.chainId}. Redeploy no local-env.`)
  }

  const result = {} as Record<TestContractName, DeployedContract>
  for (const name of TEST_CONTRACT_NAMES) {
    const entry = onChain[name]
    if (!entry) throw new Error(`Contrato ${name} ausente em deployedContracts.ts. Redeploy no local-env.`)
    result[name] = { address: entry.address.toLowerCase(), abi: entry.abi }
  }

  cache = result
  return result
}

/** Endereço (lowercase) de um contrato de teste específico. */
export async function testContractAddress(name: TestContractName): Promise<string> {
  return (await loadDeployedContracts())[name].address
}

/** ABI (JSON string, formato que `POST /v1/contracts/{address}/abi` espera em `source=UPLOAD_TEXT`). */
export async function testContractAbiJson(name: TestContractName): Promise<string> {
  return JSON.stringify((await loadDeployedContracts())[name].abi)
}
