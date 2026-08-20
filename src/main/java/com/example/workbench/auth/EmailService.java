package com.example.workbench.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String host;
    private final String from;

    public EmailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                        @Value("${spring.mail.host:}") String host,
                        @Value("${spring.mail.username:}") String username) {
        this.mailSenderProvider = mailSenderProvider;
        this.host = host;
        this.from = username == null ? "" : username.trim();
    }

    public void sendVerificationCode(String to, String code) {
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮箱服务尚未配置");
        }

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮箱服务暂不可用");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("【识海】邮箱验证码");
        message.setText("您的验证码是：" + code + "，10 分钟内有效，请勿泄露给他人。");
        sender.send(message);
        log.info("邮箱验证码已发送 email={}", to);
    }
}
