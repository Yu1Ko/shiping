import { useRef, useState } from 'react'
import { Check, Crop, X } from 'lucide-react'

export default function ImageEditor({
  image,
  close,
  apply
}: {
  image: string
  close(): void
  apply(value: string): void
}) {
  const element = useRef<HTMLImageElement>(null)
  const [selection, setSelection] = useState<{
    x: number
    y: number
    endX: number
    endY: number
  } | null>(null)
  const dragging = useRef(false)
  function point(event: React.PointerEvent) {
    const bounds = element.current!.getBoundingClientRect()
    return {
      x: Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width)),
      y: Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height))
    }
  }
  function crop() {
    const img = element.current
    if (!selection || !img) return
    const x = Math.min(selection.x, selection.endX),
      y = Math.min(selection.y, selection.endY)
    const width = Math.abs(selection.endX - selection.x),
      height = Math.abs(selection.endY - selection.y)
    if (width < 0.03 || height < 0.03) return
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(width * img.naturalWidth))
    canvas.height = Math.max(1, Math.round(height * img.naturalHeight))
    canvas
      .getContext('2d')!
      .drawImage(
        img,
        x * img.naturalWidth,
        y * img.naturalHeight,
        width * img.naturalWidth,
        height * img.naturalHeight,
        0,
        0,
        canvas.width,
        canvas.height
      )
    apply(canvas.toDataURL('image/jpeg', 0.9))
  }
  return (
    <div className="image-editor" role="dialog" aria-modal="true" aria-label="裁剪图片">
      <header>
        <button className="icon-button" onClick={close} aria-label="取消裁剪">
          <X />
        </button>
        <strong>
          <Crop size={18} /> 裁剪图片
        </strong>
        <button
          className="icon-button"
          disabled={
            !selection ||
            Math.abs(selection.endX - selection.x) < 0.03 ||
            Math.abs(selection.endY - selection.y) < 0.03
          }
          onClick={crop}
          aria-label="确认裁剪"
        >
          <Check />
        </button>
      </header>
      <p>在图片上拖动，框选需要识别的内容</p>
      <div className="crop-stage">
        <div
          className="crop-image"
          onPointerDown={(event) => {
            dragging.current = true
            event.currentTarget.setPointerCapture(event.pointerId)
            const p = point(event)
            setSelection({ ...p, endX: p.x, endY: p.y })
          }}
          onPointerMove={(event) => {
            if (dragging.current) {
              const p = point(event)
              setSelection((old) => old && { ...old, endX: p.x, endY: p.y })
            }
          }}
          onPointerUp={() => {
            dragging.current = false
          }}
          onPointerCancel={() => {
            dragging.current = false
          }}
        >
          <img ref={element} src={image} alt="待裁剪图片" draggable={false} />
          {selection && (
            <div
              className="crop-selection"
              style={{
                left: `${Math.min(selection.x, selection.endX) * 100}%`,
                top: `${Math.min(selection.y, selection.endY) * 100}%`,
                width: `${Math.abs(selection.endX - selection.x) * 100}%`,
                height: `${Math.abs(selection.endY - selection.y) * 100}%`
              }}
            />
          )}
        </div>
      </div>
      <p className="muted">裁剪只影响本次上传的图片</p>
    </div>
  )
}
