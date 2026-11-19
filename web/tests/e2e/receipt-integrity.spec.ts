import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import sample from '../../../samples/phase-01/expected.json' with { type: 'json' }
import type { ReceiptRecord } from '../../src/features/receipts/reviewModel'

test('无号收货先核重，记住原收货引用和人工判断', async ({ page }) => {
  await page.goto('/orders')
  await page.getByLabel('用户名').fill(process.env.LABREVIEW_USERNAME ?? 'receiver')
  await page.getByLabel('密码').fill(process.env.LABREVIEW_PASSWORD ?? '')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '采购订单' })).toBeVisible()
  const setup = await page.evaluate(async ({ fixture, suffix }) => {
    const session = await (await fetch('/api/session')).json()
    const headers = { [session.csrfHeader]: session.csrfToken, 'Content-Type': 'application/json' }
    const create = async (orderNo: string) => {
      const response = await fetch('/api/orders', { method: 'POST', headers, body: JSON.stringify({
        supplierName: `无号验收 ${suffix}`, orderNo, orderedOn: fixture.orderedOn, lines: [fixture.lines[0]],
      }) })
      if (!response.ok) throw new Error(await response.text())
      const { id } = await response.json()
      const order = await (await fetch(`/api/orders/${id}`)).json()
      return { id: id as number, lineId: order.lines[0].id as number }
    }
    const original = await create(`P5-REF-${suffix}`)
    const current = await create(`P5-UNKNOWN-${suffix}`)
    const response = await fetch(`/api/orders/${original.id}/receipts`, { method: 'POST', headers, body: JSON.stringify({
      deliveryNoteNo: 'DN-REFERENCE', receivedOn: '2026-11-18', lines: [{ orderLineId: original.lineId, acceptedQty: 5 }],
    }) })
    if (!response.ok) throw new Error(await response.text())
    return { original, current, receiptId: (await response.json()).id as number }
  }, { fixture: sample.purchaseOrder, suffix: randomUUID() })

  await page.goto(`/orders/${setup.current.id}`)
  await page.getByRole('button', { name: '登记本次到货' }).click()
  await page.getByLabel('单号无法核实').check()
  await page.getByLabel('缺号原因').fill('原件缺号')
  await page.getByLabel('到货日期').fill('2026-11-18')
  await page.getByRole('combobox', { name: '订单物料' }).selectOption(String(setup.current.lineId))
  await page.getByLabel('实收数').fill('5')
  await page.getByRole('button', { name: '确认收货', exact: true }).click()
  const candidates = page.getByRole('region', { name: '签收查重结果' })
  await expect(candidates).toContainText('可能是同一次到货，请核实')
  await expect(candidates.getByRole('link', { name: /DN-REFERENCE/ })).toHaveAttribute('href',
    `/orders/${setup.original.id}/receipts/${setup.receiptId}`)
  await expect(candidates).toContainText('实收 5 箱')
  await page.getByLabel('核实为另一次到货的理由').fill('签收时间和交货交接记录为另一次到货')
  await page.getByRole('button', { name: '确认收货', exact: true }).click()
  await page.getByRole('button', { name: '已收 5 箱' }).click()
  await page.getByRole('link', { name: '单号无法核实', exact: true }).click()
  await expect(page.getByRole('heading', { name: '收货记录 · 单号无法核实' })).toBeVisible()
  await expect(page.getByText('缺号原因 · 原件缺号')).toBeVisible()
  await expect(page.getByText(/签收时间和交货交接记录为另一次到货/)).toBeVisible()
})

test('更正后的合并收货仍可追溯每一页的原确认数量', async ({ page }) => {
  const item = { id: 71, lineNo: 1, ...sample.purchaseOrder.lines[0], acceptedQty: 4, remainingQty: 6 }
  const date = '2026-11-18T00:00:00Z'
  const sourceLines = [2, 3].map((qty, i) => ({
    lineKey: `p${i + 1}-r1`, pageNo: i + 1, itemCode: item.itemCode, itemName: item.itemName,
    specification: item.specification, unit: item.unit, deliveredQty: qty, acceptedQty: qty, issues: [],
  }))
  const record: ReceiptRecord = {
    header: { id: 19, deliveryNoteNo: 'DN-MULTIPAGE', receivedOn: '2026-11-18', confirmedBy: 'receiver',
      confirmedAt: date, duplicateCandidates: null },
    source: 'MATERIAL',
    lines: [{ ...item, receiptLineId: 110, orderLineId: item.id, deliveredQty: 5, acceptedQty: 4,
      originalAcceptedQty: 5, effectiveAcceptedQty: 4, note: null, revision: 1 }],
    adjustments: [{ id: 1, receiptLineId: 110, revision: 1, beforeQty: 5, afterQty: 4,
      reason: '重新清点', actor: 'receiver', createdAt: date }],
    review: {
      id: 11, orderId: 9, version: 2, status: 'POSTED', updatedAt: date, updatedBy: 'receiver',
      confirmedAt: date, confirmedBy: 'receiver', receiptId: 19, sourceChanged: false, orderLines: [item],
      edit: {
        deliveryNoteNo: 'DN-MULTIPAGE', receivedOn: '2026-11-18', noReceiptReason: null,
        pages: [1, 2].map(pageNo => ({ pageNo, disposition: 'REVIEWED' })),
        lines: sourceLines.map(line => ({
          lineKey: line.lineKey, pageNo: line.pageNo, orderLineId: item.id, unit: line.unit,
          deliveredQty: line.deliveredQty, acceptedQty: line.acceptedQty, disposition: 'POST',
        })),
      },
      source: {
        documentId: 17, taskId: 42, resultHash: 'a'.repeat(64), originalFilename: 'DN-mixed.pdf',
        mimeType: 'application/pdf', recommendations: [],
        pages: [1, 2].map(pageNo => ({ pageNo, status: 'EXTRACTED', text: null, hints: [] })),
        delivery: { lines: sourceLines, evidence: sourceLines.map(line => ({
          lineKey: line.lineKey, pageNo: line.pageNo, field: 'acceptedQty', method: 'PDF_TEXT',
          rawText: `第 ${line.pageNo} 页原实收 ${line.acceptedQty}`, value: line.acceptedQty,
          precision: 'PAGE', bbox: null, supporting: true,
        })) },
      },
    },
  }
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/session') return route.fulfill({ json: { username: 'receiver', csrfHeader: 'CSRF', csrfToken: 'fixture' } })
    if (path === '/api/orders/9/receipts/19') return route.fulfill({ json: record })
    if (path === '/api/documents/17/content') return route.fulfill({
      contentType: 'application/pdf',
      path: fileURLToPath(new URL('../../../samples/phase-03/DN-mixed.pdf', import.meta.url)),
    })
    return route.fulfill({ status: 404 })
  })
  await page.goto('/orders/9/receipts/19')
  await expect(page.getByRole('row', { name: /G-01/ })).toContainText('5 箱')
  await expect(page.getByRole('row', { name: /G-01/ })).toContainText('4 箱')
  await expect(page.getByRole('region', { name: '历次更正' })).toContainText('5 → 4')
  for (const line of sourceLines) {
    const source = page.getByRole('group', { name: new RegExp(`第 ${line.pageNo} 页 · 丁腈手套`) })
    await expect(source).toContainText(`核对实收 ${line.acceptedQty} 箱`)
    await source.getByText('原始候选与证据').click()
    await source.getByRole('button', { name: /^实收数/ }).click()
    await expect(page.getByRole('img', { name: `原件第 ${line.pageNo} 页` })).toBeVisible()
  }
})
