interface NativeEvent {
  id?: string
  result?: unknown
  error?: string
  event?: string
  [key: string]: unknown
}
declare global {
  interface Window {
    ShipingNative?: { postMessage(value: string): void }
  }
}
const pending = new Map<
  string,
  {
    resolve(value: unknown): void
    reject(error: Error): void
    timeout: ReturnType<typeof setTimeout>
  }
>()
const listeners = new Set<(event: NativeEvent) => void>()
export const isAndroid = !!window.ShipingNative

window.addEventListener('shiping-native', ((event: CustomEvent<NativeEvent>) => {
  const data = event.detail
  if (data.id) {
    const task = pending.get(data.id)
    if (task) {
      clearTimeout(task.timeout)
      pending.delete(data.id)
      if (data.error) task.reject(new Error(data.error))
      else task.resolve(data.result)
    }
  }
  if (data.event) listeners.forEach((listener) => listener(data))
}) as EventListener)

export function subscribe(listener: (event: NativeEvent) => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

let preview: unknown = null
export function call<T = unknown>(
  method: string,
  params: Record<string, unknown> = {},
  id: string = crypto.randomUUID()
): Promise<T> {
  if (!isAndroid) {
    if (method === 'load') return Promise.resolve({ state: preview, version: '浏览器预览' } as T)
    if (method === 'save') {
      preview = structuredClone(params.state)
      return Promise.resolve(true as T)
    }
    if (['ready', 'cancel', 'stopCapture'].includes(method)) return Promise.resolve(true as T)
    if (method === 'captureStatus') return Promise.resolve(false as T)
    if (method === 'copy')
      return navigator.clipboard.writeText(String(params.text)).then(() => true as T)
    return Promise.reject(new Error('此功能需要安装 Android App；浏览器仅提供界面预览'))
  }
  return new Promise<T>((resolve, reject) => {
    const timeout = setTimeout(
      () => {
        pending.delete(id)
        if (method === 'chat')
          window.ShipingNative?.postMessage(
            JSON.stringify({ id: crypto.randomUUID(), method: 'cancel', params: { requestId: id } })
          )
        reject(new Error('操作超时，请检查网络或重试'))
      },
      method === 'chat' ? 310000 : method === 'capture' ? 180000 : 100000
    )
    pending.set(id, { resolve: (value) => resolve(value as T), reject, timeout })
    window.ShipingNative!.postMessage(JSON.stringify({ id, method, params }))
  })
}
