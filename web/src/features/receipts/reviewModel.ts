import type { OrderLine } from '../orders/model'
import type { DeliveryEvidence, DeliveryLine, OrderRecommendation, ParsePage } from '../tasks/model'
import type { NoteIdentity } from './ReceiptIdentity'

export type ReviewStatus = 'DRAFT' | 'POSTED' | 'CLOSED_NO_RECEIPT'
export type QuantityField = 'acceptedQty' | 'deliveredQty'
export type PageEdit = { pageNo: number; disposition: 'PENDING' | 'REVIEWED' | 'NO_DETAILS'; reason?: string | null }
export type LineEdit = {
  lineKey: string
  pageNo: number
  orderLineId: number | null
  unit: string | null
  deliveredQty: number | null
  acceptedQty: number | null
  disposition: 'PENDING' | 'POST' | 'ZERO' | 'EXCLUDED'
  reason?: string | null
  note?: string | null
  selectedEvidence?: Partial<Record<QuantityField, number>> | null
}
export type Edit = NoteIdentity & {
  receivedOn: string | null
  noReceiptReason: string | null
  pages: PageEdit[]
  lines: LineEdit[]
}
export type ReviewSource = {
  taskId: number
  documentId: number
  resultHash: string
  originalFilename: string
  mimeType: string
  pages: ParsePage[]
  delivery: { lines: DeliveryLine[]; evidence: DeliveryEvidence[] }
  recommendations: OrderRecommendation[]
}
export type Review = {
  id: number
  orderId: number
  version: number
  status: ReviewStatus
  updatedAt: string
  updatedBy: string
  confirmedAt: string | null
  confirmedBy: string | null
  receiptId: number | null
  sourceChanged: boolean
  edit: Edit
  source: ReviewSource
  orderLines: OrderLine[]
}
export type Outcome = { status: ReviewStatus; reviewId: number; receiptId: number | null }
export type ReceiptRecord = {
  header: NoteIdentity & { id: number; receivedOn: string; confirmedBy: string; confirmedAt: string;
    duplicateCandidates: string | null }
  source: 'MANUAL' | 'MATERIAL'
  lines: (Pick<OrderLine, 'itemCode' | 'itemName' | 'specification' | 'unit'> & {
    receiptLineId: number; orderLineId: number; deliveredQty: number | null; acceptedQty: number; note: string | null
    originalAcceptedQty: number; effectiveAcceptedQty: number; revision: number; remainingQty: number
  })[]
  review: Review | null
  adjustments: { id: number; receiptLineId: number; revision: number; beforeQty: number; afterQty: number;
    reason: string; actor: string; createdAt: string }[]
}

export const dispositions = { PENDING: '待处理', POST: '入账', ZERO: '本行未收', EXCLUDED: '排除' } as const
export const fields = {
  itemCode: '物料编码', itemName: '物料名称', specification: '规格', unit: '单位',
  deliveredQty: '送货数', acceptedQty: '实收数',
} as const
export const methods = { PDF_TEXT: 'PDF 文字', PADDLE_OCR: 'PaddleOCR', DOUBAO: '豆包' }
export const reviewPath = (orderId: number | string, id: number) => `/orders/${orderId}/reviews/${id}`
export const receiptPath = (orderId: number | string, id: number) => `/orders/${orderId}/receipts/${id}`
export const openingPath = (orderId: number | string, id: number) => `/orders/${orderId}/documents/${id}/review`
export const qty = (value: number | null | undefined) => value ?? '待确认'
export const dateTime = (value: string) => new Date(value).toLocaleString('zh-CN')

export function impact(lines: LineEdit[], orderLines: OrderLine[]) {
  const groups = new Map<number, LineEdit[]>()
  for (const row of lines) {
    if (row.disposition !== 'POST' || row.orderLineId === null) continue
    const group = groups.get(row.orderLineId) ?? []
    group.push(row)
    groups.set(row.orderLineId, group)
  }
  return orderLines.flatMap(line => {
    const selected = groups.get(line.id)
    if (!selected) return []
    const accepted = selected.every(row => row.acceptedQty !== null)
      ? selected.reduce((total, row) => total + (row.acceptedQty ?? 0), 0) : null
    const delivered = selected.every(row => row.deliveredQty !== null)
      ? selected.reduce((total, row) => total + (row.deliveredQty ?? 0), 0) : null
    return [{ line, accepted, delivered, remaining: accepted === null ? null : line.remainingQty - accepted }]
  })
}
