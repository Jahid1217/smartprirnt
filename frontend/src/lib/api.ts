import axios, { AxiosError } from 'axios'

export interface ApiErrorResponse {
  timestamp?: string
  status?: number
  error?: string
  message?: string
  fieldErrors?: { field: string; message: string }[]
}

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '/api/v1').replace(/\/+$/, '')

export const api = axios.create({
  baseURL: API_BASE,
  timeout: 120_000,
  // No default Content-Type: Axios serializes plain objects to JSON itself, while a
  // preset application/json would make it convert FormData uploads to JSON too.
})

const TOKEN_STORAGE_KEY = 'smartprint.accessToken'

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY)
}

export function setAccessToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_STORAGE_KEY, token)
  else localStorage.removeItem(TOKEN_STORAGE_KEY)
}

api.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: { field: string; message: string }[]

  constructor(message: string, status: number, code: string,
              fieldErrors: { field: string; message: string }[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof AxiosError) {
    const body = error.response?.data as ApiErrorResponse | undefined
    return new ApiError(
      body?.message ?? error.message ?? 'Request failed',
      error.response?.status ?? 0,
      body?.error ?? 'NETWORK_ERROR',
      body?.fieldErrors ?? [],
    )
  }
  return new ApiError(error instanceof Error ? error.message : 'Unexpected error', 0, 'UNKNOWN')
}

export function errorMessage(cause: unknown): string {
  const error = toApiError(cause)
  const fields = error.fieldErrors.map((field) => field.message).join(' ')
  return fields ? `${error.message}: ${fields}` : error.message
}

/** Resolves a backend-returned path while respecting an absolute API base URL. */
export function assetUrl(path: string | null | undefined): string | null {
  if (!path) return null
  if (/^https?:\/\//i.test(path)) return path
  const origin = /^https?:\/\//i.test(API_BASE) ? new URL(API_BASE).origin : ''
  return origin + path
}

export function customerEventsUrl(publicToken: string): string {
  return API_BASE + `/public/orders/${encodeURIComponent(publicToken)}/events`
}

// ------------------------------------------------------------------ auth

export interface AuthResponse {
  token: string
  shopCode: string
  shopName: string
  qrToken: string
  adminId: number
  adminName: string
  username: string
}

export interface RegisterShopInput {
  shopName: string
  ownerName: string
  phone: string
  email: string
  address: string
  username: string
  password: string
}

export async function registerShop(input: RegisterShopInput): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/auth/register-shop', input)
  return data
}

export async function login(username: string, password: string): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/auth/login', { username, password })
  return data
}

export async function fetchMe(): Promise<AuthResponse> {
  const { data } = await api.get<AuthResponse>('/auth/me')
  return data
}

// ------------------------------------------------------------------ public shop

export type PaymentMethodCode = 'CASH' | 'BKASH' | 'NAGAD' | 'ROCKET'
export type ColorMode = 'BW' | 'COLOR'
export type SideMode = 'SIMPLEX' | 'DUPLEX'
export type Orientation = 'PORTRAIT' | 'LANDSCAPE'

export interface PublicShop {
  qrToken: string
  shopCode: string
  shopName: string
  address: string | null
  phone: string | null
  currency: string
  timezone: string
  publicUrl: string
  acceptingOrders: boolean
  prices: { bwPerPage: number; colorPerPage: number; duplexPercent: number }
  features: {
    color: boolean
    duplex: boolean
    landscape: boolean
    pageSelection: boolean
    pdf: boolean
    word: boolean
    image: boolean
  }
  paymentMethods: {
    code: PaymentMethodCode
    label: string
    walletNumber: string | null
    requiresTransactionId: boolean
  }[]
  maxFiles: number
  maxFileSizeMb: number
  fileRetentionHours: number
}

export async function fetchPublicShop(qrToken: string): Promise<PublicShop> {
  const { data } = await api.get<PublicShop>(`/public/shops/${encodeURIComponent(qrToken)}`)
  return data
}

// ------------------------------------------------------------------ orders

export interface PrintSettingsInput {
  colorMode: ColorMode
  sideMode: SideMode
  copies: number
  orientation: Orientation
  pageSelection: string
}

export interface QuoteFile {
  name: string
  kind: string
  pages: number
  selectedPages: number
  pageSelection: string
}

export interface Quote {
  files: QuoteFile[]
  totalPages: number
  totalSelectedPages: number
  totalAmount: number
  currency: string
  prices: { bwPerPage: number; colorPerPage: number; duplexPercent: number }
}

