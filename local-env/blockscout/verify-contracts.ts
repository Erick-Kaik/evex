/**
 * Verifica os contratos de teste no Blockscout real via API v2 (`POST /api/v2/smart-contracts/
 * {address}/verification/via/standard-input`), direto — sem passar pelo `rocketh-verify` (que fala a
 * API antiga estilo Etherscan e não bate com este endpoint, ver docs/decisions.md DEC-056).
 *
 * Usa o standard-json-input EXATO que o Hardhat já gerou em `artifacts/build-info/*.json` — inclui as
 * `remappings` (ex.: `@openzeppelin/contracts/` -> `npm/@openzeppelin/contracts@5.0.2/`), então o solc
 * do Blockscout recompila de forma autocontida, sem precisar reproduzir a estrutura de pastas do
 * projeto original.
 *
 * A verificação é assíncrona no Blockscout (`Que.add(SolidityPublisherWorker, ...)`) — este script só
 * dispara o POST e faz polling em `GET /api/v2/smart-contracts/{address}` até `is_verified` (ou
 * timeout).
 *
 * Uso: `npx tsx local-env/blockscout/verify-contracts.ts` (contratos já deployados, Blockscout real
 * já rodando em `EVEX_BLOCKSCOUT_URL`/`BLOCKSCOUT_URL`).
 */
import { pathToFileURL } from "node:url";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";

const BLOCKSCOUT_URL = process.env.BLOCKSCOUT_URL ?? "http://localhost:4001";
const CHAIN_ID = process.env.CHAIN_ID ?? "31337";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");
const HARDHAT_DIR = resolve(SCAFFOLD_ETH_DIR, "packages/hardhat");

// Contratos a verificar — nome tem que bater com o nome do arquivo .sol e do contrato.
const CONTRACTS_TO_VERIFY = ["TestERC20", "TestEventEmitter"];

type DeployedContract = { address: string };

function findBuildInputFor(contractName: string): { input: unknown; solcLongVersion: string; sourceKey: string } {
  const buildInfoDir = resolve(HARDHAT_DIR, "artifacts/build-info");
  const candidates = readdirSync(buildInfoDir).filter(f => f.endsWith(".json") && !f.endsWith(".output.json"));
  for (const file of candidates) {
    const data = JSON.parse(readFileSync(resolve(buildInfoDir, file), "utf-8"));
    const sourceKey = Object.keys(data.input.sources).find(
      key => key.endsWith(`contracts/${contractName}.sol`),
    );
    if (sourceKey) {
      return { input: data.input, solcLongVersion: data.solcLongVersion, sourceKey };
    }
  }
  throw new Error(`Nenhum build-info encontrado cobrindo ${contractName}. Rode "yarn hardhat:compile" antes.`);
}

async function loadDeployedContracts(): Promise<Map<string, DeployedContract>> {
  const path = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(path).href);
  const onChain = module.default[CHAIN_ID] ?? {};
  return new Map(Object.entries(onChain) as [string, DeployedContract][]);
}

async function submitVerification(address: string, contractName: string): Promise<void> {
  const { input, solcLongVersion, sourceKey } = findBuildInputFor(contractName);
  const compilerVersion = `v${solcLongVersion}`;
  const fullyQualifiedName = `${sourceKey}:${contractName}`;

  const form = new FormData();
  form.append("compiler_version", compilerVersion);
  form.append("contract_name", fullyQualifiedName);
  form.append("autodetect_constructor_args", "true");
  form.append("license_type", "mit");
  // "files[0]" (bracket notation), não "files" — Plug só monta um map (exigido por
  // PublishHelper.prepare_files_array/get_one_json) quando o campo tem chave, não um upload solto.
  form.append("files[0]", new Blob([JSON.stringify(input)], { type: "application/json" }), "input.json");

  const response = await fetch(`${BLOCKSCOUT_URL}/api/v2/smart-contracts/${address}/verification/via/standard-input`, {
    method: "POST",
    body: form,
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Verificação de ${contractName} (${address}) falhou: ${response.status} ${body}`);
  }
  console.log(`${contractName} (${address}): verificação disparada — ${body}`);
}

async function pollVerified(address: string, contractName: string, timeoutMs = 30_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const response = await fetch(`${BLOCKSCOUT_URL}/api/v2/smart-contracts/${address}`);
    if (response.ok) {
      const body = await response.json();
      if (body.is_verified) {
        console.log(`  ✔ ${contractName} verificado (compiler ${body.compiler_version}).`);
        return;
      }
    }
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  console.log(`  ✘ ${contractName} não confirmou verificado dentro do timeout — checar "docker logs backend".`);
}

async function main(): Promise<void> {
  const deployedContracts = await loadDeployedContracts();
  for (const name of CONTRACTS_TO_VERIFY) {
    const contract = deployedContracts.get(name);
    if (!contract) {
      console.log(`${name}: não encontrado em deployedContracts.ts, pulando.`);
      continue;
    }
    await submitVerification(contract.address, name);
    await pollVerified(contract.address, name);
  }
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
