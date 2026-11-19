import { Button } from 'antd'
import type { OrderLine } from '../orders/model'
import type { DeliveryEvidence } from '../tasks/model'
import type { Position } from './ReviewOriginal'
import { dispositions, fields, impact, methods, qty, type LineEdit, type QuantityField, type ReviewSource } from './reviewModel'

export function ReviewRows({ source, lines, orderLines, navigate, update, remove }: {
  source: ReviewSource
  lines: LineEdit[]
  orderLines: OrderLine[]
  navigate: (position: Position) => void
  update?: (line: LineEdit) => void
  remove?: (key: string) => void
}) {
  const originals = new Map(source.delivery.lines.map(line => [line.lineKey, line]))
  const recommendations = new Map(source.recommendations.map(row => [row.lineKey, row]))
  const remaining = new Map(update ? impact(lines, orderLines).map(row => [row.line.id, row.remaining]) : [])
  const evidence = new Map<string, { item: DeliveryEvidence; index: number }[]>()
  source.delivery.evidence.forEach((item, index) => {
    const group = evidence.get(item.lineKey) ?? []
    group.push({ item, index })
    evidence.set(item.lineKey, group)
  })
  return lines.map((line, index) => {
    const original = originals.get(line.lineKey)
    const recommendation = recommendations.get(line.lineKey)
    const selected = orderLines.find(row => row.id === line.orderLineId)
    const label = original?.itemName ?? original?.itemCode ?? (original ? '未识别物料' : '补录')
    const changeQty = (field: QuantityField, value: number | null, evidenceIndex?: number) => {
      const selectedEvidence = { ...line.selectedEvidence }
      if (evidenceIndex === undefined) delete selectedEvidence[field]
      else selectedEvidence[field] = evidenceIndex
      update?.({ ...line, [field]: value, selectedEvidence })
    }
    return <fieldset className="review-row" key={line.lineKey}
      id={update ? `review-line-${line.lineKey}` : undefined} tabIndex={-1}>
      <legend>第 {line.pageNo} 页 · {label} · 第 {index + 1} 行</legend>
      <div className="action-group">
        <Button size="small" onClick={() => navigate({ pageNo: line.pageNo })}>查看第 {line.pageNo} 页</Button>
        {!original && remove && <Button size="small" onClick={() => remove(line.lineKey)}>删除补录</Button>}
      </div>
      {original && <p className="review-candidate">
        原识别 {original.itemCode ?? '编码待确认'} · {original.specification ?? '规格待确认'} · {original.unit ?? '单位待确认'}
        <br />送货 {qty(original.deliveredQty)} · 实收 {qty(original.acceptedQty)}
      </p>}
      {recommendation && <p className="muted">{recommendation.reason}
        {recommendation.candidates.map(candidate => <span className="review-suggestion" key={candidate.orderLineId}>
          {candidate.itemCode} {candidate.itemName} · {candidate.specification} · {candidate.unit}
        </span>)}
      </p>}
      {original && original.issues.length > 0 && <p className="review-warning">{original.issues.map(issue =>
        issueLabels[issue] ?? issue).join(' · ')}</p>}
      {!!evidence.get(line.lineKey)?.length && <details className="review-evidence"
        open={original?.issues.includes('CONFLICT') || undefined}>
        <summary>原始候选与证据</summary>
        <ul>{evidence.get(line.lineKey)?.map(({ item, index: evidenceIndex }) => {
          const field = item.field
          const quantity = field === 'acceptedQty' || field === 'deliveredQty'
          const selectedEvidence = quantity && line.selectedEvidence?.[field] === evidenceIndex
          return <li key={evidenceIndex}>
            <button className="review-evidence-link" type="button"
              onClick={() => navigate({ pageNo: item.pageNo, evidence: item })}>
              {fields[field]} {item.value ?? '待确认'} · {methods[item.method]} · 第 {item.pageNo} 页
            </button>
            <span>{item.rawText} {item.supporting ? '' : ' · 待核建议'}</span>
            {selectedEvidence && <strong> 已选</strong>}
            {update && quantity && typeof item.value === 'number' && <Button size="small" onClick={() => {
              changeQty(field, item.value as number, evidenceIndex)
              navigate({ pageNo: item.pageNo, evidence: item })
            }}>采用{fields[field]} {item.value}</Button>}
          </li>
        })}</ul>
      </details>}
      {update ? <>
        <div className="review-fields">
          <label>订单物料<select value={line.orderLineId ?? ''}
            onChange={event => update({ ...line, orderLineId: Number(event.target.value) || null })}>
            <option value="">请选择</option>
            {orderLines.map(row => <option key={row.id} value={row.id}>
              {row.itemCode} {row.itemName} · {row.specification} · {row.unit}
            </option>)}
          </select></label>
          <label>核实单位<input maxLength={16} value={line.unit ?? ''}
            onChange={event => update({ ...line, unit: event.target.value })} /></label>
          <label>核对送货数<input type="number" min={0} max={2147483647} step={1} value={line.deliveredQty ?? ''}
            onChange={event => changeQty('deliveredQty', event.target.value ? event.target.valueAsNumber : null)} /></label>
          <label>核对实收数<input type="number" min={0} max={2147483647} step={1} value={line.acceptedQty ?? ''}
            onChange={event => changeQty('acceptedQty', event.target.value ? event.target.valueAsNumber : null)} /></label>
          <label>行处置<select value={line.disposition}
            onChange={event => update({ ...line, disposition: event.target.value as LineEdit['disposition'] })}>
            {Object.entries(dispositions).map(([value, text]) => <option key={value} value={value}>{text}</option>)}
          </select></label>
        </div>
        {selected && <p className="muted">订单单位 {selected.unit} · 当前待收 {selected.remainingQty} {selected.unit}
          {' · '}预计待收 {qty(remaining.has(selected.id) ? remaining.get(selected.id) : selected.remainingQty)}</p>}
        <div className="review-fields">
          <label>处理理由<input maxLength={500} value={line.reason ?? ''}
            onChange={event => update({ ...line, reason: event.target.value })} /></label>
          <label>备注<input maxLength={500} value={line.note ?? ''}
            onChange={event => update({ ...line, note: event.target.value })} /></label>
        </div>
      </> : <div className="review-final-values">
        <strong>最终处置 {dispositions[line.disposition]}</strong>
        {selected && <span>{selected.itemCode} {selected.itemName} · {selected.specification}</span>}
        <span>核对送货 {qty(line.deliveredQty)} · 核对实收 {qty(line.acceptedQty)} {line.unit}</span>
        {line.reason && <span>处理理由 · {line.reason}</span>}
        {line.note && <span>备注 · {line.note}</span>}
      </div>}
    </fieldset>
  })
}

const issueLabels: Record<string, string> = {
  CONFLICT: '证据冲突',
  HANDWRITING_REVIEW: '手写内容待核对',
  AMBIGUOUS_QUANTITY: '数量有歧义',
  MISSING_ACCEPTED_EVIDENCE: '缺少实收证据',
  MODEL_SUGGESTION: '模型建议待核',
}
