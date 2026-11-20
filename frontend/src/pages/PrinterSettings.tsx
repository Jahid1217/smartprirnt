import { useEffect, useRef, useState } from 'react'
import { Printer } from 'lucide-react'
import {
  createAgent, errorMessage, fetchPrinterDiscovery, savePrinterRouting,
  setPrinterEnabled, toApiError, type PrinterRouting,
} from '../lib/api'

const routes = [
  ['defaultPrinterId', 'Default Printer'],
  ['colorPrinterId', 'Color Printer'],
  ['bwPrinterId', 'Black & White Printer'],
] as const

type Snapshot = Awaited<ReturnType<typeof fetchPrinterDiscovery>>

async function localAgentCode(signal: AbortSignal): Promise<string | null> {
  try {
    const response = await fetch('http://127.0.0.1:18765/identity', {
      signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
    })
    if (response.ok) {
      const identity = await response.json()
      return typeof identity.agentCode === 'string' ? identity.agentCode : null
    }
  } catch {
    // Remote browsers and browsers that restrict loopback can still use shop heartbeats.
  }
  return null
}

export default function PrinterSettings({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [snapshot, setSnapshot] = useState<Snapshot>()
  const [localCode, setLocalCode] = useState<string | null>(null)
  const [draft, setDraft] = useState<Partial<PrinterRouting>>({})
  const [failure, setFailure] = useState('')
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [busy, setBusy] = useState(false)
  const [credentials, setCredentials] = useState<Awaited<ReturnType<typeof createAgent>>>()
  const refreshRef = useRef<() => void>(() => {})
  const unauthorized = useRef(onUnauthorized)
  unauthorized.current = onUnauthorized

  useEffect(() => {
    let stopped = false
    let pending = false
    let refreshAgain = false
    let timer = 0
    const controller = new AbortController()
    async function refresh(force = false) {
      if (stopped || document.hidden) return
      if (pending) {
        refreshAgain ||= force
        return
      }

      window.clearTimeout(timer)
      pending = true
      const local = new AbortController()
      const localTimeout = window.setTimeout(() => local.abort(), 1500)
      const abortLocal = () => local.abort()
      controller.signal.addEventListener('abort', abortLocal, { once: true })
      try {
        const [result, code] = await Promise.all([
          fetchPrinterDiscovery(controller.signal), localAgentCode(local.signal),
        ])
        if (stopped) return
        setSnapshot(result)
        setLocalCode(code)
        setFailure('')
      } catch (cause) {
        if (stopped) return
        if (toApiError(cause).status === 401) unauthorized.current()
        else setFailure(`Could not load printer discovery. ${errorMessage(cause)}`)
      } finally {
        controller.signal.removeEventListener('abort', abortLocal)
        window.clearTimeout(localTimeout)
        local.abort()
        pending = false
        if (!stopped && !document.hidden) {
          const delay = refreshAgain ? 0 : 10_000
          refreshAgain = false
          timer = window.setTimeout(refresh, delay)
        }
      }
    }

    const visibilityChanged = () => {
      window.clearTimeout(timer)
      if (!document.hidden) void refresh(true)
    }

    refreshRef.current = () => void refresh(true)
    void refresh()
    document.addEventListener('visibilitychange', visibilityChanged)
    return () => {
      stopped = true
      controller.abort()
      window.clearTimeout(timer)
      document.removeEventListener('visibilitychange', visibilityChanged)
    }
  }, [])

  const printers = snapshot?.printers ?? []
  const active = failure ? [] : snapshot?.agents.filter(agent => agent.status === 'ONLINE') ?? []
  const usable = printers.filter(printer => printer.enabled && printer.status === 'ONLINE')
  const savedDefault = snapshot?.routing.defaultPrinterId
  const savedAgent = printers.find(printer => printer.id === savedDefault)?.agentId
  const agent = active.find(item => item.agentCode === localCode)
    ?? active.find(item => item.id === savedAgent)
    ?? active.find(item => usable.some(printer => printer.agentId === item.id))
    ?? active[0]
  const windows = agent?.platform === 'WINDOWS'
  const agentPrinters = printers.filter(printer => printer.agentId === agent?.id)
  const systemDefault = agentPrinters.find(printer => printer.agentDefault && printer.status === 'ONLINE')
  const detected = windows
    ? usable.find(printer => printer.id === systemDefault?.id && !printer.virtualPrinter)
    : usable.filter(printer => printer.agentId === agent?.id).sort(
      (a, b) => Number(b.agentDefault) - Number(a.agentDefault) || a.id - b.id,
    )[0]
  const windowsDefaultMessage = systemDefault?.virtualPrinter
    ? 'The Windows default is a virtual printer. Set a physical printer as the Windows default or choose a manual route.'
    : systemDefault && !systemDefault.enabled
      ? 'The Windows default is disabled in SmartPrint. Enable it or choose a manual route.'
      : 'No available Windows system-default printer was identified. Check the default in Windows Printers & scanners or choose a manual route.'

  function duplicateQueue(printer: typeof printers[number]) {
    return printer.deviceGroup && printers.some(other => other.id !== printer.id
      && other.agentId === printer.agentId && other.deviceGroup === printer.deviceGroup)
  }
  const automaticDefault = snapshot?.routing.defaultPrinterId ?? detected?.id ?? null
  const defaultPrinterId = draft.defaultPrinterId === undefined ? automaticDefault : draft.defaultPrinterId
  const configured = {
    defaultPrinterId,
    colorPrinterId: draft.colorPrinterId === undefined
      ? snapshot?.routing.colorPrinterId ?? defaultPrinterId : draft.colorPrinterId,
    bwPrinterId: draft.bwPrinterId === undefined
      ? snapshot?.routing.bwPrinterId ?? defaultPrinterId : draft.bwPrinterId,
  }
  const configuredDefault = printers.find(printer => printer.id === defaultPrinterId)
  const available = printers.filter(printer => printer.enabled && !printer.virtualPrinter && printer.availability === 'FREE')
  const busyCount = printers.filter(printer => printer.availability === 'BUSY').length
  const offlineCount = printers.filter(printer => printer.availability === 'OFFLINE').length
  const routingChanged = !!snapshot && routes.some(([key]) =>
    draft[key] !== undefined && draft[key] !== snapshot.routing[key],
  )
  const status = failure ? 'Disconnected' : !snapshot ? 'Detecting Printers' : !agent ? 'Disconnected'
    : agent.discoveryError ? 'Detecting Printers' : agentPrinters.length ? 'Connected' : 'No Printer Found'

  async function action(work: () => Promise<void>, message?: string) {
    setBusy(true)
    setActionError('')
    setFeedback('')
    try {
      await work()
      if (message) setFeedback(message)
      refreshRef.current()
    } catch (cause) {
      if (toApiError(cause).status === 401) onUnauthorized()
      else setActionError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return <div className="stack">
    {snapshot && <div className="metrics" aria-label="Printer summary">
      {([
        ['Total printers', printers.length], ['Available printers', available.length],
        ['Busy printers', busyCount], ['Offline printers', offlineCount],
        ['Total queued jobs', snapshot.queuedJobs],
      ] as const).map(([label, value]) => <div className="panel metric" key={label}>
        <span>{label}</span><strong>{failure && label !== 'Total printers' ? '—' : value}</strong>
      </div>)}
    </div>}
    <div className="workspace-grid">
    <section className="panel stack">
      <h2>Discovered printers</h2>
      {failure && <p role="alert" className="alert error">{failure}</p>}
      {actionError && <p role="alert" className="alert error">{actionError}</p>}
      {feedback && <p role="status" className="alert info">{feedback}</p>}
      {printers.length > 0 && !savedDefault && <p>Automatic routing uses the least-loaded free compatible printer.</p>}
      {windows && detected && savedDefault && savedDefault !== detected.id &&
        <p>A saved Default Printer override is active.</p>}
      {detected && <p>Detected printer <b>{detected.name}</b>{detected.agentDefault ? (windows ? ' · Windows system default' : ' · CUPS default') : ''}</p>}
      {printers.map(printer => <div className="record-card stack" key={printer.id}>
        <b>{printer.name}</b>
        {printer.virtualPrinter && <span>Virtual queue</span>}
        {duplicateQueue(printer) && <span>Shares a printer port with another queue</span>}
        <span>Model {printer.model || 'Unreported by the driver'}</span>
        {printer.portName && <span>Port {printer.portName}</span>}
        {printer.agentDefault && <span>{agent?.id === printer.agentId && windows ? 'Windows system default' : 'System default'}</span>}
        <span>{failure || printer.status === 'UNKNOWN' ? 'Connection unknown'
          : printer.status === 'ONLINE' ? 'Online' : 'Offline'} · {failure ? 'Unknown' :
          printer.availability.charAt(0) + printer.availability.slice(1).toLowerCase()}</span>
        <span>{printer.colorSupported ? 'Color' : 'Monochrome'} · {printer.duplexSupported ? 'Duplex' : 'Single-sided'}</span>
        <span>Pending jobs {failure || printer.availability === 'OFFLINE' ? '—' : printer.pendingJobs}</span>
        <span>Active jobs {failure || printer.availability === 'OFFLINE' ? '—' : Math.max(printer.activeJobs, printer.activeOrders.length)}</span>
        {printer.activeOrders.length > 0 && <span>SmartPrint orders {printer.activeOrders.join(', ')}</span>}
        <button className="btn" disabled={busy || Boolean(failure)} onClick={() => void action(async () => {
          await setPrinterEnabled(printer.id, !printer.enabled)
        })}>{printer.enabled ? 'Disable printer' : 'Enable printer'}</button>
      </div>)}
      {snapshot && !failure && printers.length === 0 && <div className="empty-state" role="status">
        <span className="empty-icon"><Printer size={20} /></span>
        <b>No printers discovered for this shop</b>
        <p>{agent?.discoveryError ?? (agent
          ? 'The agent is connected but reported no queues. Run it under the Windows user who sees the printers.'
          : 'The shop has no active print agent. Start the enrolled agent on the printer PC and check its API URL.')}</p>
        <button className="btn small" onClick={() => refreshRef.current()}>Check again</button>
      </div>}
      {snapshot && printers.length > 0 && <form className="stack" onSubmit={event => {
        event.preventDefault()
        if (!routingChanged || busy || failure) return
        void action(async () => {
          const routing = await savePrinterRouting({ ...snapshot.routing, ...draft })
          setSnapshot(current => current && { ...current, routing })
          setDraft({})
        }, 'Printer routing saved.')
      }}>
        {routes.map(([key, label]) => <label key={key} className="field">{label}
          <select disabled={busy} value={configured[key] ?? ''} onChange={event => {
            const id = Number(event.target.value) || null
            setFeedback('')
            setDraft(current => ({ ...current, [key]: id }))
          }}>
            <option value="">{key === 'defaultPrinterId' ? 'Automatic load balancing' : 'Use default routing'}</option>
            {printers.map(printer => {
              const incompatible = key === 'colorPrinterId' && !printer.colorSupported && printer.id !== defaultPrinterId
              return <option key={printer.id} value={printer.id}
                disabled={printer.id !== configured[key] && (!printer.enabled || incompatible)}>
                {printer.name}{printer.status === 'OFFLINE' ? ' (offline)' : printer.status === 'UNKNOWN' ? ' (connection unknown)' : ''}
                {!printer.enabled ? ' (disabled)' : incompatible ? ' (color support unavailable)' : ''}
              </option>
            })}
          </select>
        </label>)}
        {configuredDefault && !configuredDefault.colorSupported && configured.colorPrinterId === defaultPrinterId &&
          <p>The default printer is monochrome. Color jobs require a color-capable route.</p>}
        <button className="btn primary" disabled={busy || Boolean(failure) || !routingChanged}>Save Printer Routing</button>
      </form>}
    </section>
    <section className="panel stack">
      <h2>Print agent</h2>
      <p role="status"><b>{status}</b></p>
      {agent && <p><b>{agent.name}</b> · {agent.agentCode === localCode ? 'This device' : 'Active shop agent'}</p>}
      {agent?.discoveryError && <p role="alert" className="alert error">{agent.discoveryError}</p>}
      {snapshot && !agent && <p>{localCode
        ? 'The local agent has no active heartbeat for this shop. Check enrollment, the API URL, and the agent log.'
        : 'Start an enrolled Print Agent under the Windows user who sees the printers, then check its server connection.'}</p>}
      {snapshot && agent && <p>{agent.printerCount} queues reported by the agent.</p>}
      {status === 'No Printer Found' && !windows && <p>Add or enable a printer in CUPS on the selected agent’s computer.</p>}
      {windows && !detected && !agent?.discoveryError && <p>{windowsDefaultMessage} Automatic routing can use other compatible printers.</p>}
      {snapshot && agent && !available.length && <p>New jobs wait until a compatible printer becomes free.</p>}
      <details className="stack">
        <summary>Set up a new print station</summary>
        <p>Generate credentials once for each new installation. Reuse those credentials after restarting the agent.</p>
        <button className="btn" disabled={busy || Boolean(credentials)} onClick={() => void action(async () => {
          setCredentials(await createAgent('Print station'))
        })}>Create agent credentials</button>
        {credentials && <pre className="credentials">{`AGENT_CODE=${credentials.agentCode}\nAGENT_TOKEN=${credentials.agentToken}`}</pre>}
      </details>
    </section>
    </div>
  </div>
}
