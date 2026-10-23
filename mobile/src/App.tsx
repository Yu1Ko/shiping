import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ArrowUp,
  BookOpen,
  Check,
  ChevronRight,
  CircleHelp,
  Copy,
  FileText,
  History,
  ImagePlus,
  Layers,
  LoaderCircle,
  MessageCircle,
  Mic,
  Plus,
  ScanLine,
  Settings2,
  Share2,
  ShieldCheck,
  Sparkles,
  Square,
  Trash2,
  X
} from 'lucide-react'
import { call, isAndroid, subscribe } from './bridge'
import {
  buildMessages,
  initialState,
  newProfile,
  newSession,
  restoreState,
  scenes,
  sessionMarkdown,
  validateProfile,
  type Knowledge,
  type Message,
  type Mode,
  type Profile,
  type Session,
  type State
} from './model'
import { parseRequestHeaders } from '../../src/shared/request-headers'
import Markdown from './Markdown'
import ImageEditor from './ImageEditor'
import InterviewPage from './InterviewPage'
import AudioPanel from './AudioPanel'
import VoiceSettings from './VoiceSettings'
import type { InterviewSnapshot } from './interview'

type Page = Mode | 'knowledge' | 'settings' | 'interview'
type Running = { requestId: string; sessionId: string; assistantId: string }
const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error))

