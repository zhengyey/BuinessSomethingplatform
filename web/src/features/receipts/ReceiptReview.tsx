import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Modal } from 'antd'
import { useEffect, useRef, useState, type RefObject } from 'react'
import { Link, useBeforeUnload, useBlocker, useNavigate, useOutletContext, useParams } from 'react-router-dom'
import { ApiError, errorText, request, type Session } from '../../api'
import { Failure, Loading } from '../../ui'
import { ReviewOriginal, type Position } from './ReviewOriginal'
import { ReviewRows } from './ReviewRows'
import { DuplicateCandidates, NoteFields, duplicateMatch, lookup, type DuplicateMatch } from './ReceiptIdentity'
import { ReceiptSnapshot, SummaryTable } from './ReceiptRecord'
import { dateTime, impact, receiptPath, reviewPath, type Edit, type LineEdit, type Outcome, type PageEdit,
  type Review } from './reviewModel'

export function ReviewEntry({ session }: { session: Session }) {
  const { orderId = '', documentId = '' } = useParams()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [attempt, retry] = useState(0)
  useEffect(() => {
    let active = true
    request<Review>(`/orders/${orderId}/documents/${documentId}/review/open`, {}, session).then(view => {
      if (active) void navigate(reviewPath(orderId, view.id), { replace: true })
    }).catch(cause => { if (active) setError(errorText(cause)) })
    return () => { active = false }
  }, [attempt, documentId, navigate, orderId, session])
  return <>
    <Link className="back-link" to={`/orders/${orderId}`}>← 返回采购单</Link>
    {error ? <Failure message={error} retry={() => { setError(''); retry(value => value + 1) }} />
      : <Loading text="正在打开签收单核对" />}
  </>
}

export function ReceiptReview({ session }: { session: Session }) {
  const { orderId = '', reviewId = '' } = useParams()
  const path = reviewPath(orderId, Number(reviewId))
  const result = useQuery({
    queryKey: ['orders', 'review', orderId, reviewId],
    queryFn: ({ signal }) => request<Review>(path, undefined, undefined, signal),
    refetchOnWindowFocus: false,
  })
  if (result.isPending) return <Loading />
  if (result.isError) return <Failure message={errorText(result.error)} retry={() => void result.refetch()} />
  return result.data.status === 'DRAFT'
    ? <Draft key={path} initial={result.data} path={path} session={session} />
    : <ReceiptSnapshot review={result.data} />
}

type Buffered = { version: number; edit: Edit }

