import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { buildRequestHeaders, parseRequestHeaders } = createRequire(import.meta.url)(
  '../../src/shared/request-headers.ts'
)

test('Android UI validates the same headers as the desktop client', () => {
  assert.deepEqual(
    parseRequestHeaders('X-Gateway: one\ninvalid\nX-Title: 中文').invalidLines,
    [2, 3]
  )
  assert.deepEqual(buildRequestHeaders('original', 'authorization: Bearer custom'), {
    Authorization: 'Bearer custom'
  })
})
