import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { localDate, request, type Session } from '../../api'
import { OrderForm, type OrderInput } from './OrderForm'
import { useOrderListUrl } from './model'

export function CreateOrder({ session }: { session: Session }) {
  const backTo = useOrderListUrl()
  const navigate = useNavigate()
  const cache = useQueryClient()
  async function create(values: OrderInput) {
    const created = await request<{ id: number }>('/orders', values, session)
    await cache.invalidateQueries({ queryKey: ['orders', 'list'] })
    navigate(`/orders/${created.id}`, { replace: true, state: { backTo } })
  }
  return <>
    <Link className="back-link" to={backTo}>← 返回采购订单</Link>
    <div className="page-heading"><h1>新建采购单</h1></div>
    <OrderForm initial={{ orderNo: '', supplierName: '', orderedOn: localDate(), lines: [] }}
      submitLabel="创建采购单" cancelTo={backTo} onSubmit={create} />
  </>
}
