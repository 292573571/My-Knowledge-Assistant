// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// vi.mock 工厂会被提升到 import 之前，外部变量必须用 vi.hoisted 声明
const mocks = vi.hoisted(() => ({
  status: vi.fn(),
  usage: vi.fn(),
  documents: vi.fn(),
  records: vi.fn(),
  models: vi.fn()
}))

vi.mock('../api/statusApi', () => ({ fetchWorkbenchStatus: mocks.status }))
vi.mock('../api/usageApi', () => ({ fetchUsageSummary: mocks.usage }))
vi.mock('../api/documentApi', () => ({ fetchDocuments: mocks.documents }))
vi.mock('../api/learningRecordApi', () => ({ fetchLearningRecords: mocks.records }))
vi.mock('../api/modelConfigApi', () => ({ fetchPersonalModels: mocks.models }))

import HomePage from './HomePage.vue'

const READY_STATUS = {
  provider: 'siliconflow',
  model: 'deepseek-ai/DeepSeek-V4-Flash',
  chatClientAvailable: true,
  vectorStore: 'Chroma',
  chromaConfigured: true,
  chromaReachable: true,
  documentCount: 5,
  chunkCount: 536
}

function usage(calls, assistantMessages, answersWithSources) {
  return { days: 7, totals: { calls }, retrieval: { assistantMessages, answersWithSources } }
}

function mountHome() {
  return mount(HomePage, {
    props: {
      user: { userName: '测试用户' },
      workspaceId: 'ws-1',
      workspace: { id: 'ws-1', name: '我的个人空间' }
    }
  })
}

beforeEach(() => {
  // 关掉首次自动弹出的 Tour，避免 jsdom 缺少 scrollIntoView
  window.localStorage.setItem('shihai_tour_done_v1', '1')
  mocks.status.mockResolvedValue(READY_STATUS)
  mocks.usage.mockResolvedValue(usage(4, 8, 6))
  mocks.documents.mockResolvedValue([
    { documentId: 'd1', fileName: 'a.pdf', chunkCount: 20, indexStatus: 'INDEXED' },
    { documentId: 'd2', fileName: 'b.md', chunkCount: 10, indexStatus: 'INDEXED' },
    { documentId: 'd3', fileName: 'c.xlsx', chunkCount: 6, indexStatus: 'INDEXED' }
  ])
  mocks.records.mockResolvedValue([{ date: '2026-11-11', title: 'MyBatis 缓存机制' }])
  mocks.models.mockResolvedValue([{ id: 'm1', name: '我的模型' }])
})

describe('HomePage 工作台', () => {
  it('用真实接口数据渲染四张状态卡', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const cards = wrapper.findAll('.home-metric')
    expect(cards).toHaveLength(4)
    // 文档数与切片数来自「当前空间」的文档列表，不是 health 的公共统计
    expect(cards[0].text()).toContain('3')
    expect(cards[0].text()).toContain('36 个切片')
    expect(cards[1].text()).toContain('本地检索正常')
    expect(cards[2].text()).toContain('4')
    expect(cards[2].text()).toContain('带出处 75%')
    // 模型名取 provider 前缀之后的部分
    expect(cards[3].text()).toContain('DeepSeek-V4-Flash')
  })

  it('Chroma 配置了但不可达时标红，不再笼统显示「Chroma」', async () => {
    mocks.status.mockResolvedValue({ ...READY_STATUS, chromaReachable: false })
    const wrapper = mountHome()
    await flushPromises()

    const retrievalCard = wrapper.findAll('.home-metric')[1]
    expect(retrievalCard.text()).toContain('本地检索不可达')
    expect(retrievalCard.text()).toContain('已降级到内存回退')
    expect(retrievalCard.classes()).toContain('tone-bad')
  })

  it('三项行为都满足时清单消失，只留一行完成提示', async () => {
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.find('.home-checklist').exists()).toBe(false)
    expect(wrapper.find('.home-checklist-done').text()).toContain('全部完成')
  })

  it('新用户没有任何痕迹时清单全部未完成，且指南默认展开', async () => {
    mocks.status.mockResolvedValue({ ...READY_STATUS, documentCount: 0, chunkCount: 0 })
    mocks.usage.mockResolvedValue(usage(0, 0, 0))
    mocks.documents.mockResolvedValue([])
    mocks.records.mockResolvedValue([])
    mocks.models.mockResolvedValue([])

    const wrapper = mountHome()
    await flushPromises()

    const items = wrapper.findAll('.home-checklist-items li')
    expect(items).toHaveLength(3)
    expect(items.every(item => !item.classes().includes('done'))).toBe(true)
    expect(wrapper.find('.home-checklist-count').text()).toBe('0 / 3')
    // v-show 在未挂载到 document 的元素上 isVisible() 恒为 true，直接断言内联样式
    expect(wrapper.find('.home-guide-body').attributes('style') || '').not.toContain('display: none')
  })

  it('老用户默认收起新手指南', async () => {
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.find('.home-guide-body').attributes('style')).toContain('display: none')
  })

  it('接口全失败时给出重试入口，而不是空白卡片', async () => {
    mocks.status.mockRejectedValue(new Error('down'))
    mocks.usage.mockRejectedValue(new Error('down'))
    mocks.documents.mockRejectedValue(new Error('down'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.find('.home-metrics-retry').exists()).toBe(true)
  })
})
