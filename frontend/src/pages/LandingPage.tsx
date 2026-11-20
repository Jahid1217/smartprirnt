import { Link } from 'react-router-dom'
import {
  ArrowRight, BarChart3, CheckCircle2, FileText, Printer, QrCode, ShieldCheck, Smartphone, Wallet,
} from 'lucide-react'
import { getAccessToken } from '../lib/api'

const features = [
  {
    icon: QrCode,
    title: 'One QR per shop',
    body: 'A unique, downloadable code sends every customer to your shop page. No app, no account.',
  },
  {
    icon: FileText,
    title: 'Real page pricing',
    body: 'PDF, Word and image uploads are converted server-side, then priced by their actual page count.',
  },
  {
    icon: Printer,
    title: 'Agent printing',
    body: 'A small desktop agent prints approved files silently and reports each result back to the shop.',
  },
  {
    icon: Wallet,
    title: 'Shop-controlled payments',
    body: 'Set per-page rates, duplex pricing, cash or mobile wallets, and enabled print options.',
  },
  {
    icon: BarChart3,
    title: 'Live queue and reports',
    body: 'Follow every request from payment review to pickup, reorder jobs and review daily sales.',
  },
  {
    icon: ShieldCheck,
    title: 'Private by default',
    body: 'Documents stay in private storage and are deleted automatically after the retention period.',
  },
]

export default function LandingPage() {
  const signedIn = Boolean(getAccessToken())
  return (
    <div className="shell">
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand">
            <span className="brand-mark">S</span>
            <span className="brand-text">
              <b>SmartPrint</b>
              <small>Scan · Upload · Print</small>
            </span>
          </Link>
          <nav className="topnav" aria-label="Main">
            <a className="nav" href="#how">How it works</a>
            <a className="nav" href="#features">Features</a>
            <Link className="nav nav-login" to="/admin/login">Admin login</Link>
            <Link className="btn primary small" to={signedIn ? '/admin' : '/admin/register'}>
              {signedIn ? 'Dashboard' : 'Register shop'}
            </Link>
          </nav>
        </div>
      </header>

      <main className="landing">
        <section className="landing-hero">
          <div className="landing-copy-col">
            <span className="eyebrow"><span className="eyebrow-dot" />Self-service printing for local shops</span>
            <h1 className="landing-title">Skip the queue.<br /><em>Print in minutes.</em></h1>
            <p className="landing-copy">
              Customers scan your QR code, upload documents, check a realistic page preview and pay.
              Your print agent takes it from there — one approved job at a time, in payment order.
            </p>
            <div className="landing-actions">
              <Link className="btn primary lg" to={signedIn ? '/admin' : '/admin/register'}>
                {signedIn ? 'Open admin panel' : 'Register your print shop'}
                <ArrowRight size={16} />
              </Link>
              <Link className="btn lg" to="/admin/login">I already have an account</Link>
            </div>
            <ul className="landing-trust">
              <li><CheckCircle2 size={15} /> No app install</li>
              <li><CheckCircle2 size={15} /> Pay by wallet or cash</li>
              <li><CheckCircle2 size={15} /> Live order status</li>
            </ul>
          </div>

          <div className="landing-visual" aria-hidden="true">
            <div className="visual-window">
              <div className="visual-toolbar">
                <span className="visual-dots"><i /><i /><i /></span>
                <span className="visual-file"><FileText size={12} /> assignment.pdf</span>
                <span className="visual-zoom">100%</span>
              </div>
              <div className="visual-stage">
                <div className="visual-sheet">
                  <span className="vs-line vs-title" />
                  <span className="vs-line vs-sub" />
                  <span className="vs-line" />
                  <span className="vs-line w-90" />
                  <span className="vs-line w-75" />
                  <span className="vs-rule" />
                  <span className="vs-line vs-sub" />
                  <span className="vs-line w-85" />
                  <span className="vs-line w-60" />
                  <div className="vs-table">
                    <span /><span /><span />
                    <span /><span /><span />
                    <span /><span /><span />
                  </div>
                  <span className="vs-page">Page 1 of 3</span>
                </div>
                <div className="visual-card">
                  <span className="visual-card-icon"><CheckCircle2 size={15} /></span>
                  <div>
                    <b>Payment confirmed</b>
                    <small>Queue #12 · Printing</small>
                  </div>
                </div>
              </div>
            </div>
            <span className="visual-float"><Smartphone size={14} /> Scans from any phone</span>
          </div>
        </section>

        <section className="landing-steps" id="how">
          <div className="section-head">
            <div>
              <span className="eyebrow">How it works</span>
              <h2>From QR scan to printed pages</h2>
            </div>
            <p>A straightforward flow for customers, with staff in control of every payment.</p>
          </div>
          <div className="steps-row">
            {[
              ['1', 'Scan the shop QR', 'The shop page opens in any mobile or desktop browser.'],
              ['2', 'Upload & preview', 'Add files, choose color, sides, pages, orientation and copies.'],
              ['3', 'Pay & submit', 'Send money by bKash, Nagad or Rocket, or pay cash at the counter.'],
              ['4', 'Collect your prints', 'The shop confirms payment and your job joins the live print queue.'],
            ].map(([number, title, body]) => <div className="step-item" key={number}>
              <i>{number}</i>
              <b>{title}</b>
              <span>{body}</span>
            </div>)}
          </div>
        </section>

        <section className="landing-features" id="features">
          <div className="section-head">
            <div>
              <span className="eyebrow">Built for print shops</span>
              <h2>Everything the counter needs</h2>
            </div>
            <p>One workspace for requests, payments, queue, printers and daily reporting.</p>
          </div>
          <div className="feature-grid">
            {features.map(({ icon: Icon, title, body }) => <article className="feature-card" key={title}>
              <span className="feature-icon"><Icon size={18} /></span>
              <h3>{title}</h3>
              <p>{body}</p>
            </article>)}
          </div>
        </section>

        <section className="cta-band">
          <div>
            <span className="eyebrow">Ready in minutes</span>
            <h2>Put your print shop on the QR</h2>
            <p>Create your shop, set your prices, download the QR and start accepting orders today.</p>
          </div>
          <div className="cta-actions">
            <Link className="btn light lg" to={signedIn ? '/admin' : '/admin/register'}>
              {signedIn ? 'Open dashboard' : 'Create your shop'}
            </Link>
            <Link className="btn ghost-light lg" to="/admin/login">Sign in</Link>
          </div>
        </section>
      </main>

      <footer className="footer">
        <div className="footer-inner">
          <span>© {new Date().getFullYear()} SmartPrint — multi-shop QR printing platform.</span>
          <div className="footer-links">
            <a href="#how">How it works</a>
            <a href="#features">Features</a>
            <Link to="/admin/register">List your shop</Link>
          </div>
        </div>
      </footer>
    </div>
  )
}
