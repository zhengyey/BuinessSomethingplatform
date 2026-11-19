import { Tag } from 'antd'
import { useLocation } from 'react-router-dom'

export const statuses = {
  PENDING: { label: '待收货', color: 'default' },
  PARTIAL: { label: '部分到货', color: 'gold' },
  COMPLETE: { label: '已收齐', color: 'green' },
} as const

export type Status = keyof typeof statuses

export type Order = {
  id: number
  orderNo: string
  supplierName: string
  orderedOn: string
  createdAt: string
  createdBy: string
  status: Status
}

export type OrderLine = {
  id: number
  lineNo: number
  itemCode: string
  itemName: string
  specification: string
  unit: string
  orderedQty: number
  acceptedQty: number
  remainingQty: number
}

export type OrderDetailData = Order & {
  lines: OrderLine[]
  importDocumentId?: number | null
  importTaskId?: number | null
}
export type Page = { items: Order[]; total: number; page: number; pageSize: number }

export function StatusTag({ status }: { status: Status }) {
  const { label, color } = statuses[status]
  return <Tag bordered={false} color={color} className="status-tag">{label}</Tag>
}

export function orderDetailsKey(id: string) {
  return ['orders', 'detail', id] as const
}

export function useOrderListUrl() {
  const backTo: unknown = useLocation().state?.backTo
  return typeof backTo === 'string' && /^\/orders(?:\?|$)/.test(backTo) ? backTo : '/orders'
}
