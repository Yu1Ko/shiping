import { test, expect, type Page } from '@playwright/test'

async function nativeFixture(page: Page) {
  await page.addInitScript(() => {
    let stored: unknown = null
    const interview = {
      session: {
        id: 'test-session',
        created: Date.now(),
        utterances: [] as { id: number; text: string; final: boolean }[],
        hints: [] as Record<string, unknown>[]
      },
      archives: [],
      status: 'stopped',
      source: 'playback',
      purpose: 'interview',
      diagnostic: false,
      level: 0,
      warning: '',
      error: '',
      storageError: '',
      transcript: ''
    }
    const cancelled = new Set<string>()
    const emit = (detail: unknown) =>
      window.dispatchEvent(new CustomEvent('shiping-native', { detail }))
    window.ShipingNative = {
      postMessage(raw: string) {
        const { id, method, params } = JSON.parse(raw)
        if (method === 'load') setTimeout(() => emit({ id, result: { state: stored } }), 1)
        else if (method === 'save') {
          stored = params.state
          setTimeout(() => emit({ id, result: true }), 1)
        } else if (method === 'interview') {
          if (params.action === 'text') {
            if (params.text === '模拟原生拒绝') {
              emit({ id, error: '本段转录已达上限' })
              return
            }
            interview.session.utterances.push({
              id: interview.session.utterances.length + 1,
              text: params.text,
              final: true
            })
          }
          if (params.action === 'hint')
            interview.session.hints = [
              {
                id: 1,
                fromId: 1,
                toId: interview.session.utterances.length,
                source: 'manual',
                status: 'done',
                text: '## 回答要点\n\n先说明核心线程，再解释任务队列。',
                reasoning: '本地界面测试',
                latencyMs: 200
              }
            ]
          emit({ event: 'interview', state: structuredClone(interview) })
          emit({ id, result: params.action === 'snapshot' ? structuredClone(interview) : true })
        } else if (method === 'startListening') {
          interview.source = params.source
          interview.purpose = params.purpose
          interview.diagnostic = params.diagnostic
          interview.status = params.diagnostic ? 'detecting' : 'listening'
          emit({ event: 'interview', state: structuredClone(interview) })
          emit({ id, result: true })
        } else if (method === 'stopListening') {
          interview.status = 'stopped'
          emit({ event: 'interview', state: structuredClone(interview) })
          emit({ id, result: true })
        } else if (method === 'models')
          setTimeout(() => emit({ id, result: ['test-vision', 'test-text'] }), 100)
        else if (method === 'captureStatus') emit({ id, result: false })
        else if (method === 'pickImages') {
          const canvas = document.createElement('canvas')
          canvas.width = 100
          canvas.height = 80
          canvas.getContext('2d')!.fillRect(10, 10, 80, 60)
          emit({ event: 'images', images: [canvas.toDataURL()] })
          emit({ id, result: true })
        } else if (method === 'chat') {
          ;(window as unknown as { lastRequest: unknown }).lastRequest = params
          const tokens = [
            '## 测试回答\n\n',
            '这是一段',
            '流式内容。',
            '\n\n公式 $x^2$ 与代码：\n```ts\nconst value = 42\n```'
          ]
          tokens.forEach((text, index) =>
            setTimeout(
              () => {
                if (!cancelled.has(id)) emit({ event: 'delta', requestId: id, kind: 'text', text })
              },
              80 + index * 150
            )
          )
          setTimeout(() => emit({ id, result: true }), 900)
        } else if (method === 'cancel') {
          cancelled.add(params.requestId)
          emit({ id, result: true })
        } else emit({ id, result: true })
      }
    }
  })
  await page.goto('/')
}

async function configure(page: Page) {
  await page.getByRole('button', { name: '设置', exact: true }).click()
  await page.getByLabel('API Key', { exact: true }).fill('local-test-key')
  await page.getByLabel('模型名称', { exact: true }).fill('test-vision')
  await page.getByRole('button', { name: '识屏', exact: true }).click()
}

