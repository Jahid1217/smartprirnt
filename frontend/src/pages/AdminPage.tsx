import PrinterSettings from './PrinterSettings'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import {
  AlertCircle, BarChart3, CheckCircle2, ChevronDown, ClipboardList, Copy, Image as ImageIcon,
  LayoutDashboard, ListOrdered, Loader2, LogOut, Printer, QrCode, RefreshCw, ReceiptText,
  ShoppingBag, SlidersHorizontal, Wallet, X,
} from 'lucide-react'
import {
  approvePayment, cancelQueueEntry, downloadProtectedAsset, errorMessage,
  fetchAdminDashboard, fetchAdminOrders, fetchAdminShop, fetchPrintAttempts,
  fetchMe, fetchSalesReport, fetchSettings, getAccessToken, moveQueueEntry, publicQrUrl, regenerateQr,
  rejectPayment, retryQueueEntry, saveSettings, setAccessToken, setQueuePaused,
  toApiError, type AdminDashboard, type AdminOrder, type AdminShop, type SalesReport,
  type Settings, type PrintAttempt,
} from '../lib/api'
import { dateTime, daysAgoIso, money, prettyStatus, statusTone, todayIso } from '../lib/format'
import ConfirmDialog from '../components/ConfirmDialog'
import { DocumentPreviewDialog, default as DocumentPreview } from '../components/DocumentPreview'

const sections = [
  { id: 'Overview', label: 'Overview', icon: LayoutDashboard },
  { id: 'Requests', label: 'Requests', icon: ClipboardList },
  { id: 'Queue', label: 'Queue', icon: ListOrdered },
  { id: 'Settings', label: 'Settings', icon: SlidersHorizontal },
  { id: 'Printers', label: 'Printers', icon: Printer },
  { id: 'Reports', label: 'Reports', icon: BarChart3 },
  { id: 'Shop QR', label: 'Shop QR', icon: QrCode },
] as const
type Section = typeof sections[number]['id']

const headings: Record<Section, { title: string; subtitle: string }> = {
  Overview: { title: 'Overview', subtitle: 'Today’s orders, payments and queue at a glance.' },
  Requests: { title: 'Requests', subtitle: 'Review payments, inspect documents and manage print requests.' },
  Queue: { title: 'Print queue', subtitle: 'Active assignments and waiting jobs in payment order.' },
  Settings: { title: 'Shop settings', subtitle: 'Pricing, file types, print options and payment methods.' },
  Printers: { title: 'Printers', subtitle: 'Connected agents, discovered printers and routing.' },
  Reports: { title: 'Reports', subtitle: 'Sales and order activity for the selected dates.' },
  'Shop QR': { title: 'Shop QR', subtitle: 'The code customers scan to reach your shop page.' },
}

const statusFilters = [
  'PAYMENT_PENDING', 'QUEUED', 'PRINTING', 'COMPLETED',
  'PAYMENT_REJECTED', 'FAILED', 'CANCELLED',
]

const fileSwitches = [
  ['pdfEnabled', 'PDF documents', 'Allow .pdf uploads.'],
  ['wordEnabled', 'Word documents', 'Allow .doc and .docx uploads, converted to PDF.'],
  ['imageEnabled', 'Images', 'Allow .jpg and .png uploads, one page each.'],
] as const

const printSwitches = [
  ['colorEnabled', 'Color printing', 'Let customers choose color.'],
  ['duplexEnabled', 'Double-sided', 'Offer double-sided printing.'],
  ['landscapeEnabled', 'Landscape', 'Offer landscape orientation.'],
  ['pageSelectionEnabled', 'Page selection', 'Let customers pick specific pages.'],
] as const

const paymentSwitches = [
  ['cashEnabled', 'Cash at counter', 'Customers pay staff before printing.'],
  ['bkashEnabled', 'bKash', 'Accept bKash transaction references.'],
  ['nagadEnabled', 'Nagad', 'Accept Nagad transaction references.'],
  ['rocketEnabled', 'Rocket', 'Accept Rocket transaction references.'],
] as const

export default function AdminPage() {
  return getAccessToken() ? <AdminWorkspace /> : <Navigate to="/admin/login" replace />
}

