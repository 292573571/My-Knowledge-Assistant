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
