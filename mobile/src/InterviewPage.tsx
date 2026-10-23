import { useEffect, useRef, useState } from 'react'
import { Copy, Download, Plus, Sparkles, Square } from 'lucide-react'
import { call } from './bridge'
import AudioPanel from './AudioPanel'
import Markdown from './Markdown'
import { useInterview } from './interview'
import { interviewScenes, type State } from './model'

export default function InterviewPage({
  data,
  change
}: {
  data: State
  change: (state: State) => void
}) {
  const { snapshot, error, setError, command } = useInterview()
  const [text, setText] = useState(''),
    [focus, setFocus] = useState<number | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const submission = useRef(false)
  const lastCard = useRef<HTMLElement | null>(null)
  const hintPanel = useRef<HTMLElement | null>(null)
  const transcriptPanel = useRef<HTMLElement | null>(null)
  const readerMoved = useRef(false)
  const revealed = useRef('')
  const previous = useRef('')
  const session = snapshot.session,
    options = data.settings.interview
  const busy = session.hints.some(
    (h) => h.status === 'capturing' || h.status === 'streaming' || h.status === 'waiting'
  )
  const focused = session.hints.find((h) => h.id === focus)
  const sceneList = interviewScenes(options)
  const latest = session.hints.at(-1)
  useEffect(() => {
    const key = session.id + ':' + session.hints.at(-1)?.id
    if (key !== previous.current) {
      previous.current = key
      readerMoved.current = false
      revealed.current = ''
      const panel = hintPanel.current,
        card = lastCard.current
      if (panel && card) {
        panel.scrollTop += card.getBoundingClientRect().top - panel.getBoundingClientRect().top
        panel.scrollIntoView({ block: 'start', behavior: 'smooth' })
      }
    }
  }, [session.id, session.hints.length])
  useEffect(() => {
    if (!latest || latest.status !== 'done' || readerMoved.current) return
    const key = session.id + ':' + latest.id + ':' + latest.latencyMs
    const panel = hintPanel.current,
      card = lastCard.current
    if (panel && card && revealed.current !== key) {
      revealed.current = key
      if (card.getBoundingClientRect().bottom > panel.getBoundingClientRect().bottom + 24)
        panel.scrollBy({ top: panel.clientHeight * 0.8, behavior: 'smooth' })
    }
  }, [session.id, latest?.id, latest?.status, latest?.latencyMs])
  useEffect(() => {
    if (focus !== null)
      transcriptPanel.current?.querySelector('.highlight')?.scrollIntoView({ block: 'nearest' })
  }, [focus])
  function pageHints(direction: number) {
    readerMoved.current = true
    const panel = hintPanel.current
    panel?.scrollBy({ top: direction * panel.clientHeight * 0.8, behavior: 'smooth' })
  }
  async function update(next: State) {
    change(next)
    try {
      await call('save', { state: next })
    } catch (e) {
      setError(String(e))
    }
  }
  async function submit() {
    if (!text.trim() || submission.current) return
    submission.current = true
    setSubmitting(true)
    const submitted = text
    try {
      await call('save', { state: data })
      if ((await command('text', { text: submitted.trim() })) === true)
        setText((current) => (current === submitted ? '' : current))
    } finally {
      submission.current = false
      setSubmitting(false)
    }
  }
  async function exportRecord() {
    const value = await command('export')
    if (typeof value === 'string')
      try {
        await call('saveText', { text: value })
      } catch (e) {
        setError(String(e))
      }
  }
  return (
    <main className="page-scroll interview-page">
      <div className="page-title">
        <span className="eyebrow">听清问题 · 组织答案</span>
        <h1>对话模式</h1>
        <p>连续转录、实时提示，切换应用也可继续。</p>
      </div>
      <AudioPanel data={data} purpose="interview" change={change} />
      <div className="interview-toolbar">
        <label>
          提示方式
          <select
            aria-label="面试提示方式"
            value={options.automatic ? 'auto' : 'manual'}
            onChange={(e) =>
              void update({
                ...data,
                settings: {
                  ...data.settings,
                  interview: { ...options, automatic: e.target.value === 'auto' }
                }
              })
            }
          >
            <option value="auto">自动 · 句末生成</option>
            <option value="manual">手动 · 按需生成</option>
          </select>
        </label>
        <label>
          面试场景
          <select
            aria-label="面试场景"
            value={options.preset}
            onChange={(e) => {
              const preset = sceneList.find((p) => p.id === e.target.value)
              void update({
                ...data,
                settings: {
                  ...data.settings,
                  interview: {
                    ...options,
                    scenes: sceneList,
                    preset: e.target.value,
                    prompt: preset?.prompt ?? options.prompt
                  }
                }
              })
            }}
          >
            {sceneList.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          回答模型
          <select
            aria-label="面试回答模型"
            value={data.settings.conversationProfile}
            onChange={(e) =>
              void update({
                ...data,
                settings: { ...data.settings, conversationProfile: e.target.value }
              })
            }
          >
            {data.settings.profiles.map((profile) => (
              <option key={profile.id} value={profile.id}>
                {profile.name} · {profile.model}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="interview-actions interview-controls">
        <button
          className="primary-button"
          disabled={!session.utterances.length}
          onClick={() => void command('hint')}
        >
          <Sparkles size={16} />
          {session.hints.length ? '提示 / 换个角度' : '生成提示'}
        </button>
        {busy && (
          <button className="secondary-button" onClick={() => void command('stopHints')}>
            <Square size={16} />
            停止提示
          </button>
        )}
        <button
          className="text-button"
          onClick={() =>
            void update({
              ...data,
              settings: {
                ...data.settings,
                interview: { ...options, showTranscript: !options.showTranscript }
              }
            })
          }
        >
          {options.showTranscript ? '收起原文' : '显示原文'}
        </button>
      </div>
      {(error || snapshot.storageError) && (
        <p className="error-banner" role="alert">
          {error || snapshot.storageError}
        </p>
      )}
      {options.showTranscript && (
        <section ref={transcriptPanel} className="interview-transcript" aria-label="面试实时转录">
          <h2>
            实时转录 <small>{session.utterances.length} 句</small>
          </h2>
          {!session.utterances.length && (
            <p className="field-hint">识别到的句子显示在这里，也可以在下方补充问题。</p>
          )}
          {session.utterances.map((line) => (
            <p
              key={line.id}
              className={
                focused && line.id >= focused.fromId && line.id <= focused.toId ? 'highlight' : ''
              }
            >
              <small>{line.id.toString().padStart(2, '0')}</small>
              {line.text}
              {!line.final && <span className="live-dot"> · 正在说</span>}
            </p>
          ))}
        </section>
      )}
      <section
        ref={hintPanel}
        className="hint-list"
        aria-label="面试提示卡"
        onWheel={() => {
          readerMoved.current = true
        }}
        onTouchStart={() => {
          readerMoved.current = true
        }}
      >
        {!session.hints.length && (
          <div className="hint-empty">
            <Sparkles size={28} />
            <h2>把复杂问题，拆成回答要点</h2>
            <p>自动模式在完整句子结束后给出提示。手动模式适合等待对方说完再组织答案。</p>
          </div>
        )}
        {session.hints.map((hint, index) => (
          <article
            key={hint.id}
            ref={index === session.hints.length - 1 ? lastCard : undefined}
            className={'hint-card ' + (hint.text.trim() === '（无需回应）' ? 'no-reply' : '')}
          >
            <header>
              <button
                className="text-button"
                onClick={() => setFocus(focus === hint.id ? null : hint.id)}
              >
                {hint.source === 'screenshot'
                  ? '截图回答'
                  : `${hint.source === 'auto' ? '自动提示' : '手动提示'} · ${hint.fromId}–${hint.toId} 句`}
              </button>
              <small>
                {hint.latencyMs !== undefined ? `${(hint.latencyMs / 1000).toFixed(1)}s` : ''}
              </small>
            </header>
            {hint.status === 'capturing' ? (
              <p role="status">正在截取屏幕，请完成系统共享授权…</p>
            ) : hint.status === 'waiting' ? (
              <p role="status">对方还在说，等待完整问题…</p>
            ) : (
              <>
                {hint.reasoning && (
                  <details className="reasoning">
                    <summary>思考过程</summary>
                    <Markdown text={hint.reasoning} />
                  </details>
                )}
                <Markdown text={hint.text} />
                {hint.status === 'streaming' && (
                  <p className="field-hint" role="status">
                    正在组织提示…
                  </p>
                )}
                {hint.status === 'stopped' && <p className="field-hint">已停止，内容可能不完整</p>}
                {hint.error && <p className="danger">{hint.error}</p>}
                {hint.notice && <p className="field-hint">{hint.notice}</p>}
              </>
            )}
            {hint.text && (
              <button
                className="text-button"
                onClick={() =>
                  void call('copy', { text: hint.text }).catch((e) => setError(String(e)))
                }
              >
                <Copy size={14} />
                复制提示
              </button>
            )}
          </article>
        ))}
      </section>
      {!!session.hints.length && (
        <div className="hint-paging">
          <button className="text-button" onClick={() => pageHints(-1)}>
            上一屏
          </button>
          <button
            className="text-button"
            onClick={() => {
              readerMoved.current = true
              const panel = hintPanel.current,
                card = lastCard.current
              if (panel && card)
                panel.scrollTop +=
                  card.getBoundingClientRect().top - panel.getBoundingClientRect().top
            }}
          >
            最新提示
          </button>
          <button className="text-button" onClick={() => pageHints(1)}>
            下一屏
          </button>
        </div>
      )}
      <section className="panel manual-question">
        <label>
          补充面试问题
          <textarea
            aria-label="补充面试问题"
            disabled={submitting}
            placeholder="漏听了？粘贴或补充对方的问题…"
            rows={3}
            maxLength={12000}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <button
          className="secondary-button"
          disabled={!text.trim() || submitting}
          onClick={() => void submit().catch((e) => setError(String(e)))}
        >
          加入转录{options.automatic ? '并生成提示' : ''}
        </button>
      </section>
      <div className="interview-actions">
        <button
          className="text-button"
          disabled={snapshot.status !== 'stopped'}
          onClick={() => {
            if (window.confirm('保存当前面试，并开始新的一段？')) void command('new')
          }}
        >
          <Plus size={16} />
          新建面试
        </button>
        <button
          className="text-button"
          disabled={!session.utterances.length && !session.hints.length}
          onClick={() => void exportRecord()}
        >
          <Download size={16} />
          保存 Markdown
        </button>
        <button className="text-button" onClick={() => setShowHistory(!showHistory)}>
          面试记录（{snapshot.archives.length}）
        </button>
      </div>
      {showHistory && (
        <section className="panel" aria-label="面试记录">
          {snapshot.archives.length === 0 ? (
            <p>新建面试后，上一段保存在这里。</p>
          ) : (
            snapshot.archives.map((item) => (
              <div className="interview-history" key={item.id}>
                <button
                  disabled={snapshot.status !== 'stopped'}
                  onClick={() => void command('load', { id: item.id })}
                >
                  {new Date(item.created).toLocaleString('zh-CN')}
                  <small>
                    {(item.utterances[0]?.text || item.hints[0]?.text || '截图回答').slice(0, 40)}
                  </small>
                </button>
                <button
                  className="text-button danger"
                  aria-label={`删除面试 ${item.id}`}
                  onClick={() => {
                    if (window.confirm('删除这段已保存的面试记录？'))
                      void command('delete', { id: item.id })
                  }}
                >
                  删除
                </button>
              </div>
            ))
          )}
        </section>
      )}
      <p className="field-hint">
        记录自动加密保存在本机，最多保留最近 16
        段。设置中的「交流使用的配置」也用于面试提示。开启自动提示后，识别内容和启用的面试资料会提交给该
        AI
        平台。悬浮窗的「截图」会直接将当前画面及启用的识屏资料发送给「识屏使用的配置」，请为它选择视觉模型。
      </p>
    </main>
  )
}
