import { useQuery, useQueryClient } from '@tanstack/react-query'
import { request, type Session } from '../../api'

export const sessionKey = ['session'] as const

export function useSession() {
  return useQuery({
    queryKey: sessionKey,
    queryFn: () => request<Session>('/session'),
    staleTime: 60_000,
    retry: false,
  })
}

export function useRefreshSession() {
  const client = useQueryClient()
  return async () => {
    const session = await request<Session>('/session')
    client.setQueryData(sessionKey, session)
  }
}
