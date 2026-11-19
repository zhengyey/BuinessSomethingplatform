import { expect, test, type Page } from '@playwright/test'
import legacyResult from '../../../contracts/parse-result/v1-delivery.json' with { type: 'json' }
import type { TaskDetailData } from '../../src/features/tasks/model'

const legacy: TaskDetailData = {
  id: 42, status: 'SUCCEEDED', purpose: 'DELIVERY_NOTE', documentId: 17,
  originalFilename: 'DN-mixed.pdf', createdAt: '2026-11-18T00:00:00Z', attemptNo: 1,
  document: {
    id: 17, orderId: 9, purpose: 'DELIVERY_NOTE', originalFilename: 'DN-mixed.pdf',
    mimeType: 'application/pdf', byteSize: 100, sha256: '0'.repeat(64),
    uploadedAt: '2026-11-18T00:00:00Z', uploadedBy: 'receiver', taskId: 42, status: 'SUCCEEDED',
  },
  pages: legacyResult.pages.map(page => ({ ...page, status: 'NEEDS_OCR' })),
  candidate: null, sources: [], hints: [], confirmedOrderId: null, failureCode: null,
  failureMessage: null, retryable: false, parserVersion: 'fixture',
}

async function openDetail(page: Page, task: unknown) {
  const writes: string[] = []
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') writes.push(path)
    if (path === '/api/session') {
      await route.fulfill({ json: { username: 'receiver', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'fixture' } })
    } else if (path === '/api/tasks/42') {
      await route.fulfill({ json: task })
    } else if (path === '/api/documents/17/content') {
      await route.fulfill({ status: 204 })
    } else {
      await route.fulfill({ status: 404 })
    }
  })
  await page.goto('/tasks/42')
  return writes
}

test('签收候选保留实收冲突证据与明确零值，部分页失败后可继续核对', async ({ page }) => {
  const pageMetrics = { width: 595, height: 842, rotation: 0, doubaoCalls: 0 }
  const conflictingEvidence = [
    { method: 'PDF_TEXT', value: 6, rawText: '实收数量 6', bbox: [0.4, 0.2, 0.5, 0.25], precision: 'REGION', supporting: true },
    { method: 'PADDLE_OCR', value: 5, rawText: '手写实收 5', bbox: [0.45, 0.21, 0.5, 0.25], precision: 'REGION', supporting: true },
    { method: 'DOUBAO', value: 5, rawText: '可能为 5', bbox: null, precision: 'PAGE', supporting: false },
  ].map(value => ({ ...value, field: 'acceptedQty', lineKey: 'p1-r1', pageNo: 1 }))
  const quantityEvidence = [
    { lineKey: 'p1-r1', field: 'deliveredQty', value: 6, rawText: '送货数量 6' },
    { lineKey: 'p1-r2', field: 'deliveredQty', value: 3, rawText: '送货数量 3' },
    { lineKey: 'p1-r2', field: 'acceptedQty', value: 0, rawText: '实收数量 0' },
  ].map(value => ({ ...value, pageNo: 1, method: 'PDF_TEXT', bbox: [0.4, 0.2, 0.5, 0.25], precision: 'REGION', supporting: true }))
  const writes = await openDetail(page, {
    ...legacy,
    protocolVersion: 2,
    pages: [
      { ...pageMetrics, pageNo: 1, status: 'REVIEW', text: '签收单 DN-002', hints: ['豆包模型部分建议无法核对'], methods: ['PDF_TEXT', 'PADDLE_OCR', 'DOUBAO'], ocrCalls: 1, doubaoCalls: 1 },
      { ...pageMetrics, pageNo: 2, status: 'PAGE_FAILED', text: null, hints: ['页面渲染失败', '豆包模型不可用'], methods: ['PDF_TEXT'], ocrCalls: 0 },
    ],
    delivery: {
      lines: [
        { lineKey: 'p1-r1', pageNo: 1, itemCode: 'G-01', itemName: '丁腈手套', specification: 'M', unit: '箱', deliveredQty: 6, acceptedQty: null, issues: ['CONFLICT'] },
        { lineKey: 'p1-r2', pageNo: 1, itemCode: 'F-02', itemName: '滤芯', specification: '0.22 μm', unit: '盒', deliveredQty: 3, acceptedQty: 0, issues: [] },
      ],
      evidence: [...conflictingEvidence, ...quantityEvidence],
    },
    recommendations: [{
      lineKey: 'p1-r1', status: 'CODE_MATCH', reason: '编码唯一匹配，规格和单位一致',
      candidates: [{ orderLineId: 71, lineNo: 1, itemCode: 'G-01', itemName: '丁腈手套', specification: 'M', unit: '箱' }],
    }],
  })
  const pages = page.getByRole('table', { name: '逐页处理结果' })
  await expect(page.getByText('2 页中 1 页待核对 · 0 页已提取 · 1 页失败')).toBeVisible()
  await expect(pages.getByText('该页失败', { exact: true })).toBeVisible()
  await expect(pages.getByText('页面渲染失败')).toBeVisible()
  await expect(pages.getByText('豆包模型不可用')).toBeVisible()
  const first = page.getByRole('region', { name: '候选行 G-01 丁腈手套' })
  await expect(first.getByText('字段证据冲突')).toBeVisible()
  await expect(first.getByText('待确认', { exact: true })).toBeVisible()
  await first.getByText('实收候选来源', { exact: true }).click()
  await expect(first.getByText('实收数量 6', { exact: true })).toBeVisible()
  await expect(first.getByText('手写实收 5', { exact: true })).toBeVisible()
  await expect(first.getByText('可能为 5', { exact: true })).toBeVisible()
  await expect(first.getByText(/PaddleOCR · 第 1 页/)).toBeVisible()
  await expect(first.getByText('编码唯一匹配，规格和单位一致')).toBeVisible()
  await expect(first.getByText('订单第 1 行 · G-01 · 丁腈手套 · M · 箱')).toBeVisible()
  const second = page.getByRole('region', { name: '候选行 F-02 滤芯' })
  await expect(second.getByText('0', { exact: true })).toBeVisible()
  await page.reload()
  await expect(second.getByText('0', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '下载原件' })).toHaveAttribute('href', '/api/documents/17/content?download=true')
  await expect(page.getByRole('link', { name: '返回关联订单' })).toHaveAttribute('href', '/orders/9')
  expect(writes).toEqual([])
})

test('旧版签收任务保留待 OCR 页状态', async ({ page }) => {
  await openDetail(page, legacy)
  await expect(page.getByRole('table', { name: '逐页处理结果' }).getByText('待 OCR', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '签收候选' })).toHaveCount(0)
})