function Draft({ initial, path, session }: { initial: Review; path: string; session: Session }) {
  const navigate = useNavigate()
  const cache = useQueryClient()
  const workspaceUnsaved = useOutletContext<RefObject<boolean>>()
  const storageKey = `labreview:unsaved:${session.username}:${path}`
  const [recovery] = useState<Buffered | null>(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as Buffered | null } catch { return null }
  })
  const [review, setReview] = useState(initial)
  const [version, setVersion] = useState(recovery?.version ?? initial.version)
  const [edit, setEdit] = useState(recovery?.edit ?? initial.edit)
  const [position, setPosition] = useState<Position>({ pageNo: initial.source.pages[0].pageNo })
  const [dirty, setDirty] = useState(Boolean(recovery))
  const [busy, setBusy] = useState(false)
  const pending = useRef(false)
  const unsaved = useRef(Boolean(recovery))
  const [failure, setFailure] = useState('')
  const [latest, setLatest] = useState<Review | null>(recovery && recovery.version !== initial.version ? initial : null)
  const [confirming, setConfirming] = useState(false)
  const [canRetryConfirmation, setCanRetryConfirmation] = useState(false)
  const [match, setMatch] = useState<DuplicateMatch | null>(null)
  const blocker = useBlocker(({ currentLocation, nextLocation }) => unsaved.current
    && !nextLocation.pathname.startsWith('/login') && currentLocation.pathname !== nextLocation.pathname)
  useBeforeUnload(event => {
    if (unsaved.current) { event.preventDefault(); event.returnValue = '' }
  })
  useEffect(() => {
    workspaceUnsaved.current = dirty
    return () => { workspaceUnsaved.current = false }
  }, [dirty, workspaceUnsaved])
  useEffect(() => {
    if (blocker.state === 'blocked') {
      if (window.confirm('修改尚未保存，仍要离开吗？')) blocker.proceed()
      else blocker.reset()
    }
  }, [blocker])

  function store(next: Edit, nextVersion = version) {
    unsaved.current = true
    setDirty(true)
    setEdit(next)
    setCanRetryConfirmation(false)
    try { sessionStorage.setItem(storageKey, JSON.stringify({ version: nextVersion, edit: next })) } catch { /* memory remains */ }
  }

  function accept(saved: Review) {
    cache.setQueryData(['orders', 'review', String(saved.orderId), String(saved.id)], saved)
    setReview(saved)
    setVersion(saved.version)
    setEdit(saved.edit)
    setLatest(null)
    unsaved.current = false
    setDirty(false)
    try { sessionStorage.removeItem(storageKey) } catch { /* storage unavailable */ }
  }

  useEffect(() => {
    if (initial.version <= version || unsaved.current) return
    setReview(initial)
    setVersion(initial.version)
    setEdit(initial.edit)
  }, [initial, version])

  const totals = impact(edit.lines, review.orderLines)
  const hasPositive = edit.lines.some(line => line.disposition === 'POST')
  const label = hasPositive ? '确认收货' : '完成核对，无入账'
  const errors = pendingItems(edit, review)
  const pageEdits = new Map(edit.pages.map(page => [page.pageNo, page]))

  function updateLine(line: LineEdit) {
    const previous = edit.lines.find(row => row.lineKey === line.lineKey)
    const membershipChanged = previous?.disposition !== line.disposition || previous?.orderLineId !== line.orderLineId
    if (membershipChanged) setMatch(null)
    store({ ...edit, ...membershipChanged && { candidateToken: null, duplicateReviewReason: null },
      lines: edit.lines.map(row => row.lineKey === line.lineKey ? line : row) })
  }

  function updatePage(page: PageEdit) {
    store({ ...edit, pages: [...edit.pages.filter(row => row.pageNo !== page.pageNo), page] })
  }

  function focus(field: string) {
    const [kind, key] = field.split(':')
    const target = kind === 'line' ? `review-line-${key}` : kind === 'page' ? `review-page-${key}` : `review-${field}`
    const element = document.getElementById(target)
    element?.scrollIntoView({ block: 'center' })
    element?.focus({ preventScroll: true })
    if (kind === 'page') setPosition({ pageNo: Number(key) })
    if (kind === 'line') {
      const row = edit.lines.find(line => line.lineKey === key)
      if (row) setPosition({ pageNo: row.pageNo })
    }
  }

  async function reloadServer(expectedVersion: number) {
    try {
      const current = await request<Review>(path)
      setReview(value => ({ ...value, orderLines: current.orderLines, sourceChanged: current.sourceChanged }))
      if (current.version !== expectedVersion || current.status !== 'DRAFT') setLatest(current)
    } catch (cause) { setFailure(errorText(cause)) }
  }

  function applyMatch(result: DuplicateMatch, value: Edit, baseVersion: number) {
    setMatch(result)
    if (value.noteStatus === 'UNKNOWN' && result.status !== 'EXACT') {
      store({ ...value, candidateToken: result.candidateToken,
        duplicateReviewReason: value.candidateToken === result.candidateToken ? value.duplicateReviewReason : null }, baseVersion)
    }
  }

  async function checkDuplicates() {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    setFailure('')
    try {
      const result = await lookup(review.orderId, edit, edit.receivedOn, totals.map(({ line }) => line.id), session)
      applyMatch(result, edit, version)
    } catch (cause) {
      setFailure(errorText(cause))
    } finally {
      pending.current = false
      setBusy(false)
    }
  }

  async function submit(action: 'save' | 'confirm' | 'retry') {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    setFailure('')
    let savedVersion = version
    let savedEdit = edit
    let confirmationSent = false
    try {
      if (action !== 'retry') {
        const saved = await request<Review>(`${path}/save`, { version, ...edit }, session)
        accept(saved)
        savedVersion = saved.version
        savedEdit = saved.edit
      }
      if (action !== 'save') {
        confirmationSent = true
        const done = await request<Outcome>(`${path}/confirm`, { version: savedVersion }, session)
        setConfirming(false)
        const target = done.receiptId ? receiptPath(review.orderId, done.receiptId) : path
        await cache.invalidateQueries({ queryKey: ['orders'] })
        if (target !== path) void navigate(target)
      }
    } catch (cause) {
      setConfirming(false)
      setFailure(errorText(cause))
      setCanRetryConfirmation(confirmationSent && (!(cause instanceof ApiError) || cause.status >= 500))
      const duplicate = duplicateMatch(cause)
      if (duplicate) applyMatch(duplicate, savedEdit, savedVersion)
      if (cause instanceof ApiError) {
        if (cause.field) focus(cause.field)
        if (cause.status === 409 && !duplicate) await reloadServer(savedVersion)
      }
    } finally {
      pending.current = false
      setBusy(false)
    }
  }

  return <>
    <Link className="back-link" to={`/orders/${review.orderId}`}>← 返回采购单</Link>
    <div className="page-heading">
      <h1>核对签收单</h1>
      <Link to={`/tasks/${review.source.taskId}`}>查看解析结果</Link>
    </div>
    {failure && <Failure message={failure} />}
    {review.sourceChanged && <Alert type="warning" showIcon message="解析结果已变更"
      description="保存与确认已暂停。可从解析结果查看最新候选并与草稿比对。" />}
    {latest && <section className="panel panel-inset form-stack">
      <h2>服务器版本 {latest.version}</h2>
      <p>由 {latest.updatedBy} 保存于 {dateTime(latest.updatedAt)}。本页保留你的输入。</p>
      <details><summary>查看服务器草稿</summary>
        <p>{latest.edit.deliveryNoteNo ?? '签收单号待填'} · {latest.edit.receivedOn ?? '到货日期待填'}</p>
        {latest.edit.pages.map(page => <p key={page.pageNo}>第 {page.pageNo} 页 · {pageStates[page.disposition]} {page.reason}</p>)}
        <ReviewRows source={latest.source} lines={latest.edit.lines} orderLines={latest.orderLines} navigate={setPosition} />
      </details>
      <div className="action-group">
        {latest.status === 'DRAFT' ? <>
          <Button onClick={() => accept(latest)}>载入服务器版本</Button>
          <Button disabled={latest.sourceChanged} onClick={() => {
            setReview(latest); setVersion(latest.version); setLatest(null); store(edit, latest.version)
          }}>保留我的编辑</Button>
        </> : <Button onClick={() => {
          accept(latest)
          void cache.invalidateQueries({ queryKey: ['orders'] })
        }}>打开已确认记录</Button>}
      </div>
    </section>}
    <div className="review-layout">
      <ReviewOriginal source={review.source} position={position} navigate={setPosition} />
      <section className="panel panel-inset review-editor">
        <fieldset className="review-form" disabled={busy || review.sourceChanged}>
          <legend>收货核对</legend>
          <div className="review-fields">
            <NoteFields value={edit} idPrefix="review-" onChange={patch => {
              if (patch.deliveryNoteNo !== undefined) setMatch(null)
              store({ ...edit, ...patch })
            }} />
            <label>到货日期<input id="review-receivedOn" type="date" value={edit.receivedOn ?? ''}
              onChange={event => {
                setMatch(null)
                store({ ...edit, receivedOn: event.target.value || null, candidateToken: null, duplicateReviewReason: null })
              }} /></label>
          </div>
          {hasPositive && <div className="action-group"><Button disabled={busy || !edit.receivedOn}
            onClick={() => void checkDuplicates()}>检查重复收货</Button></div>}
          <DuplicateCandidates match={match} note={edit} onReason={reason => store({ ...edit, duplicateReviewReason: reason })} />
          <h2>逐页核对 <span className="muted">{review.source.pages.filter(page =>
            pageEdits.get(page.pageNo)?.disposition && pageEdits.get(page.pageNo)?.disposition !== 'PENDING').length}
            {' / '}{review.source.pages.length} 页已处理</span></h2>
          {review.source.pages.map(page => {
            const pageEdit = pageEdits.get(page.pageNo) ?? { pageNo: page.pageNo, disposition: 'PENDING' }
            return <section className="review-page" key={page.pageNo} tabIndex={-1} id={`review-page-${page.pageNo}`}>
              <div className="action-group">
                <Button size="small" onClick={() => setPosition({ pageNo: page.pageNo })}>第 {page.pageNo} 页</Button>
                {page.status === 'PAGE_FAILED' && <strong className="review-warning">解析失败</strong>}
                <label>页面处置 <select aria-label={`第 ${page.pageNo} 页处置`} value={pageEdit.disposition}
                  onChange={event => updatePage({ ...pageEdit, disposition: event.target.value as PageEdit['disposition'] })}>
                  {Object.entries(pageStates).map(([value, text]) => <option key={value} value={value}>{text}</option>)}
                </select></label>
                <Button size="small" onClick={() => {
                  setPosition({ pageNo: page.pageNo })
                  store({ ...edit, lines: [...edit.lines, {
                    lineKey: `manual-${crypto.randomUUID()}`, pageNo: page.pageNo, orderLineId: null, unit: null,
                    deliveredQty: null, acceptedQty: null, disposition: 'PENDING',
                  }] })
                }}>补录本页</Button>
              </div>
              {page.hints.map(hint => <p className="muted" key={hint}>{hint}</p>)}
              {pageEdit.disposition === 'NO_DETAILS' && <label>无明细原因<input maxLength={500}
                value={pageEdit.reason ?? ''} onChange={event => updatePage({ ...pageEdit, reason: event.target.value })} /></label>}
            </section>
          })}
          <h2>物料核对</h2>
          <ReviewRows source={review.source} lines={edit.lines} orderLines={review.orderLines}
            navigate={setPosition} update={updateLine} remove={key => {
              setMatch(null)
              store({ ...edit, lines: edit.lines.filter(line => line.lineKey !== key),
                candidateToken: null, duplicateReviewReason: null })
            }} />
          {!hasPositive && <label>无入账结案原因<input id="review-noReceiptReason" maxLength={500}
            value={edit.noReceiptReason ?? ''} onChange={event => store({ ...edit, noReceiptReason: event.target.value })} /></label>}
        </fieldset>
        <h2>本次入账预览</h2>
        <SummaryTable totals={totals} />
        {errors.length > 0 && <details className="review-problems">
          <summary>{errors.length} 项待处理</summary>
          <ul>{errors.map(({ field, message }) => <li key={field}>
            <button type="button" className="review-evidence-link" onClick={() => focus(field)}>{message}</button>
          </li>)}</ul>
        </details>}
        <p role="status" className="muted">{dirty ? '有未保存的修改 · ' : ''}已保存版本 {review.version} · {dateTime(review.updatedAt)}</p>
        <div className="action-group">
          <Button loading={busy} disabled={Boolean(latest) || review.sourceChanged}
            onClick={() => void submit('save')}>保存草稿</Button>
          <Button type="primary" disabled={busy || Boolean(latest) || review.sourceChanged} onClick={() => {
            if (errors.length) { setFailure(errors[0].message); focus(errors[0].field) }
            else { setFailure(''); setConfirming(true) }
          }}>{label}</Button>
          {canRetryConfirmation && <Button disabled={busy || Boolean(latest)} onClick={() => void submit('retry')}>重试确认</Button>}
        </div>
      </section>
    </div>
    <Modal title={label} open={confirming} onCancel={() => setConfirming(false)} okText={label}
      cancelText="返回核对" confirmLoading={busy} closable={!busy} maskClosable={!busy}
      onOk={() => void submit('confirm')}>
      <SummaryTable totals={totals} />
      {!hasPositive && <p>{edit.noReceiptReason}</p>}
    </Modal>
  </>
}

