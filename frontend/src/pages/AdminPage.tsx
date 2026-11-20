import PrinterSettings from './PrinterSettings'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import {
  approvePayment, cancelQueueEntry, downloadProtectedAsset, errorMessage,
  fetchAdminDashboard, fetchAdminOrders, fetchAdminShop, fetchPrintAttempts,
  fetchSalesReport, fetchSettings, getAccessToken, moveQueueEntry, publicQrUrl, regenerateQr,
  rejectPayment, retryQueueEntry, saveSettings, setAccessToken, setQueuePaused,
  toApiError, assetUrl, api,
  type AdminDashboard, type AdminOrder, type AdminShop,
  type SalesReport, type Settings, type PrintAttempt,
} from '../lib/api'
import { dateTime, daysAgoIso, money, prettyStatus, todayIso } from '../lib/format'

const sections = ['Overview', 'Requests', 'Queue', 'Settings', 'Printers', 'Reports', 'Shop QR'] as const
type Section = typeof sections[number]

const switches = [
  ['colorEnabled', 'Color'], ['duplexEnabled', 'Double-sided'], ['landscapeEnabled', 'Landscape'],
  ['pageSelectionEnabled', 'Page selection'], ['pdfEnabled', 'PDF'], ['wordEnabled', 'DOC and DOCX'],
  ['imageEnabled', 'JPG and PNG'], ['cashEnabled', 'Cash at counter'], ['bkashEnabled', 'bKash'],
  ['nagadEnabled', 'Nagad'], ['rocketEnabled', 'Rocket'],
] as const
export default function AdminPage() {
  return getAccessToken() ? <AdminWorkspace /> : <Navigate to="/admin/login" replace />
}

