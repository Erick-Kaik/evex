/**
 * Registra no Evex os contratos de teste deployados pelo scaffold-eth-2 (local-env/deploy/).
 *
 * Lê `packages/nextjs/contracts/deployedContracts.ts` (gerado automaticamente por `yarn deploy`)
 * e chama, para cada contrato: POST /v1/contracts (cadastro) + POST /v1/contracts/{address}/abi
 * (cadastro da ABI, source=UPLOAD_TEXT) — ambos endpoints já existentes desde E2/E3, nada novo
 * no backend.
 *
 * Uso: `npx tsx local-env/seed/register-contracts.ts` (rodar de dentro de `local-env/`, depois de
 * `yarn deploy --tags TestContracts` no clone do scaffold-eth-2).
 *
 * Variáveis de ambiente:
 *   EVEX_API_URL      default "http://localhost:8080" (porta padrão do Spring Boot — sem server.port
 *                     configurado em application.yml; ajustar se você configurou outra)
 *   SCAFFOLD_ETH_DIR  default "./local-env-scaffold-eth-2" (relativo a este script)
 *   CHAIN_ID          default "31337" (Hardhat local, via scaffold-eth-2)
 */
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";

const EVEX_API_URL = process.env.EVEX_API_URL ?? "http://localhost:8080";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");
const CHAIN_ID = process.env.CHAIN_ID ?? "31337";

type DeployedContract = { address: string; abi: unknown[] };
type DeployedContracts = Record<string, Record<string, DeployedContract>>;

async function loadDeployedContracts(): Promise<DeployedContracts> {
  const deployedContractsPath = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(deployedContractsPath).href);
  return module.default as DeployedContracts;
}

async function registerContract(name: string, address: string): Promise<void> {
  const response = await fetch(`${EVEX_API_URL}/v1/contracts`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      address,
      name: `local-env: ${name}`,
      description: "Contrato de teste do local-env (scaffold-eth-2), ver local-env/CLAUDE.md",
      network: "TESTNET",
    }),
  });
  // 409 = já cadastrado numa rodada anterior do seed — não é erro, segue pro cadastro de ABI.
  if (!response.ok && response.status !== 409) {
    throw new Error(`POST /v1/contracts (${name}) falhou: ${response.status} ${await response.text()}`);
  }
}

async function registerAbi(name: string, address: string, abi: unknown[]): Promise<void> {
  const response = await fetch(`${EVEX_API_URL}/v1/contracts/${address}/abi`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      source: "UPLOAD_TEXT",
      abi: JSON.stringify(abi),
    }),
  });
  if (!response.ok) {
    throw new Error(`POST /v1/contracts/${address}/abi (${name}) falhou: ${response.status} ${await response.text()}`);
  }
}

async function main(): Promise<void> {
  const deployedContracts = await loadDeployedContracts();
  const contractsOnChain = deployedContracts[CHAIN_ID];
  if (!contractsOnChain) {
    throw new Error(
      `Nenhum contrato deployado encontrado para o chainId ${CHAIN_ID} em deployedContracts.ts. ` +
        `Rode "yarn deploy --tags TestContracts" no scaffold-eth-2 antes do seed.`,
    );
  }

  for (const [name, contract] of Object.entries(contractsOnChain)) {
    console.log(`Registrando ${name} (${contract.address})...`);
    await registerContract(name, contract.address);
    await registerAbi(name, contract.address, contract.abi);
    console.log(`  ✔ ${name} registrado no Evex.`);
  }
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
