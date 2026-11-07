package com.xlrw.account;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;

/** Turns any picture (png / jpg / gif / bmp) into a 64x64 PNG, center-cropped to a square. */
final class AvatarImage {
    private AvatarImage() {}

    static byte[] toPng64(File f) throws Exception {
        BufferedImage src = ImageIO.read(f);
        if (src == null) throw new IllegalArgumentException("không phải ảnh");
        int s = Math.min(src.getWidth(), src.getHeight());
        int x0 = (src.getWidth() - s) / 2, y0 = (src.getHeight() - s) / 2;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, 64, 64, x0, y0, x0 + s, y0 + s, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }
}
