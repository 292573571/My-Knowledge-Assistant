package com.example.workbench.modelconfig;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 透明加密字符串字段(当前仅用于 LLM API Key),避免密钥明文落库。
 *
 * <p>未配置 {@code app.ai.key-encryption-secret} 时退化为明文透传(仅打 WARN),保证应用可启动;
 * 生产环境在 {@code /etc/shihai/shihai.env} 配置 {@code AI_KEY_ENCRYPTION_SECRET} 后即自动生效。</p>
 *
 * <p>懒迁移:存量明文读取时原样返回,下次写入时自动加密;密文带 {@code enc:v1:} 前缀以便识别。</p>
 */
@Component
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);
    private static final String PREFIX = "enc:v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final boolean enabled;
    private final SecretKeySpec key;

    public EncryptedStringConverter(@Value("${app.ai.key-encryption-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            this.enabled = false;
            this.key = null;
            log.warn("AI_KEY_ENCRYPTION_SECRET 未配置,LLM API Key 将以明文存储(不安全)。"
                    + "请在 /etc/shihai/shihai.env 配置 AI_KEY_ENCRYPTION_SECRET(任意长度随机串)并重启。");
        } else {
            this.enabled = true;
            this.key = deriveKey(secret);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (!enabled || attribute == null) {
            return attribute;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] packaged = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packaged, 0, iv.length);
            System.arraycopy(ciphertext, 0, packaged, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(packaged);
        } catch (Exception exception) {
            log.error("加密 API Key 失败,回退明文存储", exception);
            return attribute;
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (!enabled || dbData == null) {
            return dbData;
        }
        if (!dbData.startsWith(PREFIX)) {
            return dbData;
        }
        try {
            byte[] packaged = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(packaged, 0, iv, 0, IV_BYTES);
            byte[] ciphertext = new byte[packaged.length - IV_BYTES];
            System.arraycopy(packaged, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.error("解密 API Key 失败", exception);
            return dbData;
        }
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("派生 API Key 加密密钥失败", exception);
        }
    }
}
