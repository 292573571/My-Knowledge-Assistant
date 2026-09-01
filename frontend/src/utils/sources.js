function fileName(source) {
  return source.fileName || source.file || source.title || source.name || '未知文件'
}

export function deduplicateDisplayedSources(sources = []) {
  const seen = new Set()

  return sources.filter((source) => {
    if (!source) return false

    const file = fileName(source)
    const page = source.pageNumber ?? source.page
    const chunk = source.chunkIndex ?? source.chunkId ?? source.id
    const heading = Array.isArray(source.headingPath) ? source.headingPath.join(' > ') : source.headingPath
    const snippet = source.snippet || source.excerpt || source.content || ''
    const key = [
      source.documentId || source.path || source.url || file,
      page != null && page !== '' ? `page:${page}` : '',
      chunk != null && chunk !== '' ? `chunk:${chunk}` : '',
      !page && !chunk ? heading || snippet : ''
    ].join('\u0000')
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function sourceFileKey(source, index) {
  return source.documentId
    || source.path
    || source.fileName
    || source.file
    || source.title
    || source.name
    || source.url
    || `source-${index}`
}

export function groupSourcesByFile(sources = []) {
  const groups = new Map()

  ;(sources || []).forEach((source, index) => {
    if (!source) return
    const key = sourceFileKey(source, index)
    const page = source.pageNumber ?? source.page
    const chunk = source.chunkIndex ?? source.chunkId
    let group = groups.get(key)
    if (!group) {
      group = { ...source, key, pages: [], chunks: [], count: 0 }
      groups.set(key, group)
    }
    group.count += 1
    if (page != null && page !== '' && !group.pages.includes(page)) group.pages.push(page)
    if (chunk !== undefined && chunk !== null && !group.chunks.includes(chunk)) group.chunks.push(chunk)
  })

  return [...groups.values()].map((group) => ({
    ...group,
    pages: [...group.pages].sort((a, b) => {
      const left = Number(a)
      const right = Number(b)
      if (!Number.isNaN(left) && !Number.isNaN(right)) return left - right
      return String(a).localeCompare(String(b), 'zh-CN')
    })
  }))
}