test('meeting source uses local detection and remains selected after stop', async ({ page }) => {
  await nativeFixture(page)
  await page.getByRole('button', { name: '面试', exact: true }).click()
  const source = page.getByLabel('连续语音音频来源')
  await source.selectOption('meeting')
  await expect(page.getByText(/首次使用需在电脑运行 activate-meeting-audio.ps1/)).toBeVisible()
  await page.getByRole('button', { name: '检测音频', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('本地音频检测')
  await expect(source).toBeDisabled()
  await expect(page.getByText(/仅检测音量，不联网，60 秒后结束/)).toBeVisible()
  await page.getByRole('button', { name: '停止监听', exact: true }).click()
  await expect(source).toBeEnabled()
  await page.getByRole('button', { name: '设置', exact: true }).click()
  await page.getByRole('button', { name: '面试', exact: true }).click()
  await expect(source).toHaveValue('meeting')
})

test('screenshot-only interview cards expose progress, answer and export', async ({ page }) => {
  await nativeFixture(page)
  await page.getByRole('button', { name: '面试', exact: true }).click()
  await expect(page.getByRole('heading', { name: '对话模式', exact: true })).toBeVisible()
  for (const status of ['capturing', 'done']) {
    await page.evaluate((status) => {
      const session = {
        id: 'screenshot-only',
        created: Date.now(),
        utterances: [],
        hints: [
          {
            id: 1,
            fromId: 0,
            toId: 0,
            source: 'screenshot',
            status,
            text: status === 'done' ? '## 二分查找\n\n时间复杂度 O(log n)。' : ''
          }
        ]
      }
      window.dispatchEvent(
        new CustomEvent('shiping-native', {
          detail: {
            event: 'interview',
            state: {
              session,
              archives: [],
              status: 'stopped',
              source: 'microphone',
              purpose: 'interview',
              diagnostic: false,
              level: 0,
              warning: '',
              error: '',
              storageError: '',
              transcript: ''
            }
          }
        })
      )
    }, status)
    await expect(page.getByRole('button', { name: '截图回答', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '保存 Markdown', exact: true })).toBeEnabled()
    if (status === 'capturing') {
      await expect(
        page.getByText('正在截取屏幕，请完成系统共享授权…', { exact: true })
      ).toBeVisible()
      await expect(page.getByRole('button', { name: '停止提示', exact: true })).toBeVisible()
    } else {
      await expect(page.getByRole('heading', { name: '二分查找', exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: '停止提示', exact: true })).toHaveCount(0)
    }
  }
})

test('phone layout, configuration, streaming math, follow-up and history', async ({ page }) => {
  await nativeFixture(page)
  await expect(page.getByRole('heading', { name: /把屏幕，\s*变成答案。/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await configure(page)
  await page.getByLabel('输入问题').fill('解释一个例子')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('heading', { name: '测试回答' })).toBeVisible()
  await expect(page.locator('.katex')).toBeVisible()
  await expect(page.getByRole('button', { name: '停止生成' })).toHaveCount(0)
  await page.getByLabel('输入问题').fill('继续解释')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('button', { name: '停止生成' })).toBeVisible()
  await page.getByRole('button', { name: '停止生成' }).click()
  await expect(page.getByText('已停止生成 · 可继续追问')).toBeVisible()
  await page.getByRole('button', { name: '历史记录', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '历史记录' })).toContainText('解释一个例子')
})

test('conversation mode preserves rejected input, renders hints and persists editable scenes', async ({
  page
}) => {
  await nativeFixture(page)
  await page.getByRole('button', { name: '面试', exact: true }).click()
  await expect(page.getByRole('heading', { name: '对话模式', exact: true })).toBeVisible()
  await page.getByLabel('面试提示方式').selectOption('manual')
  await page.getByLabel('补充面试问题').fill('模拟原生拒绝')
  await page.getByRole('button', { name: '加入转录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('上限')
  await expect(page.getByLabel('补充面试问题')).toHaveValue('模拟原生拒绝')
  await page.getByLabel('补充面试问题').fill('请介绍线程池的工作过程')
  await page.getByRole('button', { name: '加入转录', exact: true }).click()
  await expect(page.getByLabel('补充面试问题')).toHaveValue('')
  await page.getByRole('button', { name: '生成提示', exact: true }).click()
  await expect(page.getByRole('heading', { name: '回答要点' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '回答要点' })).toBeInViewport()
  await page.getByRole('button', { name: '手动提示 · 1–1 句', exact: true }).click()
  await expect(page.locator('.interview-transcript .highlight')).toContainText('线程池')
  await page.getByRole('button', { name: '收起原文', exact: true }).click()
  await expect(page.getByLabel('面试实时转录')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await page.getByRole('button', { name: '设置', exact: true }).click()
  await page.getByLabel('新场景名称').fill('Java 专项')
  await page.getByRole('button', { name: '基于当前提示词新建场景' }).click()
  await page.getByLabel('面试提示词').fill('用 Java 解释问题并给出复杂度。')
  await page.getByLabel('编辑对话场景').selectOption('english')
  await page.getByLabel('编辑对话场景').selectOption({ label: 'Java 专项' })
  await expect(page.getByLabel('面试提示词')).toHaveValue('用 Java 解释问题并给出复杂度。')
  await page.getByRole('button', { name: '面试', exact: true }).click()
  await expect(page.getByLabel('面试场景').locator('option:checked')).toHaveText('Java 专项')
  await expect(page.getByLabel('面试回答模型')).toBeVisible()
})

test('importing image from conversation does not send conversation history to screenshot provider', async ({
  page
}) => {
  await nativeFixture(page)
  await configure(page)
  await page.getByRole('button', { name: '交流', exact: true }).click()
  await page.getByLabel('输入问题').fill('这是交流专用上下文')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('button', { name: '停止生成' })).toHaveCount(0, { timeout: 5000 })
  await page.getByRole('button', { name: '添加图片' }).click()
  await page.getByLabel('输入问题').fill('识别图片')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect
    .poll(() =>
      page.evaluate(() =>
        JSON.stringify((window as unknown as { lastRequest: unknown }).lastRequest)
      )
    )
    .toContain('image_url')
  expect(
    await page.evaluate(() =>
      JSON.stringify((window as unknown as { lastRequest: unknown }).lastRequest)
    )
  ).not.toContain('这是交流专用上下文')
})

test('knowledge is included only when enabled, unsafe provider URL is rejected', async ({
  page
}) => {
  await nativeFixture(page)
  await configure(page)
  await page.getByRole('button', { name: '资料', exact: true }).click()
  await page.getByRole('button', { name: '新建资料' }).click()
  await page.getByLabel('资料名称').fill('项目背景')
  await page.getByLabel('内容', { exact: true }).fill('测试项目代号 Orion')
  await page.getByRole('button', { name: '保存资料' }).click()
  await expect(page.getByText('项目背景', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '识屏', exact: true }).click()
  await page.getByLabel('输入问题').fill('介绍项目')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect
    .poll(() =>
      page.evaluate(() =>
        JSON.stringify((window as unknown as { lastRequest: unknown }).lastRequest)
      )
    )
    .toContain('Orion')
  await page.getByRole('button', { name: '设置', exact: true }).click()
  await page.getByLabel('API Base URL').fill('http://insecure.test/v1')
  await page.getByRole('button', { name: '测试连接并获取模型' }).click()
  await expect(page.getByRole('status')).toContainText('HTTPS')
})
