/**
 * Serviço HTTP mínimo que dispara os scripts do `local-env` (deploy dos contratos de teste, emissão
 * de eventos, registro no Evex) — pra a aba "Demonstração" do frontend do Evex chamar direto, sem
 * ninguém abrir um terminal (E17, DEC-075).
 *
 * **Não é parte do produto entregável.** É a mesma família de `seed/emit-events.ts` /
 * `seed/register-contracts.ts` — ferramenta de ambiente local. Vive fora de `backend/` de propósito
 * (DEC-075: colocar execução de shell num backend sem auth seria superfície de RCE; e o princípio
 * "local-env não é produto" do `local-env/CLAUDE.md`). O browser não faz `spawn` de processo — por
 * isso precisa deste intermediário.
 *
 * Sem `package.json` próprio (mesma razão de `seed/*.ts`): só usa built-ins do Node (`node:http`,
 * `node:child_process`). Roda via `npx tsx local-env/control-panel/server.ts` — o `up.sh` sobe junto.
 *
 * Variáveis de ambiente:
 *   CONTROL_PANEL_PORT   default "8899" — porta HTTP deste serviço
 *   EVEX_API_URL         default "http://localhost:8080" — repassado pro register-contracts
 *   RPC_URL              default "http://127.0.0.1:8545" — repassado pro emit-events
 *   SCAFFOLD_ETH_DIR     default "../local-env-scaffold-eth-2" (relativo a este arquivo) — o clone
 *
 * Segurança: escuta só em `127.0.0.1` (nunca exposto na rede). CORS liberado porque o frontend do
 * Evex (`localhost:5173`) roda em outra origem. Não valida nada além do id da ação — é ambiente local
 * descartável, não recebe input não-confiável.
 */
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { spawn } from "node:child_process";
import { resolve } from "node:path";

const PORT = Number.parseInt(process.env.CONTROL_PANEL_PORT ?? "8899", 10);
const EVEX_API_URL = process.env.EVEX_API_URL ?? "http://localhost:8080";
const RPC_URL = process.env.RPC_URL ?? "http://127.0.0.1:8545";
const LOCAL_ENV_DIR = resolve(import.meta.dirname, "..");
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(LOCAL_ENV_DIR, "local-env-scaffold-eth-2");

/** Parâmetro configurável de uma ação (renderizado como campo no frontend). */
interface ActionParam {
  name: string;
  label: string;
  type: "number";
  default: number;
}

/**
 * Uma ação disparável. `command` é o texto exibido na UI **antes** de rodar (DEC-075: "mostra o
 * comando exato"); `run` monta o processo de verdade a partir dos parâmetros informados.
 */
interface Action {
  id: string;
  label: string;
  description: string;
  command: (params: Record<string, number>) => string;
  params: ActionParam[];
  run: (params: Record<string, number>) => { cmd: string; args: string[]; cwd: string; env: NodeJS.ProcessEnv };
}

const ACTIONS: Action[] = [
  {
    id: "deploy",
    label: "Deploy dos contratos de teste",
    description:
      "Copia os .sol de local-env/contracts/ pro clone do scaffold-eth-2 e roda 'yarn deploy --tags TestContracts'. " +
      "Regera packages/nextjs/contracts/deployedContracts.ts.",
    command: () =>
      "cp contracts/*.sol <clone>/packages/hardhat/contracts/ && " +
      "cp deploy/01_deploy_test_contracts.ts <clone>/packages/hardhat/deploy/ && " +
      "(cd <clone> && yarn deploy --tags TestContracts)",
    params: [],
    run: () => ({
      cmd: "bash",
      args: [
        "-c",
        `set -e
cp contracts/*.sol "${SCAFFOLD_ETH_DIR}/packages/hardhat/contracts/"
cp deploy/01_deploy_test_contracts.ts "${SCAFFOLD_ETH_DIR}/packages/hardhat/deploy/"
cd "${SCAFFOLD_ETH_DIR}"
yarn deploy --tags TestContracts`,
      ],
      cwd: LOCAL_ENV_DIR,
      env: process.env,
    }),
  },
  {
    id: "emit-events",
    label: "Disparar eventos de teste",
    description:
      "Roda seed/emit-events.ts: mint/safeMint/mintBatch/createWidget/changeStatus/processBatch nos 4 contratos. " +
      "ROUNDS repete o lote inteiro (útil pra gerar volume / testar paginação).",
    command: (p) => `ROUNDS=${p.rounds ?? 1} npx tsx seed/emit-events.ts`,
    params: [{ name: "rounds", label: "Rounds", type: "number", default: 1 }],
    run: (p) => ({
      cmd: "npx",
      args: ["tsx", "seed/emit-events.ts"],
      cwd: LOCAL_ENV_DIR,
      env: { ...process.env, ROUNDS: String(p.rounds ?? 1), RPC_URL },
    }),
  },
  {
    id: "register-contracts",
    label: "Registrar os 4 contratos no Evex",
    description:
      "Roda seed/register-contracts.ts: POST /v1/contracts + POST /v1/contracts/{address}/abi pros 4 contratos de teste. " +
      "Use quando quiser o estado 'já cadastrado' de uma vez em vez de cadastrar pela UI.",
    command: () => `EVEX_API_URL=${EVEX_API_URL} npx tsx seed/register-contracts.ts`,
    params: [],
    run: () => ({
      cmd: "npx",
      args: ["tsx", "seed/register-contracts.ts"],
      cwd: LOCAL_ENV_DIR,
      env: { ...process.env, EVEX_API_URL },
    }),
  },
  {
    id: "deploy-vault",
    label: "Deploy do cofre upgradeável (V1)",
    description:
      "Roda seed/deploy-upgradeable-vault.ts: implementação TestUpgradeableVaultV1 + proxy ERC1967 já inicializado + " +
      "2 deposit(). Imprime o endereço do proxy e o caminho da ABI a cadastrar no Evex (cenário 11 do guia de uso, " +
      "'Bloco inicial de validade = 0'). Requer 'Deploy dos contratos de teste' já rodado ao menos uma vez.",
    command: () => "npx tsx seed/deploy-upgradeable-vault.ts",
    params: [],
    run: () => ({
      cmd: "npx",
      args: ["tsx", "seed/deploy-upgradeable-vault.ts"],
      cwd: LOCAL_ENV_DIR,
      env: { ...process.env, RPC_URL },
    }),
  },
  {
    id: "upgrade-vault",
    label: "Upgrade do cofre pra V2",
    description:
      "Roda seed/upgrade-upgradeable-vault.ts: implementação TestUpgradeableVaultV2 + upgradeToAndCall no proxy do " +
      "'Deploy do cofre upgradeável' + deposit()+withdraw(). Imprime o bloco do upgrade pra cadastrar a 2ª versão da " +
      "ABI no Evex. Requer 'Deploy do cofre upgradeável (V1)' já rodado antes.",
    command: () => "npx tsx seed/upgrade-upgradeable-vault.ts",
    params: [],
    run: () => ({
      cmd: "npx",
      args: ["tsx", "seed/upgrade-upgradeable-vault.ts"],
      cwd: LOCAL_ENV_DIR,
      env: { ...process.env, RPC_URL },
    }),
  },
];

