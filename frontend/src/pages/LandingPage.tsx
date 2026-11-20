import { Link } from 'react-router-dom'
import { getAccessToken } from '../lib/api'

export default function LandingPage() {
  const signedIn = Boolean(getAccessToken())
  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          <span className="brand-mark">S</span>
          <span>
            <b>SmartPrint</b>
            <small>Scan · Upload · Print</small>
          </span>
        </Link>
        <nav>
          <a className="nav" href="#how">How it works</a>
          <Link className="nav" to="/admin/login">Admin login</Link>
          <Link className="nav active" to={signedIn ? '/admin' : '/admin/register'}>
            {signedIn ? 'Dashboard' : 'Register shop'}
          </Link>
        </nav>
      </header>

      <main className="landing">
        <section className="landing-hero">
          <div>
            <span className="eyebrow">Self-service printing</span>
            <h1 className="landing-title">Skip the queue.<br /><em>Print in minutes.</em></h1>
            <p className="landing-copy">
              Customers scan a shop's QR code, upload their documents, choose print settings,
              pay, and collect. The shop's print agent does the rest — one job at a time,
              in payment order.
            </p>
            <div className="landing-actions">
              <Link className="btn primary lg" to={signedIn ? '/admin' : '/admin/register'}>
                {signedIn ? 'Open admin panel' : 'Register your print shop'}
              </Link>
              <Link className="btn lg" to="/admin/login">I already have an account</Link>
            </div>
          </div>

          <div className="landing-grid">
            <div className="landing-card">
              <div className="landing-card-icon">▣</div>
              <h3>One QR per shop</h3>
              <p>A unique, downloadable QR code routes every visitor to that shop's upload page.</p>
            </div>
            <div className="landing-card">
              <div className="landing-card-icon">⌁</div>
              <h3>Live status</h3>
              <p>Customers watch payment approval, queue position, printing and pickup in real time.</p>
            </div>
            <div className="landing-card">
              <div className="landing-card-icon">⌘</div>
              <h3>Agent printing</h3>
              <p>A small desktop agent prints approved PDFs silently and reports success or failure.</p>
            </div>
            <div className="landing-card">
              <div className="landing-card-icon">৳</div>
              <h3>Shop-controlled pricing</h3>
              <p>Owners set per-page rates, duplex surcharge, payment methods and enabled options.</p>
            </div>
            <div className="landing-card wide">
              <div className="inline" style={{ justifyContent: 'space-between' }}>
                <div>
                  <h3>No app install for customers</h3>
                  <p>PDF, Word and image files are converted server-side, priced by real page count, and purged after printing.</p>
                </div>
                <span className="badge completed">Privacy first</span>
              </div>
            </div>
          </div>
        </section>

        <section className="landing-steps" id="how">
          <span className="eyebrow">How it works</span>
          <div className="steps-row" style={{ marginTop: 14 }}>
            <div className="step-item">
              <i>1</i>
              <b>Scan the shop QR</b>
              <span>Your shop page opens in any mobile or desktop browser.</span>
            </div>
            <div className="step-item">
              <i>2</i>
              <b>Upload & configure</b>
              <span>Add files, choose colour, sides, pages, orientation and copies.</span>
            </div>
            <div className="step-item">
              <i>3</i>
              <b>Pay & submit</b>
              <span>Send money by bKash, Nagad or Rocket, or pay cash at the counter.</span>
            </div>
            <div className="step-item">
              <i>4</i>
              <b>Watch it print</b>
              <span>After payment confirmation your job joins the live queue.</span>
            </div>
          </div>
        </section>
      </main>

      <footer className="footer">
        SmartPrint — multi-shop QR printing platform. <Link to="/admin/register">List your shop</Link>
      </footer>
    </div>
  )
}
