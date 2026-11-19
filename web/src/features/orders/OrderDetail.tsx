import { useQuery } from '@tanstack/react-query'
import { Button } from 'antd'
import { Fragment, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { errorText, request, type Session } from '../../api'
import { Failure, Loading } from '../../ui'
import { ReceiptForm } from '../receipts/ReceiptForm'
import { ReceiptHistory } from '../receipts/ReceiptHistory'
import { OrderDocuments } from '../tasks/OrderDocuments'
import { documentUrl } from '../tasks/model'
import { orderDetailsKey, StatusTag, useOrderListUrl, type OrderDetailData } from './model'

export function OrderDetail({ session }: { session: Session }) {
  const { orderId = '' } = useParams()
  return <Details key={orderId} orderId={orderId} session={session} />
}

function Details({ orderId, session }: { orderId: string; session: Session }) {
  const backTo = useOrderListUrl()
  const [expanded, setExpanded] = useState<number[]>([])
  const [registering, setRegistering] = useState(false)
  const detail = useQuery({
    queryKey: orderDetailsKey(orderId),
    queryFn: () => request<OrderDetailData>(`/orders/${encodeURIComponent(orderId)}`),
  })
  const order = detail.data

  return <>
    <Link className="back-link" to={backTo}>← 返回采购订单</Link>
    {detail.isPending && <Loading />}
    {detail.isError && <Failure message={errorText(detail.error)} retry={() => void detail.refetch()} />}
    {order && <>
      <div className="page-heading detail-heading">
        <div>
          <div className="order-title"><h1>{order.orderNo}</h1><StatusTag status={order.status} /></div>
          <dl className="order-meta">
            <div><dt>供应商</dt><dd>{order.supplierName}</dd></div>
            <div><dt>采购日期</dt><dd className="numeric">{order.orderedOn}</dd></div>
          </dl>
          {order.importDocumentId && order.importTaskId && <div className="action-group order-origin">
            <Link to={`/tasks/${order.importTaskId}`}>查看采购导入任务</Link>
            <a href={documentUrl(order.importDocumentId)} target="_blank" rel="noreferrer">预览采购原件</a>
          </div>}
        </div>
        <Button type="primary" size="large" disabled={registering || order.status === 'COMPLETE'}
          onClick={() => setRegistering(true)}>登记本次到货</Button>
      </div>
      <section className="panel">
        <div className="section-heading"><h2>订单物料</h2></div>
        <div className="table-scroll">
          <table className="data-table material-table" aria-label="订单物料">
            <thead><tr><th>行号</th><th>物料</th><th>规格</th><th>订购</th><th>已收</th><th>待收</th></tr></thead>
            <tbody>
              {order.lines.map(line => {
                const open = expanded.includes(line.id)
                return <Fragment key={line.id}>
                  <tr>
                    <td className="numeric muted">{line.lineNo}</td>
                    <td><span className="item-code">{line.itemCode}</span> <strong>{line.itemName}</strong></td>
                    <td>{line.specification}</td><td className="numeric">{line.orderedQty} {line.unit}</td>
                    <td><button type="button" className="history-toggle numeric" aria-expanded={open}
                      aria-controls={open ? `history-${line.id}` : undefined}
                      aria-label={`已收 ${line.acceptedQty} ${line.unit}`}
                      onClick={() => setExpanded(current =>
                        open ? current.filter(id => id !== line.id) : [...current, line.id])}>
                      <span className={open ? 'chevron open' : 'chevron'} aria-hidden="true" />
                      {line.acceptedQty} {line.unit}
                    </button></td>
                    <td className={line.remainingQty ? 'numeric balance' : 'numeric muted'}>
                      {line.remainingQty} {line.unit}
                    </td>
                  </tr>
                  {open && <tr className="history-row"><td colSpan={6} id={`history-${line.id}`}>
                    <ReceiptHistory orderId={orderId} line={line} />
                  </td></tr>}
                </Fragment>
              })}
            </tbody>
          </table>
        </div>
      </section>
      {registering && <ReceiptForm orderId={orderId} lines={order.lines} session={session}
        close={() => setRegistering(false)} />}
      <OrderDocuments orderId={orderId} session={session} />
    </>}
  </>
}
