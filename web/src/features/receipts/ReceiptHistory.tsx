import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { request, errorText } from '../../api'
import { Failure, Loading } from '../../ui'
import type { OrderLine } from '../orders/model'
import { receiptPath } from './reviewModel'

type HistoryItem = {
  id: number
  receiptId: number
  deliveryNoteNo: string | null
  receivedOn: string
  confirmedBy: string
  deliveredQty: number | null
  acceptedQty: number
  originalAcceptedQty: number
  revision: number
  note: string | null
}

export function ReceiptHistory({ orderId, line }: { orderId: string; line: OrderLine }) {
  const history = useQuery({
    queryKey: ['orders', 'history', orderId, line.id],
    queryFn: () => request<HistoryItem[]>(`/orders/${orderId}/lines/${line.id}/receipts`),
  })
  return <section className="history" aria-label={`${line.itemCode} 收货记录`}>
    <h3>{line.itemCode} 收货记录</h3>
    {history.isPending && <Loading />}
    {history.isError && <Failure message={errorText(history.error)} retry={() => void history.refetch()} />}
    {history.data?.length === 0 && <p className="muted">尚无收货记录</p>}
    {!!history.data?.length && <table className="data-table history-table">
      <thead><tr><th>签收单号</th><th>到货日期</th><th>确认人</th><th>送货数</th><th>实收数</th><th>备注</th></tr></thead>
      <tbody>
        {history.data.map(item => <tr key={item.id}>
          <td><Link to={receiptPath(orderId, item.receiptId)}>{item.deliveryNoteNo ?? '单号无法核实'}</Link></td>
          <td className="numeric">{item.receivedOn}</td><td>{item.confirmedBy}</td>
          <td className="numeric">{item.deliveredQty === null ? '未提供' : `${item.deliveredQty} ${line.unit}`}</td>
          <td className="numeric">{item.acceptedQty} {line.unit}
            {item.revision > 0 && <small className="receipt-history-original">原确认 {item.originalAcceptedQty} {line.unit}</small>}
          </td><td>{item.note ?? '—'}</td>
        </tr>)}
      </tbody>
    </table>}
  </section>
}
