import { memo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeHighlight from 'rehype-highlight'
import 'katex/dist/katex.min.css'

function Markdown({ text }: { text: string }) {
  const normalized = text
    .split(/(```[\s\S]*?(?:```|$)|`[^`\n]*`)/)
    .map((part, index) =>
      index % 2
        ? part
        : part
            .replace(/\\\[([\s\S]*?)\\\]/g, (_, tex) => `$$${tex}$$`)
            .replace(/\\\((.*?)\\\)/g, (_, tex) => `$${tex}$`)
    )
    .join('')
  return (
    <div className="markdown">
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[[rehypeKatex, { errorColor: 'inherit' }], rehypeHighlight]}
        components={{ img: () => <span>（外部图片已屏蔽）</span> }}
      >
        {normalized}
      </ReactMarkdown>
    </div>
  )
}
export default memo(Markdown)