function AdminWorkspace() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const section = sections.find(item => item === params.get('view')) ?? 'Overview'
  const [dashboard, setDashboard] = useState<AdminDashboard>()
  const [orders, setOrders] = useState<AdminOrder[]>([])
  const [shop, setShop] = useState<AdminShop>()
  const [settings, setSettings] = useState<Settings>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const [openOrder, setOpenOrder] = useState<number>()
  const [reason, setReason] = useState('')
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState('')
  const [from, setFrom] = useState(daysAgoIso(6))
  const [to, setTo] = useState(todayIso)
  const [report, setReport] = useState<SalesReport>()

  function signOut() {
    setAccessToken(null)
    navigate('/admin/login', { replace: true })
  }

  useEffect(() => {
    let active = true
    let loading = false
    function failed(cause: unknown) {
      if (!active) return
      if (toApiError(cause).status === 401) {
        setAccessToken(null)
        navigate('/admin/login', { replace: true })
        return
      }
      setError(errorMessage(cause))
    }
    async function refresh() {
      if (loading) return
      loading = true
      try {
        if (section === 'Overview' || section === 'Queue') {
          const snapshot = await fetchAdminDashboard()
          if (active) setDashboard(snapshot)
        }
        if (section === 'Requests') {
          const snapshot = await fetchAdminOrders(filter, page)
          if (active) setOrders(snapshot)
        }
      } catch (cause) { failed(cause) }
      finally { loading = false }
    }
    void refresh()
    if (section === 'Shop QR') fetchAdminShop().then(value => { if (active) setShop(value) }).catch(failed)
    if (section === 'Settings') {
      fetchSettings().then(value => { if (active) setSettings(value) }).catch(failed)
    }
    const timer = window.setInterval(refresh, 5000)
    return () => { active = false; window.clearInterval(timer) }
  }, [section, revision, filter, page, navigate])

  async function action(work: () => Promise<unknown>, message?: string, refresh = true) {
    setBusy(true)
    setError('')
    setFeedback('')
    try {
      await work()
      if (refresh) setRevision(value => value + 1)
      if (message) setFeedback(message)
    } catch (cause) {
      if (toApiError(cause).status === 401) signOut()
      else setError(errorMessage(cause))
    } finally { setBusy(false) }
  }

  function change<K extends keyof Settings>(key: K, value: Settings[K]) {
    setSettings(current => current && ({ ...current, [key]: value }))
  }

  function submitSettings(event: FormEvent) {
    event.preventDefault()
    if (settings) void action(async () => setSettings(await saveSettings(settings)), 'Settings saved.')
  }

  return <div className="shell">
    <header className="topbar"><Link to="/" className="brand"><span className="brand-mark">S</span>SmartPrint</Link>
      <button className="btn" onClick={signOut}>Sign out</button></header>
    <main className="content stack admin-workspace">
      <header className="hero"><h1>{dashboard?.shopName ?? 'Shop administration'}</h1><p>Manage print requests and shop settings.</p></header>
      <nav className="section-tabs" aria-label="Admin sections">{sections.map(item => <button
        className={`btn ${item === section ? 'primary' : ''}`} aria-current={item === section ? 'page' : undefined}
        key={item} onClick={() => {
          setError(''); setFeedback(''); setOpenOrder(undefined); setReason(''); setSettings(undefined)
          setParams({ view: item })
        }} disabled={busy || item === section}>{item}</button>)}</nav>
      {error && <p role="alert" className="alert error">{error}</p>}
      {feedback && <p role="status" className="alert info">{feedback}</p>}
      {section === 'Overview' && (dashboard ? <>
        <div className="metrics">
          <Metric label="Today's orders" value={dashboard.todaysOrders} />
          <Metric label="Pending approvals" value={dashboard.awaitingVerification} />
          <Metric label="Waiting in queue" value={dashboard.waiting} />
          <Metric label="Today's confirmed payments" value={money(dashboard.todaysRevenue, dashboard.currency)} />
        </div>
        <section className="panel stack"><h2>Recent orders</h2>
          {!dashboard.recentOrders.length && <p>No orders yet.</p>}
          {dashboard.recentOrders.map(order => <div className="record" key={order.orderNumber}>
            <b>{order.orderNumber}</b><span>{prettyStatus(order.status)}</span>
            <span>{money(order.totalAmount, order.currency)}</span><span>{dateTime(order.createdAt)}</span>
          </div>)}
        </section>
      </> : <p>Loading dashboard…</p>)}
      {section === 'Requests' && <section className="panel stack">
        <h2>Requests and order history</h2>
        <label className="field">Status<select value={filter} onChange={event => {
          setFilter(event.target.value); setPage(0); setOpenOrder(undefined)
        }}>
          <option value="">All statuses</option>
          {['PAYMENT_PENDING', 'QUEUED', 'PRINTING', 'COMPLETED', 'PAYMENT_REJECTED', 'FAILED', 'CANCELLED']
            .map(value => <option key={value} value={value}>{prettyStatus(value)}</option>)}
        </select></label>
        {!orders.length && <p>No matching requests.</p>}
        {orders.map(order => <article className="record-card stack" key={order.id}>
          <button className="record record-button" type="button" aria-expanded={openOrder === order.id}
            onClick={() => { setOpenOrder(openOrder === order.id ? undefined : order.id); setReason('') }}>
            <b>{order.orderNumber}</b><span>{prettyStatus(order.status)}</span>
            <span>{money(order.totalAmount, order.currency)}</span><span>{dateTime(order.createdAt)}</span>
          </button>
          {openOrder === order.id && <>
            <p>{prettyStatus(order.payment.method)} · {prettyStatus(order.payment.status)}
              {order.payment.transactionId && <> · Transaction ID <b>{order.payment.transactionId}</b></>}</p>
            {order.payment.rejectionReason && <p>{order.payment.rejectionReason}</p>}
            {order.printFailure && <p className="alert error">{order.printFailure}</p>}
            {order.queue && <AttemptHistory key={order.orderNumber} orderNumber={order.orderNumber} />}
            <ul className="file-list">{order.files.map(file => <li className="file" key={file.id}>
              <div className="file-info"><b>{file.name}</b><small>
                {file.colorMode === 'BW' ? 'Black and white' : 'Color'} · {file.sideMode === 'DUPLEX' ? 'Double-sided' : 'Single-sided'}
                {' · '}{prettyStatus(file.orientation)} · {file.selectedPages} / {file.pages} pages ({file.pageSelection}) × {file.copies}
              </small></div>
              {file.previewUrl && <PreviewButton path={file.previewUrl} />}
            </li>)}</ul>
            {order.status === 'PAYMENT_PENDING' && <>
              <label className="field">Rejection reason<input maxLength={500} value={reason}
                onChange={event => setReason(event.target.value)} /></label>
              <div className="inline">
                <button className="btn primary" disabled={busy || (order.payment.method !== 'CASH' && !order.payment.transactionId)}
                  onClick={() => void action(() => approvePayment(order.payment.id), 'Payment confirmed.')}>Payment Confirmed</button>
                <button className="btn danger" disabled={busy || !reason.trim()}
                  onClick={() => void action(() => rejectPayment(order.payment.id, reason.trim()), 'Payment rejected.')}>Reject</button>
              </div>
            </>}
            {order.status === 'FAILED' && order.queue && order.files.every(file => file.previewUrl) &&
              <button className="btn" disabled={busy} onClick={() => {
                if (window.confirm('Verify the printer output before retrying. Retry remaining files?'))
                  void action(() => retryQueueEntry(order.queue!.entryId), 'Order returned to the queue.')
              }}>Retry printing</button>}
          </>}
        </article>)}
        <div className="inline">
          <button className="btn" disabled={!page} onClick={() => { setPage(page - 1); setOpenOrder(undefined) }}>Previous</button>
          <span>Page {page + 1}</span>
          <button className="btn" disabled={orders.length < 25} onClick={() => {
            setPage(page + 1); setOpenOrder(undefined)
          }}>Next</button>
        </div>
      </section>}
      {section === 'Queue' && <section className="panel stack">
        <div className="record"><h2>Print queue</h2>{dashboard && <button className="btn"
          disabled={busy} onClick={() => void action(async () => {
            await setQueuePaused(!dashboard.queuePaused)
            setDashboard(await fetchAdminDashboard())
          })}>{dashboard.queuePaused ? 'Resume queue' : 'Pause queue'}</button>}</div>
        {dashboard?.queuePaused && <p role="status">Queue paused. Jobs already printing will finish.</p>}
        {dashboard && !dashboard.activeQueue.length && <p>The queue is empty.</p>}
        {dashboard?.activeQueue.map((item, index, items) => <article className="record-card stack" key={item.entryId}>
          <div className="record"><b>#{item.queueNumber} · {item.orderNumber}</b>
            <span>{item.state === 'QUEUED' && !dashboard.queuePaused
              ? 'Waiting for a compatible printer' : prettyStatus(item.state)}</span><span>{item.fileCount} files</span></div>
          {item.state === 'QUEUED' && <div className="inline">
            <button className="btn" disabled={busy || !index || items[index - 1].state !== 'QUEUED'}
              onClick={() => void action(() => moveQueueEntry(item.entryId, -1))}>Move up</button>
            <button className="btn" disabled={busy || items[index + 1]?.state !== 'QUEUED'}
              onClick={() => void action(() => moveQueueEntry(item.entryId, 1))}>Move down</button>
            <button className="btn danger" disabled={busy} onClick={() => {
              if (window.confirm(`Cancel ${item.orderNumber}?`)) void action(() => cancelQueueEntry(item.entryId), 'Job cancelled.')
            }}>Cancel job</button>
          </div>}
        </article>)}
      </section>}
      {section === 'Settings' && settings && <form className="panel stack" onSubmit={submitSettings}>
        <h2>Pricing and availability</h2>
        <div className="option-grid">
          {([
            ['bwPerPage', 'Black and white per page', 0.01, 100000, 0.01],
            ['colorPerPage', 'Color per page', 0.01, 100000, 0.01],
            ['duplexPercent', 'Double-sided rate (% of base price)', 1, 300, 1],
            ['fileRetentionHours', 'File retention (hours)', 1, 720, 1],
          ] as const).map(([key, label, min, max, step]) => <label key={key} className="field">{label}
            <input type="number" min={min} max={max} step={step} required value={Number.isNaN(settings[key]) ? '' : settings[key]}
              onChange={event => change(key, event.target.valueAsNumber)} /></label>)}
        </div>
        <div className="option-grid">{switches.map(([key, label]) => <label key={key} className="checkbox-label">
          <input type="checkbox" checked={settings[key]} onChange={event => change(key, event.target.checked)} />{label}
        </label>)}</div>
        <div className="option-grid">{([['bkashNumber', 'bKash'], ['nagadNumber', 'Nagad'], ['rocketNumber', 'Rocket']] as const)
          .map(([key, label]) => <label key={key} className="field">{label} wallet
            <input type="tel" maxLength={30} value={settings[key] ?? ''} onChange={event => change(key, event.target.value)} />
          </label>)}</div>
        <button className="btn primary" disabled={busy}>Save settings</button>
      </form>}
      {section === 'Printers' && <PrinterSettings onUnauthorized={signOut} />}
      {section === 'Reports' && <section className="panel stack"><h2>Sales report</h2>
        <form className="stack" onSubmit={event => {
          event.preventDefault(); void action(async () => setReport(await fetchSalesReport(from, to)), undefined, false)
        }}><div className="option-grid">
          <label className="field">From<input type="date" value={from} max={to} required onChange={event => setFrom(event.target.value)} /></label>
          <label className="field">Through<input type="date" value={to} min={from} required onChange={event => setTo(event.target.value)} /></label>
        </div><button className="btn primary" disabled={busy}>Run report</button></form>
        {report && <><p>{report.from} through {report.to}</p><div className="metrics">
          <Metric label="Orders" value={report.orders} /><Metric label="Completed" value={report.completed} />
          <Metric label="Cancelled" value={report.cancelled} /><Metric label="Confirmed payments" value={money(report.revenue, report.currency)} />
        </div></>}
      </section>}
      {section === 'Shop QR' && shop && <section className="panel stack">
        <h2>Customer QR code</h2>
        <img className="shop-qr" src={publicQrUrl(shop.qrToken)} alt={`Scan to print at ${shop.name}`} />
        <a className="shop-link" href={shop.publicUrl} target="_blank" rel="noreferrer">{shop.publicUrl}</a>
        <div className="inline">
          <button className="btn primary" disabled={busy} onClick={() => void action(
            () => downloadProtectedAsset(shop.qrImageUrl, `${shop.shopCode}-qr.png`), undefined, false,
          )}>Download PNG</button>
          <button className="btn" disabled={busy} onClick={() => {
            if (window.confirm('Replace this QR code? Existing printed codes will stop opening the shop.'))
              void action(async () => setShop(await regenerateQr()), 'QR code replaced.')
          }}>Regenerate QR</button>
        </div>
      </section>}
    </main>
  </div>
}

