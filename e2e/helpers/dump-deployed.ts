/**
 * Rodado via `tsx` (subprocesso, ver `deployed.ts`) — importa o `deployedContracts.ts` do
 * scaffold-eth-2 (arquivo `.ts`, precisa do loader do tsx) e imprime o conteúdo como JSON no stdout.
 * O runner do Playwright não registra loader pra `import()` dinâmico de `.ts` externo, por isso esse
 * desvio por subprocesso.
 */
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import { scaffoldEthDir } from './config'

const path = resolve(scaffoldEthDir, 'packages/nextjs/contracts/deployedContracts.ts')
const mod = (await import(pathToFileURL(path).href)) as { default: unknown }
process.stdout.write(JSON.stringify(mod.default))
