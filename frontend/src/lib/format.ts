export function money(amount: number | string, currency = 'BDT'): string {
  const value = Number(amount)
  return `${new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number.isFinite(value) ? value : 0)} ${currency}`
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString(undefined, {
    day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit',
  })
}

export function timeOnly(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

export function prettyStatus(status: string | null | undefined): string {
  if (!status) return 'Unknown'
  return status
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

export function statusTone(status: string): string {
  switch (status) {
    case 'PAYMENT_PENDING':
    case 'UPLOADED':
    case 'PROCESSING':
    case 'NEEDS_REVIEW':
    case 'PENDING':
      return 'waiting'
    case 'PAYMENT_APPROVED':
    case 'QUEUED':
      return 'approved'
    case 'PRINTING':
      return 'printing'
    case 'COMPLETED':
    case 'COLLECTED':
    case 'PAID':
      return 'completed'
    case 'PAYMENT_REJECTED':
    case 'REJECTED':
    case 'FAILED':
    case 'CANCELLED':
      return 'rejected'
    default:
      return ''
  }
}

export function statusLabel(status: string, label?: string): string {
  if (label) return label
  return prettyStatus(status)
}

export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

export function daysAgoIso(days: number): string {
  const date = new Date()
  date.setDate(date.getDate() - days)
  return date.toISOString().slice(0, 10)
}

export function fileNameWithoutExtension(name: string): string {
  return name.replace(/\.[^.]+$/, '')
}
