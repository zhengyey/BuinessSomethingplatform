import { useQueryClient } from '@tanstack/react-query'
import { Button } from 'antd'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError, errorText, localDate, MAX_QUANTITY, request, type Session } from '../../api'
import { Failure } from '../../ui'
import { orderDetailsKey, type OrderLine } from '../orders/model'
import { DuplicateCandidates, NoteFields, duplicateMatch, lookup, useCommandKey,
  type DuplicateMatch, type NoteIdentity } from './ReceiptIdentity'

type Entry = { id: number; lineId: string; delivered: string; accepted: string; note: string }

function emptyEntry(id: number): Entry {
  return { id, lineId: '', delivered: '', accepted: '', note: '' }
}

export function ReceiptForm({ orderId, lines, session, close }: {
  orderId: string
  lines: OrderLine[]
  session: Session
  close: () => void
}) {
  const client = useQueryClient()
  const nextId = useRef(2)
  const pending = useRef(false)
  const keyFor = useCommandKey()
  const [entries, setEntries] = useState([emptyEntry(1)])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [note, setNote] = useState<NoteIdentity>({ deliveryNoteNo: null })
  const [match, setMatch] = useState<DuplicateMatch | null>(null)
  const [receivedOn, setReceivedOn] = useState(localDate)

  function clearMatch() {
    setMatch(null)
    setNote(value => ({ ...value, candidateToken: null, duplicateReviewReason: null }))
  }

  function edit(id: number, value: Partial<Entry>) {
    if (value.lineId !== undefined) clearMatch()
    setEntries(current => current.map(entry => entry.id === id ? { ...entry, ...value } : entry))
  }

  const rowStates = entries.map(entry => {
    const line = lines.find(line => String(line.id) === entry.lineId)
    const accepted = Number(entry.accepted)
    let message = ''
    if (!line && attempted) message = '请选择订单物料'
    if (line && !entry.accepted && attempted) message = '请输入实收数'
    if (line && entry.accepted) {
      if (!Number.isInteger(accepted) || accepted <= 0) message = '实收数必须为正整数'
    }
    if (entry.lineId && entries.some(other => other.id !== entry.id && other.lineId === entry.lineId)) {
      message = '同一签收单内请勿重复选择物料'
    }
    return { entry, line, accepted, message }
  })

  async function checkDuplicates() {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    setError('')
    try {
      const result = await lookup(orderId, note, receivedOn,
        rowStates.flatMap(row => row.line ? [row.line.id] : []), session)
      setMatch(result)
      if (note.noteStatus === 'UNKNOWN') {
        setNote(value => ({ ...value, candidateToken: result.candidateToken,
          duplicateReviewReason: value.candidateToken === result.candidateToken ? value.duplicateReviewReason : null }))
      }
    } catch (cause) {
      setError(errorText(cause))
    } finally {
      pending.current = false
      setBusy(false)
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending.current) return
    const form = event.currentTarget
    setAttempted(true)
    setError('')
    if (rowStates.some(row => !row.line || !row.entry.accepted || row.message)) return
    if (!form.reportValidity()) return
    const refresh = () => Promise.all([
      client.invalidateQueries({ queryKey: orderDetailsKey(orderId) }),
      client.invalidateQueries({ queryKey: ['orders', 'list'] }),
      client.invalidateQueries({ queryKey: ['orders', 'history', orderId] }),
    ])
    pending.current = true
    setBusy(true)
    try {
      const body = {
        ...note,
        receivedOn,
        lines: rowStates.map(({ entry, line, accepted }) => ({
          orderLineId: line!.id, acceptedQty: accepted,
          deliveredQty: entry.delivered === '' ? null : Number(entry.delivered), note: entry.note.trim() || null,
        })),
      }
      await request(`/orders/${orderId}/receipts`, body, session, undefined, keyFor(body))
      await refresh()
      close()
    } catch (cause) {
      setError(errorText(cause))
      const duplicate = duplicateMatch(cause)
      if (duplicate) {
        setMatch(duplicate)
        if (duplicate.status !== 'EXACT' && note.noteStatus === 'UNKNOWN') {
          setNote(value => ({ ...value, candidateToken: duplicate.candidateToken,
            duplicateReviewReason: value.candidateToken === duplicate.candidateToken ? value.duplicateReviewReason : null }))
        }
      }
      if (cause instanceof ApiError && cause.status === 409) {
        await refresh()
      }
    } finally {
      pending.current = false
      setBusy(false)
    }
  }

  return <section className="panel panel-inset receipt-editor" aria-labelledby="receipt-heading">
    <h2 id="receipt-heading">登记本次到货</h2>
    <form className="form-stack" onSubmit={submit} noValidate>
      {error && <Failure message={error} />}
      <fieldset className="entry-fields receipt-fields" disabled={busy}>
        <NoteFields value={note} onChange={patch => {
          if (patch.deliveryNoteNo !== undefined) clearMatch()
          setNote(value => ({ ...value, ...patch }))
        }} />
        <label>到货日期<input name="receivedOn" type="date" value={receivedOn} required onChange={event => {
          setReceivedOn(event.target.value)
          clearMatch()
        }} /></label>
      </fieldset>
      <div className="action-group"><Button disabled={busy || !receivedOn}
        onClick={() => void checkDuplicates()}>检查重复收货</Button></div>
      <DuplicateCandidates match={match} note={note} onReason={reason => setNote(value => ({ ...value,
        duplicateReviewReason: reason }))} />
      {rowStates.map(({ entry, line, accepted, message }, index) =>
        <fieldset key={entry.id} className="line-editor" disabled={busy}>
          <legend>收货行 {index + 1}</legend>
          <div className="receiving-fields">
            <label>订单物料
              <select required value={entry.lineId} onChange={event => edit(entry.id, { lineId: event.target.value })}>
                <option value="">选择订单物料</option>
                {lines.map(item => <option key={item.id} value={item.id}
                  disabled={item.remainingQty === 0 && String(item.id) !== entry.lineId}>
                  {item.lineNo}. {item.itemCode} · {item.itemName} · {item.specification} / {item.unit}
                </option>)}
              </select>
            </label>
            <label>送货数<input type="number" min={0} max={MAX_QUANTITY} step={1} placeholder="可留空"
              value={entry.delivered} onChange={event => edit(entry.id, { delivered: event.target.value })} /></label>
            <label>实收数<input required type="number" min={1} max={MAX_QUANTITY} step={1}
              aria-invalid={!!message} value={entry.accepted}
              onChange={event => edit(entry.id, { accepted: event.target.value })} /></label>
            <label>备注<input maxLength={500} value={entry.note}
              onChange={event => edit(entry.id, { note: event.target.value })} /></label>
            <Button danger type="text" disabled={entries.length === 1 || busy}
              onClick={() => { clearMatch(); setEntries(current => current.filter(row => row.id !== entry.id)) }}>移除</Button>
          </div>
          {line && <div className="line-summary">
            <span>{line.specification} · 单位 {line.unit} · 当前待收 {line.remainingQty} {line.unit}</span>
            {entry.accepted && !message && <strong>{accepted > line.remainingQty
              ? `超出当前待收 ${accepted - line.remainingQty} ${line.unit}` : `预计待收 ${line.remainingQty - accepted} ${line.unit}`}
            </strong>}
          </div>}
          {message && <p className="field-error" role="alert">{message}</p>}
        </fieldset>)}
      <div className="receipt-actions">
        <Button disabled={busy} onClick={() => {
          const id = nextId.current++
          setEntries(current => [...current, emptyEntry(id)])
        }}>添加收货行</Button>
        <div className="form-actions">
          <Button type="text" disabled={busy} onClick={close}>取消</Button>
          <Button htmlType="submit" type="primary" loading={busy}>确认收货</Button>
        </div>
      </div>
    </form>
  </section>
}
