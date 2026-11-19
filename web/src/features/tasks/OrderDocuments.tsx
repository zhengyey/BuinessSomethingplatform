import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { errorText, request, type Session } from '../../api'
import { Failure, Loading } from '../../ui'
import { openingPath, receiptPath, reviewPath } from '../receipts/reviewModel'
import { FileUpload } from './FileUpload'
import { documentUrl, POLL_MS, processing, taskKeys, TaskStatus, type Material, type UploadResult } from './model'

export function OrderDocuments({ orderId, session }: { orderId: string; session: Session }) {
  const cache = useQueryClient()
  const [uploaded, setUploaded] = useState<UploadResult>()
  const path = `/orders/${encodeURIComponent(orderId)}/documents`
  const documents = useQuery({
    queryKey: taskKeys.documents(orderId),
    queryFn: ({ signal }) => request<Material[]>(path, undefined, undefined, signal),
    refetchInterval: query => query.state.data?.some(doc => processing(doc.status)) ? POLL_MS : false,
  })

  function onUploaded(result: UploadResult) {
    setUploaded(result)
    void cache.invalidateQueries({ queryKey: taskKeys.documents(orderId) })
    void cache.invalidateQueries({ queryKey: taskKeys.list })
  }

  return <section className="panel order-documents" aria-labelledby="order-documents">
    <div className="panel-inset form-stack">
      <h2 id="order-documents">签收材料</h2>
      <FileUpload session={session} path={path} label="签收材料文件" accept=".pdf,.jpg,.jpeg,.png"
        button="上传签收材料" onUploaded={onUploaded} />
      {uploaded && <p role="status">{uploaded.reused ? '材料已上传。' : '原件已登记。'}
        <Link to={`/tasks/${uploaded.taskId}`}>查看任务 #{uploaded.taskId}</Link>
        {uploaded.reused && uploaded.status && <> · <TaskStatus status={uploaded.status} /></>}
        {uploaded.reviewId && <> · <Link to={reviewPath(orderId, uploaded.reviewId)}>查看原核对</Link></>}
        {uploaded.receiptId && <> · <Link to={receiptPath(orderId, uploaded.receiptId)}>查看原收货记录</Link></>}
      </p>}
    </div>
    {documents.isPending && <Loading />}
    {documents.isError && <div className="panel-inset">
      <Failure message={errorText(documents.error)} retry={() => void documents.refetch()} />
    </div>}
    {documents.data && <div className="table-scroll">
      <table className="data-table" aria-label="签收材料列表">
        <thead><tr><th>原文件名</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          {documents.data.map(doc => <tr key={doc.id}>
            <td>{doc.originalFilename}</td>
            <td><TaskStatus status={doc.status} /></td>
            <td><div className="action-group">
              <Link to={`/tasks/${doc.taskId}`}>查看任务</Link>
              {doc.reviewId ? <Link to={reviewPath(orderId, doc.reviewId)}>
                {doc.reviewStatus === 'POSTED' ? '查看已确认核对' : doc.reviewStatus === 'CLOSED_NO_RECEIPT'
                  ? '查看无入账记录' : '继续核对'}
              </Link> : doc.reviewable && <Link to={openingPath(orderId, doc.id)}>核对签收单</Link>}
              <a href={documentUrl(doc.id)} target="_blank" rel="noreferrer">预览原件</a>
              <a href={documentUrl(doc.id, true)}>下载原件</a>
            </div></td>
          </tr>)}
          {documents.data.length === 0 && <tr><td className="empty" colSpan={3}>暂无签收材料</td></tr>}
        </tbody>
      </table>
    </div>}
  </section>
}
