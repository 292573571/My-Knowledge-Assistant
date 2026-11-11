// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import ConfirmDialog from './ConfirmDialog.vue'

// 组件用 Teleport 挂到 body，所以断言直接查 document.body，
// 不用 wrapper.find（那样拿不到传送出去的内容）。
function mountDialog(props = {}) {
  return mount(ConfirmDialog, {
    props: { title: '删除文档', message: '删除后不可恢复', ...props },
    attachTo: document.body
  })
}

function query(selector) {
  return document.body.querySelector(selector)
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ConfirmDialog', () => {
  it('渲染标题、正文与自定义确认文案', async () => {
    mountDialog({ confirmText: '仍要删除' })
    await Promise.resolve()

    expect(query('.confirm-dialog h2').textContent).toBe('删除文档')
    expect(query('.confirm-dialog p').textContent).toBe('删除后不可恢复')
    expect(query('.confirm-submit').textContent.trim()).toBe('仍要删除')
  })

  it('点击确认抛出 confirm 事件', async () => {
    const wrapper = mountDialog()
    await Promise.resolve()

    query('.confirm-submit').click()
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('点击取消抛出 cancel 事件', async () => {
    const wrapper = mountDialog()
    await Promise.resolve()

    query('.confirm-cancel').click()
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('busy 状态下禁止取消，避免重复提交把请求打断', async () => {
    const wrapper = mountDialog({ busy: true })
    await Promise.resolve()

    query('.confirm-cancel').click()
    query('.confirm-dialog-close').click()
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('cancel')).toBeUndefined()
    expect(query('.confirm-cancel').disabled).toBe(true)
  })

  it('busy 时确认按钮显示处理中且不可点', async () => {
    mountDialog({ busy: true })
    await Promise.resolve()

    expect(query('.confirm-submit').textContent.trim()).toBe('处理中...')
    expect(query('.confirm-submit').disabled).toBe(true)
  })

  it('danger 影响视觉标记，但不改变语义文案', async () => {
    mountDialog({ danger: true })
    await Promise.resolve()

    expect(query('.confirm-submit').classList.contains('danger')).toBe(true)
    expect(query('.confirm-dialog-eyebrow').textContent).toContain('需要确认的操作')
  })
})
