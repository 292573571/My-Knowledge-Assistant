function fileName(source) {
  return source.fileName || source.file || source.title || source.name || '未知文件'
}

export function deduplicateDisplayedSources(sources = []) {
  const seenPages = new Set()

  return sources.filter((source) => {
    if (!source?.pageNumber) return true

    const key = `${fileName(source)}\u0000${source.pageNumber}`
    if (seenPages.has(key)) return false
    seenPages.add(key)
    return true
  })
}