export interface OrderFileView {
  id: number
  name: string
  kind: string
  mimeType: string
  pages: number
  selectedPages: number
  colorMode: ColorMode
  sideMode: SideMode
  orientation: Orientation
  pageSelection: string
  copies: number
  previewUrl: string | null
}

export interface OrderPaymentView {
  id: number
  method: PaymentMethodCode
  status: string
  provider: string | null
  amount: number
  transactionId: string | null
  rejectionReason: string | null
  instructions: string
}

export interface OrderQueueView {
  entryId: number
  queueNumber: number
  queueDate: string
  state: string
  position: number | null
  ahead: number | null
}

export interface PublicOrder {
  orderNumber: string
  publicToken: string
  status: string
  statusLabel: string
  totalAmount: number
  currency: string
  createdAt: string
  updatedAt: string
  files: OrderFileView[]
  payment: OrderPaymentView
  queue: OrderQueueView | null
  shop: { name: string; phone: string | null; address: string | null; currency: string }
}

export interface AdminOrder extends Omit<PublicOrder, 'shop'> {
  id: number
  customerName: string | null
  customerPhone: string | null
  purgeAt: string | null
  printFailure: string | null
}

function orderForm(files: File[], settings: PrintSettingsInput): FormData {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  form.append('colorMode', settings.colorMode)
  form.append('sideMode', settings.sideMode)
  form.append('copies', String(settings.copies))
  form.append('orientation', settings.orientation)
  form.append('pageSelection', settings.pageSelection.trim() || 'ALL')
  return form
}

export async function quoteOrder(qrToken: string, files: File[],
                                  settings: PrintSettingsInput, signal?: AbortSignal): Promise<Quote> {
  const form = orderForm(files, settings)
  const { data } = await api.post<Quote>(
    `/public/shops/${encodeURIComponent(qrToken)}/quote`, form, { timeout: 180_000, signal })
  return data
}

export async function draftPreview(qrToken: string, file: File, settings: PrintSettingsInput,
                                   page: number, signal: AbortSignal): Promise<Blob> {
  const form = orderForm([], settings)
  form.append('file', file)
  form.append('previewIndex', String(page))
  const { data } = await api.post<Blob>(
    `/public/shops/${encodeURIComponent(qrToken)}/preview`, form,
    { responseType: 'blob', signal, timeout: 180_000 },
  )
  return data
}

export async function createOrder(qrToken: string, files: File[], settings: PrintSettingsInput,
                                  paymentMethod: PaymentMethodCode,
                                  customerName?: string,
                                  customerPhone?: string,
                                  transactionId?: string, expectedAmount?: number,
                                  requestKey?: string): Promise<PublicOrder> {
  const form = orderForm(files, settings)
  form.append('paymentMethod', paymentMethod)
  if (expectedAmount !== undefined) form.append('expectedAmount', String(expectedAmount))
  if (requestKey) form.append('requestKey', requestKey)
  if (transactionId?.trim()) form.append('transactionId', transactionId.trim())
  if (customerName?.trim()) form.append('customerName', customerName.trim())
  if (customerPhone?.trim()) form.append('customerPhone', customerPhone.trim())
  const { data } = await api.post<PublicOrder>(
    `/public/shops/${encodeURIComponent(qrToken)}/orders`, form, { timeout: 180_000 })
  return data
}

export async function fetchOrder(publicToken: string): Promise<PublicOrder> {
  const { data } = await api.get<PublicOrder>(`/public/orders/${encodeURIComponent(publicToken)}`)
  return data
}

export async function submitPaymentReference(publicToken: string,
                                             transactionId: string): Promise<PublicOrder> {
  const { data } = await api.post<PublicOrder>(
    `/public/orders/${encodeURIComponent(publicToken)}/payments/reference`,
    { transactionId })
  return data
}

// ------------------------------------------------------------------ admin shop & settings

export interface AdminShop {
  id: number
  shopCode: string
  name: string
  ownerName: string | null
  phone: string | null
  email: string | null
  address: string | null
  currency: string
  timezone: string
  qrToken: string
  publicUrl: string
  qrImageUrl: string
}