function AdminWorkspace() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const section = sections.find(item => item.id === params.get('view'))?.id ?? 'Overview'
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
  const [accountShopName, setAccountShopName] = useState('')
  const [preview, setPreview] = useState<{ file: AdminOrder['files'][number]; orderNumber: string }>()
  const [notice, setNotice] = useState<{
    title: string; description: string; confirmLabel: string
    tone?: 'danger' | 'primary'; run: () => void
  }>()

  function signOut() {
    setAccessToken(null)
    navigate('/admin/login', { replace: true })
  }

  function changeSection(next: Section) {
    setError(''); setFeedback(''); setOpenOrder(undefined); setReason(''); setSettings(undefined)
    setParams({ view: next })
  }

  useEffect(() => {
    fetchMe().then(me => setAccountShopName(me.shopName)).catch(() => { /* handled by section requests */ })
  }, [])

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

  function copyShopLink() {
    if (!shop) return
    navigator.clipboard?.writeText(shop.publicUrl)
      .then(() => setFeedback('Shop link copied to the clipboard.'))
      .catch(() => setError('The link could not be copied. Select and copy it manually.'))
  }

  const heading = headings[section]
  const pending = dashboard?.pendingPayments ?? []
  const queuedJobs = dashboard?.activeQueue.filter(item => item.state === 'QUEUED').length ?? 0

  return <div className="admin-shell">
    <header className="admin-top">
      <div className="admin-top-inner">
        <Link to="/" className="brand brand-inverse">
          <span className="brand-mark">S</span>
          <span className="brand-text"><b>SmartPrint</b><small>Shop workspace</small></span>
        </Link>
        <span className="admin-shop-name">{dashboard?.shopName ?? shop?.name ?? accountShopName}</span>
        <div className="admin-top-actions">
          <span className="live-chip"><i className="live-dot" aria-hidden="true" />Live</span>
          <button type="button" className="btn ghost-light small" onClick={signOut}>
            <LogOut size={15} />Sign out
          </button>
        </div>
      </div>
    </header>

    <div className="admin-layout">
      <nav className="admin-nav" aria-label="Admin sections">
        {sections.map(({ id, label, icon: Icon }) => <button key={id} type="button"
          className={`admin-nav-item ${id === section ? 'active' : ''}`}
          aria-current={id === section ? 'page' : undefined}
          onClick={() => changeSection(id)}>
          <Icon size={17} aria-hidden="true" />
          <span>{label}</span>
          {id === 'Requests' && dashboard?.awaitingVerification
            ? <span className="nav-count">{dashboard.awaitingVerification}</span> : null}
        </button>)}
      </nav>

      <main className="admin-content">
        <header className="admin-heading">
          <div>
            <h1>{heading.title}</h1>
            <p>{heading.subtitle}</p>
          </div>
          {(section === 'Overview' || section === 'Queue') && <button type="button" className="btn small"
            onClick={() => setRevision(value => value + 1)} disabled={busy}>
            <RefreshCw size={14} className={busy ? 'spin' : undefined} />Refresh
          </button>}
        </header>

        {error && <div className="alert error" role="alert">
          <AlertCircle size={16} />
          <div className="alert-body">
            <span>{error}</span>
            <button type="button" className="alert-dismiss" onClick={() => setError('')} aria-label="Dismiss error">
              <X size={14} />
            </button>
          </div>
        </div>}
        {feedback && <div className="alert success" role="status">
          <CheckCircle2 size={16} />
          <div className="alert-body">
            <span>{feedback}</span>
            <button type="button" className="alert-dismiss" onClick={() => setFeedback('')} aria-label="Dismiss message">
              <X size={14} />
            </button>
          </div>
        </div>}

        {section === 'Overview' && (dashboard ? <>
          <div className="stats">
            <Stat icon={ShoppingBag} label="Today's orders" value={dashboard.todaysOrders}
              hint={`${dashboard.queueToday} in the queue today`} />
            <Stat icon={ReceiptText} label="Pending approvals" value={dashboard.awaitingVerification}
              hint={dashboard.awaitingVerification ? 'Payments waiting for review' : 'Nothing waiting'} tone="amber" />
            <Stat icon={ListOrdered} label="Waiting in queue" value={dashboard.waiting}
              hint={`${dashboard.printingNow} printing now`} />
            <Stat icon={Wallet} label="Confirmed today" value={money(dashboard.todaysRevenue, dashboard.currency)}
              hint="Approved payments" tone="accent" />
          </div>

          <div className="overview-grid">
            <section className="panel">
              <div className="panel-head">
                <div>
                  <h2 className="panel-title">Recent orders</h2>
                  <p className="panel-sub">The latest requests from today’s customers.</p>
                </div>
                <button type="button" className="btn small" onClick={() => changeSection('Requests')}>View all</button>
              </div>
              {!dashboard.recentOrders.length
                ? <EmptyState icon={ShoppingBag} title="No orders yet"
                  text="Orders appear here as soon as customers submit their files." />
                : <div className="data-list">
                  {dashboard.recentOrders.map(order => <button type="button" className="data-row"
                    key={order.orderNumber} onClick={() => changeSection('Requests')}>
                    <span className="data-row-main">
                      <b>{order.orderNumber}</b>
                      <small>{dateTime(order.createdAt)}</small>
                    </span>
                    <span className={`badge ${statusTone(order.status)}`}>{prettyStatus(order.status)}</span>
                    <span className="data-row-amount">{money(order.totalAmount, order.currency)}</span>
                  </button>)}
                </div>}
            </section>

            <section className="panel">
              <div className="panel-head">
                <div>
                  <h2 className="panel-title">Pending payments</h2>
                  <p className="panel-sub">Confirm or reject before printing.</p>
                </div>
                <span className={`badge ${pending.length ? 'waiting' : 'completed'}`}>{pending.length}</span>
              </div>
              {!pending.length
                ? <EmptyState icon={CheckCircle2} title="All caught up"
                  text="No payments are waiting for review." />
                : <div className="data-list">
                  {pending.map(payment => <button type="button" className="data-row" key={payment.id}
                    onClick={() => changeSection('Requests')}>
                    <span className="data-row-main">
                      <b>{payment.orderNumber}</b>
                      <small>{prettyStatus(payment.method)} · {dateTime(payment.createdAt)}</small>
                    </span>
                    <span className="data-row-amount">{money(payment.amount, payment.currency)}</span>
                    <ChevronDown size={15} className="data-row-chevron" />
                  </button>)}
                </div>}
            </section>
          </div>
        </> : <LoadingPanels count={2} />)}

        {section === 'Requests' && <section className="panel">
          <div className="panel-head">
            <div>
              <h2 className="panel-title">Requests and order history</h2>
              <p className="panel-sub">Open a request to inspect its documents and payment.</p>
            </div>
            <span className="pill">{orders.length} shown</span>
          </div>

          <div className="toolbar">
            <div className="filter-tabs" role="group" aria-label="Filter by status">
              <button type="button" className={`chip ${!filter ? 'active' : ''}`} onClick={() => {
                setFilter(''); setPage(0); setOpenOrder(undefined)
              }}>All</button>
              {statusFilters.map(value => <button type="button" key={value}
                className={`chip ${filter === value ? 'active' : ''}`} onClick={() => {
                  setFilter(value); setPage(0); setOpenOrder(undefined)
                }}>{prettyStatus(value)}</button>)}
            </div>
          </div>

          {!orders.length
            ? <EmptyState icon={ClipboardList} title="No matching requests"
              text={filter ? 'No orders have this status. Try another filter.' : 'New requests will appear here.'} />
            : <div className="order-list">
              {orders.map(order => <article className={`order-card ${openOrder === order.id ? 'open' : ''}`} key={order.id}>
                <button type="button" className="order-summary" aria-expanded={openOrder === order.id}
                  onClick={() => { setOpenOrder(openOrder === order.id ? undefined : order.id); setReason('') }}>
                  <span className="order-number">{order.orderNumber}</span>
                  <span className={`badge ${statusTone(order.status)}`}>{prettyStatus(order.status)}</span>
                  <span className="order-customer">{order.customerName || 'Walk-in customer'}</span>
                  <span className="order-time">{dateTime(order.createdAt)}</span>
                  <span className="order-amount">{money(order.totalAmount, order.currency)}</span>
                  <ChevronDown size={16} className="order-chevron" aria-hidden="true" />
                </button>

                {openOrder === order.id && <div className="order-detail">
                  <div className="detail-grid">
                    <div className="detail-item"><span>Payment</span>
                      <b>{prettyStatus(order.payment.method)} · {prettyStatus(order.payment.status)}</b></div>
                    <div className="detail-item"><span>Transaction ID</span>
                      <b className="mono">{order.payment.transactionId || '—'}</b></div>
                    <div className="detail-item"><span>Queue</span>
                      <b>{order.queue ? `#${order.queue.queueNumber} · ${prettyStatus(order.queue.state)}` : '—'}</b></div>
                    <div className="detail-item"><span>Submitted</span><b>{dateTime(order.createdAt)}</b></div>
                    {order.customerPhone && <div className="detail-item"><span>Phone</span>
                      <b>{order.customerPhone}</b></div>}
                  </div>

                  {order.payment.rejectionReason && <p className="alert error compact">
                    <AlertCircle size={14} />Rejected: {order.payment.rejectionReason}</p>}
                  {order.printFailure && <p className="alert error compact">
                    <AlertCircle size={14} />Print failure: {order.printFailure}</p>}

                  <h3 className="subheading">Documents</h3>
                  <ul className="file-list">
                    {order.files.map(file => <li className="file" key={file.id}>
                      <span className="file-icon" aria-hidden="true">
                        {file.mimeType?.startsWith('image/') ? <ImageIcon size={15} /> : file.kind}
                      </span>
                      <div className="file-info">
                        <b title={file.name}>{file.name}</b>
                        <small>
                          {file.colorMode === 'BW' ? 'Black and white' : 'Color'}
                          {' · '}{file.sideMode === 'DUPLEX' ? 'Double-sided' : 'Single-sided'}
                          {' · '}{prettyStatus(file.orientation)}
                          {' · '}{file.selectedPages} of {file.pages} pages ({file.pageSelection}) × {file.copies}
                        </small>
                      </div>
                      {file.previewUrl && <button type="button" className="btn tiny"
                        onClick={() => setPreview({ file, orderNumber: order.orderNumber })}>
                        Preview
                      </button>}
                    </li>)}
                  </ul>

                  {order.queue && <AttemptHistory key={order.orderNumber} orderNumber={order.orderNumber} />}

                  {order.status === 'PAYMENT_PENDING' && <div className="order-actions">
                    <label className="field">
                      <span className="field-label">Rejection reason</span>
                      <input maxLength={500} value={reason} placeholder="Required to reject this payment"
                        onChange={event => setReason(event.target.value)} />
                    </label>
                    <div className="inline">
                      <button type="button" className="btn primary" disabled={busy ||
                        (order.payment.method !== 'CASH' && !order.payment.transactionId)}
                        onClick={() => void action(() => approvePayment(order.payment.id), 'Payment confirmed.')}>
                        <CheckCircle2 size={15} />Payment confirmed
                      </button>
                      <button type="button" className="btn danger" disabled={busy || !reason.trim()}
                        onClick={() => void action(() => rejectPayment(order.payment.id, reason.trim()), 'Payment rejected.')}>
                        <X size={15} />Reject payment
                      </button>
                    </div>
                  </div>}

                  {order.status === 'FAILED' && order.queue && order.files.every(file => file.previewUrl) &&
                    <div className="order-actions">
                      <button type="button" className="btn" disabled={busy} onClick={() => setNotice({
                        title: 'Retry printing?',
                        description: 'Verify the printer output before retrying. Files already marked complete are skipped.',
                        confirmLabel: 'Retry remaining files',
                        tone: 'primary',
                        run: () => void action(() => retryQueueEntry(order.queue!.entryId), 'Order returned to the queue.'),
                      })}><RefreshCw size={15} />Retry printing</button>
                    </div>}
                </div>}
              </article>)}
            </div>}

          <div className="pagination">
            <button type="button" className="btn small" disabled={!page}
              onClick={() => { setPage(page - 1); setOpenOrder(undefined) }}>Previous</button>
            <span className="pagination-label">Page {page + 1}</span>
            <button type="button" className="btn small" disabled={orders.length < 25}
              onClick={() => { setPage(page + 1); setOpenOrder(undefined) }}>Next</button>
          </div>
        </section>}

        {section === 'Queue' && <section className="panel">
          <div className="panel-head">
            <div>
              <h2 className="panel-title">Active and waiting jobs</h2>
              <p className="panel-sub">
                {dashboard?.activeQueue.length
                  ? `${dashboard.activeQueue.length} ${dashboard.activeQueue.length === 1 ? 'job' : 'jobs'} in the queue · ${queuedJobs} waiting for a printer`
                  : 'Jobs appear here after payment is confirmed.'}
              </p>
            </div>
            {dashboard && <button type="button" className={`btn small ${dashboard.queuePaused ? 'primary' : ''}`}
              disabled={busy} onClick={() => void action(async () => {
                await setQueuePaused(!dashboard.queuePaused)
                setDashboard(await fetchAdminDashboard())
              })}>{dashboard.queuePaused ? 'Resume queue' : 'Pause queue'}</button>}
          </div>

          {dashboard?.queuePaused && <p className="alert info compact" role="status">
            <AlertCircle size={14} />Queue paused. Jobs already printing will finish.</p>}

          {!dashboard ? <LoadingPanels count={1} /> : !dashboard.activeQueue.length
            ? <EmptyState icon={ListOrdered} title="The queue is empty"
              text="Approved orders join the queue automatically." />
            : <div className="queue-list">
              {dashboard.activeQueue.map((item, index, items) => <article className="queue-card" key={item.entryId}>
                <span className={`queue-serial ${item.state === 'QUEUED' ? '' : 'active'}`}>#{item.queueNumber}</span>
                <div className="queue-info">
                  <b>{item.orderNumber}</b>
                  <small>{item.fileCount} {item.fileCount === 1 ? 'file' : 'files'} · {money(item.totalAmount, item.currency)}</small>
                </div>
                <span className={`badge ${statusTone(item.state)}`}>
                  {item.state === 'QUEUED' && !dashboard.queuePaused
                    ? 'Waiting for printer' : prettyStatus(item.state)}
                </span>
                {item.state === 'QUEUED' && <div className="queue-actions">
                  <button type="button" className="btn tiny" disabled={busy || !index || items[index - 1].state !== 'QUEUED'}
                    onClick={() => void action(() => moveQueueEntry(item.entryId, -1))}>Move up</button>
                  <button type="button" className="btn tiny" disabled={busy || items[index + 1]?.state !== 'QUEUED'}
                    onClick={() => void action(() => moveQueueEntry(item.entryId, 1))}>Move down</button>
                  <button type="button" className="btn tiny danger" disabled={busy} onClick={() => setNotice({
                    title: `Cancel ${item.orderNumber}?`,
                    description: 'This removes the job from the print queue. The customer will see it as cancelled.',
                    confirmLabel: 'Cancel job',
                    run: () => void action(() => cancelQueueEntry(item.entryId), 'Job cancelled.'),
                  })}>Cancel job</button>
                </div>}
              </article>)}
            </div>}
        </section>}

        {section === 'Settings' && (settings
          ? <form className="panel settings-form" onSubmit={submitSettings}>
            <div className="settings-section">
              <h2 className="panel-title">Pricing and retention</h2>
              <p className="panel-sub">Prices are per printed page. Double-sided is a percentage of the base price.</p>
              <div className="option-grid">
                {([
                  ['bwPerPage', 'Black and white per page', 0.01, 100000, 0.01],
                  ['colorPerPage', 'Color per page', 0.01, 100000, 0.01],
                  ['duplexPercent', 'Double-sided rate (% of base)', 1, 300, 1],
                  ['fileRetentionHours', 'File retention (hours)', 1, 720, 1],
                ] as const).map(([key, label, min, max, step]) => <label key={key} className="field">
                  <span className="field-label">{label}</span>
                  <input type="number" min={min} max={max} step={step} required
                    value={Number.isNaN(settings[key]) ? '' : settings[key]}
                    onChange={event => change(key, event.target.valueAsNumber)} />
                </label>)}
              </div>
            </div>

            <div className="settings-section">
              <h2 className="panel-title">Accepted files</h2>
              <div className="toggle-list">
                {fileSwitches.map(([key, label, description]) => <ToggleRow key={key} label={label}
                  description={description} checked={settings[key]}
                  onChange={value => change(key, value)} />)}
              </div>
            </div>

            <div className="settings-section">
              <h2 className="panel-title">Print options</h2>
              <div className="toggle-list">
                {printSwitches.map(([key, label, description]) => <ToggleRow key={key} label={label}
                  description={description} checked={settings[key]}
                  onChange={value => change(key, value)} />)}
              </div>
            </div>

            <div className="settings-section">
              <h2 className="panel-title">Payment methods</h2>
              <div className="toggle-list">
                {paymentSwitches.map(([key, label, description]) => <ToggleRow key={key} label={label}
                  description={description} checked={settings[key]}
                  onChange={value => change(key, value)} />)}
              </div>
              <div className="option-grid wallet-grid">
                {([['bkashNumber', 'bKash'], ['nagadNumber', 'Nagad'], ['rocketNumber', 'Rocket']] as const)
                  .map(([key, label]) => <label key={key} className="field">
                    <span className="field-label">{label} wallet number</span>
                    <input type="tel" maxLength={30} placeholder="01XXXXXXXXX" value={settings[key] ?? ''}
                      onChange={event => change(key, event.target.value)} />
                  </label>)}
              </div>
            </div>

            <div className="form-actions">
              <button className="btn primary" disabled={busy}>
                {busy ? <><Loader2 size={15} className="spin" />Saving…</> : 'Save settings'}
              </button>
              <span className="muted">Changes apply to new quotes and orders.</span>
            </div>
          </form>
          : <LoadingPanels count={2} />)}

        {section === 'Printers' && <PrinterSettings onUnauthorized={signOut} />}

        {section === 'Reports' && <section className="panel">
          <div className="panel-head">
            <div>
              <h2 className="panel-title">Sales report</h2>
              <p className="panel-sub">Orders, completions and confirmed payments for the selected dates.</p>
            </div>
            {report && <span className="pill">{report.currency}</span>}
          </div>
          <form className="report-range" onSubmit={event => {
            event.preventDefault(); void action(async () => setReport(await fetchSalesReport(from, to)), undefined, false)
          }}>
            <label className="field">From
              <input type="date" value={from} max={to} required onChange={event => setFrom(event.target.value)} />
            </label>
            <label className="field">Through
              <input type="date" value={to} min={from} required onChange={event => setTo(event.target.value)} />
            </label>
            <button className="btn primary" disabled={busy}>
              {busy ? <Loader2 size={15} className="spin" /> : 'Run report'}
            </button>
          </form>

          {report
            ? <>
              <p className="muted">Showing {report.from} through {report.to}.</p>
              <div className="stats">
                <Stat icon={ShoppingBag} label="Orders" value={report.orders} />
                <Stat icon={CheckCircle2} label="Completed" value={report.completed} />
                <Stat icon={X} label="Cancelled" value={report.cancelled} />
                <Stat icon={Wallet} label="Confirmed payments" value={money(report.revenue, report.currency)} tone="accent" />
              </div>
            </>
            : <EmptyState icon={BarChart3} title="No report yet"
              text="Choose a date range and run the report." />}
        </section>}

        {section === 'Shop QR' && (shop
          ? <section className="panel">
            <div className="qr-layout">
              <div className="qr-card">
                <img className="qr-image" src={publicQrUrl(shop.qrToken)} alt={`Scan to print at ${shop.name}`} />
                <p className="muted">Scan to open the shop page</p>
              </div>
              <div className="stack">
                <div>
                  <h2 className="panel-title">Customer QR code</h2>
                  <p className="panel-sub">Print this code and place it on the counter. Scanning opens your upload page.</p>
                </div>
                <a className="qr-url" href={shop.publicUrl} target="_blank" rel="noreferrer">{shop.publicUrl}</a>
                <div className="inline">
                  <button type="button" className="btn primary" disabled={busy} onClick={() => void action(
                    () => downloadProtectedAsset(shop.qrImageUrl, `${shop.shopCode}-qr.png`), undefined, false,
                  )}><ImageIcon size={15} />Download PNG</button>
                  <button type="button" className="btn" onClick={copyShopLink}><Copy size={15} />Copy link</button>
                  <button type="button" className="btn danger" disabled={busy} onClick={() => setNotice({
                    title: 'Replace this QR code?',
                    description: 'Existing printed codes will stop opening the shop. Order tracking links keep working.',
                    confirmLabel: 'Regenerate QR',
                    run: () => void action(async () => setShop(await regenerateQr()), 'QR code replaced.'),
                  })}><QrCode size={15} />Regenerate QR</button>
                </div>
                <p className="muted">Shop code {shop.shopCode}</p>
              </div>
            </div>
          </section>
          : <LoadingPanels count={1} />)}
      </main>
    </div>

    {preview && <DocumentPreviewDialog
      title={preview.file.name}
      subtitle={`Order ${preview.orderNumber} · ${preview.file.selectedPages} pages · ${preview.file.colorMode === 'COLOR' ? 'Color' : 'Black and white'} · ${preview.file.sideMode === 'DUPLEX' ? 'Double-sided' : 'Single-sided'}`}
      onClose={() => setPreview(undefined)}
    >
      <DocumentPreview
        fileName={preview.file.name}
        pageCount={preview.file.selectedPages}
        orientation={preview.file.orientation}
        pdfPath={preview.file.previewUrl ?? undefined}
      />
    </DocumentPreviewDialog>}

    {notice && <ConfirmDialog
      title={notice.title}
      description={notice.description}
      confirmLabel={notice.confirmLabel}
      tone={notice.tone ?? 'danger'}
      busy={busy}
      onCancel={() => setNotice(undefined)}
      onConfirm={() => {
        const run = notice.run
        setNotice(undefined)
        run()
      }}
    />}
  </div>
}

