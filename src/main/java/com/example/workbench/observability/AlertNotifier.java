package com.example.workbench.observability;

/**
 * 运维告警出口。默认实现是邮件，也可以替换成企业微信/钉钉机器人。
 *
 * <p>实现必须自行吞掉异常：告警失败不能把定时任务打挂。</p>
 */
public interface AlertNotifier {

    void send(String subject, String body);
}
