import { useQuery } from '@tanstack/react-query'
import { Button, Pagination } from 'antd'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { errorText, request } from '../../api'
import { Failure, Loading } from '../../ui'
import { statuses, StatusTag, type Page } from './model'

const pageSizes = [20, 50, 100]

function positiveParam(value: string | null, fallback: number) {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 && number <= 2_147_483_647 ? number : fallback
}

export function OrderList() {
  const [params, setParams] = useSearchParams()
  const query = params.get('query') ?? ''
  const rawStatus = params.get('status') ?? ''
  const status = Object.hasOwn(statuses, rawStatus) ? rawStatus : ''
  const page = positiveParam(params.get('page'), 1)
  const rawSize = positiveParam(params.get('pageSize'), 20)
  const pageSize = pageSizes.includes(rawSize) ? rawSize : 20
  const search = new URLSearchParams({ query, status, page: String(page), pageSize: String(pageSize) })
  const backTo = params.size ? `/orders?${params.toString()}` : '/orders'
  const result = useQuery({
    queryKey: ['orders', 'list', query, status, page, pageSize],
    queryFn: () => request<Page>(`/orders?${search}`),
  })

  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const values = new FormData(event.currentTarget)
    setParams({ query: String(values.get('query')).trim(), status: String(values.get('status')),
      page: '1', pageSize: String(pageSize) })
  }

  return <>
    <div className="page-heading">
      <h1>采购订单</h1>
      <div className="action-group">
        <Link className="quiet-link" to="/orders/import" state={{ backTo }}>导入采购单</Link>
        <Link className="primary-link" to="/orders/new" state={{ backTo }}>新建采购单</Link>
      </div>
    </div>
    <section className="panel">
      <form className="filter-bar" key={`${query}-${status}`} onSubmit={filter}>
        <label>采购单号搜索<input name="query" type="search" maxLength={64} placeholder="输入采购单号关键词"
          defaultValue={query} /></label>
        <label>到货状态<select name="status" defaultValue={status}>
          <option value="">全部状态</option>
          {Object.entries(statuses).map(([key, value]) => <option key={key} value={key}>{value.label}</option>)}
        </select></label>
        <Button type="primary" htmlType="submit">查询</Button>
        {(query || status) && <Link className="quiet-link" to="/orders">清除筛选</Link>}
      </form>
      {result.isPending && <Loading />}
      {result.isError && <div className="panel-inset">
        <Failure message={errorText(result.error)} retry={() => void result.refetch()} />
      </div>}
      {result.data && <>
        <div className="table-scroll">
          <table className="data-table" aria-label="采购订单列表">
            <thead><tr><th>采购单号</th><th>供应商</th><th>采购日期</th><th>到货状态</th></tr></thead>
            <tbody>
              {result.data.items.map(order => <tr key={order.id}>
                <td><Link to={`/orders/${order.id}`} state={{ backTo }}>{order.orderNo}</Link></td>
                <td>{order.supplierName}</td><td className="numeric">{order.orderedOn}</td>
                <td><StatusTag status={order.status} /></td>
              </tr>)}
              {result.data.items.length === 0 && <tr><td className="empty" colSpan={4}>
                {query || status ? '没有符合条件的订单' : '暂无采购订单'}
              </td></tr>}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <span>共 {result.data.total} 张采购单</span>
          <Pagination current={page} total={result.data.total} pageSize={pageSize} showSizeChanger
            pageSizeOptions={pageSizes} onChange={(next, size) =>
              setParams({ query, status, page: String(size === pageSize ? next : 1), pageSize: String(size) })} />
        </div>
      </>}
    </section>
  </>
}