function Stat({ icon: Icon, label, value, hint, tone }: {
  icon: typeof ShoppingBag
  label: string
  value: number | string
  hint?: string
  tone?: 'accent' | 'amber'
}) {
  return <div className={`stat ${tone ?? ''}`}>
    <span className="stat-icon" aria-hidden="true"><Icon size={17} /></span>
    <span className="stat-label">{label}</span>
    <span className="stat-value">{value}</span>
    {hint && <span className="stat-hint">{hint}</span>}
  </div>
}

function ToggleRow({ label, description, checked, onChange }: {
  label: string
  description: string
  checked: boolean
  onChange: (value: boolean) => void
}) {
  return <label className="toggle-row">
    <span className="toggle-text">
      <b>{label}</b>
      <small>{description}</small>
    </span>
    <span className="switch">
      <input type="checkbox" checked={checked} onChange={event => onChange(event.target.checked)} />
      <span aria-hidden="true" />
    </span>
  </label>
}

function EmptyState({ icon: Icon, title, text }: { icon: typeof ShoppingBag; title: string; text: string }) {
  return <div className="empty-state">
    <span className="empty-icon"><Icon size={20} /></span>
    <b>{title}</b>
    <p>{text}</p>
  </div>
}

function LoadingPanels({ count }: { count: number }) {
  return <div className="loading-panels" aria-busy="true" aria-label="Loading">
    {Array.from({ length: count }).map((_, index) => <div className="panel skeleton-panel" key={index}>
      <span className="skeleton w-40" />
      <span className="skeleton" />
      <span className="skeleton w-80" />
      <span className="skeleton w-60" />
    </div>)}
    <span className="sr-only" role="status">Loading…</span>
  </div>
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

  return <section className="attempt-history">
    <div className="inline">
      <h3 className="subheading">Print history</h3>
      <button type="button" className="btn tiny" disabled={loading} onClick={() => void load()}>
        {loading ? <Loader2 size={13} className="spin" /> : attempts ? 'Refresh' : 'View print history'}
      </button>
    </div>
    {error && <p role="alert" className="alert error compact">{error}</p>}
    {attempts?.length === 0 && <p className="muted">No print attempts yet.</p>}
    {attempts?.map(attempt => <div className="attempt-row" key={`${attempt.fileId}-${attempt.attemptNumber}`}>
      <div>
        <b>{attempt.fileName}</b>
        <small>Attempt {attempt.attemptNumber} · {attempt.printerName ?? 'Unknown printer'}</small>
      </div>
      <span className={`badge ${statusTone(attempt.status)}`}>{prettyStatus(attempt.status)}</span>
      <small className="attempt-time">{dateTime(attempt.startedAt)}</small>
      {attempt.failureReason && <p className="alert error compact">{attempt.failureReason}</p>}
    </div>)}
  </section>
}
