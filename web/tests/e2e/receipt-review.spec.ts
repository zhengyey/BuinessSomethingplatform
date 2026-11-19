import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import sample from '../../../samples/phase-01/expected.json' with { type: 'json' }
import type { Review } from '../../src/features/receipts/reviewModel'

const fixture = (phase: string, file: string) =>
  fileURLToPath(new URL(`../../../samples/${phase}/${file}`, import.meta.url))

test('真实签收解析、两批入账、重复上传与重新编码查重、实收更正', async ({ page }) => {
  test.setTimeout(180_000)
  await page.goto('/orders')
  await page.getByLabel('用户名').fill(process.env.LABREVIEW_USERNAME ?? 'receiver')
  await page.getByLabel('密码').fill(process.env.LABREVIEW_PASSWORD ?? '')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '采购订单' })).toBeVisible()

  const { file: _file, ...purchaseOrder } = sample.purchaseOrder
  const orderNo = `PO-P4-${randomUUID()}`
  const orderId = await page.evaluate(async input => {
    const session = await (await fetch('/api/session')).json()
    const response = await fetch('/api/orders', {
      method: 'POST', headers: { [session.csrfHeader]: session.csrfToken, 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    })
    if (response.status !== 201) throw new Error(await response.text())
    return (await response.json()).id as number
  }, { ...purchaseOrder, orderNo, supplierName: `材料核对测试 ${orderNo}` })
  const orderUrl = `/orders/${orderId}`
  const balances = async () => (await (await page.request.get(`/api${orderUrl}`)).json()).lines
    .map((line: { remainingQty: number }) => line.remainingQty)
  await page.goto(orderUrl)
  expect(await balances()).toEqual([10, 5])
  const records: string[] = []

  for (const [index, delivery] of sample.deliveries.entries()) {
    await page.getByLabel('签收材料文件').setInputFiles(fixture('phase-01', delivery.file))
    await page.getByRole('button', { name: '上传签收材料' }).click()
    const material = page.getByRole('row').filter({ hasText: delivery.file })
    const link = material.getByRole('link', { name: '核对签收单' })
    await expect(link).toBeVisible({ timeout: 90_000 })
    await link.click()
    await expect(page.getByRole('heading', { name: '核对签收单' })).toBeVisible()
    if (index === 1) await expect(page.getByRole('img', { name: '原件第 1 页' })).toBeVisible({ timeout: 15_000 })
    await page.getByLabel('签收单号', { exact: true }).fill(delivery.deliveryNoteNo)
    await page.getByLabel('到货日期', { exact: true }).fill(delivery.receivedOn)
    await page.getByLabel('第 1 页处置').selectOption('REVIEWED')
    for (const [rowIndex, expected] of delivery.lines.entries()) {
      const row = page.locator('.review-row').nth(rowIndex)
      await expect(row.getByLabel('核对送货数')).toHaveValue(String(expected.deliveredQty))
      await expect(row.getByLabel('核对实收数')).toHaveValue(String(expected.acceptedQty))
      const details = row.locator('details')
      if (await details.getAttribute('open') === null) await details.getByText('原始候选与证据').click()
      await row.getByRole('button', { name: new RegExp(`^实收数 ${expected.acceptedQty} ·`) }).first().click()
      await expect(page.getByLabel('证据高亮')).toBeVisible()
      const select = row.getByLabel('订单物料')
      const option = select.getByRole('option', { name: new RegExp(expected.itemCode) })
      await select.selectOption(await option.getAttribute('value') ?? '')
      await row.getByLabel('核实单位').fill(expected.unit)
      await row.getByLabel('行处置').selectOption('POST')
      await row.getByLabel('处理理由').fill(index === 0 ? '已核对手写实收 5 箱，短 1 箱' : '与原件一致')
    }
    await page.getByRole('button', { name: '保存草稿' }).click()
    await expect(page.getByText(/已保存版本 2/)).toBeVisible()
    expect(await balances()).toEqual(index === 0 ? [10, 5] : [5, 5])
    await page.getByRole('link', { name: '返回采购单' }).click()
    await material.getByRole('link', { name: '继续核对' }).click()
    await expect(page.getByLabel('签收单号')).toHaveValue(delivery.deliveryNoteNo)
    await page.reload()
    await expect(page.getByLabel('签收单号')).toHaveValue(delivery.deliveryNoteNo)
    await page.getByRole('button', { name: '确认收货', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '确认收货', exact: true }).click()
    await expect(page.getByRole('heading', { name: `收货记录 · ${delivery.deliveryNoteNo}` })).toBeVisible()
    await expect(page.getByText('已确认入账')).toBeVisible()
    await expect(page.locator('.review-row').first()).toContainText(`核对实收 ${delivery.lines[0].acceptedQty}`)
    records.push(new URL(page.url()).pathname)
    expect(await balances()).toEqual(index === 0 ? [5, 5] : [0, 2])
    await page.getByRole('link', { name: '返回采购单' }).click()
  }

  await page.getByRole('row', { name: /G-01.*丁腈手套/ }).getByRole('button', { name: '已收 10 箱' }).click()
  const history = page.getByRole('region', { name: 'G-01 收货记录' })
  for (const [index, delivery] of sample.deliveries.entries()) {
    await expect(history.getByRole('link', { name: delivery.deliveryNoteNo })).toHaveAttribute('href', records[index])
  }
  await history.getByRole('link', { name: 'DN-001' }).click()
  await expect(page.getByRole('heading', { name: '收货记录 · DN-001' })).toBeVisible()
  await expect(page.getByText('已核对手写实收 5 箱，短 1 箱', { exact: false })).toBeVisible()
  await page.getByRole('link', { name: '返回采购单' }).click()
  const firstPhoto = await readFile(fixture('phase-01', 'DN-001.jpg'))
  await page.getByLabel('签收材料文件').setInputFiles({ name: 'DN-001-renamed.jpg', mimeType: 'image/jpeg', buffer: firstPhoto })
  const reuseResponse = page.waitForResponse(response => response.url().endsWith(`/api${orderUrl}/documents`)
    && response.request().method() === 'POST')
  await page.getByRole('button', { name: '上传签收材料' }).click()
  const reused = await reuseResponse
  expect(reused.status()).toBe(200)
  const original = await reused.json()
  expect(original.reused).toBe(true)
  await expect(page.getByText('材料已上传。', { exact: false })).toBeVisible()
  await expect(page.getByRole('link', { name: '查看原收货记录', exact: true })).toHaveAttribute('href', records[0])

  // Add a JPEG COM segment after SOI. It changes the bytes while leaving the same paper and pixels.
  const comment = Buffer.from('phase-five re-encoded source')
  const segmentLength = Buffer.alloc(2)
  segmentLength.writeUInt16BE(comment.length + 2)
  const reencoded = Buffer.concat([firstPhoto.subarray(0, 2), Buffer.from([0xff, 0xfe]), segmentLength, comment,
    firstPhoto.subarray(2)])
  await page.getByLabel('签收材料文件').setInputFiles({ name: 'DN-001-reencoded.jpg', mimeType: 'image/jpeg', buffer: reencoded })
  await page.getByRole('button', { name: '上传签收材料' }).click()
  const another = page.getByRole('row').filter({ hasText: 'DN-001-reencoded.jpg' })
    .getByRole('link', { name: '核对签收单' })
  await expect(another).toBeVisible({ timeout: 60_000 })
  await another.click()
  await page.getByLabel('签收单号', { exact: true }).fill(' dn-001 ')
  await page.getByLabel('到货日期').fill('2026-11-18')
  await page.getByLabel('第 1 页处置').selectOption('REVIEWED')
  const duplicateRow = page.locator('.review-row').first()
  await duplicateRow.getByLabel('行处置').selectOption('POST')
  await duplicateRow.getByLabel('处理理由').fill('复核为原签收单')
  await page.getByRole('button', { name: '确认收货', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认收货', exact: true }).click()
  const conflict = page.getByRole('region', { name: '签收查重结果' })
  await expect(conflict).toContainText('已处理，查看原记录')
  await expect(conflict.getByRole('link', { name: /DN-001/ })).toHaveAttribute('href', records[0])
  expect(await balances()).toEqual([0, 2])

  await conflict.getByRole('link', { name: /DN-001/ }).click()
  await page.getByRole('button', { name: '更正实收' }).click()
  await page.getByLabel('新实收数').fill('4')
  await page.getByLabel('更正原因').fill('复点确认第一批实收 4 箱')
  await expect(page.getByText('预计待收 1 箱')).toBeVisible()
  await page.getByRole('button', { name: '提交更正' }).click()
  await expect(page.getByRole('region', { name: '历次更正' })).toContainText('5 → 4')
  await expect(page.getByRole('region', { name: '历次更正' })).toContainText('复点确认第一批实收 4 箱')
  await expect(page.locator('.review-row').first()).toContainText('核对实收 5')
  expect(await balances()).toEqual([1, 2])

  await page.getByRole('link', { name: '返回采购单' }).click()
  await page.getByRole('row', { name: /G-01.*丁腈手套/ }).getByRole('button', { name: '已收 9 箱' }).click()
  await expect(history.getByRole('row').filter({ hasText: 'DN-001' })).toContainText('原确认 5 箱')
  await expect(history.getByRole('row').filter({ hasText: 'DN-002' })).toContainText('5 箱')
  await test.info().attach('真实订单和收货记录', { contentType: 'application/json',
    body: JSON.stringify({ orderNo, orderId, records, matchedDocument: original.documentId, before: [10, 5],
      afterDraft: [10, 5], afterDN001: [5, 5], afterDN002: [0, 2], afterAdjustment: [1, 2] }, null, 2) })
})

const glove = { id: 71, lineNo: 1, itemCode: 'G-01', itemName: '丁腈手套', specification: 'M 码',
  unit: '箱', orderedQty: 10, acceptedQty: 0, remainingQty: 10 }

function reviewFile(name: string, mime: string, bbox: number[], twoPages = false): Review {
  const line = { lineKey: 'p1-r1', pageNo: 1, itemCode: glove.itemCode, itemName: glove.itemName,
    specification: glove.specification, unit: glove.unit, deliveredQty: 6, acceptedQty: null, issues: ['CONFLICT'] }
  return {
    id: 11, orderId: 9, version: 1, status: 'DRAFT', updatedAt: '2026-11-18T00:00:00Z', updatedBy: 'receiver',
    confirmedAt: null, confirmedBy: null, receiptId: null, sourceChanged: false, orderLines: [glove],
    edit: { deliveryNoteNo: null, receivedOn: null, noReceiptReason: null,
      pages: [{ pageNo: 1, disposition: 'PENDING' }, ...(twoPages ? [{ pageNo: 2, disposition: 'PENDING' as const }] : [])],
      lines: [{ lineKey: line.lineKey, pageNo: 1, orderLineId: 71, unit: '箱', deliveredQty: 6,
        acceptedQty: null, disposition: 'PENDING' }] },
    source: {
      documentId: 17, taskId: 42, resultHash: 'a'.repeat(64), originalFilename: name, mimeType: mime,
      pages: [{ pageNo: 1, status: 'REVIEW', text: null, hints: [], width: 1489, height: 1049, rotation: 90 },
        ...(twoPages ? [{ pageNo: 2, status: 'PAGE_FAILED' as const, text: null, hints: ['固定失败页'] }] : [])],
      delivery: { lines: [line], evidence: [
        { lineKey: line.lineKey, field: 'acceptedQty', method: 'PADDLE_OCR', rawText: '手写实收 5', value: 5,
          pageNo: 1, bbox, precision: 'REGION', supporting: true },
        { lineKey: line.lineKey, field: 'acceptedQty', method: 'DOUBAO', rawText: '候选实收 6', value: 6,
          pageNo: 1, bbox: null, precision: 'PAGE', supporting: false },
      ] },
      recommendations: [{ lineKey: line.lineKey, status: 'CODE_MATCH', reason: '编码唯一',
        candidates: [{ ...glove, orderLineId: glove.id }] }],
    },
  }
}

async function openFixture(page: Page, review: Review) {
  await page.route('**/api/**', async route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/session') {
      await route.fulfill({ json: { username: 'receiver', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'fixture' } })
    } else if (pathname === '/api/documents/17/content') {
      await route.fulfill({ path: fixture('phase-03', review.source.originalFilename), contentType: review.source.mimeType })
    } else if (pathname.endsWith('/reviews/11/save')) {
      const { version, ...edit } = route.request().postDataJSON()
      if (version !== review.version) {
        await route.fulfill({ status: 409, json: { code: 'REVIEW_VERSION_CONFLICT', message: '草稿已更新，请查看服务器版本' } })
        return
      }
      review = { ...review, edit, version: version + 1 }
      await route.fulfill({ json: review })
    } else if (pathname.endsWith('/reviews/11')) {
      await route.fulfill({ json: review })
    } else {
      await route.fulfill({ status: 404 })
    }
  })
  await page.goto('/orders/9/reviews/11')
  return (next: Review) => { review = next }
}

