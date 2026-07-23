package dadb.helper;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A request-driven display capture stream for Android 21 and newer.
 *
 * <p>Like scrcpy, this keeps one virtual display connected to a target-size Surface. It encodes
 * only newly produced frames and never starts a screencap process or creates an intermediate PNG.</p>
 */
public final class ScreenshotStreamMain {
    private static final int PROTOCOL_MAGIC = 0x44534352; // DSCR
    private static final int PROTOCOL_VERSION = 2;
    private static final int COMMAND_NEXT = 1;
    private static final int COMMAND_STOP = 2;
    private static final int STATUS_FRAME = 0;
    private static final int STATUS_ERROR = 1;
    private static final int STATUS_NO_CHANGE = 2;
    private static final int CAPTURE_BACKEND_DISPLAY_SURFACE = 1;
    private static final int CAPTURE_BACKEND_SURFACE_CONTROL = 2;
    private static final int DEFAULT_MAX_SIZE = 720;
    private static final int DEFAULT_JPEG_QUALITY = 55;
    private static final long FRAME_WAIT_NANOS = 20_000_000L;
    private static final int INITIAL_EMPTY_FRAME_LIMIT = 100;

    private ScreenshotStreamMain() {
    }

    public static void main(String[] args) throws Exception {
        int maxSize = parseBoundedInt(args, 0, DEFAULT_MAX_SIZE, 0, 8192);
        int jpegQuality = parseBoundedInt(args, 1, DEFAULT_JPEG_QUALITY, 1, 100);

        DataInputStream input = new DataInputStream(System.in);
        DataOutputStream output = new DataOutputStream(System.out);
        try (DisplayCapture capture = new DisplayCapture(maxSize)) {
            output.writeInt(PROTOCOL_MAGIC);
            output.writeByte(PROTOCOL_VERSION);
            output.flush();

            while (true) {
                final int command;
                try {
                    command = input.readUnsignedByte();
                } catch (EOFException ignored) {
                    return;
                }

                if (command == COMMAND_STOP) {
                    return;
                }
                if (command != COMMAND_NEXT) {
                    writeError(output, "Unknown screenshot stream command: " + command);
                    continue;
                }

                try {
                    long startedAt = System.nanoTime();
                    CapturedFrame frame = capture.capture(jpegQuality);
                    int durationMillis =
                            (int) Math.min(
                                    (System.nanoTime() - startedAt) / 1_000_000L,
                                    Integer.MAX_VALUE
                            );
                    if (frame == null) {
                        output.writeByte(STATUS_NO_CHANGE);
                        output.writeInt(durationMillis);
                    } else {
                        output.writeByte(STATUS_FRAME);
                        output.writeInt(frame.sourceWidth);
                        output.writeInt(frame.sourceHeight);
                        output.writeInt(frame.imageWidth);
                        output.writeInt(frame.imageHeight);
                        output.writeLong(System.nanoTime() / 1_000_000L);
                        output.writeByte(frame.captureBackend);
                        output.writeInt(durationMillis);
                        output.writeInt(frame.jpegBytes.length);
                        output.write(frame.jpegBytes);
                    }
                    output.flush();
                } catch (Throwable error) {
                    String message = error.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = error.getClass().getSimpleName();
                    }
                    writeError(output, message);
                }
            }
        }
    }

    private static final class DisplayCapture implements AutoCloseable {
        private final int maxSize;
        private final Object displayManager;
        private final Method getDisplayInfoMethod;
        private final Method createVirtualDisplayMethod;
        private ImageReader imageReader;
        private VirtualDisplay virtualDisplay;
        private IBinder surfaceControlDisplay;
        private int sourceWidth;
        private int sourceHeight;
        private int imageWidth;
        private int imageHeight;
        private int captureBackend;
        private int initialEmptyFrameCount;
        private boolean firstFrameCaptured;
        private boolean forceSurfaceControl;

        DisplayCapture(int maxSize) throws Exception {
            this.maxSize = maxSize;
            Class<?> displayManagerGlobalClass =
                    Class.forName("android.hardware.display.DisplayManagerGlobal");
            displayManager =
                    displayManagerGlobalClass.getDeclaredMethod("getInstance").invoke(null);
            getDisplayInfoMethod = displayManager.getClass().getMethod("getDisplayInfo", int.class);
            Method optionalCreateVirtualDisplayMethod;
            try {
                optionalCreateVirtualDisplayMethod =
                        android.hardware.display.DisplayManager.class.getMethod(
                                "createVirtualDisplay",
                                String.class,
                                int.class,
                                int.class,
                                int.class,
                                Surface.class
                        );
            } catch (NoSuchMethodException ignored) {
                optionalCreateVirtualDisplayMethod = null;
            }
            createVirtualDisplayMethod = optionalCreateVirtualDisplayMethod;
            rebuildIfRequired();
        }

        CapturedFrame capture(int jpegQuality) throws Exception {
            rebuildIfRequired();
            Image image = awaitLatestImage();
            if (image == null) {
                if (!firstFrameCaptured
                        && virtualDisplay != null
                        && ++initialEmptyFrameCount >= INITIAL_EMPTY_FRAME_LIMIT) {
                    forceSurfaceControl = true;
                    releaseDisplay();
                    rebuildIfRequired();
                }
                return null;
            }
            firstFrameCaptured = true;
            initialEmptyFrameCount = 0;
            try {
                Bitmap bitmap = copyImage(image);
                try {
                    ByteArrayOutputStream jpegOutput = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegOutput)) {
                        throw new IOException("Unable to encode screenshot JPEG");
                    }
                    return new CapturedFrame(
                            sourceWidth,
                            sourceHeight,
                            imageWidth,
                            imageHeight,
                            captureBackend,
                            jpegOutput.toByteArray()
                    );
                } finally {
                    bitmap.recycle();
                }
            } finally {
                image.close();
            }
        }

        private void rebuildIfRequired() throws Exception {
            Object displayInfo = getDisplayInfoMethod.invoke(displayManager, 0);
            if (displayInfo == null) {
                throw new IOException("Default display information is unavailable");
            }
            Class<?> displayInfoClass = displayInfo.getClass();
            int nextSourceWidth = displayInfoClass.getDeclaredField("logicalWidth").getInt(displayInfo);
            int nextSourceHeight = displayInfoClass.getDeclaredField("logicalHeight").getInt(displayInfo);
            int nextLayerStack = displayInfoClass.getDeclaredField("layerStack").getInt(displayInfo);
            if (nextSourceWidth <= 0 || nextSourceHeight <= 0) {
                throw new IOException("Default display has invalid dimensions");
            }

            int nextImageWidth = nextSourceWidth;
            int nextImageHeight = nextSourceHeight;
            int longestEdge = Math.max(nextSourceWidth, nextSourceHeight);
            if (maxSize > 0 && longestEdge > maxSize) {
                float scale = maxSize / (float) longestEdge;
                nextImageWidth = Math.max(1, Math.round(nextSourceWidth * scale));
                nextImageHeight = Math.max(1, Math.round(nextSourceHeight * scale));
            }

            if ((virtualDisplay != null || surfaceControlDisplay != null)
                    && sourceWidth == nextSourceWidth
                    && sourceHeight == nextSourceHeight
                    && imageWidth == nextImageWidth
                    && imageHeight == nextImageHeight) {
                return;
            }

            releaseDisplay();
            sourceWidth = nextSourceWidth;
            sourceHeight = nextSourceHeight;
            imageWidth = nextImageWidth;
            imageHeight = nextImageHeight;
            imageReader =
                    ImageReader.newInstance(
                            imageWidth,
                            imageHeight,
                            PixelFormat.RGBA_8888,
                            2
                    );
            Throwable displayManagerError = null;
            if (!forceSurfaceControl) {
                try {
                    if (createVirtualDisplayMethod == null) {
                        throw new NoSuchMethodException(
                                "DisplayManager.createVirtualDisplay mirror API is unavailable"
                        );
                    }
                    virtualDisplay =
                            (VirtualDisplay)
                                    createVirtualDisplayMethod.invoke(
                                            null,
                                            "dadb-screenshot",
                                            imageWidth,
                                            imageHeight,
                                            0,
                                            imageReader.getSurface()
                                    );
                    if (virtualDisplay == null) {
                        throw new IOException("DisplayManager returned no virtual display");
                    }
                    captureBackend = CAPTURE_BACKEND_DISPLAY_SURFACE;
                    return;
                } catch (Throwable error) {
                    displayManagerError = error;
                    virtualDisplay = null;
                }
            }

            try {
                surfaceControlDisplay =
                        createSurfaceControlDisplay(
                                imageReader.getSurface(),
                                nextSourceWidth,
                                nextSourceHeight,
                                nextImageWidth,
                                nextImageHeight,
                                nextLayerStack
                        );
                captureBackend = CAPTURE_BACKEND_SURFACE_CONTROL;
            } catch (Throwable surfaceControlError) {
                releaseDisplay();
                IOException failure =
                        new IOException(
                                "Unable to create a screenshot display with DisplayManager or SurfaceControl",
                                surfaceControlError
                        );
                if (displayManagerError != null) {
                    failure.addSuppressed(displayManagerError);
                }
                throw failure;
            }
        }

        private static IBinder createSurfaceControlDisplay(
                Surface surface,
                int sourceWidth,
                int sourceHeight,
                int imageWidth,
                int imageHeight,
                int layerStack
        ) throws Exception {
            Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
            boolean secure =
                    Build.VERSION.SDK_INT < 30
                            || (Build.VERSION.SDK_INT == 30
                                    && !"S".equals(Build.VERSION.CODENAME));
            IBinder display =
                    (IBinder)
                            surfaceControlClass
                                    .getMethod("createDisplay", String.class, boolean.class)
                                    .invoke(null, "dadb-screenshot", secure);
            if (display == null) {
                throw new IOException("SurfaceControl returned no display token");
            }

            surfaceControlClass.getMethod("openTransaction").invoke(null);
            try {
                surfaceControlClass
                        .getMethod("setDisplaySurface", IBinder.class, Surface.class)
                        .invoke(null, display, surface);
                surfaceControlClass
                        .getMethod(
                                "setDisplayProjection",
                                IBinder.class,
                                int.class,
                                Rect.class,
                                Rect.class
                        )
                        .invoke(
                                null,
                                display,
                                0,
                                new Rect(0, 0, sourceWidth, sourceHeight),
                                new Rect(0, 0, imageWidth, imageHeight)
                        );
                surfaceControlClass
                        .getMethod("setDisplayLayerStack", IBinder.class, int.class)
                        .invoke(null, display, layerStack);
            } catch (Exception error) {
                destroySurfaceControlDisplay(surfaceControlClass, display);
                throw error;
            } finally {
                surfaceControlClass.getMethod("closeTransaction").invoke(null);
            }
            return display;
        }

        private static void destroySurfaceControlDisplay(
                Class<?> surfaceControlClass,
                IBinder display
        ) {
            try {
                surfaceControlClass
                        .getMethod("destroyDisplay", IBinder.class)
                        .invoke(null, display);
            } catch (Throwable ignored) {
                // The display token is best-effort cleanup after a failed capture setup.
            }
        }

        private Image awaitLatestImage() throws InterruptedException {
            long deadline = System.nanoTime() + FRAME_WAIT_NANOS;
            Image image;
            do {
                image = imageReader.acquireLatestImage();
                if (image != null) {
                    return image;
                }
                Thread.sleep(1L);
            } while (System.nanoTime() < deadline);
            return null;
        }

        private Bitmap copyImage(Image image) throws IOException {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length != 1) {
                throw new IOException("Screenshot ImageReader returned an unexpected plane count");
            }
            Image.Plane plane = planes[0];
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride != 4 || rowStride < imageWidth * pixelStride) {
                throw new IOException("Screenshot ImageReader returned an unsupported pixel layout");
            }

            int paddedWidth = rowStride / pixelStride;
            Bitmap paddedBitmap =
                    Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888);
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            paddedBitmap.copyPixelsFromBuffer(buffer);
            if (paddedWidth == imageWidth) {
                return paddedBitmap;
            }

            Bitmap croppedBitmap =
                    Bitmap.createBitmap(paddedBitmap, 0, 0, imageWidth, imageHeight);
            paddedBitmap.recycle();
            return croppedBitmap;
        }

        private void releaseDisplay() {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (surfaceControlDisplay != null) {
                try {
                    destroySurfaceControlDisplay(
                            Class.forName("android.view.SurfaceControl"),
                            surfaceControlDisplay
                    );
                } catch (ClassNotFoundException ignored) {
                    // The class existed when the display was created.
                }
                surfaceControlDisplay = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        }

        @Override
        public void close() {
            releaseDisplay();
        }
    }

    private static void writeError(DataOutputStream output, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, 16 * 1024);
        output.writeByte(STATUS_ERROR);
        output.writeInt(length);
        output.write(bytes, 0, length);
        output.flush();
    }

    private static int parseBoundedInt(
            String[] args,
            int index,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        if (index >= args.length) {
            return defaultValue;
        }
        int value = Integer.parseInt(args[index]);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "Argument " + index + " must be between " + minimum + " and " + maximum
            );
        }
        return value;
    }

    private static final class CapturedFrame {
        final int sourceWidth;
        final int sourceHeight;
        final int imageWidth;
        final int imageHeight;
        final int captureBackend;
        final byte[] jpegBytes;

        CapturedFrame(
                int sourceWidth,
                int sourceHeight,
                int imageWidth,
                int imageHeight,
                int captureBackend,
                byte[] jpegBytes
        ) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.captureBackend = captureBackend;
            this.jpegBytes = jpegBytes;
        }
    }
}
