/**
 * Legacy 工作台问答链路，仅用于兼容旧客户端。
 *
 * <p><b>不要再往这个包里加功能。</b></p>
 *
 * <p>现状（2026-11 核对）：</p>
 * <ul>
 *   <li>{@code POST /api/workbench/chat} 与 {@code /api/workbench/chat/stream}
 *       由 {@code WorkbenchStreamController} 暴露；</li>
 *   <li>前端 {@code frontend/src} 内对 {@code workbench} 的接口引用为 0，
 *       实际走的是 {@code /api/learning-assistant/sessions/{id}/messages/stream}
 *       与 {@code /api/agent/{teaching|support|system}/chat}；</li>
 *   <li>本包此前长期处于 1.9% 行覆盖、无对应测试目录的状态。</li>
 * </ul>
 *
 * <p>之所以保留而不是直接删除，是因为 README 承诺旧入口兼容。若要下线，
 * 需要先确认没有外部脚本或第三方客户端在调用。删除前请同步更新
 * {@code docs/architecture/normal-chat-data-flow.md}。</p>
 */
package com.example.workbench.workbench;
