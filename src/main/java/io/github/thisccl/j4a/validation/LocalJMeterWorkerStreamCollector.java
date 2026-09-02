package io.github.thisccl.j4a.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

final class LocalJMeterWorkerStreamCollector implements Runnable {
    private static final int OUTPUT_LIMIT = 64 * 1024;

    private final InputStream input;
    private final Object generationLock = new Object();
    private final Thread thread;
    private Capture activeCapture = new Capture();

    private LocalJMeterWorkerStreamCollector(InputStream input) {
        this.input = input;
        this.thread = new Thread(this, "local-jmeter-worker-stream");
        this.thread.setDaemon(true);
    }

    static LocalJMeterWorkerStreamCollector start(InputStream input) {
        LocalJMeterWorkerStreamCollector collector = new LocalJMeterWorkerStreamCollector(input);
        collector.thread.start();
        return collector;
    }

    @Override
    public void run() {
        try {
            BoundedLineReader reader = new BoundedLineReader(input, OUTPUT_LIMIT);
            Line line;
            while ((line = reader.readLine()) != null) {
                appendLine(line);
            }
        } catch (IOException ignored) {
        }
    }

    String text() {
        return text(activeCapture(), "stream");
    }

    String text(Capture capture, String streamName) {
        try {
            thread.join(1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return capture.text(streamName);
    }

    Capture beginGeneration() {
        synchronized (generationLock) {
            activeCapture.retire();
            activeCapture = new Capture();
            return activeCapture;
        }
    }

    boolean contains(String text) {
        return activeCapture().text("stream").contains(text);
    }

    private Capture activeCapture() {
        synchronized (generationLock) {
            return activeCapture;
        }
    }

    private void appendLine(Line line) {
        synchronized (generationLock) {
            if (line.dropped()) {
                activeCapture.recordDropped(line.droppedBytes());
            } else {
                activeCapture.append(line.bytes);
            }
        }
    }

    static final class BoundedLineReader {
        private static final int READ_BUFFER_SIZE = 8192;

        private final InputStream input;
        private final byte[] lineBytes;
        private final byte[] readBuffer = new byte[READ_BUFFER_SIZE];
        private int readIndex;
        private int readLength;

        BoundedLineReader(InputStream input, int lineLimit) {
            if (lineLimit < 1) {
                throw new IllegalArgumentException("lineLimit must be positive");
            }
            this.input = input;
            this.lineBytes = new byte[lineLimit];
        }

        Line readLine() throws IOException {
            int retainedBytes = 0;
            long wireBytes = 0L;
            int value;
            while ((value = readByte()) != -1) {
                wireBytes++;
                if (retainedBytes < lineBytes.length) {
                    lineBytes[retainedBytes++] = (byte) value;
                }
                if (value == '\n') {
                    break;
                }
            }
            if (wireBytes == 0L) {
                return null;
            }
            if (wireBytes > lineBytes.length) {
                return Line.dropped(wireBytes);
            }
            return Line.retained(Arrays.copyOf(lineBytes, retainedBytes));
        }

        private int readByte() throws IOException {
            if (readIndex >= readLength) {
                readLength = input.read(readBuffer);
                readIndex = 0;
                if (readLength == -1) {
                    return -1;
                }
            }
            return readBuffer[readIndex++] & 0xff;
        }
    }

    static final class Line {
        private final byte[] bytes;
        private final long droppedBytes;

        private Line(byte[] bytes, long droppedBytes) {
            this.bytes = bytes;
            this.droppedBytes = droppedBytes;
        }

        private static Line retained(byte[] bytes) {
            return new Line(bytes, 0L);
        }

        private static Line dropped(long droppedBytes) {
            return new Line(new byte[0], droppedBytes);
        }

        boolean dropped() {
            return droppedBytes > 0L;
        }

        long droppedBytes() {
            return droppedBytes;
        }

        int retainedByteCount() {
            return bytes.length;
        }

        byte[] bytes() {
            return bytes;
        }

        String text() {
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\n') {
                length--;
            }
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }
    }

    static final class Capture {
        private final ArrayDeque<byte[]> lines = new ArrayDeque<byte[]>();
        private int retainedBytes;
        private long droppedLines;
        private long droppedBytes;
        private boolean retired;

        private synchronized void append(byte[] line) {
            if (retired) {
                return;
            }
            lines.addLast(line);
            retainedBytes += line.length;
            trimBody();
        }

        private synchronized void recordDropped(long lineBytes) {
            if (retired) {
                return;
            }
            droppedLines++;
            droppedBytes += lineBytes;
        }

        private synchronized void retire() {
            retired = true;
        }

        private synchronized String text(String streamName) {
            LocalJMeterSharedWorkers.Truncation truncation = new LocalJMeterSharedWorkers.Truncation(
                    droppedLines, droppedBytes);
            if (!truncation.truncated()) {
                return body();
            }
            String marker = truncation.marker(streamName);
            while (marker.getBytes(StandardCharsets.UTF_8).length + retainedBytes > OUTPUT_LIMIT && !lines.isEmpty()) {
                recordDropped(lines.removeFirst());
                truncation = new LocalJMeterSharedWorkers.Truncation(droppedLines, droppedBytes);
                marker = truncation.marker(streamName);
            }
            return marker + body();
        }

        private void trimBody() {
            while (retainedBytes > OUTPUT_LIMIT && !lines.isEmpty()) {
                recordDropped(lines.removeFirst());
            }
        }

        private void recordDropped(byte[] line) {
            retainedBytes -= line.length;
            droppedLines++;
            droppedBytes += line.length;
        }

        private String body() {
            ByteArrayOutputStream body = new ByteArrayOutputStream(retainedBytes);
            for (byte[] line : lines) {
                body.write(line, 0, line.length);
            }
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
