import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { execFileSync } from 'node:child_process'
const { chromium } = await import(process.env.IMS_QA_PLAYWRIGHT
  ? pathToFileURL(process.env.IMS_QA_PLAYWRIGHT).href : 'playwright')
const jdbcJar = process.env.IMS_QA_JDBC
assert.ok(jdbcJar, 'Set IMS_QA_JDBC to the PostgreSQL JDBC driver jar')

const base = new URL(process.env.IMS_QA_BASE ?? 'http://127.0.0.1:55173').origin
assert.equal(new URL(base).hostname,'127.0.0.1','QA must target a loopback server')
const artifacts = resolve(process.argv[2] ?? 'backend/build/qa-artifacts')
const storeCode = 'QA' + Date.now().toString(36).toUpperCase()
const isolationStore = storeCode + 'X'
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date())
const amount = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' })
const evidence = { cases: [], reports: [], browserErrors: [], httpErrors: [] }
await mkdir(artifacts, { recursive: true })
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 }, timezoneId: 'Asia/Kolkata', acceptDownloads: true })
await context.route(/^http:\/\/127\.0\.0\.1:(17777|17778)\//, route => route.abort())
const page = await context.newPage()
page.setDefaultTimeout(8000)
page.on('pageerror', error => evidence.browserErrors.push(String(error)))
page.on('response', response => {
  if (response.status() >= 400 && response.url().startsWith(base + '/api/')) {
    evidence.httpErrors.push([response.status(), new URL(response.url()).pathname])
  }
})

async function check(name, run) {
  try {
    await run()
    evidence.cases.push({ name, result: 'PASSED' })
    console.log('PASS', name)
  } catch (error) {
    evidence.cases.push({ name, result: 'FAILED', error: String(error) })
    console.log('FAIL', name, String(error))
  }
}

async function api(path, data, expected = 200, client = context.request) {
  const response = data === undefined
    ? await client.get(base + '/api' + path)
    : await client.post(base + '/api' + path, { data, headers: { Origin: base } })
  const body = await response.json()
  assert.equal(response.status(), expected, JSON.stringify(body))
  return body
}

async function sql(query, values = []) {
  const bindings = []
  const statement = query.trim().replace(/\$(\d+)/g, (_, index) => {
    bindings.push(String(values[Number(index) - 1]))
    return '?'
  })
  const rows = execFileSync(process.env.IMS_QA_JAVA ?? 'java', [
    '-cp', jdbcJar, 'qa/QaQuery.java', Buffer.from(statement).toString('base64'), ...bindings,
  ], { encoding: 'utf8' }).trim()
  return rows ? rows.split(/\r?\n/).map(row => Object.fromEntries(row.split('\t').map(column => {
    const [name, type, encoded] = column.split(':')
    const value = encoded === '-' ? null : Buffer.from(encoded, 'base64').toString('utf8')
    return [name, type === 'jsonb' || type === 'json' ? JSON.parse(value) : value]
  }))) : []
}

async function metric(label, expected, currency = true) {
  const card = page.getByText(label, { exact: true }).locator('..').locator('p').nth(1)
  await card.waitFor()
  const wanted = currency ? amount.format(expected) : String(expected)
  await page.waitForFunction(({ label, wanted }) => Array.from(document.querySelectorAll('p')).some(
    node => node.textContent.trim() === label && node.nextElementSibling?.textContent.trim() === wanted
  ), { label, wanted }, { timeout: 5000 })
  return (await card.innerText()).trim()
}

const tab = label => page.getByRole('button', { name: label, exact: true })
async function report(key, label, expected, dbValue, field, card, options = {}) {
  const record = { key, expected, database: dbValue }
  evidence.reports.push(record)
  await check('Report ' + key, async () => {
    const network = options.supplier ? null : page.waitForResponse(r => new URL(r.url()).pathname === '/api/reports/' + key && r.ok())
    if (key === 'sales') await page.goto(base + '/reports')
    else await tab(label).click()
    let payload
    if (options.supplier) {
      const select = page.locator('select').filter({ has: page.getByRole('option', { name: 'All suppliers' }) })
      const update = page.waitForResponse(r => new URL(r.url()).pathname === '/api/reports/' + key && r.ok() && r.url().includes('supplierId='))
      await select.selectOption(String(options.supplier))
      payload = await (await update).json()
    } else payload = await (await network).json()
    record.api = field(payload)
    assert.equal(Number(record.database), expected)
    assert.equal(Number(record.api), expected)
    if (card) record.frontend = await metric(card, expected, options.currency)
    else {
      const value = page.getByText(options.visible, { exact: true }).first()
      await value.waitFor()
      record.frontend = await value.innerText()
    }
  })
}

async function verifyReportDownloads(key, sections = 1) {
  await check('Report exports ' + key, async () => {
    const XLSX = await import('xlsx')
    assert.equal(await page.getByRole('button', { name:'Excel', exact:true }).count(), sections)
    assert.equal(await page.getByRole('button', { name:'PDF', exact:true }).count(), sections)
    for (const type of ['Excel','PDF']) {
      for (let index = 0; index < sections; index++) {
        const download = page.waitForEvent('download')
        await page.getByRole('button', { name:type, exact:true }).nth(index).click()
        const file = await download
        const destination = resolve(artifacts, key + '-' + file.suggestedFilename())
        await file.saveAs(destination)
        const content = await readFile(destination)
        if (type === 'PDF') {
          assert.equal(content.subarray(0,4).toString(), '%PDF')
        } else {
          const workbook = XLSX.read(content,{type:'buffer'})
          const rows = XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]],{header:1})
          assert.ok(rows.length >= 2, 'Expected a header and at least one exported data row')
        }
      }
    }
  })
}