test('旋转照片的证据框随缩放及窗口变化，5/6 选择和理由可恢复', async ({ page }) => {
  const bbox = [0.787105, 0.442326, 0.80994, 0.480458]
  await openFixture(page, reviewFile('DN-rotated.jpg', 'image/jpeg', bbox))
  await expect(page.getByLabel('核对实收数')).toHaveValue('')
  await expect(page.getByText(/候选实收 6.*待核建议/)).toBeVisible()
  await page.getByRole('button', { name: '采用实收数 5' }).click()
  await expect(page.getByLabel('核对实收数')).toHaveValue('5')
  const paper = page.locator('.review-paper img')
  const mark = page.getByLabel('证据高亮')
  await paper.evaluate((image: HTMLImageElement) => image.decode())
  async function aligned() {
    const [image, rect] = await Promise.all([paper.boundingBox(), mark.boundingBox()])
    expect(image).not.toBeNull()
    expect(rect).not.toBeNull()
    expect((rect!.x - image!.x) / image!.width).toBeCloseTo(bbox[0], 2)
    expect((rect!.y - image!.y) / image!.height).toBeCloseTo(bbox[1], 2)
    expect(image!.width / image!.height).toBeCloseTo(1489 / 1049, 2)
  }
  await aligned()
  await page.getByRole('button', { name: '放大' }).click()
  await aligned()
  await page.setViewportSize({ width: 1080, height: 800 })
  await aligned()
  await page.getByRole('button', { name: '适应宽度' }).click()
  await aligned()
  if (process.env.LABREVIEW_PHASE4_SCREENSHOT) {
    await page.getByLabel('签收原件', { exact: true }).screenshot({ path: process.env.LABREVIEW_PHASE4_SCREENSHOT })
  }
  await page.getByLabel('处理理由').fill('采用手写候选')
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect(page.getByText(/已保存版本 2/)).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('核对实收数')).toHaveValue('5')
  await expect(page.getByLabel('处理理由')).toHaveValue('采用手写候选')
  await expect(page.getByText('已选', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /^实收数 6 ·/ }).click()
  await expect(mark).toHaveCount(0)
  await expect(page.getByText(/第 1 页 · 页级来源/)).toBeVisible()
})

