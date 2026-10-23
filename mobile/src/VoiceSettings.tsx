import { call } from './bridge'
import { useState } from 'react'
import { interviewPresets, interviewScenes, type State, type InterviewSettings } from './model'
export default function VoiceSettings({
  data,
  change
}: {
  data: State
  change: (state: State) => void
}) {
  const { asr, interview } = data.settings
  const [sceneName, setSceneName] = useState('')
  const scenes = interviewScenes(interview)
  const update = (values: Partial<InterviewSettings>) =>
    change({
      ...data,
      settings: { ...data.settings, interview: { ...interview, scenes, ...values } }
    })
  const voice = (key: keyof typeof asr, value: string) =>
    change({ ...data, settings: { ...data.settings, asr: { ...asr, [key]: value } } })
  return (
    <>
      <section className="panel">
        <h2>连续语音 · 百炼 Fun-ASR</h2>
        <p className="field-hint">
          独立于回答模型的密钥。开始连续识别后会持续上传音频并可能计费；「检测音频」只在手机内检测音量。
        </p>
        <label>
          ASR API Key
          <input
            aria-label="ASR API Key"
            type="password"
            autoComplete="off"
            value={asr.apiKey}
            onChange={(e) => voice('apiKey', e.target.value)}
            placeholder="百炼实时语音 API Key"
          />
        </label>
        <label>
          实时 WebSocket 地址
          <input
            aria-label="实时 WebSocket 地址"
            value={asr.endpoint}
            onChange={(e) => voice('endpoint', e.target.value)}
            spellCheck={false}
            autoCapitalize="none"
            placeholder="wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
          />
        </label>
        <p className="field-hint">
          支持百炼兼容协议。北京默认域名，也可填写业务空间专属 WSS
          地址；新加坡密钥需使用对应地域域名。
        </p>
        <label>
          实时 ASR 模型
          <input
            value={asr.model}
            onChange={(e) => voice('model', e.target.value)}
            spellCheck={false}
          />
        </label>
        <details>
          <summary>识别优化与悬浮提示</summary>
          <label>
            识别语言
            <select value={asr.language} onChange={(e) => voice('language', e.target.value)}>
              <option value="">自动识别</option>
              <option value="zh">中文</option>
              <option value="en">英文</option>
            </select>
          </label>
          <label>
            热词表 ID（可选）
            <input
              value={asr.vocabularyId}
              onChange={(e) => voice('vocabularyId', e.target.value)}
              placeholder="百炼中已创建的 vocabulary_id"
            />
          </label>
          <p className="field-hint">
            技术词汇可在百炼创建热词表后填入 ID。音源、语言、热词和断句参数在下次开始监听时生效。
          </p>
          <button
            className="secondary-button"
            onClick={() => void call('overlaySettings').catch((e) => window.alert(String(e)))}
          >
            允许显示悬浮面试提示
          </button>
          <p className="field-hint">
            授权后，监听时显示可拖动、可收起的提示窗。「截图」会直接发送当前画面给识屏模型并在窗内回答；「提示」切回语音提示。提示窗可能出现在会议共享中。
          </p>
        </details>
      </section>
      <section className="panel">
        <h2>面试提示</h2>
        <label>
          句末静默时间
          <select
            value={interview.silenceMs}
            onChange={(e) =>
              change({
                ...data,
                settings: {
                  ...data.settings,
                  interview: { ...interview, silenceMs: Number(e.target.value) }
                }
              })
            }
          >
            {[500, 800, 1200, 1600].map((ms) => (
              <option key={ms} value={ms}>
                {ms} ms
              </option>
            ))}
          </select>
        </label>
        <label>
          自动提示最短字数
          <select
            value={interview.minChars}
            onChange={(e) =>
              change({
                ...data,
                settings: {
                  ...data.settings,
                  interview: { ...interview, minChars: Number(e.target.value) }
                }
              })
            }
          >
            {[2, 4, 6, 8].map((n) => (
              <option key={n} value={n}>
                {n} 个有效字符
              </option>
            ))}
          </select>
        </label>
        <label>
          编辑对话场景
          <select
            aria-label="编辑对话场景"
            value={interview.preset}
            onChange={(e) => {
              const scene = scenes.find((s) => s.id === e.target.value)!
              update({ preset: scene.id, prompt: scene.prompt })
            }}
          >
            {scenes.map((scene) => (
              <option key={scene.id} value={scene.id}>
                {scene.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          场景名称
          <input
            aria-label="场景名称"
            maxLength={40}
            value={scenes.find((s) => s.id === interview.preset)?.name ?? ''}
            onChange={(e) =>
              update({
                scenes: scenes.map((s) =>
                  s.id === interview.preset ? { ...s, name: e.target.value } : s
                )
              })
            }
          />
        </label>
        <label>
          面试提示词
          <textarea
            aria-label="面试提示词"
            rows={8}
            maxLength={16000}
            value={interview.prompt}
            onChange={(e) =>
              update({
                prompt: e.target.value,
                scenes: scenes.map((s) =>
                  s.id === interview.preset ? { ...s, prompt: e.target.value } : s
                )
              })
            }
          />
        </label>
        <div className="preset-actions">
          <button
            className="text-button danger"
            disabled={scenes.length < 2}
            onClick={() => {
              if (!window.confirm('删除当前对话场景？')) return
              const remaining = scenes.filter((s) => s.id !== interview.preset)
              update({ scenes: remaining, preset: remaining[0].id, prompt: remaining[0].prompt })
            }}
          >
            删除场景
          </button>
          <button
            className="text-button"
            onClick={() => {
              if (!window.confirm('恢复三个预设场景的名称与提示词？自建场景会保留。')) return
              const restored = [
                ...interviewPresets,
                ...scenes.filter((s) => !interviewPresets.some((p) => p.id === s.id))
              ]
              update({ scenes: restored, preset: restored[0].id, prompt: restored[0].prompt })
            }}
          >
            恢复预设场景
          </button>
        </div>
        <label>
          新场景名称
          <input
            aria-label="新场景名称"
            maxLength={40}
            value={sceneName}
            onChange={(e) => setSceneName(e.target.value)}
            placeholder="例如 Java 后端面试"
          />
        </label>
        <button
          className="secondary-button"
          disabled={!sceneName.trim() || scenes.length >= 30}
          onClick={() => {
            const scene = {
              id: crypto.randomUUID(),
              name: sceneName.trim(),
              prompt: interview.prompt
            }
            update({ scenes: [...scenes, scene], preset: scene.id, prompt: scene.prompt })
            setSceneName('')
          }}
        >
          基于当前提示词新建场景
        </button>
        <p className="field-hint">
          每次提示仅携带近期转录上下文、当前问题和已启用的面试资料。对方继续说话时，正在生成的自动提示会等待并重写。提示方式在面试页切换。
        </p>
      </section>
    </>
  )
}
