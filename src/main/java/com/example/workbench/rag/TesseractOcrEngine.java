package com.example.workbench.rag;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通过本机 Tesseract 命令执行 OCR，避免在应用 JAR 中打包平台相关原生库。
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

    private final String command;
    private final String languages;
    private final Duration timeout;
    private final OcrImagePreprocessor imagePreprocessor;

    /**
     * 创建 Tesseract 命令行 OCR 引擎。
     *
     * @param command Tesseract 可执行命令或绝对路径
     * @param languages Tesseract 语言组合
     * @param timeoutSeconds 单次识别超时秒数
     * @param imagePreprocessor OCR 图片水印预处理器
     */
    public TesseractOcrEngine(
            @Value("${workbench.ocr.command:tesseract}") String command,
            @Value("${workbench.ocr.languages:chi_sim+eng}") String languages,
            @Value("${workbench.ocr.timeout-seconds:120}") long timeoutSeconds,
            OcrImagePreprocessor imagePreprocessor
    ) {
        this.command = command;
        this.languages = languages;
        this.timeout = Duration.ofSeconds(Math.max(10, timeoutSeconds));
        this.imagePreprocessor = imagePreprocessor;
    }

    @Override
    public String recognize(BufferedImage image) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("knowledge-ocr-");
            Path input = directory.resolve("input.png");
            Path outputBase = directory.resolve("result");
            Path processLog = directory.resolve("tesseract.log");
            if (!ImageIO.write(imagePreprocessor.suppressLightWatermarks(image), "png", input.toFile())) {
                throw new IllegalArgumentException("OCR 图片格式转换失败");
            }
            Process process = new ProcessBuilder(command, input.toString(), outputBase.toString(),
                    "-l", languages, "--psm", "3")
                    .redirectErrorStream(true)
                    .redirectOutput(processLog.toFile())
                    .start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("OCR 处理超时");
            }
            String output = Files.exists(processLog)
                    ? Files.readString(processLog, StandardCharsets.UTF_8).strip()
                    : "";
            if (process.exitValue() != 0) {
                throw new IllegalStateException(output.isBlank() ? "OCR 服务执行失败" : "OCR 服务执行失败：" + output);
            }
            Path textFile = Path.of(outputBase + ".txt");
            return Files.exists(textFile) ? Files.readString(textFile, StandardCharsets.UTF_8).strip() : "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR 处理被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("OCR 服务不可用，请确认已安装 Tesseract 及中文语言包", exception);
        } finally {
            deleteTemporaryDirectory(directory);
        }
    }

    private void deleteTemporaryDirectory(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件由操作系统继续清理，不覆盖原始 OCR 结果。
                }
            });
        } catch (IOException ignored) {
            // 临时目录清理失败不影响识别结果。
        }
    }
}
