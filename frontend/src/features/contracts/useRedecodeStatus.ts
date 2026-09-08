import { RedecodeApi } from '../../api/api'
import type { RedecodeStatus } from '../../api/api'
import { apiConfiguration } from '../../api/config'
import { useServerSentStatus } from '../../components/useServerSentStatus'
import type { ServerSentStatus } from '../../components/useServerSentStatus'

const redecodeApi = new RedecodeApi(apiConfiguration)

/** Chave de query do status de redecode — exportada pra quem dispara o job invalidar/acordar o polling degradado (`useContracts.ts::useRegisterAbi`). */
export const redecodeStatusKey = ['redecode-status'] as const

/**
 * Status ao vivo do job **global** de redecode (E5) via SSE (`GET /v1/redecode/status/stream`,
 * E17/DEC-074). O redecode não é por contrato — o status vale para toda a instância. Fallback pra
 * polling de `GET /v1/redecode/status` quando o stream cai (`useServerSentStatus`).
 *
 * `stopPollingWhenIdle` (achado da análise de 2026-09-07): em modo degradado (sem SSE), o polling
 * REST parava sozinho só quando o componente desmontava — mesmo com `running: false` (nenhum job
 * ativo), continuava batendo em `pollIntervalMs` indefinidamente. Como o redecode é disparado pelo
 * próprio backend ao cadastrar uma ABI com histórico (não por uma ação explícita do usuário nesta
 * tela), `useRegisterAbi` invalida `redecodeStatusKey` no `onSuccess` — acorda o polling parado assim
 * que há motivo real pra um job novo existir.
 */
export function useRedecodeStatus(): ServerSentStatus<RedecodeStatus> {
  return useServerSentStatus<RedecodeStatus>('/v1/redecode/status/stream', {
    pollQueryKey: redecodeStatusKey,
    pollQueryFn: async () => (await redecodeApi.getRedecodeStatus()).data,
    stopPollingWhenIdle: (status) => status?.running !== true,
  })
}
