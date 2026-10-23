import { useState } from 'react'
import { Mic, Square, Activity } from 'lucide-react'
import { call } from './bridge'
import { listeningLabels, useInterview } from './interview'
import type { State } from './model'

export default function AudioPanel({
  data,
  purpose,
  change,
  transcript = false
}: {
  data: State
  purpose: 'interview' | 'screenshot'
  change: (state: State) => void
  transcript?: boolean
}) {
  const { snapshot, error, setError, command } = useInterview()
  const [pending, setPending] = useState(false)
  const active = snapshot.status !== 'stopped'
  async function start(diagnostic: boolean) {
    setPending(true)
    setError('')
    try {
      await call('save', { state: data })
      await call('startListening', { source: data.settings.asr.source, purpose, diagnostic })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setPending(false)
    }
  }
  async function stop() {
    try {
      await call('stopListening')
    } catch (e) {
      setError(String(e))
    }
  }
  return (
    <section className="audio-panel" aria-label="连续语音">
      <div className="audio-heading">
        <strong>
          <Mic size={16} />
          连续语音
        </strong>
        <span role="status">{listeningLabels[snapshot.status] || snapshot.status}</span>
      </div>
      <label className="compact-field">
        音频来源
        <select
          aria-label="连续语音音频来源"
          disabled={active || pending}
          value={data.settings.asr.source}
          onChange={(e) =>
            change({
              ...data,
              settings: {
                ...data.settings,
                asr: { ...data.settings.asr, source: e.target.value as State['settings']['asr']['source'] }
              }
            })
          }
        >
          <option value="playback">应用播放音频（Android 10+）</option>
          <option value="meeting">腾讯会议（USB 激活，Android 13+）</option>
          <option value="microphone">麦克风（听外放或现场）</option>
        </select>
      </label>
      {data.settings.asr.source === 'meeting' && (
        <p className="field-hint">
          首次使用需在电脑运行 activate-meeting-audio.ps1 激活，之后可拔掉 USB。
          激活有效期 8 小时，手机重启或应用升级后请重新激活。只采集腾讯会议的播放声音，保留手机外放或耳机播放，不采集麦克风。
        </p>
      )}
      <div className="audio-actions">
        {active ? (
          <button
            className="primary-button"
            onClick={() => void stop()}
            disabled={snapshot.status === 'finishing'}
          >
            <Square size={16} />
            停止监听
          </button>
        ) : (
          <>
            <button className="primary-button" disabled={pending} onClick={() => void start(false)}>
              <Mic size={16} />
              {pending ? '等待授权…' : '开始连续识别'}
            </button>
            <button
              className="secondary-button"
              disabled={pending}
              onClick={() => void start(true)}
            >
              <Activity size={16} />
              检测音频
            </button>
          </>
        )}
      </div>
      {active && (
        <>
          <meter aria-label="采集音量" min={0} max={1} value={snapshot.level} />
          <p className="field-hint">
            {snapshot.warning || '等待音频…'}
            {snapshot.diagnostic && ' · 仅检测音量，不联网，60 秒后结束'}
          </p>
        </>
      )}
      {!active && (
        <p className="field-hint">
          先「检测音频」并切到目标应用播放声音。腾讯会议同机通话请选择「腾讯会议（USB 激活）」。普通应用播放音频不支持通话音轨。开始识别后，音频会持续发送到配置的
          ASR，可能产生费用。
        </p>
      )}
      {active && snapshot.purpose !== purpose && (
        <p className="field-hint">
          当前语音归属{snapshot.purpose === 'screenshot' ? '识屏' : '面试'}，更换用途请先停止。
        </p>
      )}
      {(error || snapshot.error) && (
        <p className="danger" role="alert">
          {error || snapshot.error}
        </p>
      )}
      {transcript && snapshot.transcript && (
        <div className="speech-transcript">
          <p>{snapshot.transcript}</p>
          <button className="text-button" onClick={() => void command('clearTranscript')}>
            清空转录
          </button>
          <small>发送截图或问题时附带这段转录</small>
        </div>
      )}
    </section>
  )
}
