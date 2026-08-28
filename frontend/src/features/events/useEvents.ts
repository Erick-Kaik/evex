import { useQuery } from '@tanstack/react-query'
import { EventsApi } from '../../api/api'
import { apiConfiguration } from '../../api/config'

const eventsApi = new EventsApi(apiConfiguration)

export interface EventFilters {
  contractAddress?: string
  eventName?: string
  fromBlock?: number
  toBlock?: number
  fromDate?: string
  toDate?: string
}

/** Lista eventos indexados com filtros opcionais e paginação (`GET /v1/events`). */
export function useEvents(filters: EventFilters, page: number, size: number) {
  return useQuery({
    queryKey: ['events', filters, page, size],
    queryFn: async () =>
      (
        await eventsApi.listEvents(
          filters.contractAddress,
          filters.eventName,
          filters.fromBlock,
          filters.toBlock,
          filters.fromDate,
          filters.toDate,
          page,
          size,
        )
      ).data,
  })
}
