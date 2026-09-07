/**
 * Deploya `TestGameLibrary` apontando pro `TestComputer` já deployado — primeira metade do cenário de
 * validação do Modelo de Entidade (E25, `docs/roadmap.md`). O construtor de `TestGameLibrary` recebe
 * o endereço do `TestComputer` (`ITestComputer.getConfig` é chamado de verdade em `runGame`), por isso
 * este script fica fora do pipeline `rocketh` de `deploy/01_deploy_test_contracts.ts` (mesmo racional
 * de `deploy-upgradeable-vault.ts`: uma dependência de ordem no deploy, mais simples de expressar
 * direto em ethers do que encadear `env.deploy()` do rocketh).
 *
 * Requer que `yarn deploy --tags TestContracts` (ou a ação "Deploy dos contratos de teste" do
 * control-panel) já tenha rodado ao menos uma vez **depois** de `TestGameLibrary.sol` estar copiado
 * pro clone — é o que gera o artifact que este script lê (o `.sol` é compilado mesmo sem estar listado
 * em `01_deploy_test_contracts.ts`, porque `yarn deploy` roda `hardhat compile` primeiro, sobre todos
 * os `.sol` de `contracts/`).
 *
 * Salva `{ address, computerAddress, chainId }` em `local-env/.logs/game-library.json` — lido por
 * `seed/game-library-scenario.ts` (evita copiar o endereço na mão entre os 2 passos).
 *
 * Uso: `npx tsx local-env/seed/deploy-game-library.ts` (de dentro de `local-env/`).
 *
 * Variáveis de ambiente:
 *   RPC_URL               default "http://127.0.0.1:8545"
 *   DEPLOYER_PRIVATE_KEY  default conta 0 do Hardhat
 *   SCAFFOLD_ETH_DIR      default "./local-env-scaffold-eth-2" (relativo a este script)
 *   CHAIN_ID              default "31337"
 */
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";
import { mkdirSync, writeFileSync } from "node:fs";

const RPC_URL = process.env.RPC_URL ?? "http://127.0.0.1:8545";
const DEPLOYER_PRIVATE_KEY =
  process.env.DEPLOYER_PRIVATE_KEY ?? "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");
const CHAIN_ID = process.env.CHAIN_ID ?? "31337";

const LOCAL_ENV_DIR = resolve(import.meta.dirname, "..");
const LOG_DIR = resolve(LOCAL_ENV_DIR, ".logs");
const STATE_FILE = resolve(LOG_DIR, "game-library.json");
const ABI_FILE = resolve(LOG_DIR, "game-library-abi.json");

const hardhatRequire = createRequire(pathToFileURL(resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/package.json")).href);
const { ContractFactory, JsonRpcProvider, NonceManager, Wallet } = hardhatRequire("ethers") as typeof import("ethers");

function loadArtifact(name: string): { abi: unknown[]; bytecode: string } {
  const path = resolve(SCAFFOLD_ETH_DIR, `packages/hardhat/artifacts/contracts/${name}.sol/${name}.json`);
  return hardhatRequire(path) as { abi: unknown[]; bytecode: string };
}

type DeployedContract = { address: string; abi: unknown[] };
type DeployedContracts = Record<string, Record<string, DeployedContract>>;

async function loadDeployedContracts(): Promise<DeployedContracts> {
  const deployedContractsPath = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(deployedContractsPath).href);
  return module.default as DeployedContracts;
}

async function main(): Promise<void> {
  const provider = new JsonRpcProvider(RPC_URL);
  const signer = new NonceManager(new Wallet(DEPLOYER_PRIVATE_KEY, provider));

  const deployedContracts = await loadDeployedContracts();
  const computerDeployed = deployedContracts[CHAIN_ID]?.TestComputer;
  if (!computerDeployed) {
    throw new Error(
      `TestComputer não encontrado em deployedContracts.ts pro chainId ${CHAIN_ID}. Rode ` +
        `"yarn deploy --tags TestContracts" antes deste script.`,
    );
  }

  const artifact = loadArtifact("TestGameLibrary");
  console.log(`==> Deployando TestGameLibrary (computer = ${computerDeployed.address})...`);
  const factory = new ContractFactory(artifact.abi, artifact.bytecode, signer);
  const library = await factory.deploy(computerDeployed.address);
  await library.waitForDeployment();
  const libraryAddress = await library.getAddress();
  console.log(`    TestGameLibrary: ${libraryAddress}`);

  mkdirSync(LOG_DIR, { recursive: true });
  writeFileSync(
    STATE_FILE,
    JSON.stringify({ address: libraryAddress, computerAddress: computerDeployed.address, chainId: CHAIN_ID }, null, 2),
  );
  writeFileSync(ABI_FILE, JSON.stringify(artifact.abi, null, 2));

  console.log("");
  console.log("Pronto. Próximos passos (cenário de validação da E25):");
  console.log(`  1. Cadastre o contrato no Evex: endereço ${libraryAddress}, rede TESTNET.`);
  console.log(`  2. Cadastre a ABI colando o conteúdo de ${ABI_FILE}.`);
  console.log(`  3. Rode game-library-scenario.ts pra gerar o roteiro completo (revert + sucesso).`);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
