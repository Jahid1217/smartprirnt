import { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage, login, setAccessToken } from '../lib/api'

export default function AdminLoginPage() {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      const auth = await login(username.trim(), password)
      setAccessToken(auth.token)
      navigate('/admin', { replace: true })
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-card">
        <Link to="/" className="auth-brand">
          <span className="brand-mark">S</span>
          <span>
            <b style={{ display: 'block', fontFamily: 'Manrope', fontSize: 16 }}>SmartPrint</b>
            <small style={{ color: '#849087', fontSize: 11 }}>Shop administration</small>
          </span>
        </Link>
        <h1>Admin login</h1>
        <p>Sign in with the username and password created when the shop registered.</p>

        {error && <div className="alert error">{error}</div>}

        <form onSubmit={submit} className="stack">
          <div className="field">
            <label htmlFor="username">Username</label>
            <input id="username" autoComplete="username" required value={username}
              onChange={(event) => setUsername(event.target.value)} placeholder="Your admin username" />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" autoComplete="current-password" required
              value={password} onChange={(event) => setPassword(event.target.value)}
              placeholder="Your password" />
          </div>
          <button className="btn primary block lg" type="submit" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <div className="auth-foot">
          New shop? <Link to="/admin/register">Register your print shop</Link>
        </div>
        <div className="auth-back"><Link to="/">← Back to SmartPrint</Link></div>
      </div>
    </div>
  )
}
