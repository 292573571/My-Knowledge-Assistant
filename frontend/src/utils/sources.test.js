import { describe, expect, it } from 'vitest'
import { deduplicateDisplayedSources, groupSourcesByFile } from './sources'

describe('deduplicateDisplayedSources', () => {
  it('同一文档同一分块的重复来源只保留第一条', () => {
    const sources = [
      { documentId: 'doc-1', chunkIndex: 3, file: 'Kettle 手册.pdf', snippet: 'A' },
      { documentId: 'doc-1', chunkIndex: 3, file: 'Kettle 手册.pdf', snippet: 'A' }
    ]

    expect(deduplicateDisplayedSources(sources)).toHaveLength(1)
  })

  it('同一文档不同分块视为不同来源', () => {
    const sources = [
      { documentId: 'doc-1', chunkIndex: 3, file: 'Kettle 手册.pdf' },
      { documentId: 'doc-1', chunkIndex: 4, file: 'Kettle 手册.pdf' }
    ]

    expect(deduplicateDisplayedSources(sources)).toHaveLength(2)
  })

  it('页码不同不会因分块为空被误判为重复', () => {
    const sources = [
      { documentId: 'doc-1', pageNumber: 2, file: '规范.pdf' },
      { documentId: 'doc-1', pageNumber: 5, file: '规范.pdf' }
    ]

    expect(deduplicateDisplayedSources(sources)).toHaveLength(2)
  })

  it('没有分块和页码时退化为按标题去重', () => {
    const sources = [
      { file: '规范.pdf', headingPath: ['总则', '范围'] },
      { file: '规范.pdf', headingPath: ['总则', '范围'] },
      { file: '规范.pdf', headingPath: ['总则', '术语'] }
    ]

    expect(deduplicateDisplayedSources(sources)).toHaveLength(2)
  })

  it('过滤空元素，不因 null 崩溃', () => {
    expect(deduplicateDisplayedSources([null, undefined, { file: 'a.pdf', chunkIndex: 0 }]))
      .toHaveLength(1)
  })
})

describe('groupSourcesByFile', () => {
  it('按文件聚合，并累计次数与页码', () => {
    const groups = groupSourcesByFile([
      { documentId: 'doc-1', file: '手册.pdf', pageNumber: 3 },
      { documentId: 'doc-1', file: '手册.pdf', pageNumber: 1 },
      { documentId: 'doc-2', file: '规范.pdf', pageNumber: 2 }
    ])

    expect(groups).toHaveLength(2)
    const manual = groups.find((item) => item.key === 'doc-1')
    expect(manual.count).toBe(2)
    expect(manual.pages).toEqual([1, 3])
  })

  it('页码按数值排序而不是字典序', () => {
    const [group] = groupSourcesByFile([
      { documentId: 'doc-1', pageNumber: 10 },
      { documentId: 'doc-1', pageNumber: 2 },
      { documentId: 'doc-1', pageNumber: 1 }
    ])

    expect(group.pages).toEqual([1, 2, 10])
  })

  it('页码里混入非数字时退化为中文本地化排序', () => {
    const [group] = groupSourcesByFile([
      { documentId: 'doc-1', pageNumber: '附录B' },
      { documentId: 'doc-1', pageNumber: '附录A' }
    ])

    expect(group.pages).toEqual(['附录A', '附录B'])
  })

  it('没有稳定标识时按序号分组，避免所有条目挤进同一组', () => {
    const groups = groupSourcesByFile([{ snippet: 'x' }, { snippet: 'y' }])

    expect(groups).toHaveLength(2)
    expect(groups.map((item) => item.key)).toEqual(['source-0', 'source-1'])
  })
})
