import { expect, test, type Locator } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import sample from '../../../samples/phase-01/expected.json' with { type: 'json' }

test('收货员建立采购单、确认两批到货并逐行追溯', async ({ page }) => {
  const { purchaseOrder, deliveries } = sample
  const orderNo = process.env.LABREVIEW_E2E_ORDER_NO ?? `${purchaseOrder.orderNo}-${randomUUID()}`
  const password = process.env.LABREVIEW_PASSWORD
  if (!password) throw new Error('运行前请设置 LABREVIEW_PASSWORD 为运行中后端的收货员密码')

  await page.goto('/orders')
  await expect(page.getByRole('heading', { name: '收货员登录' })).toBeVisible()
  await page.getByLabel('用户名').fill(process.env.LABREVIEW_USERNAME ?? 'receiver')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page.getByRole('heading', { name: '采购订单' })).toBeVisible()
  async function fillOrder() {
    await page.getByRole('link', { name: '新建采购单' }).click()
    await page.getByLabel('采购单号', { exact: true }).fill(orderNo)
    await page.getByLabel('供应商').fill(`手工收货测试 ${orderNo}`)
    await page.getByLabel('采购日期').fill(purchaseOrder.orderedOn)
    for (const [index, item] of purchaseOrder.lines.entries()) {
      if (index > 0) await page.getByRole('button', { name: '添加物料行' }).click()
      const row = page.getByRole('group', { name: `物料行 ${index + 1}`, exact: true })
      await row.getByLabel('物料编码').fill(item.itemCode)
      await row.getByLabel('物料名称').fill(item.itemName)
      await row.getByLabel('规格').fill(item.specification)
      await row.getByLabel('单位').fill(item.unit)
      await row.getByLabel('订购量').fill(String(item.orderedQty))
    }
    await page.getByRole('button', { name: '创建采购单' }).click()
  }
  await fillOrder()
  await expect(page.getByRole('heading', { name: orderNo, exact: true })).toBeVisible()
  const gloves = page.getByRole('row', { name: /G-01.*丁腈手套/ })
  const filters = page.getByRole('row', { name: /F-02.*滤芯/ })
  const assertTotals = async (row: Locator, accepted: string, remaining: string) => {
    await expect(row.getByRole('button', { name: `已收 ${accepted}` })).toBeVisible()
    await expect(row.getByRole('cell').last()).toHaveText(remaining)
  }
  await expect(page.getByText('待收货', { exact: true })).toBeVisible()
  await assertTotals(gloves, '0 箱', '10 箱')
  await assertTotals(filters, '0 盒', '5 盒')
  await page.getByRole('link', { name: '返回采购订单' }).click()
  await page.getByLabel('采购单号搜索').fill(orderNo)
  await page.getByLabel('到货状态').selectOption('PENDING')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('link', { name: orderNo, exact: true })).toBeVisible()
  await fillOrder()
  await expect(page.getByRole('alert')).toHaveText('该供应商已有相同采购单号的订单')
  await expect(page.getByLabel('采购单号', { exact: true })).toHaveValue(orderNo)
  await page.getByRole('link', { name: '返回采购订单' }).click()
  await page.getByRole('link', { name: orderNo, exact: true }).click()
  await gloves.getByRole('button', { name: '已收 0 箱' }).click()
  await expect(page.getByText('尚无收货记录')).toBeVisible()

  for (const [batchIndex, delivery] of deliveries.entries()) {
    await page.getByRole('button', { name: '登记本次到货' }).click()
    await page.getByLabel('签收单号').fill(delivery.deliveryNoteNo)
    await page.getByLabel('到货日期').fill(delivery.receivedOn)

    for (const [index, item] of delivery.lines.entries()) {
      if (index > 0) await page.getByRole('button', { name: '添加收货行' }).click()
      const row = page.getByRole('group', { name: `收货行 ${index + 1}`, exact: true })
      const select = row.getByLabel('订单物料')
      const option = select.getByRole('option', { name: new RegExp(item.itemCode) })
      await select.selectOption(await option.getAttribute('value') ?? '')
      await expect(row.getByLabel('实收数')).toHaveValue('')
      await row.getByLabel('送货数').fill(String(item.deliveredQty))
      await row.getByLabel('实收数').fill(String(item.acceptedQty))
      if ('note' in item && item.note) await row.getByLabel('备注').fill(item.note)
      await expect(row.getByText(/预计待收/)).toBeVisible()
    }

    await page.getByRole('button', { name: '确认收货' }).click()
    await expect(page.getByRole('heading', { name: '登记本次到货' })).toBeHidden()
    await expect(page.getByText('部分到货', { exact: true })).toBeVisible()
    if (batchIndex === 0) {
      await assertTotals(gloves, '5 箱', '5 箱')
      await assertTotals(filters, '0 盒', '5 盒')
    } else {
      await assertTotals(gloves, '10 箱', '0 箱')
      await assertTotals(filters, '3 盒', '2 盒')
    }
    await expect(page.getByRole('region', { name: 'G-01 收货记录' })
      .getByRole('row', { name: new RegExp(delivery.deliveryNoteNo) })).toBeVisible()
  }

  await page.reload()
  await assertTotals(gloves, '10 箱', '0 箱')
  await assertTotals(filters, '3 盒', '2 盒')
  await page.getByRole('link', { name: '返回采购订单' }).click()
  await page.getByLabel('采购单号搜索').fill(orderNo)
  await page.getByLabel('到货状态').selectOption('PENDING')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText('没有符合条件的订单')).toBeVisible()
  await page.getByLabel('到货状态').selectOption('PARTIAL')
  await page.getByRole('button', { name: '查询' }).click()
  await page.getByRole('link', { name: orderNo, exact: true }).click()

  await gloves.getByRole('button', { name: '已收 10 箱' }).click()
  await filters.getByRole('button', { name: '已收 3 盒' }).click()
  const glovesHistory = page.getByRole('region', { name: 'G-01 收货记录' })
  const filtersHistory = page.getByRole('region', { name: 'F-02 收货记录' })
  await expect(glovesHistory.getByRole('row')).toHaveCount(3)
  await expect(glovesHistory.getByRole('row', { name: /DN-001.*6 箱.*5 箱.*短 1 箱/ })).toBeVisible()
  await expect(glovesHistory.getByRole('row', { name: /DN-002.*5 箱.*5 箱/ })).toBeVisible()
  await expect(filtersHistory.getByRole('row')).toHaveCount(2)
  await expect(filtersHistory.getByRole('row', { name: /DN-002.*3 盒.*3 盒/ })).toBeVisible()
  await expect(filtersHistory.getByText('DN-001')).toHaveCount(0)

  if (process.env.LABREVIEW_E2E_SCREENSHOT) {
    await page.screenshot({ path: process.env.LABREVIEW_E2E_SCREENSHOT, fullPage: true })
  }
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '收货员登录' })).toBeVisible()
})
