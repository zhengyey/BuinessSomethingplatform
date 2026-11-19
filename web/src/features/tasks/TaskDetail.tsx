import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Modal } from 'antd'
import { useEffect, useState, type RefObject } from 'react'
import { Link, useBeforeUnload, useBlocker, useLocation, useNavigate, useOutletContext, useParams } from 'react-router-dom'
import { errorText, request, type Session } from '../../api'
import { Failure, Loading } from '../../ui'
import { OrderForm, type Candidate, type OrderInput } from '../orders/OrderForm'
import { openingPath } from '../receipts/reviewModel'
import { DeliveryResult, PageResults } from './DeliveryResult'
import { documentUrl, POLL_MS, processing, purposes, taskKeys, TaskStatus, type Material, type TaskDetailData } from './model'

export function TaskDetail({ session }: { session: Session }) {
  const { taskId = '' } = useParams()
  return <Details key={taskId} taskId={taskId} session={session} />
}

function Details({ taskId, session }: { taskId: string; session: Session }) {
  const cache = useQueryClient()
  const taskPath = `/tasks/${encodeURIComponent(taskId)}`
  const taskList: unknown = useLocation().state?.taskList
  const backTo = typeof taskList === 'string' && /^\/tasks(?:\?|$)/.test(taskList) ? taskList : '/tasks'
  const [retrying, setRetrying] = useState(false)
  const [retryError, setRetryError] = useState('')
  const result = useQuery({
    queryKey: taskKeys.detail(taskId),
    queryFn: ({ signal }) => request<TaskDetailData>(taskPath, undefined, undefined, signal),
    refetchInterval: query => query.state.data && processing(query.state.data.status) ? POLL_MS : false,
  })
  const task = result.data

  async function retry() {
    if (retrying) return
    setRetryError('')
    setRetrying(true)
    try {
      await request(`${taskPath}/retry`, undefined, session)
      await Promise.all([
        cache.invalidateQueries({ queryKey: taskKeys.detail(taskId) }),
        cache.invalidateQueries({ queryKey: taskKeys.list }),
        task?.document.orderId && cache.invalidateQueries({ queryKey: taskKeys.documents(String(task.document.orderId)) }),
      ])
    } catch (cause) {
      setRetryError(errorText(cause))
      void result.refetch()
    } finally {
      setRetrying(false)
    }
  }

  return <>
    <Link className="back-link" to={backTo}>← 返回任务中心</Link>
    <div className="page-heading task-heading">
      <div className="order-title"><h1>任务 #{taskId}</h1>{task && <TaskStatus status={task.status} />}</div>
      {task?.document.orderId && task.purpose === 'DELIVERY_NOTE' &&
        <Link className="quiet-link" to={`/orders/${task.document.orderId}`}>返回关联订单</Link>}
      {task?.document.orderId && task.purpose === 'DELIVERY_NOTE' && task.status === 'SUCCEEDED' &&
        task.protocolVersion === 2 &&
        <Link to={openingPath(task.document.orderId, task.documentId)}>核对签收单</Link>}
    </div>
    {result.isPending && <Loading />}
    {result.isError && <Failure message={errorText(result.error)} retry={() => void result.refetch()} />}
    {task && <div className="task-layout">
      <Original document={task.document} />
      <div className="form-stack task-results">
        {processing(task.status) && <div className="panel"><Loading text="正在等待材料处理，可稍后返回任务中心查看" /></div>}
        {task.status === 'FAILED' && <section className="panel panel-inset form-stack">
          <h2>处理失败</h2>
          <Failure message={task.failureMessage || '原件处理失败'} />
          {retryError && <Failure message={retryError} />}
          <div className="action-group">
            {task.retryable && !task.confirmedOrderId && <Button loading={retrying} onClick={() => void retry()}>重试处理</Button>}
            {task.purpose === 'ORDER_IMPORT' && <Link to="/orders/new">手工建单</Link>}
          </div>
        </section>}
        {!!task.hints?.length && <Alert type="warning" showIcon message="解析提示"
          description={<ul className="result-hints">{task.hints.map((hint, index) => <li key={index}>{hint}</li>)}</ul>} />}
        {task.purpose === 'ORDER_IMPORT' && task.status === 'SUCCEEDED' && <>
          {task.confirmedOrderId ? <section className="panel panel-inset">
            <h2>采购单已建立</h2><Link to={`/orders/${task.confirmedOrderId}`}>查看已建订单</Link>
          </section> : task.candidate ? <ImportConfirmation task={task} candidate={task.candidate} session={session} backTo={backTo} />
            : <section className="panel panel-inset form-stack">
              <Alert type="warning" showIcon message="当前导入支持文字型采购单，请使用手工建单" />
              <Link to="/orders/new">手工建单</Link>
            </section>}
          {task.candidate && <OriginalCandidate candidate={task.candidate} />}
        </>}
        {task.purpose === 'DELIVERY_NOTE' && task.protocolVersion === 2 && <DeliveryResult task={task} />}
        {task.pages?.length > 0 && <PageResults pages={task.pages} />}
        {!!task.sources?.length && <details className="panel panel-inset">
          <summary>识别字段来源</summary>
          <dl className="order-meta field-sources">{task.sources.map((source, index) => <div key={index}>
            <dt>{source.field} · 第 {source.pageNo} 页</dt><dd>{source.text}</dd>
          </div>)}</dl>
        </details>}
      </div>
    </div>}
  </>
}