export interface Settings {
  bwPerPage: number
  colorPerPage: number
  duplexPercent: number
  colorEnabled: boolean
  duplexEnabled: boolean
  landscapeEnabled: boolean
  pageSelectionEnabled: boolean
  pdfEnabled: boolean
  wordEnabled: boolean
  imageEnabled: boolean
  cashEnabled: boolean
  bkashEnabled: boolean
  nagadEnabled: boolean
  rocketEnabled: boolean
  bkashNumber: string
  nagadNumber: string
  rocketNumber: string
  fileRetentionHours: number
  queuePaused: boolean
  defaultPrinterId: number | null
  colorPrinterId: number | null
  bwPrinterId: number | null
}

export async function fetchAdminShop(): Promise<AdminShop> {
  const { data } = await api.get<AdminShop>('/admin/shop')
  return data
}

export async function updateAdminShop(input: {
  name: string; ownerName: string; phone: string; email: string; address: string
}): Promise<AdminShop> {
  const { data } = await api.put<AdminShop>('/admin/shop', input)
  return data
}

export async function regenerateQr(): Promise<AdminShop> {
  const { data } = await api.post<AdminShop>('/admin/shop/qr/regenerate')
  return data
}

export function publicQrUrl(qrToken: string): string {
  return assetUrl(`/api/v1/public/shops/${encodeURIComponent(qrToken)}/qr.png`) ?? ''
}

export async function fetchSettings(): Promise<Settings> {
  const { data } = await api.get<Settings>('/admin/settings')
  return data
}

export async function saveSettings(settings: Settings): Promise<Settings> {
  const { data } = await api.put<Settings>('/admin/settings', settings)
  return data
}

// ------------------------------------------------------------------ admin orders & payments

export async function fetchAdminOrders(status?: string, page = 0, limit = 25): Promise<AdminOrder[]> {
  const { data } = await api.get<AdminOrder[]>('/admin/orders', {
    params: { page, limit, ...(status ? { status } : {}) },
  })
  return data
}

export interface AdminPayment {
  id: number
  orderNumber: string
  method: PaymentMethodCode
  status: string
  amount: number
  currency: string
  transactionId: string | null
  createdAt: string
}

