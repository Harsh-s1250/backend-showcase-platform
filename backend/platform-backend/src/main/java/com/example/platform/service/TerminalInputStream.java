package com.example.platform.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple blocking InputStream that Docker's attach API reads from continuously, fed
 * byte-by-byte from whatever the browser sends over the WebSocket. Read blocks until more
 * input arrives or the stream is closed (signaled with a -1 sentinel, which is safe since
 * real byte values only ever range 0-255).
 */
class TerminalInputStream extends InputStream {

    private static final Logger log = LoggerFactory.getLogger(TerminalInputStream.class);

    private final BlockingQueue<Integer> buffer = new LinkedBlockingQueue<>();

    void write(String text) {
        log.info("Queueing {} bytes onto the container's stdin stream: {}", text.length(), escapeForLog(text));
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            buffer.offer(b & 0xff);
        }
    }

    private String escapeForLog(String text) {
        return text.replace("\n", "\\n").replace("\r", "\\r");
    }

    void closeInput() {
        buffer.offer(-1);
    }

    @Override
    public int read() throws IOException {
        try {
            return buffer.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