for (const name of ['DN-mixed.pdf', 'DN-scan.pdf']) {
  test(`${name} 使用 PDF.js 渲染当前页并定位证据`, async ({ page }) => {
    const mixed = name === 'DN-mixed.pdf'
    const bbox = mixed ? [0.79459, 0.449427, 0.803829, 0.475647] : [0.790323, 0.445714, 0.806452, 0.47619]
    await openFixture(page, reviewFile(name, 'application/pdf', bbox, mixed))
    const canvas = page.getByLabel('原件第 1 页')
    await expect(canvas).toBeVisible({ timeout: 15_000 })
    await page.getByRole('button', { name: /^实收数 5 ·/ }).click()
    await page.getByRole('button', { name: '放大' }).click()
    await expect(canvas).toBeVisible()
    const [paper, region] = await Promise.all([canvas.boundingBox(), page.getByLabel('证据高亮').boundingBox()])
    expect((region!.x - paper!.x) / paper!.width).toBeCloseTo(bbox[0], 2)
    if (mixed) {
      await page.getByLabel('原件视口，方向键左右翻页').press('ArrowRight')
      await expect(page.getByLabel('原件第 2 页')).toBeVisible()
      await expect(canvas).toHaveCount(0)
      await expect(page.getByLabel('证据高亮')).toHaveCount(0)
      await page.getByLabel('签收单号').fill('DN-MIXED')
      await page.getByLabel('到货日期').fill('2026-11-18')
      await page.getByLabel('第 1 页处置').selectOption('REVIEWED')
      await page.getByLabel('行处置').selectOption('POST')
      await page.getByRole('button', { name: '确认收货', exact: true }).click()
      await expect(page.getByRole('alert')).toContainText('请处理第 2 页')
      await page.getByLabel('第 2 页处置').selectOption('NO_DETAILS')
      await page.getByLabel('无明细原因').fill('已查看原件，第二页无收货明细')
      await page.getByRole('button', { name: '采用实收数 5' }).click()
      await page.getByLabel('处理理由').fill('核对原件确认实收')
      await page.getByRole('button', { name: '确认收货', exact: true }).click()
      await expect(page.getByRole('dialog', { name: '确认收货' })).toBeVisible()
    }
  })
}

