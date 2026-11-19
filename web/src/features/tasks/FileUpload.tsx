import { Button } from 'antd'
import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { errorText, request, type Session } from '../../api'
import { Failure } from '../../ui'
import { useOrderListUrl } from '../orders/model'
import type { UploadResult } from './model'

export function FileUpload({ session, path, label, accept, button, onUploaded }: {
  session: Session
  path: string
  label: string
  accept: string
  button: string
  onUploaded: (result: UploadResult) => void
}) {
  const submitting = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    const data = new FormData(form)
    const file = data.get('file')
    setError('')
    if (!(file instanceof File) || !file.size) {
      setError('请选择非空文件')
      return
    }
    if (file.size > 20 * 1024 * 1024) {
      setError('原件不能超过 20 MiB')
      return
    }
    submitting.current = true
    setBusy(true)
    try {
      const result = await request<UploadResult>(path, data, session)
      form.reset()
      onUploaded(result)
    } catch (cause) {
      setError(errorText(cause))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return <form className="form-stack" onSubmit={upload}>
    {error && <Failure message={error} />}
    <fieldset className="file-upload" disabled={busy}>
      <label>{label}<input name="file" type="file" accept={accept} required /></label>
      <Button type="primary" htmlType="submit" loading={busy}>{button}</Button>
    </fieldset>
  </form>
}

export function OrderImport({ session }: { session: Session }) {
  const backTo = useOrderListUrl()
  const [uploaded, setUploaded] = useState<UploadResult>()
  return <>
    <Link className="back-link" to={backTo}>← 返回采购订单</Link>
    <div className="page-heading"><h1>导入采购单</h1></div>
    <section className="panel panel-inset form-stack">
      <p className="muted">请选择带文字层的 PDF，单份上限 20 MiB。上传后可离开页面，在任务中心查看处理进度。</p>
      <FileUpload session={session} path="/order-imports" label="采购单 PDF" accept=".pdf,application/pdf"
        button="上传并登记任务" onUploaded={setUploaded} />
      {uploaded && <p role="status">原件已登记。<Link to={`/tasks/${uploaded.taskId}`}>查看任务 #{uploaded.taskId}</Link></p>}
    </section>
  </>
}