try {
  await check('Unauthenticated report rejected', () => api('/reports/payment-summary', undefined, 401))
  const admin = await browser.newContext()
  const adminUsername = process.env.IMS_QA_ADMIN ?? 'qa-platform'
  const platformAccount = (await sql('SELECT username, must_change_password FROM users WHERE store_id IS NULL'))
    .find(account => account.username === adminUsername)
  const initial = process.env.IMS_QA_ADMIN_PASSWORD ?? 'QaStage!9273'
  const rotated = process.env.IMS_QA_ADMIN_ROTATED ?? 'QaRestart!8519'
  const platform = await api('/auth/login', {
    storeCode:'PLATFORM', username:adminUsername,
    password:['t','true'].includes(platformAccount?.must_change_password) ? initial : rotated,
  }, 200, admin.request)
  if (platform.mustChangePassword) {
    await api('/auth/change-password', { currentPassword:initial,newPassword:rotated },200,admin.request)
  }
  await api('/stores', {
    storeCode, storeName:'QA Dairy', ownerName:'QA Owner', email:storeCode + '@example.test', mobile:'9000000003',
    address:'1 QA Street', state:'Karnataka', username:'owner', password:'Owner@123', branchName:'Main Branch',
  }, 200, admin.request)
  await api('/stores', {
    storeCode:isolationStore,storeName:'QA Empty Store',ownerName:'QA Other Owner',email:isolationStore+'@example.test',
    mobile:'9000000015',address:'20 QA Street',state:'Karnataka',username:'owner',password:'Owner@123',branchName:'Main Branch',
  },200,admin.request)
  await admin.close()
  await check('Browser login and session hydration', async () => {
    await page.goto(base + '/login')
    await page.getByPlaceholder('e.g. ABC001').fill(storeCode)
    await page.getByPlaceholder('Your username').fill('owner')
    await page.getByPlaceholder('Your password').fill('Owner@123')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await page.waitForURL(base + '/')
    assert.equal((await api('/auth/me')).store.storeCode, storeCode)
  })

  const me = await api('/auth/me')
  const store = me.store.id
  const main = me.branches[0].id
  const category = await api('/categories', {categoryName:'QA Food',status:'ACTIVE'})
  const taxes = await api('/taxes/options')
  const tax = taxes.find((row) => Number(row.gstPercentage) === 5)
  const item = await api('/items', {
    itemCode:'QA-ITEM',itemName:'QA Test Dairy Item',categoryId:category.id,taxId:tax.id,baseUnit:'PIECE',
    rate:50,minStockLevel:15,status:'ACTIVE',
  })
  await api('/inventory/adjustments', {
    branchId: main, itemId:item.id,type:'IN',quantity:10,unit:'PIECE',reason:'QA opening fixture',
  })
  await api('/suppliers', {supplierName:'QA Supplier',mobile:'9000000007',address:'2 QA Street',state:'Karnataka',status:'ACTIVE'})
  const staffRole = (await api('/users/roles/available')).find(role => role.code === 'BRANCH_STAFF')
  await api('/users', {
    username:'staff',email:'staff-' + storeCode + '@example.test',password:'Staff@123',fullName:'QA Staff',
    roleId:staffRole.id,branchIds:[main],
  })
  const [fixture] = await sql(`
    SELECT i.id, i.item_name, i.base_unit, t.gst_percentage, v.current_stock,
           v.avg_cost, v.selling_price
    FROM items i JOIN tax_master t ON t.id=i.tax_id
    JOIN branch_inventory v ON v.item_id=i.id
    WHERE i.store_id=$1 AND v.branch_id=$2 AND t.gst_percentage=5 AND v.current_stock>=4
    ORDER BY i.id LIMIT 1`, [store, main])
  assert.ok(fixture, 'seeded 5% GST fixture is required')
  const [supplier] = await sql('SELECT id, supplier_name FROM suppliers WHERE store_id=$1 ORDER BY id LIMIT 1', [store])
  const lines = [{ itemId: Number(fixture.id), quantity: 2, unit: fixture.base_unit, sellingRate: 50, discount: 0 }]
  const purchase = await api('/purchases', {
    branchId: main, supplierId: Number(supplier.id), purchaseDate: today, status: 'COMPLETED',
    paidAmount: 0, payableAmount: 105, items: [{ itemId: Number(fixture.id), quantity: 4, unit: fixture.base_unit, purchaseRate: 25 }],
  })
  const supplierPayment = await api('/supplier-payments', {
    supplierId: Number(supplier.id), paymentDate: today, paymentMethod: 'BANK',
    amount: 30, clientReference: 'ims-qa-supplier-payment', purchaseIds: [purchase.id],
  })
  const sale = await api('/sales', { branchId: main, salesDate: today, items: lines, paymentMethod: 'CASH' })
  assert.match(sale.salesNumber, /^[1-9][0-9]*$/)
  assert.match(purchase.purchaseNumber, /^[1-9][0-9]*$/)
  assert.match(supplierPayment.paymentNumber, /^[1-9][0-9]*$/)
  assert.equal(Number(sale.grandTotal), 105)
  await check('Supplier settlement idempotency and allocation', async () => {
    const replay = await api('/supplier-payments', {
      supplierId: Number(supplier.id), paymentDate: today, paymentMethod: 'BANK',
      amount: 30, clientReference: 'ims-qa-supplier-payment', purchaseIds: [purchase.id],
    })
    assert.equal(replay.id, supplierPayment.id)
    const [allocation] = await sql('SELECT COUNT(*) count, SUM(amount) amount FROM supplier_payment_allocations WHERE purchase_id=$1', [purchase.id])
    assert.equal(Number(allocation.count), 1)
    assert.equal(Number(allocation.amount), 30)
  })
  await check('Payment modification API validates zero, negative and excess', async () => {
    for (const value of [0, -1, 105.01]) {
      await api('/sales/' + sale.id + '/modifications', {
        baseModificationCount: 0, items: lines, payments: [{ method: 'UPI', amount: value }],
      }, 400)
    }
  })
  await check('Cash to UPI to Card to split, duplicate save and audit', async () => {
    const revisions = [[['UPI',105]], [['CARD',105]], [['CASH',40],['UPI',65]], [['CASH',30],['UPI',40],['CARD',35]]]
    for (const [revision, rows] of revisions.entries()) {
      const body = { baseModificationCount: revision, items: lines, payments: rows.map(([method, amount]) => ({ method, amount })) }
      await api('/sales/' + sale.id + '/modifications/preview', body)
      const saved = await api('/sales/' + sale.id + '/modifications', body)
      assert.deepEqual(saved.sale.payments, body.payments)
      await api('/sales/' + sale.id + '/modifications', body, 409)
    }
    const [history] = await sql('SELECT COUNT(*) n, COUNT(modified_by) editors, COUNT(modified_at) dates FROM sale_modifications WHERE sale_id=$1', [sale.id])
    assert.deepEqual(Object.values(history).map(Number), [4,4,4])
    const [payment] = await sql('SELECT paid_amount, payments FROM sales WHERE id=$1', [sale.id])
    assert.equal(Number(payment.paid_amount),105)
    assert.deepEqual(payment.payments, [{method:'CASH',amount:30},{method:'UPI',amount:40},{method:'CARD',amount:35}])
  })
  await check('Payment editor displays saved split and adds/removes rows', async () => {
    await page.goto(base + '/sales/modify/' + sale.id)
    await page.getByText('Saved payment:', { exact: false }).waitFor()
    assert.equal(await page.getByLabel('Payment amount').count(), 3)
    await page.getByRole('button', { name: 'Add payment' }).click()
    assert.equal(await page.getByLabel('Payment amount').count(), 4)
    await page.getByRole('button', { name: 'Remove payment' }).last().click()
    assert.equal(await page.getByLabel('Payment amount').count(), 3)
    await page.screenshot({ path: resolve(artifacts, 'payment-editor.png'), fullPage: true })
  })

  const [sums] = await sql(`
    SELECT
      (SELECT SUM(grand_total) FROM sales WHERE store_id=$1 AND status='COMPLETED') sales,
      (SELECT SUM(paid_amount) FROM sales WHERE store_id=$1 AND status='COMPLETED') collected,
      (SELECT SUM(tax) FROM sales WHERE store_id=$1 AND status='COMPLETED') gst,
      (SELECT SUM(grand_total) FROM purchases WHERE store_id=$1 AND status='COMPLETED') purchases,
      (SELECT SUM(amount) FROM supplier_payments WHERE store_id=$1) supplier_paid,
      (SELECT SUM(balance_amount) FROM purchases WHERE store_id=$1 AND status='COMPLETED') supplier_balance,
      (SELECT SUM(current_stock) FROM branch_inventory WHERE store_id=$1) stock,
      (SELECT SUM(d.cost_rate*d.base_quantity) FROM sales_details d JOIN sales s ON s.id=d.sales_id WHERE s.store_id=$1 AND s.status='COMPLETED') cogs,
      (SELECT SUM(d.taxable_amount) FROM sales_details d JOIN sales s ON s.id=d.sales_id WHERE s.store_id=$1 AND s.status='COMPLETED') taxable
  `, [store])
  for (const [key,label,expected,dbValue,field,card,opts] of [
    ['sales','Sales',105,sums.sales,r=>r.totalAmount,'Sales value'],
    ['payment-summary','Payment summary',105,sums.collected,r=>r.collected,'Total amount collected'],
    ['purchases','Purchases',105,sums.purchases,r=>r.totalAmount,'Purchase value'],
    ['stock','Stock movement',Number(sums.stock),sums.stock,r=>r.totalCurrentStock,'Closing quantity',{currency:false}],
    ['profit','Profit',Math.round((Number(sums.taxable)-Number(sums.cogs))*100)/100,Math.round((Number(sums.taxable)-Number(sums.cogs))*100)/100,r=>r.grossProfit,'Gross profit'],
    ['gst','GST',5,sums.gst,r=>r.totals.taxAmount,'Total tax'],
    ['branches','Branch performance',105,sums.sales,r=>r.totals.totalSales,'Total sales'],
    ['store','Store summary',105,sums.sales,r=>r.totalSalesAmount,'Sales total'],
    ['supplier-outstanding','Supplier outstanding',75,sums.supplier_balance,r=>r.totals.outstandingAmount,'Outstanding'],
    ['supplier-bills','Bill-wise outstanding',105,sums.purchases,r=>r.totals.grandTotal,'Purchase amount'],
    ['supplier-payments','Supplier payments',30,sums.supplier_paid,r=>r.totals.amount,'Total paid'],
    ['supplier-ledger','Supplier ledger',75,sums.supplier_balance,r=>r.closingBalance,'Closing balance',{supplier:supplier.id}],
  ]) {
    await report(key,label,expected,dbValue,field,card,opts)
    if (key === 'payment-summary') {
      await check('Payment Summary has no built-in export', async () => {
        assert.equal(await page.getByRole('button',{name:'Excel',exact:true}).count(),0)
        assert.equal(await page.getByRole('button',{name:'PDF',exact:true}).count(),0)
      })
    } else if (key !== 'sales') {
      await verifyReportDownloads(key,key === 'profit' ? 2 : 1)
    }
  }

  await check('Sales report exports Excel and PDF', async () => {
    await page.goto(base + '/reports')
    await metric('Sales value',105)
    await page.getByRole('row').getByRole('cell',{name:sale.salesNumber,exact:true}).first().waitFor()
    assert.equal(await page.locator('input[type=date]').first().inputValue(),today.slice(0,7)+'-01')
    for (const type of ['Excel','PDF']) {
      const download = page.waitForEvent('download')
      await page.getByRole('button',{name:type,exact:true}).click()
      const file = await download
      assert.match(file.suggestedFilename(),type === 'Excel' ? /\.xlsx$/ : /\.pdf$/)
      const destination = resolve(artifacts,file.suggestedFilename())
      await file.saveAs(destination)
      if (type === 'PDF') {
        assert.match((await readFile(destination)).toString('latin1'), /INR 105\.00/)
      } else {
        const XLSX = await import('xlsx')
        const workbook = XLSX.read(await readFile(destination), {type:'buffer'})
        const rows = XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], {header:1})
        assert.equal(String(rows[1][0]), sale.salesNumber)
      }
    }
  })
  await check('Low-stock report matches database rows and browser result', async () => {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/reports/low-stock' && r.ok())
    await tab('Low stock').click()
    const report = await (await response).json()
    const rows = await sql('SELECT current_stock, minimum_stock FROM branch_inventory WHERE store_id=$1 AND current_stock<=minimum_stock',[store])
    await page.locator('table tbody tr').first().waitFor()
    const displayed = await page.locator('table tbody tr').count()
    evidence.reports.push({key:'low-stock',expected:rows.length,database:rows.length,api:report.total,frontend:displayed})
    assert.equal(report.total,rows.length)
    assert.equal(displayed,rows.length)
  })
  await verifyReportDownloads('low-stock')
  await check('Supplier ledger excludes purchase and payment rows for another branch', async () => {
    const empty = await api('/branches', {
      branchCode:'QANONE',branchName:'QA Empty Branch',address:'3 QA Street',contactNumber:'9000000008',status:'ACTIVE',
    })
    const [source] = await sql(`
      SELECT COUNT(*) total FROM purchases WHERE store_id=$1 AND branch_id=$2 AND supplier_id=$3
    `,[store,empty.id,supplier.id])
    assert.equal(Number(source.total),0)
    const scoped = await api('/reports/supplier-ledger?supplierId='+supplier.id+'&branchId='+empty.id)
    assert.equal(scoped.rows.length,0)
    assert.equal(Number(scoped.closingBalance),0)
    await page.reload()
    await tab('Supplier ledger').click()
    await page.locator('select').filter({has:page.getByRole('option',{name:'All suppliers'})})
      .selectOption(String(supplier.id))
    const response = page.waitForResponse(r => r.url().includes('/api/reports/supplier-ledger')
      && r.url().includes('branchId='+empty.id) && r.ok())
    await page.getByLabel('Select branch').selectOption(String(empty.id))
    assert.equal(Number((await (await response).json()).closingBalance),0)
    await metric('Closing balance',0)
    await page.getByLabel('Select branch').selectOption('')
  })
  await check('Period report endpoints exclude transactions outside the date range', async () => {
    const filters = '?from=2000-01-01&to=2000-01-02'
    for (const [key,field] of [
      ['sales',r=>r.totalAmount],['purchases',r=>r.totalAmount],['gst',r=>r.totals.taxAmount],
      ['stock',r=>r.totalCurrentStock],['profit',r=>r.grossProfit],['branches',r=>r.totals.totalSales],
      ['store',r=>r.totalSalesAmount],['supplier-bills',r=>r.totals.grandTotal],
      ['supplier-payments',r=>r.totals.amount],['payment-summary',r=>r.collected],
    ]) assert.equal(Number(field(await api('/reports/'+key+filters))),0,key)
    const ledger = await api('/reports/supplier-ledger'+filters+'&supplierId='+supplier.id)
    assert.equal(Number(ledger.closingBalance),0)
  })
  await check('Payment Summary branch, tender, cashier, date and store filters', async () => {
    const reference = '/reports/payment-summary?branchId=' + main + '&cashierId=' + me.user.id
    const filtered = await api(reference + '&method=UPI')
    assert.equal(Number(filtered.collected), 40)
    assert.equal(filtered.methods[0].transactions, 1)
    assert.equal((await api(reference + '&from=2000-01-01&to=2000-01-02')).bills, 0)
    await api('/reports/payment-summary?storeId=' + (store + 1), undefined, 403)
    await tab('Payment summary').click()
    const tender = page.getByRole('combobox', { name: 'Payment method' })
    await tender.selectOption('UPI')
    await metric('Total amount collected', 40)
    assert.equal(await page.getByText('Outstanding Amount', {exact:true}).count(), 0)
    await page.screenshot({ path: resolve(artifacts, 'payment-summary.png'), fullPage:true })
  })
  await check('Empty branch is excluded from operational reports', async () => {
    const branches = await api('/branches/all')
    const empty = branches.find(branch => branch.branchCode === 'QANONE')
    assert.ok(empty)
    for (const [key,pick] of [
      ['sales',r=>r.totalAmount], ['purchases',r=>r.totalAmount],
      ['profit',r=>r.grossProfit], ['stock',r=>r.totalCurrentStock],
      ['gst',r=>r.totals.taxAmount], ['branches',r=>r.totals.totalSales],
      ['store',r=>r.totalSalesAmount], ['supplier-outstanding',r=>r.totals.outstandingAmount],
      ['supplier-bills',r=>r.totals.grandTotal], ['supplier-payments',r=>r.totals.amount],
      ['supplier-ledger',r=>r.closingBalance], ['payment-summary',r=>r.collected],
    ]) {
      const supplierParam = key === 'supplier-ledger' ? '&supplierId='+supplier.id : ''
      assert.equal(Number(pick(await api('/reports/'+key+'?branchId='+empty.id+supplierParam))),0,key)
    }
    const low = await api('/reports/low-stock?branchId='+empty.id)
    assert.ok(low.rows.every(row => Number(row.branchId) === Number(empty.id)
      && Number(row.currentStock) === 0))
  })
  await check('Report search and tax filters exclude unmatched rows', async () => {
    const suffix = '?search=__qa-no-matching-records__'
    for (const [key,pick] of [
      ['sales',r=>r.entries],['purchases',r=>r.entries],['stock',r=>r.rows.length],
      ['low-stock',r=>r.total],['profit',r=>r.byItem.length],
      ['supplier-outstanding',r=>r.rows.length],['supplier-bills',r=>r.rows.length],
      ['supplier-payments',r=>r.rows.length],
    ]) assert.equal(Number(pick(await api('/reports/'+key+suffix))),0,key)
    assert.equal(Number((await api('/reports/gst?taxRate=28')).totals.taxAmount),0)
    assert.equal(Number((await api('/reports/gst?taxRate=5')).totals.taxAmount),5)
  })
  await check('Every report respects store membership', async () => {
    const other = await browser.newContext()
    try {
      await api('/auth/login',{storeCode:isolationStore,username:'owner',password:'Owner@123'},200,other.request)
      for (const [key,pick] of [
        ['sales',r=>r.totalAmount],['purchases',r=>r.totalAmount],['stock',r=>r.totalCurrentStock],
        ['profit',r=>r.grossProfit],['gst',r=>r.totals.taxAmount],['branches',r=>r.totals.totalSales],
        ['store',r=>r.totalSalesAmount],['low-stock',r=>r.total],['supplier-outstanding',r=>r.totals.outstandingAmount],
        ['supplier-bills',r=>r.totals.grandTotal],['supplier-payments',r=>r.totals.amount],
        ['payment-summary',r=>r.collected],
      ]) assert.equal(Number(pick(await api('/reports/'+key,undefined,200,other.request))),0,key)
      await api('/reports/supplier-ledger?supplierId='+supplier.id,undefined,404,other.request)
      await api('/reports/payment-summary?storeId='+store,undefined,403,other.request)
    } finally {
      await other.close()
    }
  })
  await check('Flyway migration checks and financial/stock consistency', async () => {
    const migration = await sql('SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank')
    assert.equal(migration.at(-1).version,'14')
    assert.ok(migration.every(row => row.success))
    const [adjustment] = await sql('SELECT adjustment_number FROM stock_adjustments WHERE store_id=$1',[store])
    assert.match(adjustment.adjustment_number,/^[1-9][0-9]*$/)
    const [movement] = await sql("SELECT COUNT(*) count,SUM(quantity_out) quantity FROM inventory_transactions WHERE store_id=$1 AND reference_id=$2 AND transaction_type='SALE_OUT'",[store,sale.id])
    assert.equal(Number(movement.count),1)
    assert.equal(Number(movement.quantity),2)
    const [stock] = await sql('SELECT current_stock FROM branch_inventory WHERE store_id=$1 AND branch_id=$2 AND item_id=$3',[store,main,fixture.id])
    assert.equal(Number(stock.current_stock),Number(fixture.current_stock)+2)
  })
  await check('Restricted cashier cannot read report or modify a bill', async () => {
    const restricted = await browser.newContext()
    await api('/auth/login',{storeCode,username:'staff',password:'Staff@123'},200,restricted.request)
    await api('/reports/payment-summary', undefined, 403, restricted.request)
    await api('/sales/' + sale.id + '/modifications',{ baseModificationCount:4,items:lines,payments:[] },403,restricted.request)
    await restricted.close()
  })
  await check('POS and Bill Modification use byte-identical invoice HTML at five sizes', async () => {
    const saved = await api('/sales/' + sale.id)
    const layouts = []
    const papers = ['A4','A5','MM80','MM58','CUSTOM']
    const capture = async (source) => {
      const dialog = page.getByRole('dialog')
      const select = dialog.locator('select').filter({ has: page.getByRole('option', { name: 'Custom size' }) })
      const invoice = dialog.locator('iframe')
      const html = []
      for (const paper of papers) {
        await select.selectOption(paper)
        if (paper === 'CUSTOM') {
          const dimensions = dialog.locator('input[type=number]')
          await dimensions.nth(0).fill('100')
          await dimensions.nth(1).fill('180')
        }
        await invoice.waitFor()
        html.push(await invoice.getAttribute('srcdoc'))
        if (paper === 'MM80') {
          await dialog.frameLocator('iframe').locator('.doc').waitFor()
          await invoice.screenshot({path:resolve(artifacts,source+'-invoice-MM80.png')})
        }
      }
      return html
    }
    await page.goto(base + '/sales/modify/' + sale.id)
    await page.getByRole('button', {name:'Print invoice',exact:true}).last().click()
    layouts.push(await capture('bill-modification'))
    await page.goto(base + '/pos')
    await page.getByPlaceholder('Search items by name, code or barcode…').fill(fixture.item_name)
    await page.getByText(fixture.item_name,{exact:true}).first().click()
    await page.getByRole('button',{name:'Split payment'}).click()
    await page.getByLabel('Cash amount').fill('1')
    await page.getByLabel('UPI amount').fill('1')
    let posted
    await page.route('**/api/sales/split', async route => {
      posted = route.request().postDataJSON()
      await route.fulfill({ status:200,json:saved })
    })
    await page.getByRole('button',{name:/^Complete sale/}).click()
    await page.getByRole('button',{name:'Print invoice',exact:true}).waitFor()
    assert.deepEqual(posted?.payments,
      [{method:'CASH',amount:1},{method:'UPI',amount:1}])
    await page.getByRole('button',{name:'Print invoice',exact:true}).click()
    layouts.push(await capture('pos'))
    assert.deepEqual(layouts[0],layouts[1])
    const printPage = await context.newPage()
    for (const [index,paper] of papers.entries()) {
      const html=layouts[0][index]
      assert.match(html,/Cash.*UPI.*Card/)
      assert.ok(html.includes('<title>Invoice '+saved.salesNumber+'</title>'))
      await printPage.setContent(html)
      const sizeRule = await printPage.evaluate(() => Array.from(document.styleSheets)
        .flatMap(sheet => Array.from(sheet.cssRules))
        .find(rule => rule instanceof CSSPageRule)?.style.getPropertyValue('size'))
      assert.equal(sizeRule?.toLowerCase().replace(/ portrait$/,''), ['a4','a5','auto','auto','100mm 180mm'][index])
      const size = paper==='MM80' ? {width:'80mm',height:'180mm'} : paper==='MM58' ? {width:'58mm',height:'180mm'} : {}
      const pdf = await printPage.pdf({ preferCSSPageSize:true, printBackground:true, ...size, path:resolve(artifacts,'invoice-'+paper+'.pdf') })
      assert.equal(pdf.subarray(0,4).toString(),'%PDF')
    }
    await printPage.close()
    await page.unroute('**/api/sales/split')
  })
  await check('Silent printing uses only mocked bridge and persists outcomes', async () => {
    await page.unroute('**/api/sales/split')
    await page.unroute('**/api/sales')
    await page.goto(base+'/pos')
    await page.getByPlaceholder('Search items by name, code or barcode…').fill(fixture.item_name)
    await page.getByText(fixture.item_name,{exact:true}).first().click()
    const saved = await api('/sales/'+sale.id)
    const mockSale = (route) => route.fulfill({status:200,json:saved})
    await page.route('**/api/sales',mockSale)
    await page.getByRole('button',{name:/^Complete sale/}).click()
    await page.getByRole('button',{name:'Thermal receipt',exact:true}).waitFor()
    await page.unroute('**/api/sales',mockSale)
    await page.evaluate(() => {
      Object.defineProperty(navigator,'platform',{value:'Win32'})
      localStorage.setItem('ims-print-bridge',JSON.stringify({WINDOWS:{key:'qa-mock-bridge'}}))
      const actual = window.fetch
      window.qaBridgeRequests = []
      window.print = () => { throw Error('Physical printing is prohibited in QA') }
      window.fetch = async (url, request) => {
        if (!String(url).startsWith('http://127.0.0.1:17777/api/bridge/')) return actual(url,request)
        if (String(url).endsWith('/status')) return Response.json({ ok:true, driverInvoices:true })
        window.qaBridgeRequests.push(JSON.parse(request.body))
        if (window.qaPrintResult === 'timeout') throw new DOMException('Mock timeout','AbortError')
        if (window.qaPrintResult === 'offline') throw new TypeError('Mock connection refused')
        return Response.json(window.qaPrintResult === 'printed'
          ? {success:true} : {success:false,message:'Mock printer rejected the job'})
      }
    })
    const results = [
      ['printed','PRINTED',/Invoice sent to the printer/],
      ['failed','FAILED',/Mock printer rejected/],
      ['timeout','FAILED',/did not respond in time/],
      ['offline','FAILED',/Cannot reach the Windows print bridge/],
    ]
    for (const [mock,expected,message] of results) {
      await page.evaluate(mode => { window.qaPrintResult = mode },mock)
      const acknowledgement = page.waitForResponse(r => r.request().method() === 'PUT'
        && /\/printing\/jobs\/\d+\/status$/.test(r.url()) && r.ok())
      await page.getByRole('button',{name:'Thermal receipt',exact:true}).click()
      const result = await acknowledgement
      assert.equal(result.request().postDataJSON().status,expected)
      await page.getByText(message).first().waitFor()
      assert.equal(await page.getByRole('dialog').locator('iframe').count(),0)
    }
    const calls = await page.evaluate(() => window.qaBridgeRequests)
    assert.equal(calls.length,4)
    assert.ok(calls.every(job => job.document.raster.width === 576 && job.document.raster.bits.length > 0))
    const dbJobs = await sql("SELECT status FROM print_jobs WHERE document_id=$1 AND store_id=$2 ORDER BY id",[sale.id,store])
    assert.deepEqual(dbJobs.map(row => row.status),['PRINTED','FAILED','FAILED','FAILED'])
    await page.evaluate(() => { window.qaPrintResult = 'printed' })
    await page.getByRole('button',{name:'Print invoice',exact:true}).click()
    const preview = page.getByRole('dialog')
    await preview.locator('select').filter({has: page.getByRole('option',{name:'A5 — small laser / inkjet'})}).selectOption('A5')
    await preview.locator('select').filter({has: page.getByRole('option',{name:'Landscape'})}).selectOption('landscape')
    await preview.getByRole('button',{name:'Save for silent printing'}).click()
    assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('ims.pos.silentInvoicePaper'))),
      {template:'RETAIL',paper:{kind:'A5',orientation:'landscape'}})
    const driverAcknowledgement = page.waitForResponse(r => r.request().method() === 'PUT'
      && /\/printing\/jobs\/\d+\/status$/.test(r.url()) && r.ok())
    await page.getByRole('button',{name:'Print invoice',exact:true}).click()
    assert.equal((await driverAcknowledgement).request().postDataJSON().status,'PRINTED')
    assert.equal(await page.getByRole('dialog').locator('iframe').count(),0)
    const driverJob = await page.evaluate(() => window.qaBridgeRequests.at(-1))
    const {widthMm,heightMm,pages} = driverJob.document.driverInvoice
    assert.equal(widthMm,210)
    assert.equal(heightMm,148)
    assert.ok(pages.length > 0 && pages.every(png => png.startsWith('iVBORw0KGgo')))
    await page.getByRole('button',{name:'Print settings',exact:true}).click()
    await page.getByRole('button',{name:'Turn off silent printing'}).click()
    assert.equal(await page.evaluate(() => localStorage.getItem('ims.pos.silentInvoicePaper')),null)
    await page.evaluate(() => localStorage.removeItem('ims-print-bridge'))
  })
  await check('Responsive reports fit 390px viewport', async () => {
    await page.setViewportSize({ width:390,height:844 })
    await page.goto(base + '/reports')
    await tab('Payment summary').click()
    await metric('Total amount collected',105)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true)
    await page.screenshot({path:resolve(artifacts,'payment-summary-mobile.png'),fullPage:true})
    await page.setViewportSize({width:1440,height:980})
  })
  await check('Browser payment editor saves a corrected split and persists it after reload', async () => {
    await page.goto(base+'/sales/modify/'+sale.id)
    await page.getByLabel('Payment amount').first().fill('25')
    await page.getByLabel('Payment amount').last().fill('40')
    await page.getByRole('button',{name:'Review changes'}).click()
    await page.getByRole('dialog',{name:'Confirm bill modification'}).waitFor()
    await page.getByRole('button',{name:'Confirm modification',exact:true}).click()
    await page.getByText('Saved payment:',{exact:false}).filter({hasText:'25.00'}).waitFor()
    await page.reload()
    await page.getByText('Saved payment:',{exact:false}).filter({hasText:'25.00'}).waitFor()
    const [persisted] = await sql('SELECT payments FROM sales WHERE id=$1',[sale.id])
    assert.deepEqual(persisted.payments,[{method:'CASH',amount:25},{method:'UPI',amount:40},{method:'CARD',amount:40}])
  })
  await check('Browser POS split checkout saves a genuine sale and updates stock', async () => {
    await page.unroute('**/api/sales')
    await page.goto(base+'/pos')
    await page.getByLabel('Billing branch').selectOption(String(main))
    await page.getByPlaceholder('Search items by name, code or barcode…').fill(fixture.item_name)
    await page.getByText(fixture.item_name,{exact:true}).first().click()
    await page.getByRole('button',{name:'Split payment'}).click()
    await page.getByLabel('Cash amount').fill('1000')
    await page.getByLabel('UPI amount').fill('5')
    assert.equal(await page.getByRole('button',{name:/^Complete sale/}).isDisabled(),true)
    await page.getByLabel('Cash amount').fill('5')
    const oldServer = (route) => route.fulfill({ status: 405, json: { message: 'Method not allowed' } })
    await page.route('**/api/sales/split', oldServer)
    await page.getByRole('button',{name:/^Complete sale/}).click()
    await page.getByText('This server needs an update before it can save split payments. The sale was not saved.',{exact:true}).waitFor()
    assert.equal(await page.getByLabel('Cash amount').inputValue(),'5')
    await page.unroute('**/api/sales/split', oldServer)
    const saleResponse = page.waitForResponse(r => r.url().endsWith('/api/sales/split') && r.request().method()==='POST',
      { timeout: 20_000 })
    await page.getByRole('button',{name:/^Complete sale/}).click()
    const response = await saleResponse
    assert.equal(response.status(), 200, await response.text())
    const result = await response.json()
    assert.ok(Number(result.salesNumber) > Number(sale.salesNumber))
    assert.deepEqual(result.payments,[{method:'CASH',amount:5},{method:'UPI',amount:5}])
    const [persisted] = await sql('SELECT payments,paid_amount FROM sales WHERE id=$1',[result.id])
    assert.equal(Number(persisted.paid_amount),10)
    assert.deepEqual(persisted.payments,result.payments)
    const first = await api('/sales?page=0&size=1')
    const second = await api('/sales?page=1&size=1')
    assert.equal(first.content.length,1)
    assert.equal(second.content.length,1)
    assert.notEqual(first.content[0].id,second.content[0].id)
  })
  await check('Payment Summary reconciles paid, partial and unpaid bills and every method row', async () => {
    const unpaid = await api('/sales', { branchId:main,salesDate:today,items:lines,paymentMethod:'CREDIT' })
    assert.equal(Number(unpaid.paidAmount),0)
    const [row] = await sql(`
      SELECT SUM(grand_total) bill, SUM(paid_amount) collected, COUNT(*) bills,
        COUNT(*) FILTER (WHERE paid_amount=grand_total) paid,
        COUNT(*) FILTER (WHERE paid_amount>0 AND paid_amount<grand_total) partial,
        COUNT(*) FILTER (WHERE paid_amount=0 AND grand_total>0) unpaid
      FROM sales WHERE store_id=$1 AND status='COMPLETED'`,[store])
    assert.deepEqual([row.bills,row.paid,row.partial,row.unpaid].map(Number),[3,1,1,1])
    assert.equal(Number(row.collected),115)
    const result = await api('/reports/payment-summary')
    const reportValues = [
      ['Total bill amount',row.bill,result.billAmount,true],
      ['Total amount collected',115,result.collected,true],
      ['Net amount received',115,result.netReceived,true],
      ['Number of bills',3,result.bills,false],
      ['Number of paid bills',1,result.paid,false],
      ['Number of partially paid bills',1,result.partiallyPaid,false],
      ['Number of unpaid bills',1,result.unpaid,false],
    ]
    await page.goto(base+'/reports')
    await tab('Payment summary').click()
    const cards = []
    for (const [label,expected,apiValue,currency] of reportValues) {
      assert.equal(Number(apiValue),Number(expected))
      cards.push({label,expected:Number(expected),api:apiValue,frontend:await metric(label,Number(expected),currency)})
    }
    const expectedMethods = [['Cash','CASH',2,30],['UPI','UPI',2,45],['Card','CARD',1,40],['Bank','BANK',0,0]]
    for (const [label,method,count,collected] of expectedMethods) {
      const entry = result.methods.find(part => part.method === method)
      assert.equal(entry?.transactions ?? 0,count)
      assert.equal(Number(entry?.collected ?? 0),collected)
      const cells = await page.getByRole('row').filter({has:page.getByRole('cell',{name:label,exact:true})})
        .getByRole('cell').allTextContents()
      assert.deepEqual(cells.map(text=>text.trim()),[label,String(count),amount.format(collected)])
    }
    assert.equal(await page.getByText('Outstanding Amount',{exact:true}).count(),0)
    assert.equal(await page.getByText('Total refund amount',{exact:true}).count(),0)
    evidence.paymentStatusCards = cards
    await page.screenshot({path:resolve(artifacts,'payment-summary-three-statuses.png'),fullPage:true})
  })
  await check('Browser creates and finds a category', async () => {
    await page.goto(base+'/categories')
    await page.getByRole('button',{name:'Add category',exact:true}).first().click()
    const modal = page.getByRole('dialog',{name:'Add category'})
    await modal.getByPlaceholder('e.g. Dairy & Frozen').fill('QA Browser Category')
    await modal.getByRole('button',{name:'Create category'}).click()
    await page.getByPlaceholder('Search categories…').fill('QA Browser Category')
    await page.getByText('QA Browser Category',{exact:true}).first().waitFor()
    const [row] = await sql("SELECT COUNT(*) n FROM categories WHERE store_id=$1 AND category_name='QA Browser Category'",[store])
    assert.equal(Number(row.n),1)
  })
  for (const [path, name, values, visible] of [
    ['/units','unit',[['e.g. PCS','QATEST'],['e.g. Pieces','QA Test Unit']],'QA Test Unit'],
    ['/taxes','tax',[['e.g. GST 18%','QA GST Test'],['18','7.25']],'QA GST Test'],
    ['/suppliers','supplier',[['Business or vendor name','QA Browser Supplier'],['9876543210','9000000017'],['Street, area, city, state, PIN','3 QA Lane']],'QA Browser Supplier'],
  ]) await check('Browser creates ' + name, async () => {
    await page.goto(base+path)
    await page.getByRole('button',{name:'Add '+name,exact:true}).first().click()
    const form=page.getByRole('dialog',{name:'Add '+name})
    for (const [placeholder,value] of values) await form.getByPlaceholder(placeholder,{exact:true}).fill(value)
    await form.getByRole('button',{name:'Create '+name,exact:true}).click()
    await page.getByText(visible,{exact:true}).first().waitFor()
  })

  await check('Two-across barcode labels share a print row with odd quantity on the left', async () => {
    await page.goto(base+'/items')
    await page.getByRole('button',{name:'Barcode label for '+fixture.item_name,exact:true}).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('combobox').filter({has:page.getByRole('option',{name:'Two stickers across'})}).selectOption('two')
    await dialog.getByRole('spinbutton').first().fill('3')
    await page.evaluate(() => {
      const append = document.body.appendChild
      document.body.appendChild = function (node) {
        const result = append.call(this,node)
        if (node instanceof HTMLIFrameElement && node.style.visibility === 'hidden') {
          node.contentWindow.print = () => {
            window.qaLabelPrint = node.contentDocument.documentElement.outerHTML
          }
        }
        return result
      }
    })
    await dialog.getByRole('button',{name:'Print 3 labels',exact:true}).click()
    await page.waitForFunction(() => Boolean(window.qaLabelPrint))
    const labelHtml = await page.evaluate(() => window.qaLabelPrint)
    const printed = await context.newPage()
    try {
      await printed.setContent(labelHtml)
      const offsets = await printed.locator('.roll-row').evaluateAll(rows =>
        rows.map(row => [...row.children].map(label => label.offsetLeft)))
      assert.deepEqual(offsets.map(row => row.length), [2,1])
      assert.equal(offsets[0][0], offsets[1][0])
      assert.ok(Math.abs(offsets[0][1] - offsets[0][0] - 52 * 96 / 25.4) <= 1)
      assert.equal(await printed.locator('.label-symbol svg').count(),3)
      const pdf = await printed.pdf({preferCSSPageSize:true,path:resolve(artifacts,'barcode-labels-two-across.pdf')})
      assert.equal(pdf.toString('latin1').match(/\/Type \/Page\b/g)?.length,2)
    } finally {
      await printed.close()
    }
  })
  await check('Settings and platform administration sections load', async () => {
    await page.goto(base+'/settings')
    for (const [label,path] of [
      ['Store profile','/stores/current'], ['Branches','/branches'],
      ['Users','/users'], ['Roles & permissions','/roles'],
      ['Printers','/printing/printers?branchId='+main],
    ]) {
      if (label === 'Store profile') {
        assert.ok((await api('/stores/current')).storeName)
      } else {
        await tab(label).click()
        await page.getByRole('heading').first().waitFor()
        const response = await context.request.get(base+'/api'+path)
        assert.equal(response.status(),200,label)
      }
    }
    await api('/printing/printers',undefined,400)
    await api('/printing/printers?branchId=invalid',undefined,400)
    await tab('My security').click()
    const platformBrowser = await browser.newContext()
    try {
      await api('/auth/login',{storeCode:'PLATFORM',username:adminUsername,password:rotated},200,platformBrowser.request)
      const adminPage = await platformBrowser.newPage()
      for (const route of ['/platform/stores','/platform/accounts','/platform/audit']) {
        await adminPage.goto(base+route)
        await adminPage.locator('main').waitFor()
        assert.equal(new URL(adminPage.url()).pathname,route)
      }
    } finally {
      await platformBrowser.close()
    }
  })

  await check('Transfer numbering, stock movement and cancellation audit persist', async () => {
    const branches = await api('/branches/all')
    const destination = branches.find(branch=>branch.branchCode==='QANONE')
    const itemBefore = await sql('SELECT current_stock FROM branch_inventory WHERE store_id=$1 AND branch_id=$2 AND item_id=$3',[store,main,fixture.id])
    const transfer = await api('/transfers',{
      fromBranchId:main,toBranchId:destination.id,transferDate:today,
      items:[{itemId:Number(fixture.id),quantity:1,unit:fixture.base_unit}],
    })
    assert.match(transfer.transferNumber,/^[1-9][0-9]*$/)
    for (const action of ['approve','dispatch','receive']) await api('/transfers/'+transfer.id+'/'+action,{})
    const movement = await sql(`SELECT branch_id, reference_number, transaction_type,
      quantity_in,quantity_out FROM inventory_transactions
      WHERE store_id=$1 AND reference_id=$2 AND transaction_type IN ('TRANSFER_OUT','TRANSFER_IN')
      ORDER BY id`,[store,transfer.id])
    assert.deepEqual(movement.map(row=>row.reference_number),[transfer.transferNumber,transfer.transferNumber])
    assert.deepEqual(movement.map(row=>row.transaction_type),['TRANSFER_OUT','TRANSFER_IN'])
    assert.equal(Number(movement[0].quantity_out),1)
    assert.equal(Number(movement[1].quantity_in),1)
    const [source] = await sql('SELECT current_stock FROM branch_inventory WHERE store_id=$1 AND branch_id=$2 AND item_id=$3',[store,main,fixture.id])
    assert.equal(Number(source.current_stock),Number(itemBefore[0].current_stock)-1)
    const cancelled = await api('/sales/'+sale.id+'/cancel',{reason:'QA cancellation'})
    assert.match(cancelled.cancellationNumber,/^[1-9][0-9]*$/)
    const [stored] = await sql('SELECT cancellation_number FROM sale_cancellations WHERE sale_id=$1',[sale.id])
    assert.equal(stored.cancellation_number,cancelled.cancellationNumber)
    await api('/sales/'+sale.id+'/cancel',{reason:'QA replay'},409)
  })

  await check('Supplier ledger reconciles payments made when a purchase is recorded', async () => {
    const paidSupplier = await api('/suppliers',{
      supplierName:'QA Purchase Settlement',mobile:'9000000016',address:'4 QA Lane',state:'Karnataka',status:'ACTIVE',
    })
    const bill = await api('/purchases',{
      branchId:main,supplierId:paidSupplier.id,purchaseDate:today,status:'COMPLETED',
      payableAmount:100,paidAmount:40,items:[{itemId:Number(fixture.id),quantity:1,unit:fixture.base_unit,purchaseRate:50}],
    })
    await api('/supplier-payments',{
      supplierId:paidSupplier.id,paymentDate:today,paymentMethod:'BANK',
      amount:10,clientReference:'qa-purchase-settlement',purchaseIds:[bill.id],
    })
    const [saved] = await sql('SELECT balance_amount, paid_amount FROM purchases WHERE id=$1',[bill.id])
    const ledger = await api('/reports/supplier-ledger?supplierId='+paidSupplier.id)
    const partial = { expected:50,database:Number(saved.balance_amount),api:Number(ledger.closingBalance) }
    evidence.initialSupplierSettlement = partial
    assert.equal(Number(saved.paid_amount),50)
    assert.equal(partial.database,partial.expected)
    assert.equal(partial.api,partial.expected,'Ledger must include the initial 40 and later allocation of 10')
    await page.goto(base+'/reports')
    await tab('Supplier ledger').click()
    await page.locator('select').filter({has:page.getByRole('option',{name:'All suppliers'})})
      .selectOption(String(paidSupplier.id))
    partial.frontend = await metric('Closing balance',50)
  })
  await check('Supplier payment report and detail scope multi-branch allocations', async () => {
    const branches = await api('/branches/all')
    const otherBranch = branches.find(branch=>branch.branchCode==='QANONE')
    const mixed = await api('/suppliers',{
      supplierName:'QA Cross-branch Supplier',mobile:'9000000020',address:'5 QA Lane',state:'Karnataka',status:'ACTIVE',
    })
    const bills = []
    for (const [branchId,payableAmount] of [[main,60],[otherBranch.id,100]]) {
      bills.push(await api('/purchases',{
        branchId,supplierId:mixed.id,purchaseDate:today,status:'COMPLETED',
        payableAmount,paidAmount:0,items:[{itemId:Number(fixture.id),quantity:1,unit:fixture.base_unit,purchaseRate:50}],
      }))
    }
    const payment = await api('/supplier-payments',{
      supplierId:mixed.id,paymentDate:today,paymentMethod:'BANK',amount:110,
      clientReference:'qa-allocated-across-branches',purchaseIds:bills.map(bill=>bill.id),
    })
    const db = await sql('SELECT purchase_id,amount FROM supplier_payment_allocations WHERE payment_id=$1 ORDER BY id',[payment.id])
    assert.deepEqual(db.map(row=>Number(row.amount)),[60,50])
    const checks = []
    for (const [branch,total] of [[main,60],[otherBranch.id,50]]) {
      const data = await api('/reports/supplier-payments?supplierId='+mixed.id+'&branchId='+branch)
      checks.push({branch,expected:total,api:Number(data.totals.amount),bills:data.rows[0]?.bills})
    }
    evidence.multiBranchSupplierPayment = checks
    for (const [index,entry] of checks.entries()) {
      assert.equal(entry.api,entry.expected)
      assert.equal(entry.bills,bills[index].purchaseNumber)
    }
    const manager = (await api('/users/roles/available')).find(role=>role.code==='BRANCH_MANAGER')
    await api('/users',{username:'qa-branch-manager',email:'manager-'+storeCode+'@example.test',
      password:'Manager@123',fullName:'QA Branch Manager',roleId:manager.id,branchIds:[main]})
    const branchUser = await browser.newContext()
    try {
      await api('/auth/login',{storeCode,username:'qa-branch-manager',password:'Manager@123'},200,branchUser.request)
      const detail = await api('/supplier-payments/'+payment.id,undefined,200,branchUser.request)
      assert.equal(Number(detail.amount),60)
      assert.deepEqual(detail.allocations.map(row=>row.purchaseNumber),[bills[0].purchaseNumber])
      const list = await api('/supplier-payments?supplierId='+mixed.id,undefined,200,branchUser.request)
      assert.equal(Number(list.content[0].amount),60)
    } finally {
      await branchUser.close()
    }
  })
  for (const [name,path] of [
    ['Dashboard','/'],['Items','/items'],['Categories','/categories'],['Tax master','/taxes'],['Unit master','/units'],
    ['Inventory','/inventory'],['Purchases','/purchases'],['Suppliers','/suppliers'],['Supplier Payments','/supplier-payments'],
    ['POS','/pos'],['Sales entry','/sales/new'],['Bill Lookup','/sales/modify'],
    ['Bill cancellation','/sales/cancel'],['Sales','/sales'],['Settings','/settings'],
    ['Transfers','/transfers'],['Low stock','/low-stock'],['Print History','/print-history'],['Item Imports','/items/imports'],
  ]) await check('Browser navigation ' + name, async () => {
    await page.goto(base + path)
    await page.locator('main').waitFor()
    assert.equal(new URL(page.url()).pathname,path)
  })
  await check('Browser sign out revokes authenticated access', async () => {
    await page.getByRole('button',{name:'Sign out',exact:true}).click()
    await page.waitForURL('**/login')
    await api('/auth/me',undefined,401)
  })
  await check('Browser has no unexpected server errors', async () => assert.deepEqual(evidence.httpErrors.filter(([status])=>status>=500),[]))
  await check('Browser console contains no uncaught page errors', async () => assert.deepEqual(evidence.browserErrors,[]))
} catch (error) {
  evidence.cases.push({name:'Test setup / remaining cases',result:'FAILED',error:String(error)})
  console.error(error)
} finally {
  await writeFile(resolve(artifacts,'ims-e2e-results.json'), JSON.stringify(evidence,null,2))
  await browser.close()
}
process.exitCode = evidence.cases.some(test => test.result === 'FAILED') ? 1 : 0
