import { AlertTriangle } from 'lucide-react'
import Modal from './Modal'

interface ConfirmDialogProps {
  title: string
  description: string
  confirmLabel: string
  cancelLabel?: string
  tone?: 'danger' | 'primary'
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/** Styled confirmation for actions that change or discard shop data. */
export default function ConfirmDialog({
  title, description, confirmLabel, cancelLabel = 'Cancel', tone = 'danger', busy = false,
  onConfirm, onCancel,
}: ConfirmDialogProps) {
  const danger = tone === 'danger'
  return <Modal
    title={title}
    onClose={onCancel}
    actions={<>
      <button type="button" className="btn" onClick={onCancel} disabled={busy}>{cancelLabel}</button>
      <button type="button" className={`btn ${danger ? 'danger-solid' : 'primary'}`}
        onClick={onConfirm} disabled={busy}>{busy ? 'Working…' : confirmLabel}</button>
    </>}
  >
    <div className="confirm-body">
      <span className={`confirm-icon ${danger ? 'danger' : ''}`}>
        <AlertTriangle size={20} />
      </span>
      <p>{description}</p>
    </div>
  </Modal>
}
