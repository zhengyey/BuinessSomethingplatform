import { useQuery } from '@tanstack/react-query'
import { Button, Pagination } from 'antd'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { errorText, request } from '../../api'
import { Failure, Loading } from '../../ui'
import { POLL_MS, processing, purposes, taskKeys, taskStatuses, TaskStatus, type TasksPage } from './model'

export function TaskList() {
  const [params, setParams] = useSearchParams()
  const rawStatus = params.get('status') ?? ''
  const rawPurpose = params.get('purpose') ?? ''
  const status = Object.hasOwn(taskStatuses, rawStatus) ? rawStatus : ''
  const purpose = Object.hasOwn(purposes, rawPurpose) ? rawPurpose : ''
  const rawPage = Number(params.get('page'))
  const page = Number.isInteger(rawPage) && rawPage > 0 && rawPage <= 2_147_483_647 ? rawPage : 1
  const pageSize = 20
  const search = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (status) search.set('status', status)
  if (purpose) search.set('purpose', purpose)
  const result = useQuery({
    queryKey: [...taskKeys.list, search.toString()],
    queryFn: ({ signal }) => request<TasksPage>(`/tasks?${search}`, undefined, undefined, signal),
    refetchInterval: query => query.state.data?.items.some(task => processing(task.status)) ? POLL_MS : false,
  })

  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    setParams({ status: String(data.get('status')), purpose: String(data.get('purpose')), page: '1' })
  }

  return <>
    <Link className="back-link" to="/orders">← 返回采购订单</Link>
    <div className="page-heading"><h1>任务中心</h1></div>
    <section className="panel">
      <form className="filter-bar" key={`${status}-${purpose}`} onSubmit={filter}>
        <label>任务状态<select name="status" defaultValue={status}>
          <option value="">全部状态</option>
          {Object.entries(taskStatuses).map(([key, value]) => <option key={key} value={key}>{value.label}</option>)}
        </select></label>
        <label>材料用途<select name="purpose" defaultValue={purpose}>
          <option value="">全部用途</option>
          {Object.entries(purposes).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
        </select></label>
        <Button type="primary" htmlType="submit">查询</Button>
      </form>
      {result.isPending && <Loading />}
      {result.isError && <div className="panel-inset">
        <Failure message={errorText(result.error)} retry={() => void result.refetch()} />
      </div>}
      {result.data && <>
        <div className="table-scroll">
          <table className="data-table" aria-label="任务列表">
            <thead><tr><th>任务编号</th><th>原文件名</th><th>材料用途</th><th>状态</th><th>登记时间</th></tr></thead>
            <tbody>
              {result.data.items.map(task => <tr key={task.id}>
                <td><Link to={`/tasks/${task.id}`} state={{ taskList: `/tasks?${params}` }}>#{task.id}</Link></td>
                <td>{task.originalFilename}</td><td>{purposes[task.purpose]}</td>
                <td><TaskStatus status={task.status} /></td>
                <td>{new Date(task.createdAt).toLocaleString('zh-CN')}</td>
              </tr>)}
              {result.data.items.length === 0 && <tr><td className="empty" colSpan={5}>暂无符合条件的任务</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <span>共 {result.data.total} 项任务</span>
          <Pagination current={page} total={result.data.total} pageSize={pageSize} showSizeChanger={false}
            onChange={next => setParams({ status, purpose, page: String(next) })} />
        </div>
      </>}
    </section>
  </>
}