test('送货数不填入实收栏，明确零值可选择无入账结案', async ({ page }) => {
  const only = reviewFile('DN-delivery-only.pdf', 'application/pdf', [])
  only.source.delivery.lines[0].issues = ['MISSING_ACCEPTED_EVIDENCE']
  only.source.delivery.evidence = []
  await openFixture(page, only)
  await expect(page.getByLabel('核对送货数')).toHaveValue('6')
  await expect(page.getByLabel('核对实收数')).toHaveValue('')
  const zero = reviewFile('DN-zero.pdf', 'application/pdf', [])
  zero.edit.lines[0].acceptedQty = 0
  zero.source.delivery.lines[0].acceptedQty = 0
  zero.source.delivery.lines[0].issues = []
  zero.source.delivery.evidence = []
  await page.unroute('**/api/**')
  await openFixture(page, zero)
  await expect(page.getByLabel('核对实收数')).toHaveValue('0')
  await page.getByLabel('签收单号').fill('DN-ZERO')
  await page.getByLabel('到货日期').fill('2026-11-18')
  await page.getByLabel('第 1 页处置').selectOption('REVIEWED')
  await page.getByLabel('行处置').selectOption('ZERO')
  await page.getByLabel('处理理由').fill('签收单明确实收 0')
  await page.getByLabel('无入账结案原因').fill('本次未收货')
  await page.getByRole('button', { name: '完成核对，无入账' }).click()
  await expect(page.getByRole('dialog', { name: '完成核对，无入账' })).toContainText('本次未收货')
})

