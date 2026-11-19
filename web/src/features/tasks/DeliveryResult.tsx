import { Tag } from 'antd'
import { useId } from 'react'
import type { DeliveryEvidence, DeliveryField, DeliveryLine, OrderRecommendation, ParseMethod, ParsePage, TaskDetailData } from './model'

const fieldLabels: Record<DeliveryField, string> = {
  itemCode: '物料编码',
  itemName: '物料名称',
  specification: '规格',
  unit: '单位',
  deliveredQty: '送货数',
  acceptedQty: '实收候选',
}
const fields = Object.keys(fieldLabels) as DeliveryField[]

const issueLabels: Record<string, string> = {
  CONFLICT: '字段证据冲突',
  MISSING_ACCEPTED_EVIDENCE: '缺少实收依据',
  MISSING_DELIVERED_EVIDENCE: '缺少送货数量依据',
  HANDWRITING_REVIEW: '手写内容待核对',
  AMBIGUOUS_QUANTITY: '数量归属待核对',
  MODEL_SUGGESTION: '豆包建议待核对',
}

const methodLabels: Record<ParseMethod, string> = {
  PDF_TEXT: 'PDF 文字',
  PADDLE_OCR: 'PaddleOCR',
  DOUBAO: '豆包',
}

const pageStatuses = {
  TEXT: { label: '已提取文字', color: 'success' },
  NEEDS_OCR: { label: '待 OCR', color: 'default' },
  EXTRACTED: { label: '已提取', color: 'success' },
  REVIEW: { label: '待核对', color: 'warning' },
  PAGE_FAILED: { label: '该页失败', color: 'error' },
}

export function PageResults({ pages }: { pages: ParsePage[] }) {
  return <section className="panel">
    <div className="section-heading"><h2>逐页处理结果 · {pages.length} 页</h2></div>
    <div className="table-scroll">
      <table className="data-table" aria-label="逐页处理结果">
        <thead><tr><th>页码</th><th>状态</th><th>文字与提示</th></tr></thead>
        <tbody>{pages.map(page => {
          const status = pageStatuses[page.status]
          return <tr key={page.pageNo}>
            <td className="numeric">{page.pageNo}</td>
            <td><Tag color={status.color} bordered={false}>{status.label}</Tag></td>
            <td>
              {!!page.methods?.length && <div>{page.methods.map(method => methodLabels[method]).join(' · ')}</div>}
              {page.ocrCalls != null && page.doubaoCalls != null &&
                <div className="muted">OCR {page.ocrCalls} 次 · 豆包 {page.doubaoCalls} 次</div>}
              {page.text && <details><summary>查看文字</summary><pre className="page-text">{page.text}</pre></details>}
              {!!page.hints?.length && <ul className="result-hints">{page.hints.map((hint, index) => <li key={index}>{hint}</li>)}</ul>}
            </td>
          </tr>
        })}</tbody>
      </table>
    </div>
  </section>
}

