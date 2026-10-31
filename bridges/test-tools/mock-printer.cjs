#!/usr/bin/env node
// Minimal ESC/POS printer simulator used by the automated bridge tests.
// It listens on TCP 9100 and appends every received job to mock-printer-output.bin.
const fs = require('fs')
const net = require('net')
const path = require('path')

const port = Number(process.env.MOCK_PRINTER_PORT || 9100)
const output = process.env.MOCK_PRINTER_OUTPUT || path.join(__dirname, 'mock-printer-output.bin')

const server = net.createServer((socket) => {
  const chunks = []
  let finished = false
  socket.on('data', (chunk) => chunks.push(chunk))
  const finish = () => {
    if (finished || !chunks.length) return
    finished = true
    const data = Buffer.concat(chunks)
    const header = Buffer.from(`--- job ${new Date().toISOString()} ${data.length} bytes ---\n`)
    fs.appendFileSync(output, Buffer.concat([header, data, Buffer.from('\n')]))
    console.log(`received ${data.length} bytes from ${socket.remoteAddress}`)
  }
  socket.on('end', finish)
  socket.on('close', finish)
  socket.on('error', (error) => console.error(`socket error: ${error.message}`))
})

server.listen(port, '0.0.0.0', () => {
  console.log(`mock printer listening on 0.0.0.0:${port}, writing to ${output}`)
})
