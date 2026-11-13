// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  submitFeedback: vi.fn(),
  createWikiPage: vi.fn()
}))

vi.mock('../api/chatFeedbackApi', () => ({ submitMessageFeedback: mocks.submitFeedback }))
vi.mock('../api/wikiPageApi', () => ({ createWikiPage: mocks.createWikiPage }))

import ChatMessage from './ChatMessage.vue'

const ASSISTANT_MESSAGE = {
  id: 457,
  role: 'assistant',
  content: '这是一个有帮助的回答。',
  sources: [{ file: 'guide.md' }],
  createdAt: '2026-11-13T10:00:00Z'
}

function mountMessage(overrides = {}, props = {}) {
  return mount(ChatMessage, {
    props: {
      message: { ...ASSISTANT_MESSAGE, ...overrides },
      streaming: false,
      workspaceId: 'ws-1',
      conversationId: 'session-1',
      ...props
    }
  })
}

describe('ChatMessage 反馈', () => {
  it('对已落库的助手消息渲染 👍/👎', () => {
    const wrapper = mountMessage()
    expect(wrapper.find('.message-feedback').exists()).toBe(true)
    expect(wrapper.findAll('.feedback-button')).toHaveLength(2)
  })

  it('点 👍 调用反馈接口并显示完成', async () => {
    mocks.submitFeedback.mockResolvedValue({ id: 1, rating: 1 })
    const wrapper = mountMessage()
    await wrapper.findAll('.feedback-button')[0].trigger('click')
    await flushPromises()

    expect(mocks.submitFeedback).toHaveBeenCalledWith(457, 'session-1', 1, '')
    expect(wrapper.find('.feedback-done').exists()).toBe(true)
  })

  it('点 👎 展开理由输入框，提交时携带理由', async () => {
    mocks.submitFeedback.mockResolvedValue({ id: 1, rating: -1 })
    const wrapper = mountMessage()
    await wrapper.findAll('.feedback-button')[1].trigger('click')

    const textarea = wrapper.find('.feedback-reason textarea')
    expect(textarea.exists()).toBe(true)
    textarea.setValue('答非所问')
    await wrapper.find('.feedback-reason button').trigger('click')
    await flushPromises()

    expect(mocks.submitFeedback).toHaveBeenCalledWith(457, 'session-1', -1, '答非所问')
    expect(wrapper.find('.feedback-done').exists()).toBe(true)
  })

  it('流式中的消息不渲染反馈', () => {
    const wrapper = mountMessage({}, { streaming: true })
    expect(wrapper.find('.message-feedback').exists()).toBe(false)
  })

  it('没有 dbMessageId（历史消息缺 id / 缺会话）不渲染反馈', () => {
    const wrapper = mountMessage({ id: null }, { conversationId: '' })
    expect(wrapper.find('.message-feedback').exists()).toBe(false)
  })

  it('实时消息可从 learning.dbMessageId 拿到反馈目标', async () => {
    mocks.submitFeedback.mockResolvedValue({ id: 1, rating: 1 })
    const wrapper = mountMessage({ id: null, learning: { dbMessageId: 889 } })
    await wrapper.findAll('.feedback-button')[0].trigger('click')
    await flushPromises()

    expect(mocks.submitFeedback).toHaveBeenCalledWith(889, 'session-1', 1, '')
  })
})
