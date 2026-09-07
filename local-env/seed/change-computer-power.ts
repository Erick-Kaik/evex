/**
 * Aumenta ou diminui o `processingPower` de um `TestComputer` já criado (via
 * `computer-vault-scenario.ts` ou `create()` direto) e reflete no Cofre — depósito se o poder subiu,
 * saque se caiu. Complementa `computer-vault-scenario.ts` (que só roda o roteiro fixo de 3 trocas):
 * aqui é um único ajuste avulso, com o delta que você escolher, útil pra gerar mais eventos/entregas
 * de teste sem repetir o roteiro inteiro — inclusive disponível como ação própria na aba
 * "Demonstração" ("Ajustar poder do Computador"), com `computerId`/`powerDelta` como campos.
 *
 * Mantém `motherboard`/`gpu`/`cpu` como estão (lê a config atual via `getConfig()` antes de chamar
 * `changeParts()` com os mesmos valores) — só o `processingPower` muda, mesmo quando o script é usado
 * isoladamente sem trocar peça nenhuma. Requer o cofre já em V2 (`upgrade-upgradeable-vault.ts`) se
 * `POWER_DELTA` for negativo (saque só existe a partir de V2).
 *
 * Uso: `npx tsx local-env/seed/change-computer-power.ts` (de dentro de `local-env/`).
 *
 * Variáveis de ambiente:
 *   RPC_URL               default "http://127.0.0.1:8545"
 *   DEPLOYER_PRIVATE_KEY  default conta 0 do Hardhat
 *   SCAFFOLD_ETH_DIR      default "./local-env-scaffold-eth-2" (relativo a este script)
 *   CHAIN_ID              default "31337"
 *   COMPUTER_ID           default "1" — precisa já ter sido criado antes (reverte com "computador
 *                         inexistente" senão — rode computer-vault-scenario.ts ou create() direto)
 *   POWER_DELTA           default "50" — positivo aumenta (deposit no Cofre), negativo diminui
 *                         (withdraw); nunca pode deixar o poder resultante negativo (revert do
 *                         contrato: "processingPower nao pode ficar negativo")
 */
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";
import { existsSync, readFileSync } from "node:fs";

const RPC_URL = process.env.RPC_URL ?? "http://127.0.0.1:8545";
const DEPLOYER_PRIVATE_KEY =
  process.env.DEPLOYER_PRIVATE_KEY ?? "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");
const CHAIN_ID = process.env.CHAIN_ID ?? "31337";
const COMPUTER_ID = BigInt(process.env.COMPUTER_ID ?? "1");
const POWER_DELTA = BigInt(process.env.POWER_DELTA ?? "50");

const LOCAL_ENV_DIR = resolve(import.meta.dirname, "..");
const LOG_DIR = resolve(LOCAL_ENV_DIR, ".logs");
const VAULT_STATE_FILE = resolve(LOG_DIR, "upgradeable-vault.json");
const VAULT_V2_ABI_FILE = resolve(LOG_DIR, "vault-v2-abi.json");

