package com.assessmentnotebook.burp;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Captures the screen to PNG bytes so a tester can grab the target page (or an
 * interactive state of it) without leaving Burp. The tester arranges the window
 * they want, optionally sets a short delay, then triggers the capture.
 *
 * <p>Screen capture is used because Burp does not render pages itself; importing
 * an existing image file is offered as the alternative in the UI.
 */
public final class ScreenshotCapture {
    private ScreenshotCapture() {}

    /**
     * Capture the full primary screen after an optional delay.
     *
     * @param delayMs milliseconds to wait before capturing (to let menus open)
     * @return PNG-encoded bytes
     */
    public static byte[] captureFullScreen(int delayMs) throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("No display available for screen capture");
        }
        try {
            if (delayMs > 0) Thread.sleep(delayMs);
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage image = new Robot().createScreenCapture(screen);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (AWTException e) {
            throw new IOException("Screen capture unavailable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Screen capture interrupted", e);
        }
    }
}
