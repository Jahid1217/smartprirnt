import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  AlertCircle, ArrowRight, Clock3, FileText, Loader2, Printer, ShieldCheck, UploadCloud, X,
} from 'lucide-react'
import {
  createOrder, customerEventsUrl, draftPreview, errorMessage, fetchOrder, fetchPublicShop,
  quoteOrder, submitPaymentReference,
  type PaymentMethodCode, type PrintSettingsInput, type PublicOrder, type PublicShop, type Quote,
} from '../lib/api'
import { fileSize, money, prettyStatus, statusTone } from '../lib/format'
import DocumentPreview, { DocumentPreviewDialog } from '../components/DocumentPreview'

const defaults: PrintSettingsInput = {
  colorMode: 'BW', sideMode: 'SIMPLEX', orientation: 'PORTRAIT', copies: 1, pageSelection: '',
}

function extension(name: string) {
  return name.split('.').pop()?.slice(0, 4).toUpperCase() || 'FILE'
}

export default function CustomerPage() {
  const { qrToken = '' } = useParams()
  const [shop, setShop] = useState<PublicShop>()
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    setShop(undefined)
    setError('')
    fetchPublicShop(qrToken)
      .then(value => { if (active) setShop(value) })
      .catch(cause => { if (active) setError(errorMessage(cause)) })
    return () => { active = false }
  }, [qrToken, attempt])

  if (!shop) return <main className="customer-shell">
    <header className="shop-bar">
      <div className="shop-bar-inner">
        <Link to="/" className="brand brand-inverse">
          <span className="brand-mark">S</span>
          <span className="brand-text"><b>SmartPrint</b><small>Self-service printing</small></span>
        </Link>
      </div>
    </header>
    <div className="customer-content">
      <div className="panel loading-panel">
        {error ? <>
          <span className="loading-icon error"><AlertCircle size={22} /></span>
          <b>This shop page could not be opened</b>
          <p role="alert">{error}</p>
          <button type="button" className="btn" onClick={() => setAttempt(value => value + 1)}>Try again</button>
        </> : <>
          <span className="spinner" />
          <p role="status">Opening shop…</p>
        </>}
      </div>
    </div>
  </main>

  return <main className="customer-shell">
    <header className="shop-bar">
      <div className="shop-bar-inner">
        <div className="shop-identity">
          <span className="shop-avatar" aria-hidden="true">{shop.shopName.charAt(0)}</span>
          <div className="shop-identity-text">
            <b>{shop.shopName}</b>
            <small>{shop.address || 'Self-service printing'}</small>
          </div>
        </div>
        <div className="shop-bar-actions">
          <span className={`open-badge ${shop.acceptingOrders ? '' : 'closed'}`}>
            <i aria-hidden="true" />{shop.acceptingOrders ? 'Open now' : 'Closed'}
          </span>
          <Link to="/" className="brand-mini">SmartPrint</Link>
        </div>
      </div>
    </header>

    <div className="customer-content">
      <div className="page-intro">
        <div>
          <span className="eyebrow">Self-service printing</span>
          <h1>Upload, preview and print</h1>
          <p>Add your documents, choose the print settings and submit. The shop confirms your payment before printing.</p>
        </div>
        <ol className="mini-steps" aria-label="How it works">
          <li><span>1</span>Upload</li>
          <li><span>2</span>Preview</li>
          <li><span>3</span>Pay</li>
          <li><span>4</span>Collect</li>
        </ol>
      </div>

      {shop.acceptingOrders ? <PrintForm key={qrToken} shop={shop} /> : <section className="panel empty-state">
        <span className="empty-icon"><Clock3 size={22} /></span>
        <b>This shop is closed right now</b>
        <p>{shop.shopName} is not accepting new print orders. Please try again later.</p>
      </section>}
    </div>
  </main>
}

function randomRequestKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function PrintForm({ shop }: { shop: PublicShop }) {
  const navigate = useNavigate()
  const [files, setFiles] = useState<File[]>([])
  const [settings, setSettings] = useState(defaults)
  const [method, setMethod] = useState<PaymentMethodCode | undefined>(shop.paymentMethods[0]?.code)
  const [reference, setReference] = useState('')
  const [quoteResult, setQuoteResult] = useState<{ key: object; value: Quote }>()
  const [quoteError, setQuoteError] = useState('')
  const [submitError, setSubmitError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [fileIndex, setFileIndex] = useState(0)
  const [page, setPage] = useState(0)
  const [dragging, setDragging] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const submission = useRef<{ input: object; method: PaymentMethodCode; reference: string; key: string }>()
  const previewAbort = useRef<AbortController>()
  const request = useMemo(() => ({ files, settings }), [files, settings])
  const quote = quoteResult?.key === request ? quoteResult.value : undefined
  const selected = shop.paymentMethods.find(payment => payment.code === method)
  const currentFile = files[fileIndex]
  const currentQuoteFile = quote?.files[fileIndex]
  const pageCount = currentQuoteFile?.selectedPages ?? 0
  const accept = [
    shop.features.pdf && '.pdf', shop.features.word && '.doc,.docx',
    shop.features.image && '.jpg,.jpeg,.png',
  ].filter(Boolean).join(',')

  useEffect(() => {
    setQuoteError('')
    if (!request.files.length) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      quoteOrder(shop.qrToken, request.files, request.settings, controller.signal)
        .then(value => { if (!controller.signal.aborted) setQuoteResult({ key: request, value }) })
        .catch(cause => { if (!controller.signal.aborted) setQuoteError(errorMessage(cause)) })
    }, 450)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [request, shop.qrToken])

  useEffect(() => () => previewAbort.current?.abort(), [])

  const loadPage = useCallback(async (index: number) => {
    if (!currentFile) throw new Error('No document selected')
    previewAbort.current?.abort()
    const controller = new AbortController()
    previewAbort.current = controller
    return draftPreview(shop.qrToken, currentFile, settings, index, controller.signal)
  }, [shop.qrToken, currentFile, settings])

  function update<K extends keyof PrintSettingsInput>(key: K, value: PrintSettingsInput[K]) {
    setSettings(current => ({ ...current, [key]: value }))
    setPage(0)
  }

  function addFiles(incoming: File[]) {
    if (!incoming.length) return
    const next = [...files, ...incoming]
    if (next.length > shop.maxFiles || next.some(file => file.size > shop.maxFileSizeMb * 1024 * 1024)) {
      setUploadError(`Choose up to ${shop.maxFiles} files, each up to ${shop.maxFileSizeMb} MB.`)
      return
    }
    setUploadError('')
    setFiles(next)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!quote || !method || submitting) return
    setSubmitting(true)
    setSubmitError('')
    try {
      const previous = submission.current
      const matching = previous?.input === request && previous.method === method && previous.reference === reference
      const key = matching ? previous.key : randomRequestKey()
      submission.current = { input: request, method, reference, key }
      const order = await createOrder(shop.qrToken, files, settings, method, undefined, undefined,
        selected?.requiresTransactionId ? reference : undefined, quote.totalAmount, key)
      navigate(`/orders/${order.publicToken}`, { state: { shopToken: shop.qrToken } })
    } catch (cause) {
      setSubmitError(errorMessage(cause))
    } finally {
      setSubmitting(false)
    }
  }

  const cacheKey = `${currentFile?.name ?? ''}:${fileIndex}:${settings.colorMode}:${settings.orientation}:${settings.pageSelection}`

  return <form onSubmit={submit} className="checkout-grid">
    <div className="checkout-main">
      <fieldset className="panel" disabled={submitting}>
        <div className="panel-head">
          <div>
            <h2 className="panel-title"><span className="step-dot">1</span>Documents</h2>
            <p className="panel-sub">PDF, Word and image files up to {shop.maxFileSizeMb} MB each.</p>
          </div>
          <span className="pill">{files.length} of {shop.maxFiles} files</span>
        </div>

        <label
          className={`dropzone ${dragging ? 'dragging' : ''} ${!accept ? 'disabled' : ''}`}
          onDragOver={event => { event.preventDefault(); if (accept) setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={event => {
            event.preventDefault()
            setDragging(false)
            if (accept) addFiles(Array.from(event.dataTransfer.files ?? []))
          }}
        >
          <input type="file" multiple accept={accept} disabled={!accept} onChange={event => {
            addFiles(Array.from(event.target.files ?? []))
            event.target.value = ''
          }} />
          <span className="upload-icon"><UploadCloud size={22} aria-hidden="true" /></span>
          <b>{accept ? 'Drop files here or browse' : 'Uploads are disabled for this shop'}</b>
          <small>{accept ? `${accept.replace(/\./g, '').replace(/,/g, ', ').toUpperCase()} · up to ${shop.maxFiles} files` : 'Ask the shop to enable a file type.'}</small>
        </label>

        {uploadError && <p role="alert" className="alert error">{uploadError}</p>}

        {files.length > 0 && <ul className="file-list">
          {files.map((file, index) => <li className={`file ${index === fileIndex ? 'selected' : ''}`} key={`${file.name}-${index}`}>
            <span className="file-icon" aria-hidden="true">{extension(file.name)}</span>
            <div className="file-info">
              <b title={file.name}>{file.name}</b>
              <small>
                {fileSize(file.size)}
                {quote?.files[index] && <> · {quote.files[index].selectedPages} of {quote.files[index].pages} pages</>}
              </small>
            </div>
            {quote && <button type="button" className="btn tiny" onClick={() => {
              setFileIndex(index); setPage(0)
            }} disabled={index === fileIndex}>{index === fileIndex ? 'Previewing' : 'Preview'}</button>}
            <button type="button" className="icon-btn" aria-label={`Remove ${file.name}`} title="Remove file"
              onClick={() => {
                setFiles(files.filter((_, i) => i !== index))
                setFileIndex(0); setPage(0); setUploadError('')
              }}><X size={16} /></button>
          </li>)}
        </ul>}
      </fieldset>

      <fieldset className="panel" disabled={submitting}>
        <div className="panel-head">
          <div>
            <h2 className="panel-title"><span className="step-dot">2</span>Print settings</h2>
            <p className="panel-sub">These settings apply to every document in this order.</p>
          </div>
        </div>

        <div className="settings-grid">
          {shop.features.color && <div className="option-block">
            <span className="field-label">Color</span>
            <div className="segmented" role="radiogroup" aria-label="Color">
              <button type="button" role="radio" aria-checked={settings.colorMode === 'BW'}
                className={`segment ${settings.colorMode === 'BW' ? 'active' : ''}`}
                onClick={() => update('colorMode', 'BW')}>Black &amp; white</button>
              <button type="button" role="radio" aria-checked={settings.colorMode === 'COLOR'}
                className={`segment ${settings.colorMode === 'COLOR' ? 'active' : ''}`}
                onClick={() => update('colorMode', 'COLOR')}>Color</button>
            </div>
          </div>}

          {shop.features.duplex && <div className="option-block">
            <span className="field-label">Sides</span>
            <div className="segmented" role="radiogroup" aria-label="Sides">
              <button type="button" role="radio" aria-checked={settings.sideMode === 'SIMPLEX'}
                className={`segment ${settings.sideMode === 'SIMPLEX' ? 'active' : ''}`}
                onClick={() => update('sideMode', 'SIMPLEX')}>Single-sided</button>
              <button type="button" role="radio" aria-checked={settings.sideMode === 'DUPLEX'}
                className={`segment ${settings.sideMode === 'DUPLEX' ? 'active' : ''}`}
                onClick={() => update('sideMode', 'DUPLEX')}>Double-sided</button>
            </div>
          </div>}

          {shop.features.landscape && <div className="option-block">
            <span className="field-label">Orientation</span>
            <div className="segmented" role="radiogroup" aria-label="Orientation">
              <button type="button" role="radio" aria-checked={settings.orientation === 'PORTRAIT'}
                className={`segment ${settings.orientation === 'PORTRAIT' ? 'active' : ''}`}
                onClick={() => update('orientation', 'PORTRAIT')}>Portrait</button>
              <button type="button" role="radio" aria-checked={settings.orientation === 'LANDSCAPE'}
                className={`segment ${settings.orientation === 'LANDSCAPE' ? 'active' : ''}`}
                onClick={() => update('orientation', 'LANDSCAPE')}>Landscape</button>
            </div>
          </div>}

          <label className="field option-block">
            <span className="field-label">Copies</span>
            <input type="number" required min={1} max={100} value={settings.copies}
              onChange={event => update('copies', event.target.valueAsNumber || 1)} />
          </label>

          {shop.features.pageSelection && <label className="field option-block">
            <span className="field-label">Pages in each file</span>
            <input maxLength={2000} placeholder="All pages, 1-5, or 1,3,7" value={settings.pageSelection}
              onChange={event => update('pageSelection', event.target.value.replace(/\u2013/g, '-'))} />
            <span className="hint">Leave blank to print every page of every file.</span>
          </label>}
        </div>

        {quoteError && <p role="alert" className="alert error">{quoteError}</p>}
      </fieldset>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2 className="panel-title"><span className="step-dot">3</span>Print preview</h2>
            <p className="panel-sub">Check every page before submitting — this is what the printer will produce.</p>
          </div>
          {quote && <span className="pill">{quote.totalSelectedPages} selected {quote.totalSelectedPages === 1 ? 'page' : 'pages'}</span>}
        </div>

        {!quote ? <div className="empty-state compact">
          <span className="empty-icon"><FileText size={20} /></span>
          <b>{files.length ? 'Calculating pages…' : 'No document to preview'}</b>
          <p>{files.length ? 'The preview appears as soon as the page count is ready.' : 'Add a document above to see its A4 print preview.'}</p>
          {files.length > 0 && <span className="spinner" />}
        </div> : <>
          <div className="preview-controls">
            <label className="field">
              <span className="field-label">Document</span>
              <select value={fileIndex} onChange={event => {
                setFileIndex(Number(event.target.value)); setPage(0)
              }}>
                {quote.files.map((file, index) => <option key={`${file.name}-${index}`} value={index}>{file.name}</option>)}
              </select>
            </label>
            <p className="preview-summary">
              {settings.sideMode === 'DUPLEX' ? 'Double-sided' : 'Single-sided'}
              {' · '}{settings.copies} {settings.copies === 1 ? 'copy' : 'copies'}
              {' · '}{settings.colorMode === 'COLOR' ? 'Color' : 'Black & white'}
            </p>
          </div>
          {currentFile && <DocumentPreview
            fileName={currentFile.name}
            pageCount={pageCount}
            orientation={settings.orientation}
            loadPage={loadPage}
            cacheKey={cacheKey}
          />}
        </>}
      </section>
    </div>

    <aside className="checkout-side">
      <fieldset className="panel summary-card" disabled={submitting}>
        <div className="summary-head">
          <h2>Order summary</h2>
          <ShieldCheck size={17} aria-hidden="true" />
        </div>

        <div className="price-box" aria-live="polite">
          <div className="price-row">
            <span>Selected pages</span>
            <b>{quote ? quote.totalSelectedPages : files.length && !quoteError ? '—' : '0'}</b>
          </div>
          <div className="price-row">
            <span>Copies</span>
            <b>{settings.copies}</b>
          </div>
          <div className="price-row">
            <span>Pages to print</span>
            <b>{quote ? quote.totalSelectedPages * settings.copies : '—'}</b>
          </div>
          <div className="price-total">
            <span>Total</span>
            <span className="price-big">{quote ? money(quote.totalAmount, quote.currency)
              : files.length && !quoteError ? 'Calculating…' : '—'}</span>
          </div>
        </div>

        <div className="summary-section">
          <span className="field-label">Payment method</span>
          {shop.paymentMethods.length ? <div className="payment-grid" role="radiogroup" aria-label="Payment method">
            {shop.paymentMethods.map(payment => <label key={payment.code}
              className={`payment-option ${payment.code === method ? 'active' : ''}`}>
              <input type="radio" name="paymentMethod" value={payment.code}
                checked={payment.code === method}
                onChange={() => { setMethod(payment.code); setReference('') }} />
              <span>
                <b>{payment.label}</b>
                <small>{payment.code === 'CASH' ? 'Pay at the counter'
                  : payment.walletNumber ? `Send to ${payment.walletNumber}` : 'Wallet number at the counter'}</small>
              </span>
            </label>)}
          </div> : <div className="alert info compact">
            <AlertCircle size={14} /> This shop has no payment method enabled. Please ask staff.
          </div>}
        </div>

        {selected?.requiresTransactionId && <div className="summary-section">
          <div className="payment-instructions">
            {selected.walletNumber
              ? <>Send the total to <b>{selected.walletNumber}</b> via {selected.label}, then enter the transaction ID.</>
              : <>Ask the shop for its {selected.label} number, then enter the transaction ID.</>}
          </div>
          <label className="field">
            <span className="field-label">Transaction ID</span>
            <input required autoComplete="off" pattern={'[A-Za-z0-9\\-]{6,64}'}
              placeholder="e.g. 8AF7K2QW1L" value={reference}
              onChange={event => setReference(event.target.value)} />
            <span className="hint">6–64 letters, numbers or hyphens.</span>
          </label>
        </div>}

        {selected?.code === 'CASH' && <div className="payment-instructions">
          Pay at the counter and show your order number after submitting. Printing starts after staff confirm the payment.
        </div>}

        {submitError && <div className="summary-section">
          <p role="alert" className="alert error">{submitError}</p>
          <button type="button" className="btn small" onClick={() => {
            setSettings(current => ({ ...current })); setSubmitError('')
          }}>Recalculate total</button>
        </div>}

        <button className="btn primary lg block submit-button" disabled={!quote || !selected || submitting}
          type="submit">
          {submitting ? <><Loader2 size={16} className="spin" />Submitting…</>
            : <><Printer size={16} />Submit print request<ArrowRight size={16} /></>}
        </button>
        <p className="privacy-note">
          The shop confirms your payment before printing. Files are deleted after the retention period.
        </p>
      </fieldset>
    </aside>
  </form>
}

const orderSteps = ['Payment review', 'Queue', 'Printing', 'Pickup']

function progressOf(status: string) {
  switch (status) {
    case 'PAYMENT_PENDING':
    case 'UPLOADED':
    case 'PROCESSING':
    case 'NEEDS_REVIEW':
    case 'REFUND_PENDING':
      return { current: 0, failed: false }
    case 'PAYMENT_APPROVED':
    case 'QUEUED':
      return { current: 1, failed: false }
    case 'PRINTING':
      return { current: 2, failed: false }
    case 'COMPLETED':
    case 'COLLECTED':
    case 'REFUNDED':
      return { current: 4, failed: false }
    default:
      return { current: -1, failed: true }
  }
}

export function OrderStatusPage() {
  const { publicToken = '' } = useParams()
  const location = useLocation()
  const shopToken = location.state?.shopToken as string | undefined
  const [order, setOrder] = useState<PublicOrder>()
  const [error, setError] = useState('')
  const [reference, setReference] = useState('')
  const [busy, setBusy] = useState(false)
  const [refreshError, setRefreshError] = useState('')
  const [previewFile, setPreviewFile] = useState<PublicOrder['files'][number]>()
  const [attempt, setAttempt] = useState(0)
  const hasOrder = useRef(false)

  useEffect(() => {
    hasOrder.current = false
    setOrder(undefined)
  }, [publicToken])

  useEffect(() => {
    let active = true
    let loading = false
    async function refresh() {
      if (loading) return
      loading = true
      try {
        const value = await fetchOrder(publicToken)
        if (active) { hasOrder.current = true; setOrder(value); setError(''); setRefreshError('') }
      } catch (cause) {
        if (!active) return
        if (hasOrder.current) setRefreshError(errorMessage(cause))
        else setError(errorMessage(cause))
      } finally {
        loading = false
      }
    }
    void refresh()
    const events = new EventSource(customerEventsUrl(publicToken))
    events.addEventListener('ready', refresh)
    events.addEventListener('update', refresh)
    const timer = window.setInterval(refresh, 5000)
    return () => { active = false; events.close(); window.clearInterval(timer) }
  }, [publicToken, attempt])

  async function save(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      setOrder(await submitPaymentReference(publicToken, reference.trim()))
      setError('')
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  const progress = order ? progressOf(order.status) : { current: 0, failed: false }

  return <main className="status-page">
    <header className="shop-bar">
      <div className="shop-bar-inner">
        <Link to="/" className="brand brand-inverse">
          <span className="brand-mark">S</span>
          <span className="brand-text"><b>SmartPrint</b><small>{order?.shop.name ?? 'Order tracking'}</small></span>
        </Link>
        <span className="live-chip"><i className="live-dot" aria-hidden="true" />Live updates</span>
      </div>
    </header>

    <div className="status-content">
      {error && !order && <div className="panel loading-panel">
        <span className="loading-icon error"><AlertCircle size={22} /></span>
        <b>This order could not be loaded</b>
        <p role="alert">{error}</p>
        <button type="button" className="btn" onClick={() => { setError(''); setAttempt(value => value + 1) }}>
          Try again
        </button>
      </div>}

      {!order && !error && <div className="panel loading-panel">
        <span className="spinner" />
        <p role="status">Loading order…</p>
      </div>}

      {order && <>
        {refreshError && <p role="alert" className="alert warning compact">
          <AlertCircle size={14} /> Live updates are temporarily unavailable. Showing the latest known status.
        </p>}

        <section className={`status-hero ${progress.failed ? 'failed' : ''}`} aria-live="polite">
          <div className="status-hero-top">
            <span className={`status-badge ${progress.failed ? 'rejected' : statusTone(order.status)}`}>
              <i aria-hidden="true" />{prettyStatus(order.status)}
            </span>
            <span className="status-order-no">Order {order.orderNumber}</span>
          </div>
          <h1>{order.statusLabel}</h1>
          <p className="status-shop">{order.shop.name}{order.shop.address ? ` · ${order.shop.address}` : ''}</p>

          {order.queue && <div className="queue-number">
            <b>#{order.queue.queueNumber}</b>
            <div>
              <span>Your queue serial</span>
              <em>{order.queue.ahead === 0 ? 'You are next'
                : order.queue.ahead === 1 ? '1 order ahead of you' : `${order.queue.ahead ?? 0} orders ahead of you`}</em>
            </div>
          </div>}

          {!progress.failed && <ol className="status-steps" aria-label="Order progress">
            {orderSteps.map((step, index) => <li key={step}
              className={`status-step ${index < progress.current ? 'done' : ''} ${index === progress.current ? 'current' : ''}`}>
              <b>{step}</b>
            </li>)}
          </ol>}
          {progress.failed && <p className="status-failure">
            {order.status === 'PAYMENT_REJECTED' && order.payment.rejectionReason
              ? `Reason: ${order.payment.rejectionReason}`
              : 'This order stopped before printing. Please speak to shop staff with your order number.'}
          </p>}
        </section>

        <div className="status-grid">
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2 className="panel-title">Order details</h2>
                <p className="panel-sub">Keep this page — it updates automatically.</p>
              </div>
              {shopToken && <Link className="btn small" to={`/s/${shopToken}`}>New order</Link>}
            </div>

            <div className="receipt-rows">
              <div className="receipt-row"><span>Amount</span><b>{money(order.totalAmount, order.currency)}</b></div>
              <div className="receipt-row"><span>Payment</span><b>{prettyStatus(order.payment.method)} · {prettyStatus(order.payment.status)}</b></div>
              {order.payment.transactionId && <div className="receipt-row">
                <span>Transaction ID</span><b className="mono">{order.payment.transactionId}</b>
              </div>}
              <div className="receipt-row"><span>Submitted</span><b>{new Date(order.createdAt).toLocaleString()}</b></div>
            </div>

            {order.status === 'PAYMENT_PENDING' && <div className="receipt-instructions">
              {order.payment.status === 'SUBMITTED'
                ? 'The shop is reviewing your transaction. This page updates automatically.'
                : order.payment.instructions}
            </div>}

            {order.payment.method !== 'CASH' && order.payment.status === 'PENDING' &&
              <form onSubmit={save} className="pay-form">
                <label className="field">
                  <span className="field-label">Transaction ID</span>
                  <input required value={reference} minLength={6} maxLength={64} autoComplete="off"
                    placeholder="Enter the transaction ID"
                    onChange={event => setReference(event.target.value)} />
                </label>
                <button className="btn primary" disabled={busy}>
                  {busy ? 'Submitting…' : 'Submit transaction ID'}
                </button>
              </form>}
          </section>

          <section className="panel">
            <div className="panel-head">
              <div>
                <h2 className="panel-title">Documents</h2>
                <p className="panel-sub">{order.files.length} {order.files.length === 1 ? 'file' : 'files'} in this order.</p>
              </div>
            </div>
            <ul className="file-list">
              {order.files.map(file => <li className="file" key={file.id}>
                <span className="file-icon" aria-hidden="true">{extension(file.name)}</span>
                <div className="file-info">
                  <b title={file.name}>{file.name}</b>
                  <small>{file.selectedPages} {file.selectedPages === 1 ? 'page' : 'pages'}
                    {' × '}{file.copies} {file.copies === 1 ? 'copy' : 'copies'}
                    {' · '}{file.colorMode === 'COLOR' ? 'Color' : 'B&W'}
                    {' · '}{file.sideMode === 'DUPLEX' ? 'Double-sided' : 'Single-sided'}</small>
                </div>
                {file.previewUrl && <button type="button" className="btn tiny" onClick={() => setPreviewFile(file)}>
                  <FileText size={13} />Preview
                </button>}
              </li>)}
            </ul>
            <p className="privacy-note">Document previews stay available until the shop deletes the files.</p>
          </section>
        </div>

        <div className="status-foot">
          <Link to="/" className="btn plain">Back to SmartPrint</Link>
          <span><Clock3 size={13} /> Updates automatically every few seconds</span>
        </div>
      </>}
    </div>

    {previewFile && <DocumentPreviewDialog
      title={previewFile.name}
      subtitle={`Order ${order?.orderNumber ?? ''} · ${previewFile.selectedPages} pages · ${previewFile.orientation === 'LANDSCAPE' ? 'Landscape' : 'Portrait'}`}
      onClose={() => setPreviewFile(undefined)}
    >
      <DocumentPreview
        fileName={previewFile.name}
        pageCount={previewFile.selectedPages}
        orientation={previewFile.orientation}
        pdfPath={previewFile.previewUrl ?? undefined}
      />
    </DocumentPreviewDialog>}
  </main>
}