export function DeliveryResult({ task }: { task: TaskDetailData }) {
  if (!task.delivery) return null
  const { lines, evidence } = task.delivery
  const pageCount = task.pages.length
  const extractedCount = task.pages.filter(page => page.status === 'EXTRACTED').length
  const reviewCount = task.pages.filter(page => page.status === 'REVIEW').length
  const failedCount = task.pages.filter(page => page.status === 'PAGE_FAILED').length
  const recommendations = new Map(task.recommendations?.map(value => [value.lineKey, value]))
  const linesByPage = new Map<number, DeliveryLine[]>()
  const evidenceByLine = new Map<string, DeliveryEvidence[]>()
  for (const line of lines) {
    const pageLines = linesByPage.get(line.pageNo) ?? []
    pageLines.push(line)
    linesByPage.set(line.pageNo, pageLines)
  }
  for (const entry of evidence) {
    const lineEvidence = evidenceByLine.get(entry.lineKey) ?? []
    lineEvidence.push(entry)
    evidenceByLine.set(entry.lineKey, lineEvidence)
  }
  return <section className="panel panel-inset form-stack delivery-results">
    <div>
      <h2>签收候选</h2>
      <p className="delivery-summary">{pageCount} 页中 {reviewCount} 页待核对 · {extractedCount} 页已提取 · {failedCount} 页失败</p>
    </div>
    {!lines.length && <p className="muted">暂无可核对的候选行。可查看逐页提示，或返回关联订单手工登记。</p>}
    {task.pages.map(page => {
      const pageLines = linesByPage.get(page.pageNo)
      if (!pageLines?.length) return null
      return <div className="form-stack" key={page.pageNo}>
        <h3>第 {page.pageNo} 页</h3>
        {pageLines.map((line, index) =>
          <CandidateLine key={line.lineKey} line={line} pageRow={index + 1}
            evidence={evidenceByLine.get(line.lineKey) ?? []} recommendation={recommendations.get(line.lineKey)} />)}
      </div>
    })}
  </section>
}

function CandidateLine({ line, pageRow, evidence, recommendation }: {
  line: DeliveryLine
  pageRow: number
  evidence: DeliveryEvidence[]
  recommendation?: OrderRecommendation
}) {
  const titleId = useId()
  const name = [line.itemCode, line.itemName].filter(Boolean).join(' ') || `第 ${pageRow} 行`
  return <section className="delivery-line" aria-labelledby={titleId}>
    <h4 id={titleId}>候选行 {name}</h4>
    <dl className="delivery-fields">
      {fields.map(field => {
        const label = fieldLabels[field]
        const entries = evidence.filter(entry => entry.field === field)
        return <div key={field}>
          <dt>{label}</dt>
          <dd>{line[field] ?? (field === 'acceptedQty' || field === 'deliveredQty' ? '待确认' : '未知')}</dd>
          {!!entries.length && <dd><FieldEvidence label={label} entries={entries} /></dd>}
        </div>
      })}
    </dl>
    {!!line.issues.length && <ul className="result-hints delivery-issues" aria-label="候选行异常">
      {line.issues.map((issue, index) => <li key={index}>{Object.hasOwn(issueLabels, issue) ? issueLabels[issue] : issue}</li>)}
    </ul>}
    {recommendation && <Recommendation recommendation={recommendation} />}
  </section>
}

function FieldEvidence({ label, entries }: { label: string; entries: DeliveryEvidence[] }) {
  return <details className="delivery-evidence">
    <summary>{label}来源</summary>
    <ul>{entries.map((entry, index) => <li key={index}>
      <p>{methodLabels[entry.method]} · 第 {entry.pageNo} 页 · {entry.precision === 'REGION' ? '区域定位' : '整页定位'}</p>
      <p className="evidence-text">{entry.rawText}</p>
      <p className="muted">证据值 {entry.value ?? '未知'}{!entry.supporting && ' · 待核对建议'}</p>
    </li>)}</ul>
  </details>
}

const recommendationLabels: Record<OrderRecommendation['status'], string> = {
  CODE_MATCH: '编码匹配',
  NAME_MATCH: '名称、规格及单位匹配 · 待确认',
  REVIEW: '订单行待核对',
  UNMATCHED: '未匹配到订单行',
}

function Recommendation({ recommendation }: { recommendation: OrderRecommendation }) {
  return <div className="delivery-recommendation">
    <h5>订单行推荐</h5>
    <p>{recommendationLabels[recommendation.status]}</p>
    <p className="muted">{recommendation.reason}</p>
    {!!recommendation.candidates.length && <ul className="result-hints">
      {recommendation.candidates.map(candidate => <li key={candidate.orderLineId}>
        {[`订单第 ${candidate.lineNo} 行`, candidate.itemCode, candidate.itemName, candidate.specification, candidate.unit].filter(Boolean).join(' · ')}
      </li>)}
    </ul>}
  </div>
}
