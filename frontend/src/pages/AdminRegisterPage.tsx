import { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage, registerShop, setAccessToken, toApiError } from '../lib/api'

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
    setForm((current) => ({ ...current, [key]: value }))
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

  return (
    <div className="auth-shell">
      <div className="auth-card wide">
        <Link to="/" className="auth-brand">
          <span className="brand-mark">S</span>
          <span>
            <b style={{ display: 'block', fontFamily: 'Manrope', fontSize: 16 }}>SmartPrint</b>
            <small style={{ color: '#849087', fontSize: 11 }}>Shop registration</small>
          </span>
        </Link>
        <h1>Register your print shop</h1>
        <p>
          Your shop gets a unique QR code immediately. Print it and place it on the counter —
          customers scan it to send print jobs without installing anything.
        </p>

        {error && (
          <div className="alert error">
            {error}
            {fieldErrors.length > 0 && (
              <ul>
                {fieldErrors.map((field) => (
                  <li key={field.field}><b>{field.field}</b>: {field.message}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        <form onSubmit={submit}>
          <div className="form-grid">
            <div className="field">
              <label htmlFor="shopName">Shop name</label>
              <input id="shopName" required maxLength={150} value={form.shopName}
                onChange={(event) => update('shopName', event.target.value)} placeholder="City Digital Print" />
            </div>
            <div className="field">
              <label htmlFor="ownerName">Owner name</label>
              <input id="ownerName" required maxLength={150} value={form.ownerName}
                onChange={(event) => update('ownerName', event.target.value)} placeholder="Full name" />
            </div>
            <div className="field">
              <label htmlFor="phone">Phone</label>
              <input id="phone" required maxLength={30} value={form.phone}
                onChange={(event) => update('phone', event.target.value)} placeholder="01712 345678" />
            </div>
            <div className="field">
              <label htmlFor="email">Email</label>
              <input id="email" type="email" required maxLength={150} value={form.email}
                onChange={(event) => update('email', event.target.value)} placeholder="owner@example.com" />
            </div>
            <div className="field full">
              <label htmlFor="address">Shop address</label>
              <input id="address" maxLength={400} value={form.address}
                onChange={(event) => update('address', event.target.value)}
                placeholder="Shop number, market, city" />
            </div>
            <div className="field">
              <label htmlFor="username">Admin username</label>
              <input id="username" required minLength={3} maxLength={60} autoComplete="username"
                value={form.username} onChange={(event) => update('username', event.target.value)}
                placeholder="At least 3 characters" />
            </div>
            <div className="field">
              <label htmlFor="password">Password</label>
              <input id="password" type="password" required minLength={8} maxLength={72}
                autoComplete="new-password" value={form.password}
                onChange={(event) => update('password', event.target.value)}
                placeholder="At least 8 characters" />
            </div>
          </div>

          <div className="form-actions">
            <button className="btn primary lg grow" type="submit" disabled={busy}>
              {busy ? 'Creating shop…' : 'Create shop & get QR code'}
            </button>
          </div>
        </form>

        <div className="auth-foot">
          Already registered? <Link to="/admin/login">Sign in</Link>
        </div>
        <div className="auth-back"><Link to="/">← Back to SmartPrint</Link></div>
      </div>
    </div>
  )
}
