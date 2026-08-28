/**
 * Stub HTTP simulando os 3 endpoints da API v2 do Blockscout que o `BlockscoutClient` do Evex
 * consome (ver backend/src/main/kotlin/.../adapters/outbound/discovery/BlockscoutClient.kt):
 *   GET /api/v2/smart-contracts?filter=verified   -> discovery de contratos verificados
 *   GET /api/v2/smart-contracts/{address}         -> ABI (source=BLOCKSCOUT)
 *   GET /api/v2/addresses/{address}/logs          -> fallback de topic0s (sugestão de interface)
 *
 * Plano B enquanto o bug real do Blockscout (Postgrex "ssl not available", ver docs/decisions.md
 * DEC-055) não é corrigido upstream ou a imagem compilada a partir do source (v11.2.8) não é
 * validada — este stub não testa o Blockscout em si, só o lado do cliente/discovery do Evex.
 *
 * Uso: `npx tsx local-env/blockscout-stub/server.ts` (lê deployedContracts.ts do scaffold-eth-2,
 * então precisa do deploy já feito). Depois, apontar `indexer.blockscout.base-url` do Evex pra
 * `http://localhost:4000` (default deste stub).
 */
import http from "node:http";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";

const PORT = Number(process.env.PORT ?? 4000);
const CHAIN_ID = process.env.CHAIN_ID ?? "31337";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");

// topic0s reais (computados via ethers.id(...) — não hardcoded de memória) dos eventos padrão
// ERC721/ERC1155 — usados só pros contratos que ainda não têm evento observado no Evex (o
// InterfaceDetectionService só cai pro fallback do Blockscout quando findDistinctTopic0s vier vazio).
const FALLBACK_TOPIC0S: Record<string, string[]> = {
  TestERC721: [
    "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", // Transfer(address,address,uint256)
    "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925", // Approval(address,address,uint256)
    "0x17307eab39ab6107e8899845ad3d59bd9653f200f220920489ca2b5937696c31", // ApprovalForAll(address,address,bool)
  ],
  TestERC1155: [
    "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62", // TransferSingle(...)
    "0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb", // TransferBatch(...)
    "0x17307eab39ab6107e8899845ad3d59bd9653f200f220920489ca2b5937696c31", // ApprovalForAll(address,address,bool)
  ],
};

type DeployedContract = { address: string; abi: unknown[] };

async function loadContracts(): Promise<Map<string, DeployedContract & { name: string }>> {
  const path = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(path).href);
  const onChain = module.default[CHAIN_ID] ?? {};
  const byAddress = new Map<string, DeployedContract & { name: string }>();
  for (const [name, contract] of Object.entries(onChain) as [string, DeployedContract][]) {
    byAddress.set(contract.address.toLowerCase(), { name, ...contract });
  }
  return byAddress;
}

async function main() {
  const byAddress = await loadContracts();
  if (byAddress.size === 0) {
    throw new Error("Nenhum contrato encontrado em deployedContracts.ts — rode o deploy antes do stub.");
  }

  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const send = (status: number, body: unknown) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };

    if (url.pathname === "/api/v2/smart-contracts" && url.searchParams.get("filter") === "verified") {
      const items = [...byAddress.values()].map(c => ({ address: c.address, name: c.name }));
      return send(200, { items });
    }

    const smartContractMatch = url.pathname.match(/^\/api\/v2\/smart-contracts\/(0x[a-fA-F0-9]{40})$/);
    if (smartContractMatch) {
      const contract = byAddress.get(smartContractMatch[1].toLowerCase());
      if (!contract) return send(404, { message: "not found" });
      return send(200, { abi: contract.abi });
    }

    const logsMatch = url.pathname.match(/^\/api\/v2\/addresses\/(0x[a-fA-F0-9]{40})\/logs$/);
    if (logsMatch) {
      const contract = byAddress.get(logsMatch[1].toLowerCase());
      const topic0s = contract ? (FALLBACK_TOPIC0S[contract.name] ?? []) : [];
      return send(200, { items: topic0s.map(topic0 => ({ topics: [topic0] })) });
    }

    send(404, { message: "unknown stub route" });
  });

  server.listen(PORT, () => {
    console.log(`Blockscout stub ouvindo em http://localhost:${PORT}`);
    console.log(`Contratos conhecidos: ${[...byAddress.values()].map(c => `${c.name} (${c.address})`).join(", ")}`);
  });
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
