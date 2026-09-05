import { RedecodeApi } from '../../api/api'
import type { RedecodeStatus } from '../../api/api'
import { apiConfiguration } from '../../api/config'
import { useServerSentStatus } from '../../components/useServerSentStatus'
import type { ServerSentStatus } from '../../components/useServerSentStatus'

const redecodeApi = new RedecodeApi(apiConfiguration)

/**
 * Status ao vivo do job **global** de redecode (E5) via SSE (`GET /v1/redecode/status/stream`,
 * E17/DEC-074). O redecode não é por contrato — o status vale para toda a instância. Fallback pra
 * polling de `GET /v1/redecode/status` quando o stream cai (`useServerSentStatus`).
 */
export function useRedecodeStatus(): ServerSentStatus<RedecodeStatus> {
  return useServerSentStatus<RedecodeStatus>('/v1/redecode/status/stream', {
    pollQueryKey: ['redecode-status'],
    pollQueryFn: async () => (await redecodeApi.getRedecodeStatus()).data,
  })
}
