// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

// 这些用例盯的是「模型输出不可信」这条前提：回答正文来自 LLM 和用户文档，
// 渲染前必须保证不会执行脚本、不会注入属性。
describe('renderMarkdown 安全性', () => {
  it('原始 HTML 被转义而不是执行', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)">')

    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img')
  })

  it('script 标签不会进入最终 DOM', () => {
    const html = renderMarkdown('<script>alert(1)</script>')

    expect(html.toLowerCase()).not.toContain('<script')
  })

  it('javascript: 协议不会变成可点击链接', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))')

    // markdown-it 会拒绝该协议，把原文当普通文本渲染；关键是不能出现 href="javascript:"
    expect(html.toLowerCase()).not.toContain('href="javascript:')
    expect(html).not.toContain('<a ')
  })

  it('wiki 链接标题里的引号不会逃出属性', () => {
    const html = renderMarkdown('[[标题" onclick="alert(1)]]', { wikiLinks: true })
    const doc = new DOMParser().parseFromString(html, 'text/html')
    const anchor = doc.querySelector('a.wiki-link')

    // 整串必须留在 data-wiki-title 这一个属性里，不能被拆成额外的 onclick
    expect(anchor).not.toBeNull()
    expect(anchor.getAttribute('data-wiki-title')).toBe('标题" onclick="alert(1)')
    expect(anchor.hasAttribute('onclick')).toBe(false)
    expect(doc.querySelectorAll('[onclick]')).toHaveLength(0)
  })
})

describe('renderMarkdown 渲染行为', () => {
  it('普通 Markdown 正常渲染成 HTML', () => {
    const html = renderMarkdown('**加粗** 和 `行内代码`')

    expect(html).toContain('<strong>加粗</strong>')
    expect(html).toContain('<code>行内代码</code>')
  })

  it('代码块带语言标签与复制按钮', () => {
    const html = renderMarkdown('```js\nconst a = 1\n```')

    expect(html).toContain('code-block')
    expect(html).toContain('JS')
    expect(html).toContain('code-copy-button')
  })

  it('代码块里的尖括号保持转义，不破坏页面结构', () => {
    const html = renderMarkdown('```html\n<div>hi</div>\n```')

    // 高亮器会把标签拆成多个 span，因此不能断言连续的 &lt;div&gt;；
    // 真正要保证的是页面上不存在一个真的 div 元素。
    expect(html).not.toContain('<div>hi</div>')
    const doc = new DOMParser().parseFromString(html, 'text/html')
    expect(doc.querySelector('pre code').textContent).toContain('<div>hi</div>')
    // 结构里只有代码块容器和它的头部两个 div；多出来就说明标签被当成 HTML 解析了
    expect(doc.querySelectorAll('div')).toHaveLength(2)
  })

  it('默认不启用 wiki 链接，避免聊天里出现无意义锚点', () => {
    const html = renderMarkdown('[[Kettle 手册]]')

    expect(html).not.toContain('wiki-link')
    expect(html).toContain('[[Kettle 手册]]')
  })

  it('启用 wikiLinks 后渲染成带 data-wiki-title 的锚点', () => {
    const html = renderMarkdown('[[Kettle 手册|手册]]', { wikiLinks: true })

    expect(html).toContain('class="wiki-link"')
    expect(html).toContain('data-wiki-title="Kettle 手册"')
    expect(html).toContain('>手册</a>')
  })

  it('空内容返回空串而不是抛异常', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
  })
})