test('冲突单列出 5 和 6 的原文，服务器版本冲突时保留人工裁决', async ({ page }) => {
  const review = reviewFile('DN-conflict.jpg', 'image/jpeg', [0.079301, 0.547619, 0.406586, 0.659048])
  const [written, printed] = review.source.delivery.evidence
  written.rawText = '实收5箱，短1箱'
  Object.assign(printed, { rawText: '实收数量 6', method: 'PADDLE_OCR', supporting: true,
    precision: 'REGION', bbox: [0.793683, 0.449524, 0.805108, 0.473333] })
  const updateServer = await openFixture(page, review)
  await expect(page.getByText('实收5箱，短1箱', { exact: true })).toBeVisible()
  await expect(page.getByText('实收数量 6', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '采用实收数 5' }).click()
  await page.getByLabel('处理理由').fill('现场确认手写为准')
  updateServer({ ...review, version: 2, edit: { ...review.edit, deliveryNoteNo: '服务器修改的单号' } })
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect(page.getByRole('heading', { name: '服务器版本 2' })).toBeVisible()
  await expect(page.getByLabel('核对实收数')).toHaveValue('5')
  await page.getByText('查看服务器草稿').click()
  await expect(page.getByText(/服务器修改的单号/)).toBeVisible()
  await page.getByRole('button', { name: '保留我的编辑' }).click()
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect(page.getByText(/已保存版本 3/)).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('核对实收数')).toHaveValue('5')
  await expect(page.getByLabel('处理理由')).toHaveValue('现场确认手写为准')
})

test('规格或单位不一致的候选需要人工选择和核实单位', async ({ page }) => {
  const review = reviewFile('DN-mismatch.pdf', 'application/pdf', [])
  review.source.delivery.evidence = []
  Object.assign(review.source.delivery.lines[0], { specification: 'L 码', unit: '盒',
    deliveredQty: 2, acceptedQty: 1, issues: [] })
  Object.assign(review.edit.lines[0], { orderLineId: null, unit: '盒', deliveredQty: 2, acceptedQty: 1 })
  Object.assign(review.source.recommendations[0], { status: 'REVIEW', reason: '编码相同，规格或单位缺失或不一致' })
  await openFixture(page, review)
  await expect(page.getByText('编码相同，规格或单位缺失或不一致')).toBeVisible()
  await expect(page.getByLabel('订单物料')).toHaveValue('')
  await page.getByLabel('签收单号').fill('DN-MISMATCH')
  await page.getByLabel('到货日期').fill('2026-11-18')
  await page.getByLabel('第 1 页处置').selectOption('REVIEWED')
  await page.getByLabel('订单物料').selectOption('71')
  await page.getByLabel('行处置').selectOption('POST')
  await page.getByRole('button', { name: '确认收货', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('请选择订单物料并核实订单单位')
  await page.getByLabel('核实单位').fill('箱')
  await page.getByRole('button', { name: '确认收货', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('请填写异常处置或人工修正原因')
})