const hardhatRequire = createRequire(pathToFileURL(resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/package.json")).href);
const { Contract, JsonRpcProvider, NonceManager, Wallet } = hardhatRequire("ethers") as typeof import("ethers");

type DeployedContract = { address: string; abi: unknown[] };
type DeployedContracts = Record<string, Record<string, DeployedContract>>;

async function loadDeployedContracts(): Promise<DeployedContracts> {
  const deployedContractsPath = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(deployedContractsPath).href);
  return module.default as DeployedContracts;
}

function resolveVaultProxyAddress(): string {
  if (process.env.VAULT_PROXY_ADDRESS) return process.env.VAULT_PROXY_ADDRESS;
  if (!existsSync(VAULT_STATE_FILE)) {
    throw new Error(
      `Nenhum cofre conhecido (${VAULT_STATE_FILE} não existe). Rode deploy-upgradeable-vault.ts antes.`,
    );
  }
  return (JSON.parse(readFileSync(VAULT_STATE_FILE, "utf8")) as { proxyAddress: string }).proxyAddress;
}

function loadVaultAbi(): unknown[] {
  // POWER_DELTA negativo precisa de withdraw() (só existe em V2) — positivo funcionaria só com V1,
  // mas exige V2 de qualquer forma por simplicidade (mesmo pré-requisito de computer-vault-scenario.ts,
  // um único script sem 2 caminhos a manter).
  if (!existsSync(VAULT_V2_ABI_FILE)) {
    throw new Error(
      `Cofre ainda não upgradado pra V2 (${VAULT_V2_ABI_FILE} não existe) — rode upgrade-upgradeable-vault.ts antes.`,
    );
  }
  return JSON.parse(readFileSync(VAULT_V2_ABI_FILE, "utf8")) as unknown[];
}

async function main(): Promise<void> {
  const provider = new JsonRpcProvider(RPC_URL);
  const signer = new NonceManager(new Wallet(DEPLOYER_PRIVATE_KEY, provider));

  const deployedContracts = await loadDeployedContracts();
  const computerDeployed = deployedContracts[CHAIN_ID]?.TestComputer;
  if (!computerDeployed) {
    throw new Error(
      `TestComputer não encontrado em deployedContracts.ts pro chainId ${CHAIN_ID}. Rode ` +
        `"yarn deploy --tags TestContracts" (com o TestComputer.sol já copiado) antes deste script.`,
    );
  }
  const vaultProxyAddress = resolveVaultProxyAddress();
  const vaultAbi = loadVaultAbi();

  const computer = new Contract(computerDeployed.address, computerDeployed.abi, signer);
  const vault = new Contract(vaultProxyAddress, vaultAbi, signer);

  const before = await computer.getConfig(COMPUTER_ID);
  const [motherboard, gpu, cpu, currentPower] = before as [string, string, string, bigint];
  if (motherboard === "" && gpu === "" && cpu === "" && currentPower === 0n) {
    throw new Error(
      `Computador id=${COMPUTER_ID} parece não existir ainda (getConfig devolveu tudo vazio/zero) — ` +
        `rode computer-vault-scenario.ts (ou create() direto) antes de ajustar o poder dele.`,
    );
  }

  console.log(`==> Computador ${COMPUTER_ID} antes: ${motherboard}/${gpu}/${cpu}, power=${currentPower}`);
  console.log(`==> Aplicando powerDelta=${POWER_DELTA} (mesmas peças, só o poder muda)...`);

  const changeTx = await computer.changeParts(COMPUTER_ID, motherboard, gpu, cpu, POWER_DELTA);
  const changeReceipt = await changeTx.wait();
  const newPower = currentPower + POWER_DELTA;
  console.log(`    ✔ PartsChanged(${POWER_DELTA}, newPower=${newPower}) — bloco ${changeReceipt.blockNumber} (tx ${changeTx.hash})`);

  const amount = POWER_DELTA >= 0n ? POWER_DELTA : -POWER_DELTA;
  if (POWER_DELTA >= 0n) {
    const depositTx = await vault.deposit(amount);
    const depositReceipt = await depositTx.wait();
    console.log(`    ✔ Deposited(${amount}) — bloco ${depositReceipt.blockNumber} (tx ${depositTx.hash})`);
  } else {
    const withdrawTx = await vault.withdraw(amount);
    const withdrawReceipt = await withdrawTx.wait();
    console.log(`    ✔ Withdrawn(${amount}) — bloco ${withdrawReceipt.blockNumber} (tx ${withdrawTx.hash})`);
  }

  console.log("");
  console.log(
    `Pronto. Computador ${COMPUTER_ID} agora tem power=${newPower} (era ${currentPower}). Confira o ` +
      `webhook/entrega correspondente pra ver o enriquecimento refletindo essa mudança no bloco certo.`,
  );
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
