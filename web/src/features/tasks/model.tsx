import { Tag } from 'antd'
import type { Candidate } from '../orders/OrderForm'

export const taskStatuses = {
  QUEUED: { label: '排队中', color: 'default' },
  RUNNING: { label: '处理中', color: 'processing' },
  SUCCEEDED: { label: '完成', color: 'green' },
  FAILED: { label: '失败', color: 'red' },
} as const

export const purposes = { ORDER_IMPORT: '采购单导入', DELIVERY_NOTE: '签收材料' } as const
type Status = keyof typeof taskStatuses
type Purpose = keyof typeof purposes

export type UploadResult = { documentId: number; taskId: number; reused?: boolean; status?: Status;
  reviewId?: number | null; receiptId?: number | null }
export type Material = {
  id: number
  purpose: Purpose
  orderId: number | null
  originalFilename: string
  mimeType: string
  byteSize: number
  sha256: string
  uploadedAt: string
  uploadedBy: string
  taskId: number
  status: Status
  reviewable?: boolean
  reviewId?: number | null
  reviewStatus?: 'DRAFT' | 'POSTED' | 'CLOSED_NO_RECEIPT' | null
}

export type Task = {
  id: number
  status: Status
  purpose: Purpose
  documentId: number
  originalFilename: string
  createdAt: string
  attemptNo: number
}

export type TasksPage = { items: Task[]; total: number; page: number; pageSize: number }

export type ParseMethod = 'PDF_TEXT' | 'PADDLE_OCR' | 'DOUBAO'
export type ParsePage = {
  pageNo: number
  status: 'TEXT' | 'NEEDS_OCR' | 'EXTRACTED' | 'REVIEW' | 'PAGE_FAILED'
  text: string | null
  hints: string[]
  width?: number
  height?: number
  rotation?: number
  methods?: ParseMethod[]
  ocrCalls?: number
  doubaoCalls?: number
}

export type DeliveryLine = {
  lineKey: string
  pageNo: number
  itemCode: string | null
  itemName: string | null
  specification: string | null
  unit: string | null
  deliveredQty: number | null
  acceptedQty: number | null
  issues: string[]
}

export type DeliveryField = 'itemCode' | 'itemName' | 'specification' | 'unit' | 'deliveredQty' | 'acceptedQty'
export type DeliveryEvidence = {
  lineKey: string
  field: DeliveryField
  method: ParseMethod
  rawText: string
  value: string | number | null
  pageNo: number
  bbox: number[] | null
  precision: 'REGION' | 'PAGE'
  supporting: boolean
}

export type OrderRecommendation = {
  lineKey: string
  status: 'CODE_MATCH' | 'NAME_MATCH' | 'REVIEW' | 'UNMATCHED'
  reason: string
  candidates: {
    orderLineId: number
    lineNo: number
    itemCode: string | null
    itemName: string
    specification: string
    unit: string
  }[]
}

export type TaskDetailData = Task & {
  document: Material
  pages: ParsePage[]
  protocolVersion?: number | null
  delivery?: { lines: DeliveryLine[]; evidence: DeliveryEvidence[] } | null
  recommendations?: OrderRecommendation[] | null
  candidate: Candidate | null
  sources: { field: string; pageNo: number; text: string; bbox: number[] | null }[]
  hints: string[]
  confirmedOrderId: number | null
  failureCode: string | null
  failureMessage: string | null
  retryable: boolean
  parserVersion: string | null
}

export function processing(status: Status) {
  return status === 'QUEUED' || status === 'RUNNING'
}

export const POLL_MS = 2000

export function TaskStatus({ status }: { status: Status }) {
  const { color, label } = taskStatuses[status]
  return <Tag bordered={false} color={color} className="status-tag">{label}</Tag>
}

export function documentUrl(id: number, download = false) {
  return `/api/documents/${id}/content${download ? '?download=true' : ''}`
}

export const taskKeys = {
  list: ['tasks', 'list'] as const,
  detail: (id: string) => ['tasks', 'detail', id] as const,
  documents: (id: string) => ['orders', 'documents', id] as const,
}