function Original({ document }: { document: Material }) {
  const url = documentUrl(document.id)
  return <section className="panel panel-inset original-panel">
    <h2>原件</h2>
    <p className="original-name">{document.originalFilename}</p>
    <p className="muted">{purposes[document.purpose]} · {new Date(document.uploadedAt).toLocaleString('zh-CN')}</p>
    <div className="action-group original-links">
      <a href={url} target="_blank" rel="noreferrer">在新标签页打开</a>
      <a href={documentUrl(document.id, true)}>下载原件</a>
    </div>
    {document.mimeType === 'application/pdf'
      ? <iframe className="original-preview" src={url} title="原件预览" />
      : <img className="original-image" src={url} alt="原件预览" />}
  </section>
}

function OriginalCandidate({ candidate }: { candidate: Candidate }) {
  return <details className="panel panel-inset">
    <summary>原始识别值</summary>
    <dl className="order-meta">
      <div><dt>采购单号</dt><dd>{candidate.orderNo ?? '—'}</dd></div>
      <div><dt>供应商</dt><dd>{candidate.supplierName ?? '—'}</dd></div>
      <div><dt>采购日期</dt><dd>{candidate.orderedOn ?? '—'}</dd></div>
    </dl>
    <div className="table-scroll">
      <table className="data-table" aria-label="识别物料">
        <thead><tr><th>编码</th><th>物料</th><th>规格</th><th>单位</th><th>订购量</th></tr></thead>
        <tbody>{candidate.lines.map((line, index) => <tr key={index}>
          <td>{line.itemCode ?? '—'}</td><td>{line.itemName ?? '—'}</td><td>{line.specification ?? '—'}</td>
          <td>{line.unit ?? '—'}</td><td>{line.orderedQty ?? '—'}</td>
        </tr>)}</tbody>
      </table>
    </div>
  </details>
}

function ImportConfirmation({ task, candidate, session, backTo }: {
  task: TaskDetailData
  candidate: Candidate
  session: Session
  backTo: string
}) {
  const dirty = useOutletContext<RefObject<boolean>>()
  const navigate = useNavigate()
  const cache = useQueryClient()
  useEffect(() => () => { dirty.current = false }, [dirty])
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty.current &&
    nextLocation.pathname !== '/login' && (currentLocation.pathname !== nextLocation.pathname ||
      currentLocation.search !== nextLocation.search))
  useBeforeUnload(event => {
    if (dirty.current) {
      event.preventDefault()
      event.returnValue = ''
    }
  })

  async function confirm(values: OrderInput) {
    const created = await request<{ id: number }>(`/order-imports/${task.document.id}/confirm`, values, session)
    dirty.current = false
    cache.setQueryData<TaskDetailData>(taskKeys.detail(String(task.id)), { ...task, confirmedOrderId: created.id })
    void cache.invalidateQueries({ queryKey: ['orders', 'list'] })
    navigate(`/orders/${created.id}`, { replace: true })
  }

  return <>
    <h2>核对采购单</h2>
    <OrderForm initial={candidate} submitLabel="确认建单" cancelTo={backTo}
      onEdit={() => { dirty.current = true }} onSubmit={confirm} />
    <Modal title="离开采购单确认？" open={blocker.state === 'blocked'}
      okText="放弃修改" cancelText="继续编辑"
      onOk={() => blocker.proceed?.()} onCancel={() => blocker.reset?.()}>
      <p>页面上的修改尚未提交，离开后将恢复原始识别值。</p>
    </Modal>
  </>
}
