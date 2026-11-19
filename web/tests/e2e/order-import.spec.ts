import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import sample from '../../../samples/phase-01/expected.json' with { type: 'json' }

const fixture = (name: string) => fileURLToPath(new URL(`../../../samples/phase-01/${name}`, import.meta.url))
const mixedFixture = fileURLToPath(new URL('../../../samples/phase-03/DN-mixed.pdf', import.meta.url))

test('采购单异步导入、返回核对和签收材料登记', async ({ page }) => {
  test.setTimeout(150_000)
  const password = process.env.LABREVIEW_PASSWORD
  if (!password) throw new Error('运行前请设置 LABREVIEW_PASSWORD 为运行中后端的收货员密码')
  const { purchaseOrder } = sample
  const correctedNo = `IMPORT-${randomUUID()}`
  await page.goto('/orders')
  await page.getByLabel('用户名').fill(process.env.LABREVIEW_USERNAME ?? 'receiver')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '采购订单' })).toBeVisible()
  async function originalOrderIds() {
    const response = await page.request.get(`/api/orders?query=${purchaseOrder.orderNo}&pageSize=100`)
    expect(response.ok()).toBeTruthy()
    const { items } = await response.json() as { items: { id: number; orderNo: string; supplierName: string }[] }
    return items.filter(order => order.orderNo === purchaseOrder.orderNo && order.supplierName === purchaseOrder.supplierName)
      .map(order => order.id)
  }
  const originalIdsBeforeUpload = await originalOrderIds()
  await page.getByRole('link', { name: '导入采购单' }).click()
  await page.getByLabel('采购单 PDF').setInputFiles(fixture(purchaseOrder.file))
  await page.getByRole('button', { name: '上传并登记任务' }).click()
  const task = page.getByRole('link', { name: /^查看任务 #\d+$/ })
  await expect(task).toBeVisible()
  const taskHref = (await task.getAttribute('href'))!
  await page.goto('/orders')
  await page.getByRole('link', { name: '任务中心' }).click()
  await page.locator(`a[href="${taskHref}"]`).click()
  const preview = page.getByTitle('原件预览')
  await expect(preview).toBeVisible()
  const original = await page.request.get((await preview.getAttribute('src'))!)
  expect(original.ok()).toBeTruthy()
  expect(await original.body()).toEqual(await readFile(fixture(purchaseOrder.file)))
  const no = page.getByLabel('采购单号', { exact: true })
  await expect(no).toHaveValue(purchaseOrder.orderNo, { timeout: 25_000 })
  await expect(page.getByLabel('采购日期')).toHaveValue(purchaseOrder.orderedOn)
  await expect(page.getByLabel('供应商')).toHaveValue(purchaseOrder.supplierName)
  for (const [index, item] of purchaseOrder.lines.entries()) {
    const row = page.getByRole('group', { name: `物料行 ${index + 1}`, exact: true })
    await expect(row.getByLabel('物料编码')).toHaveValue(item.itemCode)
    await expect(row.getByLabel('物料名称')).toHaveValue(item.itemName)
    await expect(row.getByLabel('规格')).toHaveValue(item.specification)
    await expect(row.getByLabel('单位')).toHaveValue(item.unit)
    await expect(row.getByLabel('订购量')).toHaveValue(String(item.orderedQty))
  }
  expect(await originalOrderIds()).toEqual(originalIdsBeforeUpload)
  await no.fill(correctedNo)
  await page.getByRole('link', { name: '返回任务中心' }).click()
  const guard = page.getByRole('dialog', { name: '离开采购单确认？' })
  await expect(guard).toBeVisible()
  await guard.getByRole('button', { name: '继续编辑' }).click()
  await expect(no).toHaveValue(correctedNo)
  await page.getByLabel('订购量').first().fill('11')
  await page.getByRole('button', { name: '确认建单' }).click()
  await expect(page.getByRole('heading', { name: correctedNo })).toBeVisible()
  const orderUrl = new URL(page.url()).pathname
  const { csrfHeader, csrfToken } = await (await page.request.get('/api/session')).json()
  const taskResponse = await page.request.get(`/api${taskHref}`)
  expect(taskResponse.ok()).toBeTruthy()
  const { documentId } = await taskResponse.json()
  const repeated = await page.request.post(`/api/order-imports/${documentId}/confirm`, {
    headers: { [csrfHeader]: csrfToken },
    data: {
      orderNo: correctedNo, supplierName: purchaseOrder.supplierName, orderedOn: purchaseOrder.orderedOn,
      lines: purchaseOrder.lines.map((line, index) => index === 0 ? { ...line, orderedQty: 11 } : line),
    },
  })
  expect(repeated.status()).toBe(200)
  expect(await repeated.json()).toEqual({ id: Number(orderUrl.split('/').at(-1)) })
  const gloves = page.getByRole('row', { name: /G-01.*丁腈手套/ })
  await expect(gloves.getByRole('cell').last()).toHaveText('11 箱')
  await page.getByRole('link', { name: '查看采购导入任务' }).click()
  await expect(page.getByRole('link', { name: '查看已建订单' })).toHaveAttribute('href', orderUrl)
  await page.getByText('原始识别值').click()
  await expect(page.getByRole('table', { name: '识别物料' }).getByText('10', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: '查看已建订单' }).click()

  const deliveryFiles = sample.deliveries.map(({ file }) => ({ file, path: fixture(file) }))
  deliveryFiles.push({ file: 'DN-mixed.pdf', path: mixedFixture })
  for (const { file, path } of deliveryFiles) {
    await page.getByLabel('签收材料文件').setInputFiles(path)
    await page.getByRole('button', { name: '上传签收材料' }).click()
    const row = page.getByRole('row', { name: file, exact: false })
    await expect(row).toBeVisible()
    await row.getByRole('link', { name: '查看任务' }).click()
    const pageTable = page.getByRole('table', { name: '逐页处理结果' })
    await expect(pageTable).toBeVisible({ timeout: 60_000 })
    if (file.endsWith('.jpg')) await expect(pageTable.getByText(/^(待 OCR|待核对|已提取|该页失败)$/)).toBeVisible()
    const download = page.getByRole('link', { name: '下载原件' })
    await expect(download).toBeVisible()
    if (file === 'DN-mixed.pdf') {
      await expect(pageTable.getByText('该页失败', { exact: true })).toBeVisible()
      await expect(pageTable.getByText('OCR 0 次 · 豆包 0 次', { exact: true })).toBeVisible()
      const mixedGloves = page.getByRole('region', { name: /候选行 G-01/ })
      const mixedFilters = page.getByRole('region', { name: /候选行 F-02/ })
      await expect(mixedGloves.getByText('5', { exact: true })).toHaveCount(2)
      await expect(mixedFilters.getByText('3', { exact: true })).toHaveCount(2)
      await expect(mixedGloves.getByText('编码匹配', { exact: true })).toBeVisible()
      await page.reload()
      await mixedGloves.getByText('实收候选来源', { exact: true }).click()
      const openedEvidence = mixedGloves.locator('details[open]')
      await expect(openedEvidence.getByText(/PDF 文字 · 第 1 页/)).toBeVisible()
      await expect(openedEvidence.getByText('实收数量 5', { exact: true })).toBeVisible()
      const downloaded = await page.request.get((await download.getAttribute('href'))!)
      expect(downloaded.ok()).toBeTruthy()
      expect(await downloaded.body()).toEqual(await readFile(path))
    }
    await page.getByRole('link', { name: '返回关联订单' }).click()
    await expect(gloves.getByRole('button', { name: '已收 0 箱' })).toBeVisible()
    await expect(gloves.getByRole('cell').last()).toHaveText('11 箱')
    const filters = page.getByRole('row', { name: /F-02.*滤芯/ })
    await expect(filters.getByRole('button', { name: '已收 0 盒' })).toBeVisible()
    await expect(filters.getByRole('cell').last()).toHaveText('5 盒')
  }
  await gloves.getByRole('button', { name: '已收 0 箱' }).click()
  await expect(page.getByText('尚无收货记录')).toBeVisible()
})
