import { useEffect, useState } from 'react'
import { call, isAndroid, subscribe } from './bridge'

export interface Utterance {
  id: number
  text: string
  final: boolean
}
export interface Hint {
  id: number
  fromId: number
  toId: number
  source: 'auto' | 'manual' | 'screenshot'
  status: 'capturing' | 'streaming' | 'waiting' | 'done' | 'stopped' | 'error'
  text: string
  reasoning?: string
  error?: string
  notice?: string
  latencyMs?: number
}
export interface InterviewSession {
  id: string
  created: number
  utterances: Utterance[]
  hints: Hint[]
}
export interface InterviewSnapshot {
  session: InterviewSession
  archives: InterviewSession[]
  status: string
  source: string
  purpose: string
  diagnostic: boolean
  level: number
  warning: string
  error: string
  storageError: string
  transcript: string
}
const empty: InterviewSnapshot = {
  session: { id: '', created: 0, utterances: [], hints: [] },
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
export function useInterview() {
  const [snapshot, setSnapshot] = useState(empty)
  const [error, setError] = useState('')
  useEffect(() => {
    let mounted = true
    const unsubscribe = subscribe((event) => {
      if (event.event === 'interview') setSnapshot(event.state as InterviewSnapshot)
    })
    if (isAndroid)
      void call<InterviewSnapshot>('interview', { action: 'snapshot' })
        .then((value) => {
          if (mounted) setSnapshot(value)
        })
        .catch((e) => {
          if (mounted) setError(String(e))
        })
    return () => {
      mounted = false
      unsubscribe()
    }
  }, [])
  async function command(action: string, args: Record<string, unknown> = {}) {
    setError('')
    try {
      return await call('interview', { action, ...args })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      return undefined
    }
  }
  return { snapshot, error, setError, command }
}
export const listeningLabels: Record<string, string> = {
  stopped: '未监听',
  connecting: '连接 ASR…',
  reconnecting: '正在重连…',
  listening: '连续识别中',
  detecting: '本地音频检测',
  finishing: '正在接收末句…'
}
