package dadb.helper;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * A persistent single-pointer touch injection stream.
 */
@SuppressLint("PrivateApi")
public final class TouchStreamMain {
    private static final int PROTOCOL_MAGIC = 0x44544348; // DTCH
    private static final int COMMAND_STOP = 4;
    private static final int STATUS_OK = 0;
    private static final int STATUS_ERROR = 1;
    private static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;

    private TouchStreamMain() {
    }

    public static void main(String[] args) throws Exception {
        Object inputManager = AppIconExportMain.obtainShellInputManager();
        Method injectInputEvent =
                android.hardware.input.InputManager.class.getMethod(
                        "injectInputEvent",
                        InputEvent.class,
                        int.class
                );
        DataInputStream input = new DataInputStream(System.in);
        DataOutputStream output = new DataOutputStream(System.out);
        long downTime = 0L;
        boolean pointerActive = false;

        output.writeInt(PROTOCOL_MAGIC);
        output.flush();

        try {
            while (true) {
                final int action;
                try {
                    action = input.readUnsignedByte();
                } catch (EOFException ignored) {
                    return;
                }
                if (action == COMMAND_STOP) {
                    return;
                }

                int x = input.readInt();
                int y = input.readInt();
                try {
                    long now = SystemClock.uptimeMillis();
                    if (action == MotionEvent.ACTION_DOWN) {
                        if (pointerActive) {
                            throw new IOException("A touch pointer is already active");
                        }
                        downTime = now;
                        pointerActive = true;
                    } else if (!pointerActive) {
                        throw new IOException("No touch pointer is active");
                    }

                    float pressure =
                            action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                                    ? 0f
                                    : 1f;
                    MotionEvent event =
                            MotionEvent.obtain(
                                    downTime,
                                    now,
                                    action,
                                    x,
                                    y,
                                    pressure,
                                    1f,
                                    0,
                                    1f,
                                    1f,
                                    0,
                                    0
                            );
                    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    boolean injected;
                    try {
                        injected =
                                (Boolean)
                                        injectInputEvent.invoke(
                                                inputManager,
                                                event,
                                                INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
                                        );
                    } finally {
                        event.recycle();
                    }
                    if (!injected) {
                        throw new IOException("Touch event injection was rejected");
                    }
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        pointerActive = false;
                        downTime = 0L;
                    }
                    output.writeByte(STATUS_OK);
                    output.flush();
                } catch (Throwable error) {
                    writeError(output, describeError(error));
                }
            }
        } finally {
            if (pointerActive) {
                injectCancel(inputManager, injectInputEvent, downTime);
            }
        }
    }

    private static void injectCancel(
            Object inputManager,
            Method injectInputEvent,
            long downTime
    ) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event =
                MotionEvent.obtain(
                        downTime,
                        now,
                        MotionEvent.ACTION_CANCEL,
                        0f,
                        0f,
                        0f,
                        1f,
                        0,
                        1f,
                        1f,
                        0,
                        0
                );
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            injectInputEvent.invoke(
                    inputManager,
                    event,
                    INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            );
        } catch (Throwable ignored) {
        } finally {
            event.recycle();
        }
    }

    private static void writeError(DataOutputStream output, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        output.writeByte(STATUS_ERROR);
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    private static String describeError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }
}