export interface QueueItem {
  entryId: number
  queueNumber: number
  queueDate: string
  state: string
  orderId: number
  orderNumber: string
  customerName: string | null
  totalAmount: number
  currency: string
  fileCount: number
  selectedPrinterId: number | null
  approvedAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface AdminDashboard {
  shopName: string
  currency: string
  todaysOrders: number
  awaitingVerification: number
  queueToday: number
  printingNow: number
  waiting: number
  queuePaused: boolean
  todaysRevenue: number
  recentOrders: {
    orderNumber: string
    status: string
    totalAmount: number
    currency: string
    queueNumber: number | null
    createdAt: string
  }[]
  pendingPayments: AdminPayment[]
  activeQueue: QueueItem[]
}

export async function fetchAdminDashboard(): Promise<AdminDashboard> {
  const { data } = await api.get<AdminDashboard>('/admin/dashboard')
  return data
}

export async function approvePayment(paymentId: number) {
  const { data } = await api.post(`/admin/payments/${paymentId}/approve`)
  return data
}

export async function rejectPayment(paymentId: number, reason?: string) {
  const { data } = await api.post(`/admin/payments/${paymentId}/reject`,
    reason ? { reason } : {})
  return data
}

// ------------------------------------------------------------------ queue

export interface QueueSummary { paused: boolean; active: number; printing: number; waiting: number }

export async function fetchQueue(): Promise<QueueItem[]> {
  const { data } = await api.get<QueueItem[]>('/admin/queue')
  return data
}

export async function setQueuePaused(paused: boolean): Promise<QueueSummary> {
  const { data } = await api.post<QueueSummary>('/admin/queue/pause', { paused })
  return data
}

export async function selectQueuePrinter(entryId: number, printerId: number | null) {
  await api.patch('/admin/queue/' + entryId + '/printer', { printerId })
}

export async function moveQueueEntry(entryId: number, direction: -1 | 1): Promise<void> {
  await api.post(`/admin/queue/${entryId}/move`, { direction })
}

export async function cancelQueueEntry(entryId: number): Promise<void> {
  await api.post(`/admin/queue/${entryId}/cancel`)
}

export async function retryQueueEntry(entryId: number): Promise<void> {
  await api.post(`/admin/queue/${entryId}/retry`)
}

export async function completeQueueEntry(entryId: number): Promise<void> {
  await api.post(`/admin/queue/${entryId}/complete`)
}

// ------------------------------------------------------------------ printers & agents

export interface Printer {
  id: number
  code: string
  name: string
  connectionType: string
  uri: string
  status: string
  enabled: boolean
  colorSupported: boolean
  duplexSupported: boolean
  lastSeenAt: string | null
  agentId: number | null
  agentDefault: boolean
  virtualPrinter: boolean
  deviceGroup: string | null
  model: string | null
  portName: string | null
  availability: 'FREE' | 'BUSY' | 'OFFLINE' | 'ERROR' | 'PAUSED'
  pendingJobs: number
  activeJobs: number
  activeOrders: string[]
}

export interface Agent {
  id: number
  agentCode: string
  name: string
  status: string
  lastSeenAt: string | null
  printerCount: number
  discoveryError: string | null
  platform: string
  createdAt: string
}

export async function fetchPrinters(): Promise<Printer[]> {
  const { data } = await api.get<Printer[]>('/admin/printers')
  return data
}

export async function setPrinterEnabled(printerId: number, enabled: boolean): Promise<Printer> {
  const { data } = await api.patch<Printer>(`/admin/printers/${printerId}`, { enabled })
  return data
}

export async function fetchAgents(): Promise<Agent[]> {
  const { data } = await api.get<Agent[]>('/admin/agents')
  return data
}

export async function createAgent(name: string): Promise<{
  agentCode: string; agentToken: string; name: string
}> {
  const { data } = await api.post('/admin/agents', { name })
  return data
}

export async function issueDevicePairing(name: string) {
  return (await api.post<{ id: string; code: string; expiresAt: string }>('/admin/agents/pairing', { name })).data
}

export async function fetchDevicePairing(id: string, signal: AbortSignal) {
  return (await api.get<{ agentId: number | null; expiresAt: string }>(`/admin/agents/pairing/${id}`, { signal })).data
}

export async function revokeDevice(id: number) {
  await api.delete(`/admin/agents/${id}`)
}

// ------------------------------------------------------------------ reports

export interface SalesReport {
  from: string
  to: string
  currency: string
  orders: number
  completed: number
  cancelled: number
  revenue: number
}

export async function fetchSalesReport(from: string, to: string): Promise<SalesReport> {
  const { data } = await api.get<SalesReport>('/admin/reports/sales', { params: { from, to } })
  return data
}

/** Fetches a protected or public asset path as a blob, resolving the API origin. */
export async function fetchAssetBlob(path: string): Promise<Blob> {
  const { data } = await api.get<Blob>(assetUrl(path) ?? path, { baseURL: '/', responseType: 'blob' })
  return data
}

/** Opens an admin-only asset (PDF preview, QR) that needs the bearer header. */
export async function openProtectedAsset(path: string): Promise<void> {
  const { data } = await api.get<Blob>(assetUrl(path) ?? path, { responseType: 'blob', baseURL: '/' })
  const url = URL.createObjectURL(data)
  window.open(url, '_blank', 'noopener')
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export async function downloadProtectedAsset(path: string, filename: string): Promise<void> {
  const { data } = await api.get<Blob>(assetUrl(path) ?? path, { responseType: 'blob', baseURL: '/' })
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

export async function fetchHealth(): Promise<unknown> {
  return (await api.get('/health')).data
}

export type PrinterRouting = Pick<Settings, 'defaultPrinterId' | 'colorPrinterId' | 'bwPrinterId'>

export async function fetchPrinterDiscovery(signal: AbortSignal) {
  const { data } = await api.get<{ agents: Agent[]; printers: Printer[]; routing: PrinterRouting; queuedJobs: number; queuePaused: boolean }>(
    '/admin/printers/discovery', { signal, timeout: 8000 },
  )
  if (!data || !Array.isArray(data.agents) || !Array.isArray(data.printers) || !data.routing) {
    throw new Error('The printer discovery endpoint returned an unexpected response. Check the API URL and backend version.')
  }
  return data
}

export async function savePrinterRouting(routing: PrinterRouting) {
  return (await api.patch<PrinterRouting>('/admin/printers/routing', routing)).data
}


export interface PrintAttempt {
  spoolerStatus: string | null
  spoolerReference: string | null
  fileId: number
  fileName: string
  attemptNumber: number
  printerName: string | null
  startedAt: string | null
  finishedAt: string | null
  status: string
  failureReason: string | null
}

export async function fetchPrintAttempts(orderNumber: string) {
  return (await api.get<PrintAttempt[]>(`/admin/orders/${encodeURIComponent(orderNumber)}/print-attempts`)).data
}
