import { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AlertCircle, Loader2 } from 'lucide-react'
import { registerShop, setAccessToken, toApiError } from '../lib/api'
import AuthLayout from '../components/AuthLayout'

export default function AdminRegisterPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({
    shopName: '', ownerName: '', phone: '', email: '', address: '',
    username: '', password: '',
  })
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ field: string; message: string }[]>([])
  const [busy, setBusy] = useState(false)

  function update(key: keyof typeof form, value: string) {
    setForm(current => ({ ...current, [key]: value }))
  }

  function fieldError(field: string) {
    return fieldErrors.find(item => item.field === field)?.message
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setFieldErrors([])
    setBusy(true)
    try {
      const auth = await registerShop({
        ...form,
        shopName: form.shopName.trim(),
        ownerName: form.ownerName.trim(),
        phone: form.phone.trim(),
        email: form.email.trim(),
        username: form.username.trim(),
      })
      setAccessToken(auth.token)
      navigate('/admin', { replace: true })
    } catch (cause) {
      const apiError = toApiError(cause)
      setError(apiError.message)
      setFieldErrors(apiError.fieldErrors)
    } finally {
      setBusy(false)
    }
  }

  function errorClass(field: string) {
    return fieldError(field) ? 'has-error' : undefined
  }

  return <AuthLayout
    eyebrow="Shop registration"
    title="Register your print shop"
    description="Your shop gets a unique QR code immediately. Print it and place it on the counter — customers scan it to send print jobs without installing anything."
  >
    {error && <div className="alert error" role="alert">
      <AlertCircle size={15} />
      <div>
        <b>{error}</b>
        {fieldErrors.length > 0 && <ul>
          {fieldErrors.map(field => <li key={field.field}><b>{field.field}</b>: {field.message}</li>)}
        </ul>}
      </div>
    </div>}

    <form onSubmit={submit} className="stack">
      <div className="form-section">
        <span className="form-section-title">Shop details</span>
        <div className="form-grid">
          <div className={`field ${errorClass('shopName') ?? ''}`}>
            <label htmlFor="shopName">Shop name</label>
            <input id="shopName" required maxLength={150} value={form.shopName}
              onChange={event => update('shopName', event.target.value)} placeholder="City Digital Print" />
            {fieldError('shopName') && <span className="field-error">{fieldError('shopName')}</span>}
          </div>
          <div className={`field ${errorClass('ownerName') ?? ''}`}>
            <label htmlFor="ownerName">Owner name</label>
            <input id="ownerName" required maxLength={150} value={form.ownerName}
              onChange={event => update('ownerName', event.target.value)} placeholder="Full name" />
            {fieldError('ownerName') && <span className="field-error">{fieldError('ownerName')}</span>}
          </div>
          <div className={`field ${errorClass('phone') ?? ''}`}>
            <label htmlFor="phone">Phone</label>
            <input id="phone" type="tel" required maxLength={30} value={form.phone}
              onChange={event => update('phone', event.target.value)} placeholder="01712 345678" />
            {fieldError('phone') && <span className="field-error">{fieldError('phone')}</span>}
          </div>
          <div className={`field ${errorClass('email') ?? ''}`}>
            <label htmlFor="email">Email</label>
            <input id="email" type="email" required maxLength={150} value={form.email}
              onChange={event => update('email', event.target.value)} placeholder="owner@example.com" />
            {fieldError('email') && <span className="field-error">{fieldError('email')}</span>}
          </div>
          <div className="field full">
            <label htmlFor="address">Shop address</label>
            <input id="address" maxLength={400} value={form.address}
              onChange={event => update('address', event.target.value)}
              placeholder="Shop number, market, city" />
            <span className="hint">Shown to customers on your shop page.</span>
          </div>
        </div>
      </div>

      <div className="form-section">
        <span className="form-section-title">Admin account</span>
        <div className="form-grid">
          <div className={`field ${errorClass('username') ?? ''}`}>
            <label htmlFor="username">Admin username</label>
            <input id="username" required minLength={3} maxLength={60} autoComplete="username"
              value={form.username} onChange={event => update('username', event.target.value)}
              placeholder="At least 3 characters" />
            {fieldError('username') && <span className="field-error">{fieldError('username')}</span>}
          </div>
          <div className={`field ${errorClass('password') ?? ''}`}>
            <label htmlFor="password">Password</label>
            <input id="password" type="password" required minLength={8} maxLength={72}
              autoComplete="new-password" value={form.password}
              onChange={event => update('password', event.target.value)}
              placeholder="At least 8 characters" />
            {fieldError('password') && <span className="field-error">{fieldError('password')}</span>}
          </div>
        </div>
      </div>

      <button className="btn primary block lg" type="submit" disabled={busy}>
        {busy ? <><Loader2 size={16} className="spin" />Creating shop…</> : 'Create shop & get QR code'}
      </button>
    </form>

    <div className="auth-foot">
      Already registered? <Link to="/admin/login">Sign in</Link>
    </div>
  </AuthLayout>
}
