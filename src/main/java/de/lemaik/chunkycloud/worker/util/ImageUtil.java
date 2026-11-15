package de.lemaik.chunkycloud.worker.util;

import se.llbit.chunky.resources.BitmapImage;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

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

    /**
     * Create a {@link BufferedImage} from a {@link BitmapImage}.
     *
     * @param input Bitmap image
     * @return Buffered image
     */
    public static BufferedImage bitmapImageToBufferedImage(BitmapImage input) {
        BufferedImage dst = new BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        System.arraycopy(input.data, 0, pixels, 0, input.data.length);
        return dst;
    }
}
