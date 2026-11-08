package de.lemaik.chunkycloud.worker.util;

import java.awt.*;
import java.awt.image.BufferedImage;

public class ImageUtil {
    /**
     * Creates an RGB thumbnail that fits within the given bounds without upscaling.
     */
    public static BufferedImage createThumbnail(BufferedImage image, int maxWidth, int maxHeight) {
        double scale = Math.min(1.0, Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return thumbnail;
    }
}