/** Só uma ação por vez — os scripts mexem no mesmo nó Hardhat / deployedContracts.ts. */
let running: string | null = null;

function setCors(res: ServerResponse): void {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  setCors(res);
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

async function readBody(req: IncomingMessage): Promise<Record<string, number>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, number>;
  } catch {
    return {};
  }
}

/** Descrição das ações pro frontend montar os botões + mostrar o comando. */
function describeActions(): unknown {
  return ACTIONS.map((a) => ({
    id: a.id,
    label: a.label,
    description: a.description,
    params: a.params,
    commandTemplate: a.command(Object.fromEntries(a.params.map((p) => [p.name, p.default]))),
  }));
}

/** `running` já foi reservado (sincronamente) pelo handler antes de chamar aqui. */
function runAction(action: Action, params: Record<string, number>, res: ServerResponse): void {
  setCors(res);
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
  });

  // `child.on('error')` e `child.on('close')` podem disparar os dois — guarda contra `res.write`
  // depois de `res.end()` (`ERR_STREAM_WRITE_AFTER_END` derrubaria o serviço).
  let finished = false;
  const send = (event: string, data: unknown) => {
    if (!finished) res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  };
  const finish = (payload: { exitCode: number; error?: string }) => {
    if (finished) return;
    send("done", payload);
    finished = true;
    running = null;
    res.end();
  };

  const spec = action.run(params);
  send("started", { action: action.id, command: action.command(params) });

  const child = spawn(spec.cmd, spec.args, { cwd: spec.cwd, env: spec.env });

  const pipe = (buf: Buffer) => {
    for (const line of buf.toString("utf8").split(/\r?\n/)) {
      if (line.length > 0) send("line", line);
    }
  };
  child.stdout.on("data", pipe);
  child.stderr.on("data", pipe);

  child.on("error", (err) => finish({ exitCode: -1, error: err.message }));
  child.on("close", (code) => finish({ exitCode: code ?? -1 }));

  // Cliente fechou a aba/parou de escutar — mata o processo pra não deixar órfão.
  res.on("close", () => {
    if (!child.killed) child.kill("SIGTERM");
    finished = true;
    running = null;
  });
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${PORT}`);

  if (req.method === "OPTIONS") {
    setCors(res);
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, { ok: true, running });
    return;
  }

  if (req.method === "GET" && url.pathname === "/actions") {
    sendJson(res, 200, describeActions());
    return;
  }

  const runMatch = url.pathname.match(/^\/run\/([a-z-]+)$/);
  if (req.method === "POST" && runMatch) {
    const action = ACTIONS.find((a) => a.id === runMatch[1]);
    if (!action) {
      sendJson(res, 404, { error: `ação desconhecida: ${runMatch[1]}` });
      return;
    }
    // Reserva `running` **sincronamente**, antes do `await readBody` — senão 2 POSTs quase
    // simultâneos passam os dois pelo check e disparam 2 `spawn`.
    if (running) {
      sendJson(res, 409, { error: `já há uma ação rodando: ${running}` });
      return;
    }
    running = action.id;
    void readBody(req).then(
      (params) => runAction(action, params, res),
      (err) => {
        running = null;
        sendJson(res, 400, { error: `corpo inválido: ${String(err)}` });
      },
    );
    return;
  }

  sendJson(res, 404, { error: "not found" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`[control-panel] escutando em http://127.0.0.1:${PORT}`);
  console.log(`[control-panel] ações: ${ACTIONS.map((a) => a.id).join(", ")}`);
});