export default function App() {
  const [data, setData] = useState<State>(initialState)
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [page, setPage] = useState<Page>('screenshot')
  const [draft, setDraft] = useState(() => newSession('screenshot'))
  const [activeId, setActiveId] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [images, setImages] = useState<string[]>([])
  const [editingImage, setEditingImage] = useState<number | null>(null)
  const [running, setRunning] = useState<Running | null>(null)
  const activeRequest = useRef<Running | null>(null)
  const [notice, setNotice] = useState('')
  const [history, setHistory] = useState(false)
  const [search, setSearch] = useState('')
  const [capture, setCapture] = useState(false)
  const [capturePending, setCapturePending] = useState(false)
  const [liveTranscript, setLiveTranscript] = useState('')
  const [help, setHelp] = useState(false)
  const [knowledgeEdit, setKnowledgeEdit] = useState<Knowledge | null>(null)
  const [profileEdit, setProfileEdit] = useState('')
  const [models, setModels] = useState<string[]>([])
  const [testing, setTesting] = useState(false)
  const [saveError, setSaveError] = useState('')
  const scroll = useRef<HTMLDivElement>(null)
  const followScroll = useRef(true)
  const nativeBack = useRef(() => {})
  const session = data.sessions.find((item) => item.id === activeId) ?? draft
  const inChat = page === 'screenshot' || page === 'conversation'
  const mode: Mode = page === 'conversation' ? 'conversation' : 'screenshot'
  const currentProfile =
    data.settings.profiles.find(
      (profile) =>
        profile.id ===
        (mode === 'screenshot'
          ? data.settings.screenshotProfile
          : data.settings.conversationProfile)
    ) ?? data.settings.profiles[0]
  const edited =
    data.settings.profiles.find((profile) => profile.id === profileEdit) ??
    data.settings.profiles[0]
  const sceneKey = mode === 'screenshot' ? 'scene' : 'conversationScene'
  const latest = useRef({ data, session, edited })
  latest.current = { data, session, edited }

  const updateMessage = useCallback((request: Running, change: (message: Message) => Message) => {
    setData((previous) => ({
      ...previous,
      sessions: previous.sessions.map((item) =>
        item.id !== request.sessionId
          ? item
          : {
              ...item,
              messages: item.messages.map((message) =>
                message.id === request.assistantId ? change(message) : message
              )
            }
      )
    }))
  }, [])

  async function load() {
    setLoadError('')
    try {
      const result = await call<{ state: State | null }>('load')
      setData(restoreState(result.state))
      setLoaded(true)
      await call('ready')
      setCapture(await call<boolean>('captureStatus'))
    } catch (error) {
      setLoadError(errorText(error))
    }
  }

  useEffect(() => {
    void load()
  }, [])
  useEffect(
    () =>
      subscribe((event) => {
        if (event.event === 'delta') {
          const request = activeRequest.current
          if (!request || event.requestId !== request.requestId) return
          if (event.kind === 'notice') setNotice(String(event.text))
          else
            updateMessage(request, (message) =>
              event.kind === 'reasoning'
                ? { ...message, reasoning: (message.reasoning ?? '') + event.text }
                : { ...message, text: message.text + event.text }
            )
        } else if (event.event === 'images') {
          if (latest.current.session.mode !== 'screenshot') {
            setActiveId(null)
            setDraft(newSession('screenshot'))
            setText('')
          }
          setImages((previous) => [...previous, ...(event.images as string[])].slice(0, 4))
          setNotice('图片已就绪，确认内容后点发送；点缩略图可裁剪')
          setPage('screenshot')
        } else if (event.event === 'text')
          setText((previous) => [previous, String(event.text)].filter(Boolean).join('\n'))
        else if (event.event === 'document')
          setKnowledgeEdit({
            id: crypto.randomUUID(),
            title: '导入的资料',
            text: String(event.text),
            enabled: true
          })
        else if (event.event === 'notice') setNotice(String(event.text))
        else if (event.event === 'captureStatus') setCapture(Boolean(event.active))
        else if (event.event === 'interview')
          setLiveTranscript((event.state as InterviewSnapshot).transcript)
        else if (event.event === 'openInterview') setPage('interview')
        else if (event.event === 'back') nativeBack.current()
      }),
    [updateMessage]
  )

  useEffect(() => {
    if (!loaded || running) return
    const timer = setTimeout(() => {
      void call('save', { state: data })
        .then(() => setSaveError(''))
        .catch((error) => setSaveError(errorText(error)))
    }, 500)
    return () => clearTimeout(timer)
  }, [data, loaded, running])
  useEffect(() => {
    if (!loaded || !running) return
    // Save edits independently from token updates; a long stream must not block settings.
    const timer = setTimeout(() => {
      void call('save', { state: latest.current.data })
        .then(() => setSaveError(''))
        .catch((error) => setSaveError(errorText(error)))
    }, 500)
    return () => clearTimeout(timer)
  }, [data.settings, data.knowledge, loaded, running])
  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => setNotice(''), 6500)
    return () => clearTimeout(timer)
  }, [notice])
  useEffect(() => {
    if (followScroll.current && scroll.current && running)
      scroll.current.scrollTop = scroll.current.scrollHeight
  }, [session.messages, running])
  useEffect(() => {
    const resume = () => {
      void call<boolean>('captureStatus')
        .then(setCapture)
        .catch(() => {})
    }
    window.addEventListener('focus', resume)
    return () => window.removeEventListener('focus', resume)
  }, [])
  useEffect(() => {
    nativeBack.current = () => {
      if (editingImage !== null) setEditingImage(null)
      else if (knowledgeEdit) setKnowledgeEdit(null)
      else if (help) setHelp(false)
      else if (history) setHistory(false)
      else if (!inChat) setPage('screenshot')
      else if (activeId) {
        setActiveId(null)
        setDraft(newSession(mode))
      } else void call('minimize').catch(() => setNotice('使用系统主页手势可回到桌面'))
    }
  }, [editingImage, knowledgeEdit, help, history, inChat, activeId, mode])
  const modalOpen = editingImage !== null || !!knowledgeEdit || help || history
  useEffect(() => {
    if (!modalOpen) return
    const previous = document.activeElement as HTMLElement | null
    const dialogs = document.querySelectorAll<HTMLElement>('[role="dialog"]')
    const dialog = dialogs[dialogs.length - 1]
    if (!dialog) return
    const focusable = () =>
      Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input, textarea, select, [tabindex="0"]'
        )
      )
    focusable()[0]?.focus()
    const trap = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        nativeBack.current()
        return
      }
      if (event.key !== 'Tab') return
      const items = focusable(),
        first = items[0],
        last = items[items.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last?.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first?.focus()
      }
    }
    document.addEventListener('keydown', trap)
    return () => {
      document.removeEventListener('keydown', trap)
      previous?.focus()
    }
  }, [modalOpen, editingImage, help, history])

  function navigate(next: Page) {
    if ((next === 'screenshot' || next === 'conversation') && session.mode !== next) {
      setActiveId(null)
      setDraft(newSession(next))
      setImages([])
      setText('')
    }
    setPage(next)
  }

  async function action(method: string, params: Record<string, unknown> = {}) {
    try {
      return await call(method, params)
    } catch (error) {
      setNotice(errorText(error))
      return undefined
    }
  }

  function stop() {
    const request = activeRequest.current
    if (!request) return
    activeRequest.current = null
    updateMessage(request, (message) => ({ ...message, status: 'stopped' }))
    setRunning(null)
    void action('cancel', { requestId: request.requestId })
  }

  async function send(retry = false) {
    if (activeRequest.current) return
    if (!retry && !text.trim() && !images.length && !(mode === 'screenshot' && liveTranscript))
      return
    const validation = validateProfile(currentProfile)
    if (validation) {
      setNotice(validation)
      setProfileEdit(currentProfile.id)
      setPage('settings')
      return
    }
    if (
      data.knowledge
        .filter(
          (doc) =>
            doc.enabled &&
            (mode === 'screenshot' ? doc.forScreenshot !== false : doc.forInterview !== false)
        )
        .reduce((sum, doc) => sum + doc.text.length, 0) > 30000
    ) {
      setNotice('已启用资料超过 3 万字，请精简后发送')
      return
    }
    const assistant: Message = {
      id: crypto.randomUUID(),
      role: 'assistant',
      text: '',
      status: 'streaming'
    }
    const previous = retry ? session.messages.slice(0, -1) : session.messages
    const messages: Message[] = retry
      ? previous
      : [
          ...previous,
          {
            id: crypto.randomUUID(),
            role: 'user',
            text:
              [
                text.trim(),
                mode === 'screenshot' && liveTranscript ? '【实时语音转录】\n' + liveTranscript : ''
              ]
                .filter(Boolean)
                .join('\n\n') || '请理解图片中的内容并回答。',
            images: images.length ? images : undefined
          }
        ]
    const next: Session = {
      ...session,
      mode,
      title: messages.find((message) => message.role === 'user')?.text.slice(0, 28) || '图片问答',
      messages: [...messages, assistant]
    }
    const snapshot = {
      ...data,
      sessions: [next, ...data.sessions.filter((item) => item.id !== next.id)].slice(0, 16)
    }
    const request: Running = {
      requestId: crypto.randomUUID(),
      sessionId: next.id,
      assistantId: assistant.id
    }
    activeRequest.current = request
    setRunning(request)
    setActiveId(next.id)
    setData(snapshot)
    setText('')
    setImages([])
    followScroll.current = true
    try {
      // Persist credentials and the pending turn before the native request can start.
      await call('save', { state: snapshot })
      if (!retry && mode === 'screenshot' && liveTranscript)
        await call('interview', { action: 'consumeTranscript', expected: liveTranscript })
      if (activeRequest.current?.requestId !== request.requestId) return
      await call(
        'chat',
        {
          profileId: currentProfile.id,
          messages: buildMessages({ ...next, messages }, data.settings, data.knowledge)
        },
        request.requestId
      )
      if (activeRequest.current?.requestId === request.requestId)
        updateMessage(request, (message) => ({ ...message, status: 'done' }))
    } catch (error) {
      if (activeRequest.current?.requestId === request.requestId)
        updateMessage(request, (message) => ({
          ...message,
          status: 'error',
          error: errorText(error)
        }))
    } finally {
      if (activeRequest.current?.requestId === request.requestId) {
        activeRequest.current = null
        setRunning(null)
      }
    }
  }

  async function beginCapture(overlay: boolean) {
    setCapturePending(true)
    try {
      await call('capture', { overlay })
      setCapture(true)
    } catch (error) {
      setNotice(errorText(error))
    } finally {
      setCapturePending(false)
    }
  }

  function updateProfile(change: Partial<Profile>) {
    setModels([])
    setData((previous) => ({
      ...previous,
      settings: {
        ...previous.settings,
        profiles: previous.settings.profiles.map((profile) =>
          profile.id === edited.id ? { ...profile, ...change } : profile
        )
      }
    }))
  }

  async function testModels() {
    const error = validateProfile(edited)
    if (error) {
      setNotice(error)
      return
    }
    if (parseRequestHeaders(edited.headers).invalidLines.length) {
      setNotice('自定义请求头存在无效行，请先修正')
      return
    }
    setTesting(true)
    const fingerprint = JSON.stringify(edited)
    try {
      await call('save', { state: data })
      const result = await call<string[]>('models', { profileId: edited.id })
      if (JSON.stringify(latest.current.edited) === fingerprint) {
        setModels(result)
        setNotice(`连接成功，发现 ${result.length} 个模型`)
      }
    } catch (error) {
      setNotice(errorText(error))
    } finally {
      setTesting(false)
    }
  }

  if (!loaded)
    return (
      <div className="loading-screen">
        <ScanLine size={40} />
        <h1>识屏</h1>
        {loadError ? (
          <>
            <p role="alert">{loadError}</p>
            <button onClick={() => void load()}>重新读取</button>
          </>
        ) : (
          <p>正在打开你的随身助手…</p>
        )}
      </div>
    )

  return (
    <div
      className="app"
      style={{ '--answer-size': `${data.settings.fontSize}px` } as React.CSSProperties}
    >
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">
            <ScanLine size={24} />
          </span>
          <div>
            <strong>
              识屏<span className="brand-dot">.</span>
            </strong>
            <span className="brand-caption">随身 AI 助手</span>
          </div>
        </div>
        <div className="top-actions">
          <button className="icon-button" aria-label="使用帮助" onClick={() => setHelp(true)}>
            <CircleHelp size={20} />
          </button>
          <button className="icon-button" aria-label="历史记录" onClick={() => setHistory(true)}>
            <History size={21} />
          </button>
        </div>
      </header>
      {!isAndroid && (
        <div className="preview-banner">浏览器界面预览 · 原生功能请使用 Android App</div>
      )}
      {saveError && (
        <div className="error-banner" role="alert">
          保存失败：{saveError}
        </div>
      )}
      {page === 'interview' ? (
        <InterviewPage data={data} change={setData} />
      ) : inChat ? (
        <>
          {mode === 'screenshot' && (
            <details className="screen-audio">
              <summary>连续语音{liveTranscript ? ' · 有待发送转录' : ' · 为截图补充问题'}</summary>
              <AudioPanel data={data} purpose="screenshot" change={setData} transcript />
            </details>
          )}
          <div className="chat-options">
            <label className="scene-select">
              <Layers size={15} />
              <select
                aria-label="回答场景"
                value={data.settings[sceneKey]}
                onChange={(event) =>
                  setData({
                    ...data,
                    settings: { ...data.settings, [sceneKey]: event.target.value }
                  })
                }
              >
                {scenes
                  .filter((scene) => scene.mode === mode || scene.mode === 'all')
                  .map((scene) => (
                    <option key={scene.id} value={scene.id}>
                      {scene.name}
                    </option>
                  ))}
              </select>
            </label>
            <button
              className="model-chip"
              onClick={() => {
                setProfileEdit(currentProfile.id)
                setPage('settings')
              }}
            >
              <span
                className={validateProfile(currentProfile) ? 'status-dot pending' : 'status-dot'}
              />
              {currentProfile.model || '设置 AI'}
              <ChevronRight size={13} />
            </button>
          </div>
          <main
            className="chat-scroll"
            ref={scroll}
            onScroll={(event) => {
              const el = event.currentTarget
              followScroll.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100
            }}
          >
            {!session.messages.length ? (
              <div className="welcome">
                <div className="eyebrow">
                  <span /> {mode === 'screenshot' ? '看见问题 · 即刻理解' : '认真倾听 · 从容表达'}
                </div>
                <h1>
                  {mode === 'screenshot' ? (
                    <>
                      把屏幕，
                      <br />
                      变成<span>答案。</span>
                    </>
                  ) : (
                    <>
                      把问题，
                      <br />
                      聊得<span>更明白。</span>
                    </>
                  )}
                </h1>
                <p className="welcome-description">
                  {mode === 'screenshot'
                    ? '代码、图表、外文与灵感。截一下，问一问。'
                    : '说出或输入问题，获得简洁、清晰的交流提示。'}
                </p>
                {mode === 'screenshot' ? (
                  <>
                    <button
                      className="capture-card"
                      disabled={capturePending}
                      onClick={() => void beginCapture(true)}
                    >
                      <div className="capture-illustration">
                        <ScanLine size={46} strokeWidth={1.4} />
                        <span className="scan-spark">
                          <Sparkles size={17} />
                        </span>
                      </div>
                      <div>
                        <strong>{capturePending ? '正在等待授权…' : '截取屏幕'}</strong>
                        <span>开启悬浮按钮，随时捕捉问题</span>
                      </div>
                      <span className="round-arrow">
                        <ArrowUp size={20} />
                      </span>
                    </button>
                    <div className="quick-grid">
                      <button onClick={() => void action('pickImages')}>
                        <ImagePlus size={22} />
                        <strong>从相册导入</strong>
                        <span>选择截图或图片</span>
                      </button>
                      <button disabled={capturePending} onClick={() => void beginCapture(false)}>
                        <ScanLine size={22} />
                        <strong>单次截屏</strong>
                        <span>5 秒倒计时，无需悬浮窗</span>
                      </button>
                    </div>
                  </>
                ) : (
                  <button className="capture-card voice-card" onClick={() => void action('speech')}>
                    <span className="voice-bars">
                      <i />
                      <i />
                      <i />
                      <i />
                      <i />
                    </span>
                    <div>
                      <strong>说出你的问题</strong>
                      <span>系统语音识别 · 发送前可编辑</span>
                    </div>
                    <Mic size={25} />
                  </button>
                )}
                <div className="ideas">
                  <span className="section-label">试试这样问</span>
                  <div>
                    {(mode === 'screenshot'
                      ? ['解释这段代码', '总结图片重点', '翻译成中文']
                      : ['帮我理清回答思路', '给我三个简洁要点', '如何自然地用英文表达？']
                    ).map((prompt) => (
                      <button key={prompt} onClick={() => setText(prompt)}>
                        {prompt}
                        <ChevronRight size={13} />
                      </button>
                    ))}
                  </div>
                </div>
                <div className="privacy-note">
                  <ShieldCheck size={14} />
                  <span>图片和资料仅在你发送时交给所选 AI 平台</span>
                </div>
              </div>
            ) : (
              <div className="messages">
                <div className="conversation-heading">
                  <span>{session.title}</span>
                  <button
                    className="text-button"
                    onClick={() => {
                      stop()
                      setDraft(newSession(mode))
                      setActiveId(null)
                      setImages([])
                      setText('')
                    }}
                  >
                    <Plus size={16} />
                    新对话
                  </button>
                </div>
                {session.messages.map((message, index) => (
                  <article key={message.id} className={`message ${message.role}`}>
                    {message.role === 'user' ? (
                      <>
                        <div className="message-images">
                          {message.images?.map((image, i) => (
                            <a key={i} href={image} onClick={(event) => event.preventDefault()}>
                              <img src={image} alt={`提问图片 ${i + 1}`} />
                            </a>
                          ))}
                        </div>
                        <p>{message.text}</p>
                      </>
                    ) : (
                      <>
                        <div className="answer-label">
                          <span>
                            <Sparkles size={15} />
                          </span>
                          识屏{' '}
                          {message.status === 'streaming' && (
                            <small>
                              <LoaderCircle size={12} className="spin" /> 正在思考与作答
                            </small>
                          )}
                        </div>
                        {message.reasoning && (
                          <details className="reasoning">
                            <summary>思考过程</summary>
                            <Markdown text={message.reasoning} />
                          </details>
                        )}
                        {message.text ? (
                          <Markdown text={message.text} />
                        ) : message.status === 'streaming' ? (
                          <div className="typing">
                            <i />
                            <i />
                            <i />
                          </div>
                        ) : null}
                        {message.error && (
                          <div className="message-error" role="alert">
                            {message.error}
                          </div>
                        )}
                        {message.status === 'stopped' && (
                          <p className="muted small">已停止生成 · 可继续追问</p>
                        )}
                        {message.status !== 'streaming' && (
                          <div className="answer-actions">
                            <button
                              aria-label="复制回答"
                              onClick={() =>
                                void action('copy', { text: message.text }).then(
                                  (result) => result && setNotice('回答已复制')
                                )
                              }
                            >
                              <Copy size={15} />
                              复制
                            </button>
                            <button
                              aria-label="分享对话"
                              onClick={() =>
                                void action('share', { text: sessionMarkdown(session) })
                              }
                            >
                              <Share2 size={15} />
                              导出
                            </button>
                            {index === session.messages.length - 1 && (
                              <button disabled={!!running} onClick={() => void send(true)}>
                                重新回答
                              </button>
                            )}
                          </div>
                        )}
                      </>
                    )}
                  </article>
                ))}
                <p className="answer-disclaimer">AI 回答仅供参考，请核对重要信息</p>
              </div>
            )}
          </main>
          {capture && (
            <div className="capture-status">
              <span className="status-dot" />
              截屏会话开启中
              <button onClick={() => void action('stopCapture').then(() => setCapture(false))}>
                停止截屏
              </button>
            </div>
          )}
          <section className="composer-area" aria-label="提问输入区">
            {images.length > 0 && (
              <div className="attachments">
                {images.map((image, index) => (
                  <div key={index}>
                    <button
                      className="attachment-image"
                      onClick={() => setEditingImage(index)}
                      aria-label={`裁剪图片 ${index + 1}`}
                    >
                      <img src={image} alt={`待发送图片 ${index + 1}`} />
                      <span>裁剪</span>
                    </button>
                    <button
                      className="remove-image"
                      aria-label={`移除图片 ${index + 1}`}
                      onClick={() => setImages(images.filter((_, i) => i !== index))}
                    >
                      <X size={12} />
                    </button>
                  </div>
                ))}
                <small>
                  {images.length}/4 张<br />
                  确认后发送
                </small>
              </div>
            )}
            <div className="composer">
              <textarea
                aria-label="输入问题"
                rows={1}
                placeholder={
                  images.length
                    ? '想了解图片中的什么？'
                    : mode === 'conversation'
                      ? '输入问题，或者点麦克风…'
                      : '问点什么，或者添加一张截图…'
                }
                value={text}
                maxLength={10000}
                onChange={(event) => setText(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                    event.preventDefault()
                    void send()
                  }
                }}
              />
              <div className="composer-tools">
                <div>
                  <button
                    className="icon-button"
                    aria-label="添加图片"
                    disabled={images.length >= 4}
                    onClick={() => void action('pickImages')}
                  >
                    <ImagePlus size={21} />
                  </button>
                  <button
                    className="icon-button"
                    aria-label="语音输入"
                    onClick={() => void action('speech')}
                  >
                    <Mic size={21} />
                  </button>
                  <span>{images.length ? '图片已就绪' : '支持多轮追问'}</span>
                </div>
                {running ? (
                  <button className="send-button stop" aria-label="停止生成" onClick={stop}>
                    <Square size={17} fill="currentColor" />
                  </button>
                ) : (
                  <button
                    className="send-button"
                    aria-label="发送问题"
                    disabled={
                      !text.trim() && !images.length && !(mode === 'screenshot' && liveTranscript)
                    }
                    onClick={() => void send()}
                  >
                    <ArrowUp size={21} />
                  </button>
                )}
              </div>
            </div>
          </section>
        </>
      ) : page === 'settings' ? (
        <main className="page-scroll settings-page">
          <div className="page-title">
            <span className="eyebrow">让助手更懂你</span>
            <h1>设置</h1>
            <p>连接自己的 AI，按你的习惯使用。</p>
          </div>
          <VoiceSettings data={data} change={setData} />
          <section className="panel">
            <div className="panel-title">
              <h2>AI 配置</h2>
              <button
                className="text-button"
                onClick={() => {
                  const profile = newProfile()
                  setData({
                    ...data,
                    settings: { ...data.settings, profiles: [...data.settings.profiles, profile] }
                  })
                  setProfileEdit(profile.id)
                }}
              >
                <Plus size={16} />
                新增
              </button>
            </div>
            <div className="profile-tabs">
              {data.settings.profiles.map((profile) => (
                <button
                  className={profile.id === edited.id ? 'selected' : ''}
                  key={profile.id}
                  onClick={() => {
                    setProfileEdit(profile.id)
                    setModels([])
                  }}
                >
                  {profile.name}
                </button>
              ))}
            </div>
            <label>
              配置名称
              <input
                value={edited.name}
                maxLength={30}
                onChange={(event) => updateProfile({ name: event.target.value })}
              />
            </label>
            <label>
              快捷填入平台
              <select
                aria-label="快捷填入平台"
                value=""
                onChange={(event) => {
                  const options: Record<string, Partial<Profile>> = {
                    openai: { baseURL: 'https://api.openai.com/v1', model: 'gpt-4.1-mini' },
                    siliconflow: {
                      baseURL: 'https://api.siliconflow.cn/v1',
                      model: 'Qwen/Qwen2.5-VL-72B-Instruct'
                    },
                    openrouter: {
                      baseURL: 'https://openrouter.ai/api/v1',
                      model: 'openai/gpt-4.1-mini'
                    },
                    deepseek: { baseURL: 'https://api.deepseek.com/v1', model: 'deepseek-chat' }
                  }
                  updateProfile(options[event.target.value])
                  if (event.target.value === 'deepseek')
                    setNotice('DeepSeek 此配置用于文字交流；图片请选支持视觉的模型')
                }}
              >
                <option value="">选择平台（也可手动填写）</option>
                <option value="openai">OpenAI</option>
                <option value="siliconflow">硅基流动</option>
                <option value="openrouter">OpenRouter</option>
                <option value="deepseek">DeepSeek · 文字交流</option>
              </select>
            </label>
            <label>
              API Base URL
              <input
                aria-label="API Base URL"
                type="url"
                inputMode="url"
                autoCapitalize="off"
                value={edited.baseURL}
                onChange={(event) => updateProfile({ baseURL: event.target.value })}
                placeholder="https://api.example.com/v1"
              />
            </label>
            <label>
              API Key
              <input
                aria-label="API Key"
                type="password"
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                value={edited.apiKey}
                onChange={(event) => updateProfile({ apiKey: event.target.value })}
                placeholder="填入你自己的密钥"
              />
            </label>
            <label>
              模型名称
              <input
                aria-label="模型名称"
                autoCapitalize="off"
                spellCheck={false}
                value={edited.model}
                onChange={(event) => updateProfile({ model: event.target.value })}
                placeholder="请选择支持图片输入的模型"
              />
            </label>
            <p className="field-hint">识屏模式需要视觉模型。模型名称以服务商提供的列表为准。</p>
            <button
              className="secondary-button full"
              disabled={testing}
              onClick={() => void testModels()}
            >
              {testing ? <LoaderCircle className="spin" size={16} /> : <Check size={16} />}
              {testing ? '正在连接…' : '测试连接并获取模型'}
            </button>
            {models.length > 0 && (
              <label>
                可用模型
                <select
                  aria-label="可用模型"
                  value={edited.model}
                  onChange={(event) => updateProfile({ model: event.target.value })}
                >
                  <option value={edited.model}>{edited.model}</option>
                  {models
                    .filter((model) => model !== edited.model)
                    .map((model) => (
                      <option key={model}>{model}</option>
                    ))}
                </select>
              </label>
            )}
            <details className="advanced">
              <summary>高级选项</summary>
              <label className="toggle-row">
                尝试关闭模型思考
                <input
                  type="checkbox"
                  checked={edited.disableThinking}
                  onChange={(event) => updateProfile({ disableThinking: event.target.checked })}
                />
              </label>
              <label>
                自定义请求头
                <textarea
                  rows={3}
                  value={edited.headers}
                  onChange={(event) => updateProfile({ headers: event.target.value })}
                  placeholder="每行一个 Name: Value"
                />
              </label>
              {parseRequestHeaders(edited.headers).invalidLines.length > 0 && (
                <p className="danger">请求头格式有误，请检查</p>
              )}
              <p className="field-hint">
                不支持的思考参数会自动重试兼容。网络遵循手机系统网络或 VPN。
              </p>
            </details>
            {data.settings.profiles.length > 1 && (
              <button
                className="text-button danger"
                onClick={() => {
                  if (!window.confirm(`删除配置「${edited.name}」？`)) return
                  const profiles = data.settings.profiles.filter(
                    (profile) => profile.id !== edited.id
                  )
                  setData({
                    ...data,
                    settings: {
                      ...data.settings,
                      profiles,
                      screenshotProfile:
                        data.settings.screenshotProfile === edited.id
                          ? profiles[0].id
                          : data.settings.screenshotProfile,
                      conversationProfile:
                        data.settings.conversationProfile === edited.id
                          ? profiles[0].id
                          : data.settings.conversationProfile
                    }
                  })
                }}
              >
                <Trash2 size={15} />
                删除此配置
              </button>
            )}
          </section>
          <section className="panel">
            <h2>每种模式，选合适的 AI</h2>
            {(['screenshotProfile', 'conversationProfile'] as const).map((key) => (
              <label key={key}>
                {key === 'screenshotProfile' ? '识屏使用的配置' : '交流使用的配置'}
                <select
                  value={data.settings[key]}
                  onChange={(event) =>
                    setData({ ...data, settings: { ...data.settings, [key]: event.target.value } })
                  }
                >
                  {data.settings.profiles.map((profile) => (
                    <option key={profile.id} value={profile.id}>
                      {profile.name} · {profile.model}
                    </option>
                  ))}
                </select>
              </label>
            ))}
            <label>
              自定义场景提示词
              <textarea
                rows={4}
                value={data.settings.customPrompt}
                maxLength={15000}
                onChange={(event) =>
                  setData({
                    ...data,
                    settings: { ...data.settings, customPrompt: event.target.value }
                  })
                }
              />
            </label>
            <p className="field-hint">在对话上方的场景菜单选择「自定义」后生效。</p>
          </section>
          <section className="panel">
            <h2>阅读与存储</h2>
            <label>
              回答字号 · {data.settings.fontSize}px
              <input
                aria-label="回答字号"
                type="range"
                min="14"
                max="22"
                value={data.settings.fontSize}
                onChange={(event) =>
                  setData({
                    ...data,
                    settings: { ...data.settings, fontSize: Number(event.target.value) }
                  })
                }
              />
            </label>
            <p className="field-hint">
              设置自动保存。Android 上的密钥、资料与最近 16 段对话使用 Keystore
              加密保存，不参与系统云备份。
            </p>
            <p className="field-hint">
              识屏 Android 1.1.1 · 基于 Shiping
              <br />
              CC BY-NC 4.0 · 仅限非商业使用
            </p>
          </section>
        </main>
      ) : (
        <main className="page-scroll knowledge-page">
          <div className="page-title">
            <span className="eyebrow">带着你的背景来回答</span>
            <h1>资料库</h1>
            <p>放入项目笔记、常用知识，让回答更贴近你。</p>
          </div>
          <div className="knowledge-actions">
            <button
              className="primary-button"
              onClick={() =>
                setKnowledgeEdit({ id: crypto.randomUUID(), title: '', text: '', enabled: true })
              }
            >
              <Plus size={18} />
              新建资料
            </button>
            <button className="secondary-button" onClick={() => void action('pickDocument')}>
              <FileText size={17} />
              导入资料
            </button>
          </div>
          <div className="info-card">
            <ShieldCheck size={21} />
            <p>
              启用的资料会随每次提问发送给所选 AI 平台。建议总计不超过 3 万字。支持 UTF-8
              PDF、Word（DOCX）、TXT、Markdown 或直接粘贴。文件在本机提取文字。
            </p>
          </div>
          {!data.knowledge.length && (
            <div className="empty-state">
              <BookOpen size={40} strokeWidth={1.2} />
              <h2>你的专属参考书</h2>
              <p>添加第一份资料，从一段笔记开始。</p>
            </div>
          )}
          {data.knowledge.map((doc) => (
            <section className="panel document-card" key={doc.id}>
              <div className="panel-title">
                <button className="doc-name" onClick={() => setKnowledgeEdit({ ...doc })}>
                  <FileText size={20} />
                  <strong>{doc.title}</strong>
                </button>
                <input
                  aria-label={`启用 ${doc.title}`}
                  type="checkbox"
                  checked={doc.enabled}
                  onChange={(event) =>
                    setData({
                      ...data,
                      knowledge: data.knowledge.map((item) =>
                        item.id === doc.id ? { ...item, enabled: event.target.checked } : item
                      )
                    })
                  }
                />
              </div>
              <p>{doc.text.slice(0, 110)}</p>
              <footer>
                <span>
                  {doc.text.length.toLocaleString()} 字 ·{' '}
                  {doc.enabled ? '已加入回答参考' : '未启用'}
                </span>
                <button
                  className="icon-button danger"
                  aria-label={`删除 ${doc.title}`}
                  onClick={() => {
                    if (window.confirm(`删除资料「${doc.title}」？`))
                      setData({
                        ...data,
                        knowledge: data.knowledge.filter((item) => item.id !== doc.id)
                      })
                  }}
                >
                  <Trash2 size={16} />
                </button>
              </footer>
            </section>
          ))}
        </main>
      )}
      <nav className="bottom-nav" aria-label="主导航">
        {(
          [
            { id: 'screenshot', name: '识屏', Icon: ScanLine },
            { id: 'conversation', name: '交流', Icon: MessageCircle },
            { id: 'interview', name: '面试', Icon: Mic },
            { id: 'knowledge', name: '资料', Icon: BookOpen },
            { id: 'settings', name: '设置', Icon: Settings2 }
          ] as const
        ).map(({ id, name, Icon }) => (
          <button
            key={id}
            aria-current={page === id ? 'page' : undefined}
            className={page === id ? 'active' : ''}
            onClick={() => navigate(id)}
          >
            <span>
              <Icon size={22} strokeWidth={page === id ? 2.2 : 1.7} />
            </span>
            {name}
          </button>
        ))}
      </nav>
      {notice && (
        <div className="toast" role="status">
          <span>{notice}</span>
          <button aria-label="关闭提示" onClick={() => setNotice('')}>
            <X size={15} />
          </button>
        </div>
      )}
      {history && (
        <div className="modal-backdrop">
          <section className="sheet" role="dialog" aria-modal="true" aria-label="历史记录">
            <header>
              <h2>历史记录</h2>
              <button
                className="icon-button"
                aria-label="关闭历史记录"
                onClick={() => setHistory(false)}
              >
                <X />
              </button>
            </header>
            <input
              aria-label="搜索历史"
              placeholder="搜索对话标题或内容"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <div className="sheet-scroll">
              {data.sessions
                .filter((item) =>
                  [item.title, ...item.messages.map((message) => message.text)]
                    .join(' ')
                    .includes(search)
                )
                .map((item) => (
                  <div className="history-row" key={item.id}>
                    <button
                      onClick={() => {
                        setActiveId(item.id)
                        setPage(item.mode)
                        setHistory(false)
                        setImages([])
                        setText('')
                        followScroll.current = false
                      }}
                    >
                      <span>
                        {item.mode === 'screenshot' ? (
                          <ScanLine size={19} />
                        ) : (
                          <MessageCircle size={19} />
                        )}
                      </span>
                      <div>
                        <strong>{item.title}</strong>
                        <small>
                          {new Date(item.created).toLocaleString('zh-CN', {
                            month: 'numeric',
                            day: 'numeric',
                            hour: '2-digit',
                            minute: '2-digit'
                          })}{' '}
                          · {item.messages.length / 2} 轮对话
                        </small>
                      </div>
                    </button>
                    <button
                      className="icon-button danger"
                      aria-label={`删除对话 ${item.title}`}
                      onClick={() => {
                        if (!window.confirm('删除这段对话及其图片？')) return
                        if (running?.sessionId === item.id) stop()
                        setData((previous) => ({
                          ...previous,
                          sessions: previous.sessions.filter((value) => value.id !== item.id)
                        }))
                        if (activeId === item.id) {
                          setActiveId(null)
                          setDraft(newSession(mode))
                        }
                      }}
                    >
                      <Trash2 size={17} />
                    </button>
                  </div>
                ))}
              {!data.sessions.length && (
                <div className="empty-state">
                  <History size={36} />
                  <p>完成第一次提问后，对话会保存在这里。</p>
                </div>
              )}
            </div>
          </section>
        </div>
      )}
      {help && (
        <div className="modal-backdrop">
          <section className="sheet" role="dialog" aria-modal="true" aria-label="使用帮助">
            <header>
              <h2>把识屏装进口袋</h2>
              <button className="icon-button" aria-label="关闭帮助" onClick={() => setHelp(false)}>
                <X />
              </button>
            </header>
            <div className="sheet-scroll help-content">
              <h3>01 · 先连接 AI</h3>
              <p>
                在设置里填入 HTTPS Base URL、API Key
                和模型。识屏需要支持图片输入的模型；文字交流可单独选择配置。
              </p>
              <h3>02 · 截屏，或分享图片</h3>
              <p>
                开启「截取屏幕」并允许悬浮窗与系统截屏授权。切换到目标应用后，点悬浮按钮截图，再回来确认发送。按钮可拖动，×
                或通知里的「停止」可结束共享。
              </p>
              <p>
                不想开悬浮窗？用「单次截屏」，授权后有 5 秒切换画面。也可在相册中点分享 →
                识屏。最多添加 4 张图片，点缩略图可裁剪。
              </p>
              <h3>03 · 接着追问</h3>
              <p>
                回答会逐字显示，支持代码、公式和表格。继续输入即可追问；追加图片会保留最近 10
                轮上下文。停止后已生成的内容仍会保留。
              </p>
              <h3>安卓上的能力边界</h3>
              <p>
                受保护的应用画面可能呈黑屏；安卓端不承诺共享隐身。对话模式在「面试」页，可连续识别、自动或手动给出提示。腾讯会议、微信小程序的通话音轨可能不允许捕获，请先使用「检测音频」。检测仅在本机运行；连续识别需要另行配置百炼
                ASR。麦克风是手动选择的音源，通话期间也可能受限。
              </p>
              <h3>资料与隐私</h3>
              <p>
                图片最长边压缩到 1600 像素。最近 16
                段历史和设置在设备内加密保存；只在你主动发送时把所需内容提交到配置的 AI
                平台。卸载应用会移除本地数据，可用「导出」先分享文字记录。
              </p>
            </div>
          </section>
        </div>
      )}
      {knowledgeEdit && (
        <div className="modal-backdrop">
          <section className="sheet" role="dialog" aria-modal="true" aria-label="编辑资料">
            <header>
              <h2>编辑资料</h2>
              <button
                className="icon-button"
                aria-label="关闭资料编辑"
                onClick={() => setKnowledgeEdit(null)}
              >
                <X />
              </button>
            </header>
            <div className="sheet-scroll">
              <label>
                资料名称
                <input
                  value={knowledgeEdit.title}
                  maxLength={60}
                  onChange={(event) =>
                    setKnowledgeEdit({ ...knowledgeEdit, title: event.target.value })
                  }
                  placeholder="例如：项目背景、常用术语"
                />
              </label>
              <label>
                内容
                <textarea
                  rows={12}
                  value={knowledgeEdit.text}
                  maxLength={30000}
                  onChange={(event) =>
                    setKnowledgeEdit({ ...knowledgeEdit, text: event.target.value })
                  }
                  placeholder="粘贴你希望 AI 参考的资料…"
                />
              </label>
              <p className="field-hint">{knowledgeEdit.text.length.toLocaleString()} / 30,000 字</p>
              <label className="check-row">
                <input
                  type="checkbox"
                  checked={knowledgeEdit.forInterview !== false}
                  onChange={(e) =>
                    setKnowledgeEdit({ ...knowledgeEdit, forInterview: e.target.checked })
                  }
                />
                用于面试与交流
              </label>
              <label className="check-row">
                <input
                  type="checkbox"
                  checked={knowledgeEdit.forScreenshot !== false}
                  onChange={(e) =>
                    setKnowledgeEdit({ ...knowledgeEdit, forScreenshot: e.target.checked })
                  }
                />
                用于识屏
              </label>
              <button
                className="primary-button full"
                disabled={!knowledgeEdit.title.trim() || !knowledgeEdit.text.trim()}
                onClick={() => {
                  setData({
                    ...data,
                    knowledge: [
                      ...data.knowledge.filter((item) => item.id !== knowledgeEdit.id),
                      { ...knowledgeEdit, title: knowledgeEdit.title.trim() }
                    ]
                  })
                  setKnowledgeEdit(null)
                  setNotice('资料已保存')
                }}
              >
                <Check size={18} />
                保存资料
              </button>
            </div>
          </section>
        </div>
      )}
      {editingImage !== null && images[editingImage] && (
        <ImageEditor
          image={images[editingImage]}
          close={() => setEditingImage(null)}
          apply={(image) => {
            setImages(images.map((value, index) => (index === editingImage ? image : value)))
            setEditingImage(null)
          }}
        />
      )}
    </div>
  )
}
