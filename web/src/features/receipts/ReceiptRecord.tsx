import { useQuery } from '@tanstack/react-query'
import { Button } from 'antd'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { errorText, MAX_QUANTITY, request, type Session } from '../../api'
import { Failure, Loading } from '../../ui'
import { ReviewOriginal, type Position } from './ReviewOriginal'
import { ReviewRows } from './ReviewRows'
import { ReceiptAdjustment } from './ReceiptAdjustment'
import { dateTime, impact, qty, receiptPath, reviewPath, type ReceiptRecord as RecordData, type Review } from './reviewModel'

export function ReceiptRecord({ session }: { session: Session }) {
  const { orderId = '', receiptId = '' } = useParams()
  const [editingLine, editLine] = useState<number | null>(null)
  const record = useQuery({
    queryKey: ['orders', 'receipt', orderId, receiptId],
    queryFn: ({ signal }) => request<RecordData>(receiptPath(orderId, Number(receiptId)), undefined, undefined, signal),
  })
  if (record.isPending) return <Loading />
  if (record.isError) return <Failure message={errorText(record.error)} retry={() => void record.refetch()} />
  const { header, source, lines, review } = record.data
  const selected = lines.find(line => line.receiptLineId === editingLine)
  const lineNames = new Map(lines.map(line => [line.receiptLineId, line.itemCode]))
  const reviewedCandidates: number[] = header.duplicateCandidates ? JSON.parse(header.duplicateCandidates) : []
  return <>
    <Link className="back-link" to={`/orders/${orderId}`}>← 返回采购单</Link>
    <div className="page-heading"><h1>收货记录 · {header.deliveryNoteNo ?? '单号无法核实'}</h1>
      <span className="muted">{source === 'MANUAL' ? '手工登记' : '签收材料'}</span></div>
    <section className="panel panel-inset form-stack">
      <p>到货日期 {header.receivedOn} · {header.confirmedBy} 确认于 {dateTime(header.confirmedAt)}</p>
      {header.unknownNoteReason && <p>缺号原因 · {header.unknownNoteReason}</p>}
      {header.duplicateReviewReason && <p>核重理由 · {header.duplicateReviewReason}
        {reviewedCandidates.length > 0 && <> · 已核对收货记录 #{reviewedCandidates.join('、#')}</>}</p>}
      <div className="table-scroll"><table className="data-table">
        <thead><tr><th>订单物料</th><th>规格</th><th>送货数</th><th>{review ? '原页分项合计' : '原确认实收'}</th>
          <th>当前有效实收</th><th>备注</th><th>操作</th></tr></thead>
        <tbody>{lines.map(line => <tr key={line.orderLineId}>
          <td>{line.itemCode} {line.itemName}</td><td>{line.specification}</td>
          <td>{line.deliveredQty ?? '未提供'} {line.deliveredQty !== null && line.unit}</td>
          <td>{line.originalAcceptedQty} {line.unit}</td>
          <td>{line.effectiveAcceptedQty} {line.unit}</td><td>{line.note ?? '—'}</td>
          <td><Button size="small" onClick={() => editLine(line.receiptLineId)}>更正实收</Button></td>
        </tr>)}</tbody>
      </table></div>
      {selected && <ReceiptAdjustment key={selected.receiptLineId} orderId={orderId} receiptId={header.id}
        line={selected} session={session} close={() => editLine(null)} />}
      {record.data.adjustments.length > 0 && <section aria-label="历次更正">
        <h2>历次更正</h2>
        <ul>{record.data.adjustments.map(change => <li key={change.id}>
          {lineNames.get(change.receiptLineId)} · {change.beforeQty} → {change.afterQty} · {change.reason}
          {' · '}{change.actor} · {dateTime(change.createdAt)}
        </li>)}</ul>
      </section>}
    </section>
    {review && <ReceiptSnapshot key={review.id} review={review} inReceipt />}
  </>
}

export function ReceiptSnapshot({ review, inReceipt = false }: { review: Review; inReceipt?: boolean }) {
  const [params] = useSearchParams()
  const requested = Number(params.get('page'))
  const initial = review.source.pages.find(page => page.pageNo === requested) ?? review.source.pages[0]
  const [position, navigate] = useState<Position>({ pageNo: initial.pageNo })
  return <>
    {!inReceipt && <Link className="back-link" to={`/orders/${review.orderId}`}>← 返回采购单</Link>}
    <div className="page-heading review-record-heading">
      {inReceipt ? <h2>原确认核对记录</h2> : <h1>核对记录 · {review.edit.deliveryNoteNo ?? '单号无法核实'}</h1>}
      <div className="action-group">
        {inReceipt && <Link to={`${reviewPath(review.orderId, review.id)}?page=${position.pageNo}`}>打开核对记录</Link>}
        {!inReceipt && review.receiptId && <Link to={`${receiptPath(review.orderId, review.receiptId)}?page=${position.pageNo}`}>打开收货记录</Link>}
        <Link to={`/tasks/${review.source.taskId}`}>查看解析结果</Link>
      </div>
    </div>
    <div className="review-layout">
      <ReviewOriginal source={review.source} position={position} navigate={navigate} />
      <section className="panel panel-inset review-editor">
        <p><strong>{review.status === 'POSTED' ? '已确认入账' : '已完成核对，无入账'}</strong>
          {' · '}{review.confirmedBy} · {review.confirmedAt && dateTime(review.confirmedAt)}</p>
        <p>签收单号 {review.edit.deliveryNoteNo ?? '单号无法核实'} · 到货日期 {review.edit.receivedOn}</p>
        {review.edit.unknownNoteReason && <p>缺号原因 · {review.edit.unknownNoteReason}</p>}
        {review.edit.duplicateReviewReason && <p>核重理由 · {review.edit.duplicateReviewReason}</p>}
        {review.edit.noReceiptReason && review.status === 'CLOSED_NO_RECEIPT' && <p>{review.edit.noReceiptReason}</p>}
        <h2>逐页处置</h2>
        {review.edit.pages.map(page => <div className="action-group" key={page.pageNo}>
          <button className="review-evidence-link" type="button" onClick={() => navigate({ pageNo: page.pageNo })}>
            第 {page.pageNo} 页
          </button><span>{page.disposition === 'NO_DETAILS' ? '本页无收货明细' : '已核对明细'} {page.reason}</span>
        </div>)}
        <h2>来源行与最终值</h2>
        <ReviewRows source={review.source} lines={review.edit.lines} orderLines={review.orderLines} navigate={navigate} />
      </section>
    </div>
  </>
}

export function SummaryTable({ totals }: { totals: ReturnType<typeof impact> }) {
  if (!totals.length) return <p className="muted">当前没有入账行</p>
  return <div className="table-scroll"><table className="data-table" aria-label="本次入账合计">
    <thead><tr><th>物料</th><th>送货合计</th><th>本次实收</th><th>当前待收</th><th>预计待收</th></tr></thead>
    <tbody>{totals.map(({ line, accepted, delivered, remaining }) => <tr key={line.id}>
      <td>{line.itemCode} {line.itemName} · {line.unit}</td>
      <td>{delivered === null ? '未知' : delivered > MAX_QUANTITY ? '超出整数范围' : delivered}</td>
      <td>{qty(accepted)}</td><td>{line.remainingQty}</td>
      <td className={remaining !== null && remaining < 0 ? 'review-warning' : undefined}>
        {remaining !== null && remaining < 0 ? `超收 ${-remaining}` : qty(remaining)}
      </td>
    </tr>)}</tbody>
  </table></div>
}
