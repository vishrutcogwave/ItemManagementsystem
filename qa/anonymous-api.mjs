import assert from 'node:assert/strict'
import { readdir, readFile, writeFile } from 'node:fs/promises'

const base = new URL(process.env.IMS_QA_API ?? 'http://127.0.0.1:58080')
assert.equal(base.hostname, '127.0.0.1', 'API checks are limited to a disposable local server')
const controllerDir = new URL('../backend/src/main/java/com/itemmanagement/controller/', import.meta.url)
const publicRoutes = new Set([
  '/api/auth/login', '/api/auth/register-store', '/api/platform/public-contact',
  '/api/health', '/api/version', '/api/printing/devices/pair', '/api/printing/device/config',
])
const routes = []
for (const file of await readdir(controllerDir)) {
  if (!file.endsWith('Controller.java')) continue
  const source = await readFile(new URL(file, controllerDir), 'utf8')
  const prefix = source.match(/@RequestMapping\("([^"]+)"\)/)?.[1]
  assert.ok(prefix, `Class-level path missing in ${file}`)
  for (const [, verb, args = ''] of source.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?/g)) {
    const suffix = args.match(/^(?:value\s*=\s*)?"([^"]+)"/)?.[1] ?? ''
    assert.ok(!args || suffix, `Unknown route syntax in ${file}: ${args}`)
    const path = prefix + suffix.replaceAll(/\{[^}]+\}/g, '1')
    if (!publicRoutes.has(path)) routes.push({ method: verb.toUpperCase(), path })
  }
}
assert.ok(routes.length >= 100, 'API inventory unexpectedly small')
const results = []
for (let start = 0; start < routes.length; start += 4) {
  const group = await Promise.all(routes.slice(start, start + 4).map(async route => {
    const response = await fetch(new URL(route.path, base), {
      method: route.method,
      redirect: 'manual',
      signal: AbortSignal.timeout(5000),
    })
    await response.body?.cancel()
    return { ...route, status: response.status }
  }))
  results.push(...group)
}
if (process.argv[2]) await writeFile(process.argv[2], JSON.stringify(results, null, 2))
assert.deepEqual(results.filter(route => route.status !== 401), [])
for (const path of ['/api/health', '/api/version', '/api/platform/public-contact']) {
  const response = await fetch(new URL(path, base), { signal: AbortSignal.timeout(5000) })
  assert.equal(response.status, 200, path)
  await response.body?.cancel()
}
console.log(`PASS anonymous access rejected on ${results.length} protected API mappings; public health, version and contact reachable`)
