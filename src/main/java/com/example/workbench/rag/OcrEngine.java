package com.example.workbench.rag;

import java.awt.image.BufferedImage;

/**
 * 将渲染后的页面或图片识别为文本。
 */
public interface OcrEngine {

    /**
     * 识别图片中的文字。
     *
     * @param image 待识别图片
     * @return 识别后的纯文本
     */
    String recognize(BufferedImage image);
}
