package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class OcrImagePreprocessorTest {

    @Test
    void suppressesLightPixelsAndPreservesDarkText() {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(210, 210, 210).getRGB());
        source.setRGB(1, 0, new Color(40, 50, 60).getRGB());

        BufferedImage result = new OcrImagePreprocessor(205).suppressLightWatermarks(source);

        assertThat(new Color(result.getRGB(0, 0))).isEqualTo(Color.WHITE);
        assertThat(new Color(result.getRGB(1, 0))).isEqualTo(new Color(40, 50, 60));
    }

    @Test
    void compositesTransparentPixelsOnWhiteBackground() {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(0, 0, 0, 20).getRGB());

        BufferedImage result = new OcrImagePreprocessor(215).suppressLightWatermarks(source);

        assertThat(new Color(result.getRGB(0, 0))).isEqualTo(Color.WHITE);
    }
}
