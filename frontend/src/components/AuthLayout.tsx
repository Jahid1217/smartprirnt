import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, CheckCircle2, Printer, QrCode, Wallet } from 'lucide-react'

/** Shared split layout for shop sign-in and registration. */
export default function AuthLayout({ eyebrow, title, description, children }: {
  eyebrow: string
  title: string
  description?: string
  children: ReactNode
}) {
  return <div className="auth-shell">
    <div className="auth-panel">
      <aside className="auth-aside">
        <Link to="/" className="brand brand-inverse">
          <span className="brand-mark">S</span>
          <span className="brand-text"><b>SmartPrint</b><small>Shop administration</small></span>
        </Link>
        <div className="auth-aside-copy">
          <span className="eyebrow">Self-service printing</span>
          <h2>Print jobs, payments and printers in one calm workspace.</h2>
          <ul className="auth-points">
            <li><QrCode size={16} /><span>Your own QR code and customer page</span></li>
            <li><Wallet size={16} /><span>Payment review before anything prints</span></li>
            <li><Printer size={16} /><span>Queue, printer routing and sales reports</span></li>
          </ul>
        </div>
        <p className="auth-aside-note"><CheckCircle2 size={14} /> No app install for you or your customers.</p>
      </aside>

      <main className="auth-main">
        <div className="auth-card">
          <Link to="/" className="auth-brand">
            <span className="brand-mark">S</span>
            <span className="brand-text"><b>SmartPrint</b><small>{eyebrow}</small></span>
          </Link>
          <span className="eyebrow">{eyebrow}</span>
          <h1>{title}</h1>
          {description && <p className="auth-subtitle">{description}</p>}
          {children}
          <Link to="/" className="auth-back"><ArrowLeft size={14} /> Back to SmartPrint</Link>
        </div>
      </main>
    </div>
  </div>
}
