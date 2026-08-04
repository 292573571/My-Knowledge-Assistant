import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import MarkdownIt from 'markdown-it'

function escapeHtml(value) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value
      } catch {
        return escapeHtml(code)
      }
    }

    return escapeHtml(code)
  }
})

md.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index]
  const language = token.info.trim().split(/\s+/)[0]
  let code = escapeHtml(token.content)
  if (language && hljs.getLanguage(language)) {
    try { code = hljs.highlight(token.content, { language }).value } catch {}
  }
  const label = language ? language.toUpperCase() : 'CODE'
  const languageClass = language ? ` language-${escapeHtml(language)}` : ''
  return `<div class="code-block"><div class="code-block-header"><span>${escapeHtml(label)}</span><button type="button" class="code-copy-button">复制</button></div><pre><code class="hljs${languageClass}">${code}</code></pre></div>`
}

export function renderMarkdown(content) {
  return DOMPurify.sanitize(md.render(content || ''))
}
