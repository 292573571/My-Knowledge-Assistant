package com.example.workbench.rag;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 在 OCR 前压白浅色背景和半透明水印，同时保留较深的正文笔画。
 */
@Component
public class OcrImagePreprocessor {

    private final int lightnessThreshold;

    /**
     * 创建 OCR 图片预处理器。
     *
     * @param lightnessThreshold 被视为浅色背景的亮度阈值，取值范围为 180 至 250
     */
    public OcrImagePreprocessor(
            @Value("${workbench.ocr.watermark-lightness-threshold:215}") int lightnessThreshold
    ) {
        this.lightnessThreshold = Math.max(180, Math.min(250, lightnessThreshold));
    }

    /**
     * 将透明像素合成到白色背景，并压白高亮度像素以降低浅色水印对 OCR 的干扰。
     *
     * @param source 原始页面或图片
     * @return 适合 OCR 的 RGB 图片
     */
    public BufferedImage suppressLightWatermarks(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                Color color = new Color(source.getRGB(x, y), true);
                int red = compositeOnWhite(color.getRed(), color.getAlpha());
                int green = compositeOnWhite(color.getGreen(), color.getAlpha());
                int blue = compositeOnWhite(color.getBlue(), color.getAlpha());
                int lightness = (red * 299 + green * 587 + blue * 114) / 1000;
                result.setRGB(x, y, lightness >= lightnessThreshold
                        ? Color.WHITE.getRGB()
                        : new Color(red, green, blue).getRGB());
            }
        }
        return result;
    }

    private int compositeOnWhite(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha)) / 255;
    }
}
