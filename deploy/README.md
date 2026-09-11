# Deploy do Evex no Cloud Run

Guia passo a passo pra subir **backend** e **frontend** no Cloud Run, cada um com seu próprio
repositório no Artifact Registry e seu próprio serviço — via terminal, autenticado com `gcloud auth
login`. Assume que o projeto GCP já existe, as APIs já estão habilitadas e o banco (Postgres) já
está provisionado em algum lugar da mesma org, alcançável por um VPC Connector já existente — nada
disso é criado por estes scripts, só consumido.

> Isto não é parte do produto entregável do Evex (como `local-env/`/`e2e/`) — é infraestrutura pra
> colocar **esta instância** de pé num ambiente real, conectada a uma rede Besu da EBS.

## 0. O que existe aqui

| Arquivo | Papel |
|---|---|
| `deploy/lib.sh` | Helpers compartilhados (carregar `.env`, checar `gcloud`, nome da imagem, estado entre etapas) — não roda sozinho, só é importado pelos scripts abaixo. |
| `deploy/scripts/01-artifact-registry.sh` | Cria os 2 repositórios Docker (1 por app). |
| `deploy/scripts/02-build-backend.sh` | Builda a imagem do backend (Cloud Build) e sobe pro Artifact Registry. |
| `deploy/scripts/03-deploy-backend.sh` | Sobe/atualiza o serviço Cloud Run do backend. |
| `deploy/scripts/04-build-frontend.sh` | Builda a imagem do frontend, já com a URL real do backend. |
| `deploy/scripts/05-deploy-frontend.sh` | Sobe/atualiza o serviço Cloud Run do frontend. |
| `deploy/scripts/06-update-cors.sh` | Aponta o CORS do backend pra URL real do front (só env var). |
| `deploy/deploy-all.sh` | Roda os 6 acima em ordem — atalho pra fazer tudo de uma vez. |
| `deploy/.env.example` | Template das variáveis — copiar pra `deploy/.env` e preencher. |
| `../backend/Dockerfile` | Build da imagem do backend. |
| `../frontend/Dockerfile` + `frontend/deploy/cloudbuild.yaml` | Build da imagem do frontend (contexto = raiz do repo — ver comentário no Dockerfile). |
| `../.gcloudignore` | Evita o Cloud Build subir `local-env/` (4.6GB) junto do build do frontend. |

Cada script em `scripts/` roda sozinho (`./deploy/scripts/03-deploy-backend.sh`) — útil quando só
uma etapa precisa repetir (ex.: mudou só o front → rodar direto o `04` e o `05`). A ordem numérica é
a ordem de dependência real; um script lê o que o anterior deixou em `deploy/.state/` quando precisa
(ex.: `04` lê a URL do backend que o `03` salvou).

## 1. Pré-requisitos

**`gcloud` CLI instalado** (WSL/Ubuntu, se ainda não tiver):
```bash
curl https://sdk.cloud.google.com | bash
exec -l $SHELL
gcloud version
```

**Login:**
```bash
gcloud auth login
gcloud auth list --filter=status:ACTIVE --format='value(account)'   # confirma
```

**Já precisa existir, fora destes scripts** (fica a cargo de quem administra o projeto na org):
- Projeto GCP com billing ativo e as APIs Cloud Run/Artifact Registry/Cloud Build habilitadas.
- O banco Postgres já rodando e alcançável, com endereço/porta/credenciais em mãos.
- Um VPC Connector (Serverless VPC Access) que alcance a rede onde esse banco está.

## 2. Descobrindo/preenchendo cada variável de `deploy/.env`

```bash
cp deploy/.env.example deploy/.env
```

| Variável | Comando pra descobrir o valor |
|---|---|
| `GCP_PROJECT_ID` | `gcloud projects list --format="table(projectId,name)"` |
| `GCP_REGION` | `gcloud run regions list --format="value(name)"` — o default (`southamerica-east1`) já serve se não houver preferência. |
| `AR_BACKEND_REPO` / `AR_FRONTEND_REPO` | Nomes livres, os scripts criam — defaults já servem. |
| `BACKEND_SERVICE` / `FRONTEND_SERVICE` | Nomes livres, viram a URL do serviço — defaults já servem. |
| `EVEX_RPC_URL` | Vem da EBS — teste antes de colar: `curl -s -X POST -H 'Content-Type: application/json' --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' <URL>` precisa devolver um bloco. |
| `EVEX_NETWORK` | `TESTNET` ou `MAINNET`, conforme a rede real. |
| `EVEX_BLOCKSCOUT_URL` | Vem da EBS também, se existir. |
| `EVEX_BLOCKCHAIN_AUTH_TOKEN` | Opcional — só preencher se a rede Besu de teste exigir auth no RPC (PAT/Bearer). Vazio desliga o header, igual hoje. |
| `SPRING_DATASOURCE_URL` | Formato `jdbc:postgresql://HOST:5432/DBNAME` — `HOST` é o endereço do banco que você já tem (privado, alcançável pelo VPC Connector). |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | Credenciais do banco que você já tem. |
| `VPC_CONNECTOR` | `gcloud compute networks vpc-access connectors list --region=$GCP_REGION --format="table(name,network,state)"` — use o `name` de um com `state=READY` que alcance a rede do banco. |

