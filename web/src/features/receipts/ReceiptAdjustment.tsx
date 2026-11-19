import { useQueryClient } from '@tanstack/react-query'
import { Button } from 'antd'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError, errorText, MAX_QUANTITY, request, type Session } from '../../api'
import { Failure } from '../../ui'
import { useCommandKey } from './ReceiptIdentity'
import { receiptPath, type ReceiptRecord } from './reviewModel'

export function ReceiptAdjustment({ orderId, receiptId, line, session, close }: {
  orderId: string
  receiptId: number
  line: ReceiptRecord['lines'][number]
  session: Session
  close: () => void
}) {
  const cache = useQueryClient()
  const pending = useRef(false)
  const [quantity, setQuantity] = useState(String(line.effectiveAcceptedQty))
  const [reason, setReason] = useState('')
  const [revision, setRevision] = useState(line.revision)
  const [failure, setFailure] = useState('')
  const [busy, setBusy] = useState(false)
  const keyFor = useCommandKey()
  const projected = line.remainingQty + line.effectiveAcceptedQty - Number(quantity)
  const stale = revision !== line.revision

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending.current || stale) return
    const newAcceptedQty = Number(quantity)
    if (!quantity || !Number.isInteger(newAcceptedQty) || newAcceptedQty < 0 || newAcceptedQty > MAX_QUANTITY || !reason.trim()) {
      setFailure('请输入非负整数实收数及更正原因')
      return
    }
    pending.current = true
    setBusy(true)
    setFailure('')
    const body = { newAcceptedQty, reason: reason.trim(), expectedRevision: revision }
    try {
      await request(`${receiptPath(orderId, receiptId)}/lines/${line.receiptLineId}/adjustments`, body, session, undefined, keyFor(body))
      await cache.invalidateQueries({ queryKey: ['orders'] })
      close()
    } catch (cause) {
      setFailure(errorText(cause))
      if (cause instanceof ApiError && cause.status === 409) {
        await cache.invalidateQueries({ queryKey: ['orders', 'receipt', orderId, String(receiptId)] })
      }
    } finally {
      pending.current = false
      setBusy(false)
    }
  }

  return <form className="form-stack line-editor receipt-adjustment" onSubmit={submit}>
    <h3>更正 {line.itemCode} {line.itemName} 实收</h3>
    <p>原确认 {line.originalAcceptedQty} {line.unit} · 当前有效 {line.effectiveAcceptedQty} {line.unit}
      {' · '}修订 {line.revision}</p>
    {failure && <Failure message={failure} />}
    {stale && <div className="action-group">
      <span>明细已有更新，输入已保留。</span>
      <Button disabled={busy} onClick={() => { setRevision(line.revision); setFailure('') }}>使用最新版本继续</Button>
    </div>}
    <fieldset className="review-fields receipt-note-fields" disabled={busy}>
      <label>新实收数<input type="number" min={0} max={MAX_QUANTITY} step={1} required
        value={quantity} onChange={event => setQuantity(event.target.value)} /></label>
      <label>更正原因<input required maxLength={500} value={reason}
        onChange={event => setReason(event.target.value)} /></label>
    </fieldset>
    {quantity !== '' && <p className={projected < 0 ? 'review-warning' : 'muted'}>
      {projected < 0 ? `将超收 ${-projected}` : `预计待收 ${projected}`} {line.unit}
    </p>}
    <div className="action-group">
      <Button disabled={busy} onClick={close}>取消更正</Button>
      <Button type="primary" htmlType="submit" loading={busy} disabled={stale}>提交更正</Button>
    </div>
  </form>
}
