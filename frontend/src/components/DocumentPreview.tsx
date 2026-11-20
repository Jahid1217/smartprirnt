import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  ChevronLeft, ChevronRight, Download, ExternalLink, FileText, Maximize2, Printer,
  StretchHorizontal, X, ZoomIn, ZoomOut,
} from 'lucide-react'
import { fetchAssetBlob } from '../lib/api'

type Orientation = 'PORTRAIT' | 'LANDSCAPE'

/** A4 in CSS pixels at 96 dpi. The sheet keeps this exact ratio at every zoom level. */
const A4 = { width: 794, height: 1123 }

export interface DocumentPreviewProps {
  /** Display name used for the download file and accessible labels. */
  fileName: string
  /** Number of pages in the printable document. */
  pageCount: number
  /** Page orientation applied by the server while rendering. */
  orientation?: Orientation
  /** Renders one page as an image (draft previews before an order exists). */
  loadPage?: (index: number) => Promise<Blob>
  /** Path to the printable PDF (order and admin previews). */
  pdfPath?: string
  /** Changing this value clears cached images (for example when print settings change). */
  cacheKey?: string
  className?: string
}

function clampZoom(value: number) {
  return Math.min(3, Math.max(0.35, Math.round(value * 100) / 100))
}

function downloadName(fileName: string, extension: string) {
  const base = fileName.replace(/\.[^.]+$/, '').trim() || 'document'
  return `${base}.${extension}`
}

