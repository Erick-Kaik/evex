/**
 * Deploya o "cofre" de teste upgradeável (padrão UUPS/ERC1967) na sua V1 e dispara alguns depósitos —
 * primeira metade do cenário 11 do guia de uso (proxy upgradável, ABI versionada por faixa de blocos).
 * A segunda metade (upgrade pra V2 + eventos pós-upgrade) é `upgrade-upgradeable-vault.ts`, rodado
 * depois de cadastrar a ABI de V1 no Evex.
 *
 * Deploya `TestUpgradeableVaultV1` (implementação) + `TestUpgradeableVaultProxy` (ERC1967Proxy) já
 * inicializado através dele (calldata de `initialize()` no construtor do proxy — nunca chama
 * `initialize` direto na implementação, ela mesma se tranca via `_disableInitializers()`). O endereço
 * a cadastrar no Evex é o do **proxy**, não o da implementação.
 *
 * Salva `{ proxyAddress, chainId }` em `local-env/.logs/upgradeable-vault.json` — lido por
 * `upgrade-upgradeable-vault.ts` (evita o operador ter que copiar o endereço na mão entre os 2 passos;
 * dá pra sobrescrever via `VAULT_PROXY_ADDRESS` se preferir). Também grava a ABI de V1 em
 * `local-env/.logs/vault-v1-abi.json` — é o conteúdo a colar na tela "Cadastrar ABI" do Evex.
 *
 * Mesmo padrão de `emit-events.ts`: lê os artifacts do Hardhat pelo clone do scaffold-eth-2 (via
 * `createRequire` escopado, sem `package.json` próprio aqui), assina com a conta deployer padrão do
 * Hardhat. Precisa que `yarn deploy --tags TestContracts` (ou a ação "Deploy dos contratos de teste"
 * do control-panel) já tenha rodado pelo menos uma vez depois de `contracts/*.sol` terem sido copiados
 * pro clone — é o que compila `TestUpgradeableVaultV1/V2/Proxy.sol` e gera os artifacts que este
 * script lê. Não usa o pipeline `rocketh`/`env.deploy()` de propósito: um proxy UUPS é 2 deploys +
 * 1 calldata de init amarrados numa transação lógica só, mais simples de expressar direto em ethers
 * (mesmo raciocínio que já levou `emit-events.ts` a não usar a UI `/debug`, DEC-059).
 *
 * Uso: `npx tsx local-env/seed/deploy-upgradeable-vault.ts` (de dentro de `local-env/`).
 *
 * Variáveis de ambiente:
 *   RPC_URL               default "http://127.0.0.1:8545"
 *   DEPLOYER_PRIVATE_KEY  default conta 0 do Hardhat
 *   SCAFFOLD_ETH_DIR      default "./local-env-scaffold-eth-2" (relativo a este script)
 *   CHAIN_ID              default "31337"
 *   DEPOSITS              default "2" — quantos `deposit()` disparar após o deploy (valores 100, 200, ...)
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
const DEPOSITS = Number.parseInt(process.env.DEPOSITS ?? "2", 10);

const LOCAL_ENV_DIR = resolve(import.meta.dirname, "..");
const LOG_DIR = resolve(LOCAL_ENV_DIR, ".logs");
const STATE_FILE = resolve(LOG_DIR, "upgradeable-vault.json");
const V1_ABI_FILE = resolve(LOG_DIR, "vault-v1-abi.json");

const hardhatRequire = createRequire(pathToFileURL(resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/package.json")).href);
const { Contract, ContractFactory, Interface, JsonRpcProvider, NonceManager, Wallet } =
  hardhatRequire("ethers") as typeof import("ethers");

function loadArtifact(name: string): { abi: unknown[]; bytecode: string } {
  const path = resolve(SCAFFOLD_ETH_DIR, `packages/hardhat/artifacts/contracts/${name}.sol/${name}.json`);
  return hardhatRequire(path) as { abi: unknown[]; bytecode: string };
}

async function main(): Promise<void> {
  const provider = new JsonRpcProvider(RPC_URL);
  const signer = new NonceManager(new Wallet(DEPLOYER_PRIVATE_KEY, provider));

  const v1Artifact = loadArtifact("TestUpgradeableVaultV1");
  const proxyArtifact = loadArtifact("TestUpgradeableVaultProxy");

  console.log("==> Deployando TestUpgradeableVaultV1 (implementação)...");
  const v1Factory = new ContractFactory(v1Artifact.abi, v1Artifact.bytecode, signer);
  const v1 = await v1Factory.deploy();
  await v1.waitForDeployment();
  const v1Address = await v1.getAddress();
  console.log(`    implementação V1: ${v1Address}`);

  const v1Interface = new Interface(v1Artifact.abi);
  const initData = v1Interface.encodeFunctionData("initialize", []);

  console.log("==> Deployando TestUpgradeableVaultProxy (ERC1967Proxy) já inicializado...");
  const proxyFactory = new ContractFactory(proxyArtifact.abi, proxyArtifact.bytecode, signer);
  const proxy = await proxyFactory.deploy(v1Address, initData);
  await proxy.waitForDeployment();
  const proxyAddress = await proxy.getAddress();
  console.log(`    proxy (endereço a cadastrar no Evex): ${proxyAddress}`);

  const vault = new Contract(proxyAddress, v1Artifact.abi, signer);
  for (let i = 1; i <= DEPOSITS; i++) {
    const amount = 100n * BigInt(i);
    const tx = await vault.deposit(amount);
    const receipt = await tx.wait();
    console.log(`    ✔ deposit(${amount}) — bloco ${receipt.blockNumber} (tx ${tx.hash})`);
  }

  mkdirSync(LOG_DIR, { recursive: true });
  writeFileSync(STATE_FILE, JSON.stringify({ proxyAddress, chainId: CHAIN_ID }, null, 2));
  writeFileSync(V1_ABI_FILE, JSON.stringify(v1Artifact.abi, null, 2));

  console.log("");
  console.log("Pronto. Próximos passos (cenário 11 do guia de uso):");
  console.log(`  1. Cadastre o contrato no Evex com o endereço do proxy: ${proxyAddress} (rede TESTNET).`);
  console.log(`  2. Cadastre a ABI colando o conteúdo de ${V1_ABI_FILE}, com "Bloco inicial de validade = 0".`);
  console.log(`  3. Confira os eventos Deposited em GET /v1/events (ou na aba Eventos).`);
  console.log(`  4. Rode upgrade-upgradeable-vault.ts para upgradar pra V2 e ver a 2ª faixa de blocos.`);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
