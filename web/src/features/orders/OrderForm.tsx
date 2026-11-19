import { Button } from 'antd'
import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { errorText, MAX_QUANTITY } from '../../api'
import { Failure } from '../../ui'

type LineInput = { itemCode: string; itemName: string; specification: string; unit: string; orderedQty: number }
export type OrderInput = { orderNo: string; supplierName: string; orderedOn: string; lines: LineInput[] }
type Nullable<T> = { [K in keyof T]: T[K] | null }
export type Candidate = Nullable<Omit<OrderInput, 'lines'>> & { lines: Nullable<LineInput>[] }

export function OrderForm({ initial, submitLabel, cancelTo, onSubmit, onEdit }: {
  initial: Candidate
  submitLabel: string
  cancelTo: string
  onSubmit: (values: OrderInput) => Promise<void>
  onEdit?: () => void
}) {
  const nextId = useRef(Math.max(1, initial.lines.length) + 1)
  const [rows, setRows] = useState(() => Array.from({ length: Math.max(1, initial.lines.length) }, (_, i) => i + 1))
  const submitting = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const values = new FormData(event.currentTarget)
    const field = (name: string) => String(values.get(name) ?? '').trim()
    const lines = rows.map(id => ({
      itemCode: field(`code-${id}`), itemName: field(`name-${id}`),
      specification: field(`spec-${id}`), unit: field(`unit-${id}`),
      orderedQty: Number(field(`qty-${id}`)),
    }))
    submitting.current = true
    setBusy(true)
    setError('')
    try {
      await onSubmit({
        orderNo: field('orderNo'), supplierName: field('supplierName'), orderedOn: field('orderedOn'), lines,
      })
    } catch (cause) {
      setError(errorText(cause))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return <form onSubmit={create} onChange={onEdit} className="form-stack">
    {error && <Failure message={error} />}
    <fieldset className="panel panel-inset entry-fields" disabled={busy}>
      <label>采购单号<input name="orderNo" maxLength={64} defaultValue={initial.orderNo ?? ''} required /></label>
      <label>供应商<input name="supplierName" maxLength={120} defaultValue={initial.supplierName ?? ''} required /></label>
      <label>采购日期<input type="date" name="orderedOn" defaultValue={initial.orderedOn ?? ''} required /></label>
    </fieldset>
    <section className="panel panel-inset">
      <h2>订购物料</h2>
      <div className="form-stack">
        {rows.map((id, index) => <fieldset className="line-editor" key={id} disabled={busy}>
          <legend>物料行 {index + 1}</legend>
          <div className="purchase-fields">
            <label>物料编码<input name={`code-${id}`} maxLength={64} defaultValue={initial.lines[id - 1]?.itemCode ?? ''} required /></label>
            <label>物料名称<input name={`name-${id}`} maxLength={120} defaultValue={initial.lines[id - 1]?.itemName ?? ''} required /></label>
            <label>规格<input name={`spec-${id}`} maxLength={120} defaultValue={initial.lines[id - 1]?.specification ?? ''} required /></label>
            <label>单位<input name={`unit-${id}`} maxLength={16} defaultValue={initial.lines[id - 1]?.unit ?? ''} required /></label>
            <label>订购量<input name={`qty-${id}`} type="number" min={1} max={MAX_QUANTITY} step={1}
              defaultValue={initial.lines[id - 1]?.orderedQty ?? ''} required /></label>
            <Button danger type="text" disabled={rows.length === 1 || busy}
              onClick={() => { onEdit?.(); setRows(current => current.filter(row => row !== id)) }}>移除</Button>
          </div>
        </fieldset>)}
        <div><Button disabled={busy} onClick={() => {
          const id = nextId.current++
          onEdit?.()
          setRows(current => [...current, id])
        }}>添加物料行</Button></div>
      </div>
    </section>
    <div className="form-actions">
      <Link to={cancelTo} className="quiet-link">取消</Link>
      <Button type="primary" htmlType="submit" loading={busy}>{submitLabel}</Button>
    </div>
  </form>
}