export default function DocumentPreview({
  fileName, pageCount, orientation = 'PORTRAIT', loadPage, pdfPath, cacheKey, className,
}: DocumentPreviewProps) {
  const total = Math.max(1, pageCount || 1)
  const ratio = orientation === 'LANDSCAPE'
    ? A4.height / A4.width
    : A4.width / A4.height

  const [page, setPage] = useState(0)
  const [zoom, setZoom] = useState(1)
  const [fit, setFit] = useState<'width' | 'page'>('width')
  const [stage, setStage] = useState({ width: 0, height: 0 })
  const [imageUrl, setImageUrl] = useState('')
  const [pdfUrl, setPdfUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)

  const stageRef = useRef<HTMLDivElement>(null)
  const cache = useRef(new Map<number, string>())
  const generation = useRef(0)

  // Track the available area so the sheet can fit the width or the whole page.
  useEffect(() => {
    const element = stageRef.current
    if (!element) return
    const measure = () => setStage({ width: element.clientWidth, height: element.clientHeight })
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  // The PDF is fetched once per document and shown at its true page size.
  useEffect(() => {
    if (!pdfPath) {
      setPdfUrl('')
      return
    }
    let active = true
    let url: string | undefined
    setLoading(true)
    setError('')
    fetchAssetBlob(pdfPath)
      .then(blob => {
        if (!active) return
        url = URL.createObjectURL(blob)
        setPdfUrl(url)
      })
      .catch(() => { if (active) setError('This document preview could not be loaded.') })
      .finally(() => { if (active) setLoading(false) })
    return () => {
      active = false
      if (url) URL.revokeObjectURL(url)
      setPdfUrl('')
    }
  }, [pdfPath])

  // Drop cached page images when the print settings or selected document change.
  useEffect(() => {
    setPage(0)
    setError('')
    cache.current.forEach(url => URL.revokeObjectURL(url))
    cache.current.clear()
    setImageUrl('')
  }, [cacheKey, pdfPath])

  useEffect(() => {
    if (!loadPage) {
      setImageUrl('')
      return
    }
    const cached = cache.current.get(page)
    if (cached) {
      setImageUrl(cached)
      setError('')
      return
    }
    const request = ++generation.current
    setImageUrl('')
    setLoading(true)
    setError('')
    loadPage(page)
      .then(blob => {
        if (request !== generation.current) return
        const url = URL.createObjectURL(blob)
        cache.current.set(page, url)
        setImageUrl(url)
      })
      .catch(() => {
        if (request === generation.current) setError('This page preview could not be rendered.')
      })
      .finally(() => { if (request === generation.current) setLoading(false) })
  }, [loadPage, page, attempt])

  useEffect(() => {
    return () => {
      cache.current.forEach(url => URL.revokeObjectURL(url))
      cache.current.clear()
    }
  }, [])

  useEffect(() => {
    setPage(current => Math.min(current, total - 1))
  }, [total])

  const pad = stage.width < 480 ? 24 : 48
  const fitWidth = stage.width ? Math.max(160, Math.min(stage.width - pad, A4.width)) : A4.width
  const fitHeightWidth = stage.height ? Math.max(160, (stage.height - pad) / ratio) : fitWidth
  const pageWidth = fit === 'page'
    ? Math.max(140, Math.min(fitWidth, fitHeightWidth))
    : Math.min(fitWidth * zoom, fitWidth * 3)
  const zoomPercent = Math.round((pageWidth / fitWidth) * 100)
  const sourceUrl = pdfUrl || imageUrl
  const canPrint = Boolean(sourceUrl)

  function goTo(next: number) {
    setPage(Math.min(total - 1, Math.max(0, next)))
  }

  function zoomBy(delta: number) {
    setFit('width')
    setZoom(clampZoom(pageWidth / fitWidth + delta))
  }

  function download() {
    if (!sourceUrl) return
    const link = document.createElement('a')
    link.href = sourceUrl
    link.download = downloadName(fileName, pdfUrl ? 'pdf' : 'jpg')
    link.click()
  }

  function print() {
    if (pdfUrl) {
      const frame = document.createElement('iframe')
      frame.setAttribute('title', 'Print preview')
      frame.style.cssText = 'position:fixed;right:0;bottom:0;width:1px;height:1px;border:0;opacity:0'
      frame.src = pdfUrl
      let printed = false
      frame.onload = () => {
        if (printed) return
        printed = true
        window.setTimeout(() => {
          try {
            frame.contentWindow?.focus()
            frame.contentWindow?.print()
          } catch {
            window.open(pdfUrl, '_blank', 'noopener')
          }
          window.setTimeout(() => frame.remove(), 60_000)
        }, 250)
      }
      document.body.appendChild(frame)
      return
    }
    if (!imageUrl) return
    const preview = window.open('', '_blank')
    if (!preview) return
    preview.document.write(`<!doctype html><html><head><title>${fileName}</title>
      <style>@page{size:A4 ${orientation === 'LANDSCAPE' ? 'landscape' : 'portrait'};margin:0}
      html,body{margin:0;padding:0;background:#fff}
      img{display:block;width:100%;height:auto}</style></head>
      <body><img src="${imageUrl}" alt="" onload="window.focus();window.print()"></body></html>`)
    preview.document.close()
  }

  return <div className={`a4-preview ${className ?? ''}`} role="group"
    aria-label={`Document preview of ${fileName}`}>
    <div className="a4-toolbar">
      <div className="a4-group" aria-label="Page navigation">
        <button type="button" className="a4-btn" onClick={() => goTo(page - 1)} disabled={page === 0}
          aria-label="Previous page" title="Previous page"><ChevronLeft size={16} /></button>
        <span className="a4-pagecount">
          <input className="a4-pagefield" type="number" inputMode="numeric" min={1} max={total}
            value={page + 1} aria-label="Page number"
            onChange={event => goTo((event.target.valueAsNumber || 1) - 1)} />
          <span className="a4-of">of {total}</span>
        </span>
        <button type="button" className="a4-btn" onClick={() => goTo(page + 1)} disabled={page >= total - 1}
          aria-label="Next page" title="Next page"><ChevronRight size={16} /></button>
      </div>

      <div className="a4-group" aria-label="Zoom controls">
        <button type="button" className="a4-btn" onClick={() => zoomBy(-0.15)}
          aria-label="Zoom out" title="Zoom out"><ZoomOut size={16} /></button>
        <span className="a4-zoom" aria-live="polite" title="Zoom level">{zoomPercent}%</span>
        <button type="button" className="a4-btn" onClick={() => zoomBy(0.15)}
          aria-label="Zoom in" title="Zoom in"><ZoomIn size={16} /></button>
        <button type="button" className={`a4-btn ${fit === 'width' ? 'active' : ''}`}
          onClick={() => { setFit('width'); setZoom(1) }} aria-label="Fit to width"
          aria-pressed={fit === 'width'} title="Fit to width"><StretchHorizontal size={16} /></button>
        <button type="button" className={`a4-btn ${fit === 'page' ? 'active' : ''}`}
          onClick={() => setFit('page')} aria-label="Fit to page"
          aria-pressed={fit === 'page'} title="Fit to page"><Maximize2 size={16} /></button>
      </div>

      <div className="a4-group a4-actions">
        <button type="button" className="a4-btn" onClick={print} disabled={!canPrint}
          aria-label="Print document" title="Print"><Printer size={16} /></button>
        <button type="button" className="a4-btn" onClick={download} disabled={!canPrint}
          aria-label="Download document" title="Download"><Download size={16} /></button>
        {sourceUrl && <a className="a4-btn" href={sourceUrl} target="_blank" rel="noreferrer"
          aria-label="Open document in a new tab" title="Open in new tab"><ExternalLink size={16} /></a>}
      </div>
    </div>

    <div className="a4-stage" ref={stageRef} tabIndex={0}
      aria-label={`${fileName}, page ${page + 1} of ${total}`}>
      <div className="a4-canvas">
        {!sourceUrl && !loading && !error && <div className="a4-placeholder">
          <FileText size={26} />
          <span>Choose a document to see its print preview.</span>
        </div>}
        {sourceUrl && <div className="a4-page" style={{ width: pageWidth, height: pageWidth * ratio }}>
          {pdfUrl ? <iframe
            key={`${pdfUrl}-${page}`}
            src={`${pdfUrl}#page=${page + 1}&view=FitH&toolbar=0&navpanes=0&statusbar=0`}
            title={`${fileName} page ${page + 1}`} /> : imageUrl
            ? <img src={imageUrl} alt={`${fileName} page ${page + 1} preview`} draggable={false} />
            : <span className="a4-sheet-loading"><span className="spinner" /></span>}
          {loading && <div className="a4-loading" role="status">
            <span className="spinner" /><span>Rendering page…</span>
          </div>}
          {error && <div className="a4-loading a4-error" role="alert">
            <span>{error}</span>
            <button type="button" className="btn small" onClick={() => {
              cache.current.delete(page)
              setError('')
              setImageUrl('')
              setAttempt(value => value + 1)
            }}>Try again</button>
          </div>}
        </div>}
      </div>
    </div>
    <p className="a4-footnote">
      A4 {orientation === 'LANDSCAPE' ? 'landscape' : 'portrait'} · {total} {total === 1 ? 'page' : 'pages'}
      {' '}· Preview reflects the printable output.
    </p>
  </div>
}

export function DocumentPreviewDialog({
  title, subtitle, onClose, children,
}: {
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = previous
    }
  }, [onClose])

  return <div className="preview-dialog-backdrop" onMouseDown={event => {
    if (event.target === event.currentTarget) onClose()
  }}>
    <div className="preview-dialog" role="dialog" aria-modal="true" aria-label={title}>
      <header className="preview-dialog-head">
        <span className="preview-doc-icon"><FileText size={18} /></span>
        <div className="preview-dialog-title">
          <b>{title}</b>
          {subtitle && <small>{subtitle}</small>}
        </div>
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Close preview" title="Close">
          <X size={18} />
        </button>
      </header>
      <div className="preview-dialog-body">{children}</div>
    </div>
  </div>
}
