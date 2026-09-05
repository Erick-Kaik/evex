/**
 * Upgrada o "cofre" de teste (proxy UUPS já deployado por `deploy-upgradeable-vault.ts`) de V1 pra V2
 * e dispara depósitos/saques pós-upgrade — segunda metade do cenário 11 do guia de uso (proxy
 * upgradável, ABI versionada por faixa de blocos).
 *
 * Deploya `TestUpgradeableVaultV2` (implementação nova) e chama `upgradeToAndCall(v2Address, "0x")`
 * **através do proxy** (função herdada de UUPSUpgradeable, já presente na ABI de V1 — quem upgrada é a
 * própria implementação atual, via delegatecall, não o proxy). O bloco dessa transação é o `B` que vai
 * no campo "Bloco inicial de validade" ao cadastrar a ABI de V2 no Evex (feche a versão anterior nesse
 * mesmo bloco, exatamente como o cenário 11 descreve).
 *
 * Lê o endereço do proxy de `local-env/.logs/upgradeable-vault.json` (escrito por
 * `deploy-upgradeable-vault.ts`) — sobrescreva com `VAULT_PROXY_ADDRESS` se preferir apontar pra outro
 * proxy. Grava a ABI de V2 em `local-env/.logs/vault-v2-abi.json` (conteúdo a colar no Evex).
 *
 * Uso: `npx tsx local-env/seed/upgrade-upgradeable-vault.ts` (de dentro de `local-env/`, depois de
 * `deploy-upgradeable-vault.ts` e — idealmente — de já ter cadastrado a ABI de V1 no Evex com "Bloco
 * inicial de validade = 0", pra ver a distinção real entre as 2 faixas na tela do contrato).
 *
 * Variáveis de ambiente:
 *   RPC_URL               default "http://127.0.0.1:8545"
 *   DEPLOYER_PRIVATE_KEY  default conta 0 do Hardhat
 *   VAULT_PROXY_ADDRESS   default: lido de .logs/upgradeable-vault.json
 *   SCAFFOLD_ETH_DIR      default "./local-env-scaffold-eth-2" (relativo a este script)
 *   DEPOSITS              default "1" — quantos `deposit()` disparar depois do upgrade
 *   WITHDRAWALS           default "1" — quantos `withdraw()` disparar depois do upgrade (evento novo)
 */
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const RPC_URL = process.env.RPC_URL ?? "http://127.0.0.1:8545";
const DEPLOYER_PRIVATE_KEY =
  process.env.DEPLOYER_PRIVATE_KEY ?? "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");
const DEPOSITS = Number.parseInt(process.env.DEPOSITS ?? "1", 10);
const WITHDRAWALS = Number.parseInt(process.env.WITHDRAWALS ?? "1", 10);

const LOCAL_ENV_DIR = resolve(import.meta.dirname, "..");
const LOG_DIR = resolve(LOCAL_ENV_DIR, ".logs");
const STATE_FILE = resolve(LOG_DIR, "upgradeable-vault.json");
const V2_ABI_FILE = resolve(LOG_DIR, "vault-v2-abi.json");

const hardhatRequire = createRequire(pathToFileURL(resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/package.json")).href);
const { Contract, ContractFactory, JsonRpcProvider, NonceManager, Wallet } =
  hardhatRequire("ethers") as typeof import("ethers");

function loadArtifact(name: string): { abi: unknown[]; bytecode: string } {
  const path = resolve(SCAFFOLD_ETH_DIR, `packages/hardhat/artifacts/contracts/${name}.sol/${name}.json`);
  return hardhatRequire(path) as { abi: unknown[]; bytecode: string };
}

function resolveProxyAddress(): string {
  if (process.env.VAULT_PROXY_ADDRESS) return process.env.VAULT_PROXY_ADDRESS;
  if (!existsSync(STATE_FILE)) {
    throw new Error(
      `Nenhum proxy conhecido (${STATE_FILE} não existe). Rode deploy-upgradeable-vault.ts antes, ou ` +
        `passe VAULT_PROXY_ADDRESS explicitamente.`,
    );
  }
  return (JSON.parse(readFileSync(STATE_FILE, "utf8")) as { proxyAddress: string }).proxyAddress;
}

async function main(): Promise<void> {
  const provider = new JsonRpcProvider(RPC_URL);
  const signer = new NonceManager(new Wallet(DEPLOYER_PRIVATE_KEY, provider));
  const proxyAddress = resolveProxyAddress();

  const v2Artifact = loadArtifact("TestUpgradeableVaultV2");

  console.log("==> Deployando TestUpgradeableVaultV2 (implementação nova)...");
  const v2Factory = new ContractFactory(v2Artifact.abi, v2Artifact.bytecode, signer);
  const v2 = await v2Factory.deploy();
  await v2.waitForDeployment();
  const v2Address = await v2.getAddress();
  console.log(`    implementação V2: ${v2Address}`);

  // `upgradeToAndCall` é chamado no endereço do proxy, mas executa o código da implementação atual
  // (V1) via delegatecall — é ela quem sabe fazer o upgrade (UUPSUpgradeable), não o proxy.
  console.log(`==> Upgradando o proxy ${proxyAddress} de V1 pra V2 (upgradeToAndCall)...`);
  const proxyAsV2 = new Contract(proxyAddress, v2Artifact.abi, signer);
  const upgradeTx = await proxyAsV2.upgradeToAndCall(v2Address, "0x");
  const upgradeReceipt = await upgradeTx.wait();
  const upgradeBlock = upgradeReceipt.blockNumber as number;
  console.log(`    ✔ upgrade confirmado no bloco ${upgradeBlock} (tx ${upgradeTx.hash})`);

  for (let i = 1; i <= DEPOSITS; i++) {
    const amount = 500n * BigInt(i);
    const tx = await proxyAsV2.deposit(amount);
    const receipt = await tx.wait();
    console.log(`    ✔ deposit(${amount}) pós-upgrade — bloco ${receipt.blockNumber} (tx ${tx.hash})`);
  }
  for (let i = 1; i <= WITHDRAWALS; i++) {
    const amount = 50n * BigInt(i);
    const tx = await proxyAsV2.withdraw(amount);
    const receipt = await tx.wait();
    console.log(`    ✔ withdraw(${amount}) — bloco ${receipt.blockNumber} (tx ${tx.hash})`);
  }

  writeFileSync(V2_ABI_FILE, JSON.stringify(v2Artifact.abi, null, 2));

  console.log("");
  console.log("Pronto. Próximos passos (cenário 11 do guia de uso):");
  console.log(
    `  1. Cadastre a nova versão da ABI (colando ${V2_ABI_FILE}) com "Bloco inicial de validade = ${upgradeBlock}".`,
  );
  console.log(`     O Evex fecha a versão de V1 automaticamente em ${upgradeBlock - 1}.`);
  console.log(`  2. GET /v1/contracts/${proxyAddress}/abi (ou a tela do contrato) deve listar 2 versões.`);
  console.log(`  3. GET /v1/events deve mostrar Deposited antes E depois do upgrade, e Withdrawn só depois.`);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
