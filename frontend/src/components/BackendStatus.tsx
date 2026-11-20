import { useEffect, useState } from 'react'
import { fetchHealth } from '../lib/api'

type State = 'checking' | 'online' | 'offline'

export default function BackendStatus() {
  const [state, setState] = useState<State>('checking')

  useEffect(() => {
    let cancelled = false
    fetchHealth()
      .then(() => !cancelled && setState('online'))
      .catch(() => !cancelled && setState('offline'))
    return () => { cancelled = true }
  }, [])

  const styles: Record<State, string> = {
    checking: 'bg-slate-100 text-slate-600',
    online: 'bg-emerald-100 text-emerald-800',
    offline: 'bg-amber-100 text-amber-800',
  }

  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${styles[state]}`}>
      <span className="h-1.5 w-1.5 rounded-full bg-current" />
      API {state}
    </span>
  )
}