function Metric({ label, value }: { label: string; value: number | string }) {
  return <div className="panel metric"><span>{label}</span><strong>{value}</strong></div>
}

function PreviewButton({ path }: { path: string }) {
  const [error, setError] = useState('')
  const [url, setUrl] = useState('')
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  async function preview() {
    try {
      const { data } = await api.get<Blob>(assetUrl(path) ?? path, { baseURL: '/', responseType: 'blob' })
      setUrl(URL.createObjectURL(data))
    } catch (cause) { setError(errorMessage(cause)) }
  }
  return <>{url ? <a className="btn small" href={url} target="_blank" rel="noreferrer">Open PDF</a>
    : <button className="btn small" onClick={() => void preview()}>Load preview</button>}
    {error && <span role="alert">{error}</span>}</>
}


function AttemptHistory({ orderNumber }: { orderNumber: string }) {
  const [attempts, setAttempts] = useState<PrintAttempt[]>()
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function load() {
    setLoading(true)
    setError('')
    try {
      setAttempts(await fetchPrintAttempts(orderNumber))
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setLoading(false)
    }
  }

  return <section className="stack">
    <button className="btn small" disabled={loading} onClick={() => void load()}>
      {attempts ? 'Refresh print history' : 'View print history'}
    </button>
    {error && <p role="alert">{error}</p>}
    {attempts?.length === 0 && <p>No print attempts yet.</p>}
    {attempts?.map(attempt => <div className="record-card stack" key={`${attempt.fileId}-${attempt.attemptNumber}`}>
      <b>{attempt.fileName} · Attempt {attempt.attemptNumber}</b>
      <span>{attempt.printerName ?? 'Unknown printer'}</span>
      <span>{prettyStatus(attempt.status)} · {dateTime(attempt.startedAt)}</span>
      {attempt.finishedAt && <span>Finished {dateTime(attempt.finishedAt)}</span>}
      {attempt.failureReason && <span>{attempt.failureReason}</span>}
    </div>)}
  </section>
}
