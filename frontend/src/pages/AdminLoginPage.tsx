import { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AlertCircle, Loader2, Lock, User } from 'lucide-react'
import { errorMessage, login, setAccessToken } from '../lib/api'
import AuthLayout from '../components/AuthLayout'

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

  return <AuthLayout
    eyebrow="Shop administration"
    title="Welcome back"
    description="Sign in with the username and password created when your shop registered."
  >
    {error && <div className="alert error" role="alert"><AlertCircle size={15} />{error}</div>}

    <form onSubmit={submit} className="stack">
      <label className="field">
        <span className="field-label">Username</span>
        <span className="input-wrap">
          <User size={16} aria-hidden="true" />
          <input id="username" autoComplete="username" required value={username}
            onChange={event => setUsername(event.target.value)} placeholder="Your admin username" />
        </span>
      </label>
      <label className="field">
        <span className="field-label">Password</span>
        <span className="input-wrap">
          <Lock size={16} aria-hidden="true" />
          <input id="password" type="password" autoComplete="current-password" required
            value={password} onChange={event => setPassword(event.target.value)}
            placeholder="Your password" />
        </span>
      </label>
      <button className="btn primary block lg" type="submit" disabled={busy}>
        {busy ? <><Loader2 size={16} className="spin" />Signing in…</> : 'Sign in'}
      </button>
    </form>

    <div className="auth-foot">
      New shop? <Link to="/admin/register">Register your print shop</Link>
    </div>
  </AuthLayout>
}
