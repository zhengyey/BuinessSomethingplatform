import { useRef } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, request, type Session } from '../../api'
import { documentUrl } from '../tasks/model'
import { receiptPath } from './reviewModel'

export type NoteIdentity = {
  noteStatus?: 'VERIFIED' | 'UNKNOWN' | null
  deliveryNoteNo: string | null
  unknownNoteReason?: string | null
  duplicateReviewReason?: string | null
  candidateToken?: string | null
}

export type DuplicateMatch = {
  status: 'NONE' | 'EXACT' | 'POSSIBLE'
  candidateToken: string | null
  candidates: {
    reference: { receiptId: number; orderId: number; orderNo: string; documentId: number | null;
      deliveryNoteNo: string | null; receivedOn: string }
    lines: { receiptLineId: number; itemCode: string; itemName: string; unit: string; effectiveAcceptedQty: number }[]
  }[]
}

export function lookup(orderId: string | number, identity: NoteIdentity, receivedOn: string | null,
  orderLineIds: number[], session: Session) {
  return request<DuplicateMatch>(`/orders/${orderId}/receipts/duplicate-check`, {
    noteStatus: identity.noteStatus, deliveryNoteNo: identity.deliveryNoteNo, receivedOn, orderLineIds,
  }, session)
}

export function duplicateMatch(error: unknown) {
  return error instanceof ApiError && ['DUPLICATE_RECEIPT', 'POSSIBLE_DUPLICATE_RECEIPT'].includes(error.code)
    ? error.details as DuplicateMatch | undefined : undefined
}

export function NoteFields({ value, onChange, idPrefix = '' }: {
  value: NoteIdentity
  onChange: (patch: Partial<NoteIdentity>) => void
  idPrefix?: string
}) {
  const unknown = value.noteStatus === 'UNKNOWN'
  return <>
    <label className="receipt-note-status"><input type="checkbox" checked={unknown} onChange={event =>
      onChange({ noteStatus: event.target.checked ? 'UNKNOWN' : 'VERIFIED', deliveryNoteNo: null,
        candidateToken: null, unknownNoteReason: null, duplicateReviewReason: null })} />单号无法核实</label>
    {unknown ? <label>缺号原因<input id={`${idPrefix}unknownNoteReason`} required maxLength={500}
      value={value.unknownNoteReason ?? ''} onChange={event => onChange({ unknownNoteReason: event.target.value })} /></label>
      : <label>签收单号<input id={`${idPrefix}deliveryNoteNo`} name="deliveryNoteNo" maxLength={64} required
        value={value.deliveryNoteNo ?? ''} onChange={event => onChange({ deliveryNoteNo: event.target.value })} /></label>}
  </>
}

export function DuplicateCandidates({ match, note, onReason }: {
  match: DuplicateMatch | null
  note: NoteIdentity
  onReason: (reason: string) => void
}) {
  const possible = match?.status === 'POSSIBLE'
  return <>
    {match && <section className="duplicate-candidates" aria-label="签收查重结果">
      <strong>{match.status === 'EXACT' ? '已处理，查看原记录'
        : possible ? '可能是同一次到货，请核实' : '未发现匹配的收货记录'}</strong>
      {match.candidates.map(({ reference, lines }) => <div key={reference.receiptId}>
        <Link to={receiptPath(reference.orderId, reference.receiptId)}>
          查看原收货记录 · {reference.deliveryNoteNo ?? '单号无法核实'}
        </Link>
        <span> · 采购单 {reference.orderNo} · {reference.receivedOn}</span>
        {reference.documentId && <> · <a target="_blank" rel="noreferrer"
          href={documentUrl(reference.documentId)}>原件</a></>}
        <ul>{lines.map(line => <li key={line.receiptLineId}>
          {line.itemCode} {line.itemName} · 实收 {line.effectiveAcceptedQty} {line.unit}
        </li>)}</ul>
      </div>)}
    </section>}
    {note.noteStatus === 'UNKNOWN' && (possible || (!match && note.candidateToken)) &&
      <label>核实为另一次到货的理由<input id="review-duplicateReviewReason" value={note.duplicateReviewReason ?? ''}
        maxLength={500} onChange={event => onReason(event.target.value)} /></label>}
  </>
}

export function useCommandKey() {
  const previous = useRef<{ content: string; key: string } | null>(null)
  return (body: unknown) => {
    const content = JSON.stringify(body)
    if (previous.current?.content !== content) previous.current = { content, key: crypto.randomUUID() }
    return previous.current.key
  }
}
