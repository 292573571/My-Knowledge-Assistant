package com.example.workbench.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 邮件告警通道，复用已有的 SMTP 配置（spring.mail.*）。
 *
 * <p>SMTP 未配置、或没配收件人时静默跳过——服务器默认没有 SMTP，
 * 此时告警仍然会写进 system_log，不影响巡检本身。</p>
 */
@Component
public class MailAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailAlertNotifier.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String host;
    private final String from;
    private final String to;

    public MailAlertNotifier(ObjectProvider<JavaMailSender> mailSenderProvider,
                             @Value("${spring.mail.host:}") String host,
                             @Value("${spring.mail.username:}") String username,
                             @Value("${workbench.alerts.mail-to:}") String to) {
        this.mailSenderProvider = mailSenderProvider;
        this.host = host == null ? "" : host.trim();
        this.from = username == null ? "" : username.trim();
        this.to = to == null ? "" : to.trim();
    }

    @Override
    public void send(String subject, String body) {
        if (host.isBlank() || to.isBlank()) {
            log.debug("运维告警邮件未发送：SMTP 或收件人未配置 subject={}", subject);
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.debug("运维告警邮件未发送：JavaMailSender 不可用 subject={}", subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!from.isBlank()) {
                message.setFrom(from);
            }
            message.setTo(to.split("\\s*,\\s*"));
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            log.info("运维告警邮件已发送 to={} subject={}", to, subject);
        } catch (RuntimeException exception) {
            log.warn("运维告警邮件发送失败 subject={} reason={}", subject, exception.toString());
        }
    }
}