## 3. Rodar o deploy

Tudo de uma vez:
```bash
./deploy/deploy-all.sh
```

Ou etapa por etapa (útil pra ver o que cada uma faz, ou pra repetir só uma):
```bash
./deploy/scripts/01-artifact-registry.sh
./deploy/scripts/02-build-backend.sh
./deploy/scripts/03-deploy-backend.sh
./deploy/scripts/04-build-frontend.sh   # precisa do 03 já ter rodado (lê a URL do backend)
./deploy/scripts/05-deploy-frontend.sh
./deploy/scripts/06-update-cors.sh      # precisa do 03 e do 05
```

Por que essa ordem exata: a URL do backend é assada no bundle JS do frontend em **build-time**
(`VITE_API_BASE_URL`, ver `frontend/src/api/config.ts`) — não dá pra buildar o front antes do
backend existir. E o CORS do backend só pode apontar pra URL do front depois dela existir também.

No final, `deploy-all.sh` imprime as 2 URLs.

## 4. Como o backend conecta no banco

Container único, sem sidecar — `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` são as mesmas env
vars que o backend já lê local (`backend/src/main/resources/application.yml`), zero mudança de
código. O Cloud Run alcança o endereço do banco porque o serviço sobe com `--vpc-connector`
apontando pra um conector que já existe na mesma rede/org do banco.

## 5. Redeploy depois de mudar código

```bash
./deploy/deploy-all.sh
```
Builda imagem nova (tag = short SHA do commit atual) e atualiza os 2 serviços existentes — nada é
recriado do zero. Se só mudou variável de ambiente (ex.: trocar `EVEX_RPC_URL` pra outra rede), não
precisa rebuildar nada:
```bash
gcloud run services update evex-backend --region=$GCP_REGION \
  --update-env-vars=EVEX_RPC_URL=https://nova-url-aqui
```

## 6. Troubleshooting

- **`PERMISSION_DENIED` no `02-build-backend.sh`/`04-build-frontend.sh`** — a service account do
  Cloud Build (`PROJECT_NUMBER@cloudbuild.gserviceaccount.com`) não tem `roles/artifactregistry.writer`.
  Fix (pedir pra quem administra IAM da org, se você não tiver permissão):
  ```bash
  gcloud projects add-iam-policy-binding $GCP_PROJECT_ID \
    --member="serviceAccount:$(gcloud projects describe $GCP_PROJECT_ID --format='value(projectNumber)')@cloudbuild.gserviceaccount.com" \
    --role="roles/artifactregistry.writer"
  ```
- **`03-deploy-backend.sh` sobe mas `/actuator/health` nunca fica `UP`, ou timeout de conexão com o
  banco** — o VPC Connector não alcança a rede do banco, ou o `HOST` da `SPRING_DATASOURCE_URL` está
  errado. Testar de dentro da mesma rede antes de culpar o Cloud Run, ou conferir os logs:
  `gcloud run services logs read evex-backend --region=$GCP_REGION --limit=50`.
- **`eth_getLogs`/RPC falha pro RPC da EBS** — teste isolado: `curl -X POST -H 'Content-Type:
  application/json' --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
  $EVEX_RPC_URL`.
- **Front carrega mas toda chamada de API falha com erro de CORS no console** — rode o
  `06-update-cors.sh` (só ele mexe nisso; se você rodou os scripts fora de ordem, essa etapa pode ter
  ficado pra trás).

## 7. Pontos em aberto (não bloqueiam a demo, mas valem citar)

- **API sem autenticação, pública** (`--allow-unauthenticated` no `03`/`05`) — decisão consciente da
  PoC (`CLAUDE.md`: "sem autenticação/autorização"), mas no Cloud Run isso significa qualquer um com
  a URL cadastra contrato/dispara redecode/lê todos os eventos indexados. Pra fechar isso sem
  implementar auth no Evex: `gcloud run services remove-iam-policy-binding evex-backend
  --region=$GCP_REGION --member=allUsers --role=roles/run.invoker` e acessar via Identity-Aware Proxy
  ou `gcloud run services proxy`.
- **1 instância do backend sempre ligada** (`--min-instances=1 --max-instances=1
  --no-cpu-throttling`) — decorre do poller/dispatcher de webhook/snapshot noturno rodarem fora de
  requisição HTTP e do cursor de indexação ser global e único (ver `CLAUDE.md` raiz, DEC-001). Custo
  é o de uma instância pequena sempre ativa, não escala-a-zero — esperado, não é bug do deploy.
- **`EVEX_START_BLOCK` não existe** — se a rede da EBS já tiver muito histórico, a 1ª subida do
  backend indexa desde o bloco 0 antes de alcançar o presente (nenhum destes scripts semeia o
  cursor).
