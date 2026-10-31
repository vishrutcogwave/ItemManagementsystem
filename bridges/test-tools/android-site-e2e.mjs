#!/usr/bin/env node
// Drives the website inside Chrome on the Android emulator over CDP:
// login, bridge key from localStorage, POS sale, automatic silent print.
// Requires: adb forward tcp:9333 localabstract:chrome_devtools_remote
//           adb reverse tcp:5173 tcp:5173
//           npm install --no-save ws
// Usage: node android-site-e2e.mjs <bridgeKey> [pageUrl]
import { createRequire } from 'node:module'

const bridgeKey = process.argv[2]
const pageUrl = process.argv[3] || 'http://10.0.2.2:5173/login'
const { WebSocket } = createRequire(import.meta.url)('ws')
const siteOrigin = new URL(pageUrl).origin
if (!bridgeKey) {
  console.error('Bridge key is required')
  process.exit(1)
}

const targets = await (await fetch('http://127.0.0.1:9333/json')).json()
const page = targets.find((target) => target.type === 'page')
if (!page) {
  console.error('No Chrome page target found')
  process.exit(1)
}

const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.onopen = resolve
  ws.onerror = reject
})

let nextId = 0
const pending = new Map()
ws.onmessage = (event) => {
  const message = JSON.parse(event.data)
  const handler = pending.get(message.id)
  if (handler) {
    pending.delete(message.id)
    handler(message)
  }
}

function send(method, params = {}) {
  return new Promise((resolve) => {
    const id = ++nextId
    pending.set(id, resolve)
    ws.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const response = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
    userGesture: true,
  })
  if (response.result?.exceptionDetails) {
    throw new Error(response.result.exceptionDetails.text + ' ' + (response.result.exceptionDetails.exception?.description ?? ''))
  }
  return response.result?.result?.value
}

const helpers = `
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const setValue = (el, value) => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const clickText = (text) => {
    const button = [...document.querySelectorAll('button')].find((b) => b.textContent.trim().includes(text));
    if (button) button.click();
    return Boolean(button);
  };
`

await send('Page.enable')
await send('Runtime.enable')

await evaluate(`location.href = ${JSON.stringify(pageUrl)}; true`)
await evaluate(`(async () => { ${helpers} for (let i = 0; i < 40; i++) { await wait(250); if (document.querySelector('input')) return true; } return false; })()`)

const loginResult = await evaluate(`(async () => {
  ${helpers}
  const inputs = [...document.querySelectorAll('input')];
  if (inputs.length < 3) return 'no-inputs';
  setValue(inputs[0], 'PRINT1');
  setValue(inputs[1], 'printowner');
  setValue(inputs[2], 'Print@1234');
  await wait(200);
  clickText('Sign in');
  for (let i = 0; i < 60; i++) {
    await wait(250);
    if (location.pathname === '/') return 'logged-in';
  }
  return 'login-timeout:' + location.pathname + ':' + document.body.innerText.slice(0, 160);
})()`)
console.log('login:', loginResult)
if (loginResult !== 'logged-in') process.exit(2)

await evaluate(`localStorage.setItem('ims-print-bridge', ${JSON.stringify(
  JSON.stringify({ WINDOWS: { key: '', port: 17777 }, ANDROID: { key: bridgeKey, port: 17778 } }),
)}); true`)

await evaluate(`location.href = ${JSON.stringify(`${siteOrigin}/pos`)}; true`)

const itemReady = await evaluate(`(async () => {
  ${helpers}
  for (let i = 0; i < 60; i++) {
    await wait(250);
    if (document.body.innerText.includes('Print Test Item')) return true;
  }
  return false;
})()`)
console.log('pos item ready:', itemReady)
if (!itemReady) process.exit(3)

const saleResult = await evaluate(`(async () => {
  ${helpers}
  const item = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('Print Test Item'));
  item.click();
  await wait(900);
  const completed = clickText('Complete sale');
  if (!completed) return { ok: false, reason: 'no complete button' };
  for (let i = 0; i < 30; i++) {
    await wait(400);
    if (document.body.innerText.includes('Sale completed')) break;
  }
  await wait(7000);
  const dialog = [...document.querySelectorAll('[role="dialog"]')].map((d) => d.innerText).join(' | ');
  return {
    ok: dialog.includes('Sale completed'),
    dialog: dialog.slice(0, 400),
    printMessages: document.body.innerText.split('\\n').filter((l) => /print|receipt|bridge|printer/i.test(l)).slice(0, 10),
  };
})()`)
console.log('sale:', JSON.stringify(saleResult, null, 2))

ws.close()
if (!saleResult?.ok) process.exit(4)
