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

function highlight(code, lang) {
  if (lang && hljs.getLanguage(lang)) {
    try {
      return hljs.highlight(code, { language: lang }).value
    } catch {
      return escapeHtml(code)
    }
  }
  return escapeHtml(code)
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight
})

md.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index]
  const language = token.info.trim().split(/\s+/)[0]
  let code = highlight(token.content, language)
  const label = language ? language.toUpperCase() : 'CODE'
  const languageClass = language ? ` language-${escapeHtml(language)}` : ''
  return `<div class="code-block"><div class="code-block-header"><span>${escapeHtml(label)}</span><button type="button" class="code-copy-button">复制</button></div><pre><code class="hljs${languageClass}">${code}</code></pre></div>`
}

// 把 [[标题]] 与 [[标题|别名]] 渲染成可点击的 wiki 页面链接。
// 链接只带 data-wiki-title，点击行为由挂载点（WikiPages 预览区）统一处理，
// 因此该规则不影响聊天/学习记录等其他 markdown 渲染场景。
function wikiLinkPlugin(instance) {
  instance.inline.ruler.before('link', 'wiki_link', (state, silent) => {
    const start = state.pos
    const src = state.src
    if (src.charCodeAt(start) !== 0x5b /* [ */ || src.charCodeAt(start + 1) !== 0x5b) return false
    const end = src.indexOf(']]', start + 2)
    if (end === -1) return false
    const inner = src.slice(start + 2, end)
    if (inner.trim() === '' || inner.includes('[')) return false
    if (!silent) {
      const sep = inner.indexOf('|')
      const target = (sep === -1 ? inner : inner.slice(0, sep)).trim()
      const alias = (sep === -1 ? inner : inner.slice(sep + 1)).trim() || target
      const token = state.push('wiki_link', '', 0)
      token.meta = { target, alias }
    }
    state.pos = end + 2
    return true
  })
  instance.renderer.rules.wiki_link = (tokens, idx) => {
    const { target, alias } = tokens[idx].meta
    const title = instance.utils.escapeHtml(target)
    const text = instance.utils.escapeHtml(alias)
    return `<a class="wiki-link" data-wiki-title="${title}" href="#">${text}</a>`
  }
}

const mdWiki = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight
})
mdWiki.renderer.rules.fence = md.renderer.rules.fence
mdWiki.use(wikiLinkPlugin)

export function renderMarkdown(content, options = {}) {
  const instance = options.wikiLinks ? mdWiki : md
  return DOMPurify.sanitize(instance.render(content || ''))
}
