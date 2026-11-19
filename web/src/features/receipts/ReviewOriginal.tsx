import { Button } from 'antd'
import { useEffect, useRef, useState } from 'react'
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'
import { Failure, Loading } from '../../ui'
import { documentUrl, type DeliveryEvidence } from '../tasks/model'
import type { ReviewSource } from './reviewModel'

export type Position = { pageNo: number; evidence?: DeliveryEvidence }

export function ReviewOriginal({ source, position, navigate }: {
  source: ReviewSource; position: Position; navigate: (position: Position) => void
}) {
  const pageIndex = source.pages.findIndex(page => page.pageNo === position.pageNo)
  const metrics = source.pages[pageIndex]
  const [zoom, setZoom] = useState(1)
  const [failedImage, setFailedImage] = useState(false)
  const viewport = useRef<HTMLDivElement>(null)
  const paper = useRef<HTMLDivElement>(null)
  const highlight = useRef<HTMLDivElement>(null)
  const url = documentUrl(source.documentId)
  const bbox = position.evidence?.precision === 'REGION' ? position.evidence.bbox : null
  useEffect(() => {
    const scroller = viewport.current
    const content = paper.current
    if (!scroller || !content) return
    const align = () => {
      const mark = highlight.current
      if (!mark) return
      const area = scroller.getBoundingClientRect()
      const region = mark.getBoundingClientRect()
      if (region.top < area.top || region.bottom > area.bottom) scroller.scrollTop += region.top - area.top - 24
      if (region.left < area.left || region.right > area.right) scroller.scrollLeft += region.left - area.left - 24
    }
    if (!bbox) scroller.scrollTo(0, 0)
    if (position.evidence && window.matchMedia('(max-width: 980px)').matches) {
      scroller.scrollIntoView({ block: 'nearest' })
    }
    align()
    const resize = new ResizeObserver(align)
    resize.observe(content)
    return () => resize.disconnect()
  }, [bbox, position])
  const move = (offset: number) => {
    const target = source.pages[pageIndex + offset]
    if (target) navigate({ pageNo: target.pageNo })
  }
  return <section className="panel review-original" aria-label="签收原件">
    <div className="review-original-header">
      <strong>{source.originalFilename}</strong>
      <div className="action-group">
        <a href={url} target="_blank" rel="noreferrer">打开原件</a>
        <a href={documentUrl(source.documentId, true)}>下载原件</a>
      </div>
      <div className="action-group">
        <Button size="small" disabled={pageIndex <= 0} onClick={() => move(-1)}>上一页</Button>
        <label>第 <select aria-label="原件页码" value={position.pageNo}
          onChange={event => navigate({ pageNo: Number(event.target.value) })}>
          {source.pages.map(page => <option key={page.pageNo} value={page.pageNo}>{page.pageNo}</option>)}
        </select> 页 / {source.pages.length} 页</label>
        <Button size="small" disabled={pageIndex >= source.pages.length - 1} onClick={() => move(1)}>下一页</Button>
        <Button size="small" aria-label="缩小" disabled={zoom <= .5} onClick={() => setZoom(value => value - .25)}>−</Button>
        <Button size="small" aria-label="放大" disabled={zoom >= 3} onClick={() => setZoom(value => value + .25)}>＋</Button>
        <Button size="small" onClick={() => setZoom(1)}>适应宽度</Button>
        <span className="muted">{Math.round(zoom * 100)}%</span>
      </div>
    </div>
    <div className="review-viewport" ref={viewport} tabIndex={0} aria-label="原件视口，方向键左右翻页"
      onKeyDown={event => {
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
          event.preventDefault()
          move(event.key === 'ArrowLeft' ? -1 : 1)
        }
      }}>
      <div className="review-paper" ref={paper} style={{ width: `${zoom * 100}%` }}>
        {source.mimeType === 'application/pdf'
          ? <PdfPage url={url} pageNo={position.pageNo} zoom={zoom} ratio={metrics?.width && metrics.height
            ? metrics.width / metrics.height : undefined} />
          : failedImage ? <Failure message="无法读取原图" /> : <img src={url} alt={source.originalFilename}
            onError={() => setFailedImage(true)} />}
        {bbox && <div className="review-highlight" ref={highlight} aria-label="证据高亮" style={{
          left: `${bbox[0] * 100}%`, top: `${bbox[1] * 100}%`,
          width: `${(bbox[2] - bbox[0]) * 100}%`, height: `${(bbox[3] - bbox[1]) * 100}%`,
        }} />}
      </div>
    </div>
    {position.evidence && <p className="review-position" role="status">第 {position.pageNo} 页
      {bbox ? ' · 已定位区域' : ' · 页级来源'}</p>}
  </section>
}

function PdfPage({ url, pageNo, zoom, ratio }: { url: string; pageNo: number; zoom: number; ratio?: number }) {
  const [document, setDocument] = useState<PDFDocumentProxy>()
  const [loadError, setLoadError] = useState(false)
  const [renderError, setRenderError] = useState(false)
  const [rendered, setRendered] = useState(false)
  const host = useRef<HTMLDivElement>(null)
  useEffect(() => {
    let cancelled = false
    let destroy: (() => void) | undefined
    const controller = new AbortController()
    void Promise.all([import('pdfjs-dist/legacy/build/pdf.mjs'), fetch(url, { signal: controller.signal }).then(response => {
      if (!response.ok) throw new Error(`PDF HTTP ${response.status}`)
      return response.arrayBuffer()
    })]).then(async ([pdf, bytes]) => {
      if (cancelled) return
      pdf.GlobalWorkerOptions.workerSrc = workerUrl
      const loading = pdf.getDocument({ data: new Uint8Array(bytes), isEvalSupported: false })
      destroy = () => { void loading.destroy().catch(() => undefined) }
      const loaded = await loading.promise
      if (!cancelled) setDocument(loaded)
    }).catch(() => { if (!cancelled) setLoadError(true) })
    return () => { cancelled = true; controller.abort(); destroy?.() }
  }, [url])

  useEffect(() => {
    if (!document || !host.current) return
    const target = host.current
    let cancelled = false
    let task: RenderTask | undefined
    setRendered(false)
    setRenderError(false)
    void document.getPage(pageNo).then(async page => {
      if (cancelled) return
      const base = page.getViewport({ scale: 1 })
      const pixelWidth = Math.min(3000, Math.max(900 * zoom, target.clientWidth * window.devicePixelRatio))
      const viewport = page.getViewport({ scale: pixelWidth / base.width })
      const canvas = window.document.createElement('canvas')
      canvas.width = Math.ceil(viewport.width)
      canvas.height = Math.ceil(viewport.height)
      canvas.setAttribute('role', 'img')
      canvas.setAttribute('aria-label', `原件第 ${pageNo} 页`)
      task = page.render({ canvas, viewport })
      await task.promise
      if (cancelled) return
      target.replaceChildren(canvas)
      setRendered(true)
    }).catch(() => { if (!cancelled) setRenderError(true) })
    return () => {
      cancelled = true
      task?.cancel()
      target.replaceChildren()
    }
  }, [document, pageNo, zoom])

  return <>
    <div ref={host} className="review-pdf" style={rendered ? undefined : { aspectRatio: ratio ?? 0.707 }} />
    {!rendered && <div className="review-pdf-status">{loadError || renderError
      ? <Failure message="无法显示此 PDF 页面，请打开原件核对" /> : <Loading text="正在载入原件" />}</div>}
  </>
}