const pageStates = { PENDING: '待处理', REVIEWED: '已核对明细', NO_DETAILS: '已看原件，本页无收货明细' }

function pendingItems(edit: Edit, review: Review) {
  const errors: { field: string; message: string }[] = []
  const add = (field: string, message: string) => errors.push({ field, message })
  if (edit.noteStatus === 'UNKNOWN') {
    if (!edit.unknownNoteReason?.trim()) add('unknownNoteReason', '请填写单号无法核实的原因')
  } else if (!edit.deliveryNoteNo?.trim()) add('deliveryNoteNo', '请填写签收单号')
  if (!edit.receivedOn) add('receivedOn', '请填写到货日期')
  for (const page of review.source.pages) {
    const saved = edit.pages.find(row => row.pageNo === page.pageNo)
    const rows = edit.lines.filter(row => row.pageNo === page.pageNo)
    if (!saved || saved.disposition === 'PENDING') add(`page:${page.pageNo}`, `请处理第 ${page.pageNo} 页`)
    else if (saved.disposition === 'NO_DETAILS' && (!saved.reason?.trim() || rows.some(row => row.disposition !== 'EXCLUDED'))) {
      add(`page:${page.pageNo}`, `第 ${page.pageNo} 页无明细时，请填写原因并排除本页所有行`)
    } else if (saved.disposition === 'REVIEWED' && !rows.some(row => row.disposition === 'POST' || row.disposition === 'ZERO')) {
      add(`page:${page.pageNo}`, `请补录第 ${page.pageNo} 页明细，或说明本页无收货明细`)
    }
  }
  for (const row of edit.lines) {
    const target = review.orderLines.find(line => line.id === row.orderLineId)
    const source = review.source.delivery.lines.find(line => line.lineKey === row.lineKey)
    const recommendation = review.source.recommendations.find(candidate => candidate.lineKey === row.lineKey)
    const suggested = recommendation?.status === 'CODE_MATCH' && recommendation.candidates.length === 1
      ? recommendation.candidates[0].orderLineId : null
    const field = `line:${row.lineKey}`
    if (row.disposition === 'PENDING') add(field, '请选择行处置')
    else if (row.disposition === 'EXCLUDED' && !row.reason?.trim()) add(field, '请说明排除原因')
    else if (row.disposition === 'ZERO' && (row.acceptedQty !== 0 || !row.reason?.trim())) add(field, '未收行须填写实收 0 和原因')
    else if ((row.disposition === 'POST' || (row.disposition === 'ZERO' && target)) && (!target || target.unit !== row.unit?.trim())) {
      add(field, '请选择订单物料并核实订单单位')
    } else if (row.disposition === 'POST') {
      if (row.acceptedQty === null || !Number.isInteger(row.acceptedQty) || row.acceptedQty < 1) add(field, '请输入正整数实收量')
      else if (source && !row.reason?.trim() && (source.issues.length || row.orderLineId !== suggested
        || source.unit?.trim() !== row.unit?.trim() || source.acceptedQty !== row.acceptedQty || source.deliveredQty !== row.deliveredQty)) {
        add(field, '请填写异常处置或人工修正原因')
      }
    }
  }
  if (!edit.lines.some(line => line.disposition === 'POST') && !edit.noReceiptReason?.trim()) {
    add('noReceiptReason', '请填写无入账结案原因')
  }
  return errors
}
