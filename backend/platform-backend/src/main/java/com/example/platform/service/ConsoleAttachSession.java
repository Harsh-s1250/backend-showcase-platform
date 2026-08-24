package com.example.platform.service;

import com.example.platform.config.DockerClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * One live "docker attach" against a running console app's own process — never a shell,
 * per PRD §27. Docker attaches to the container's PID 1 (the java app itself, since that's
 * what the Dockerfile's ENTRYPOINT runs); there's no separate shell process involved at all,
 * which is what makes this safe by construction rather than by a permissions check.
 *
 * DELIBERATELY bypasses docker-java's own AttachContainerCmd and talks to the Docker Engine
 * API directly over a raw java.net.Socket. This isn't a stylistic choice — docker-java's
 * attach implementation wraps the underlying transport in Channels.newOutputStream() /
 * Channels.newInputStream(), which synchronize on SelectableChannel#blockingLock(); this is
 * a well-documented upstream bug (docker-java#1768, fixed by docker-java#1769 in 3.2.13) that
 * causes stdin writes to sit unsent until the next stdout activity — exactly the symptom
 * observed here (typed commands never reached the app until it happened to print something
 * on its own). The upstream fix (docker-java#1769) only patched the Unix-socket transport
 * path; this project connects over tcp://localhost:2375 (a documented Windows named-pipe
 * workaround — see DockerClientProvider), a different code path the fix didn't cover.
 * A plain blocking Socket has no such NIO-channel locking issue, and this is exactly the
 * protocol the real `docker attach` CLI itself speaks (Docker Engine API "attach" endpoint,
 * hijacked HTTP connection, 8-byte multiplexed stdout/stderr framing since containers here
 * are created with tty=false — see RunService).
 */
public class ConsoleAttachSession implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAttachSession.class);

    private final Socket socket;
    private final OutputStream rawOut;
    private volatile boolean closed = false;

    public ConsoleAttachSession(String containerId, Consumer<String> onOutput, Runnable onExit) {
        try {
            this.socket = new Socket(DockerClientProvider.DOCKER_HOST, DockerClientProvider.DOCKER_PORT);
            this.rawOut = socket.getOutputStream();

            String request = "POST /containers/" + containerId + "/attach?stream=1&stdin=1&stdout=1&stderr=1&logs=1 HTTP/1.1\r\n" +
                    "Host: " + DockerClientProvider.DOCKER_HOST + "\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Upgrade: tcp\r\n" +
                    "\r\n";
            rawOut.write(request.getBytes(StandardCharsets.US_ASCII));
            rawOut.flush();

            InputStream rawIn = socket.getInputStream();
            String statusLine = readHeadersAndGetStatusLine(rawIn);
            if (statusLine == null || (!statusLine.contains(" 101 ") && !statusLine.contains(" 200 "))) {
                throw new IOException("Docker attach failed, unexpected response: " + statusLine);
            }

            Thread reader = new Thread(() -> readFrames(rawIn, onOutput, onExit), "console-attach-reader-" + containerId);
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not attach to container " + containerId, e);
        }
    }

    private String readHeadersAndGetStatusLine(InputStream in) throws IOException {
        StringBuilder headers = new StringBuilder();
        int state = 0; // tracks progress through the \r\n\r\n that ends the header block
        int b;
        while ((b = in.read()) != -1) {
            headers.append((char) b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) break;
        }
        int firstLineEnd = headers.indexOf("\r\n");
        return firstLineEnd == -1 ? null : headers.substring(0, firstLineEnd);
    }

    /**
     * Docker's stream framing (containers here always run with tty=false — see RunService):
     * each frame is an 8-byte header [STREAM_TYPE, 0, 0, 0, SIZE1..SIZE4 (big-endian uint32)]
     * followed by SIZE bytes of payload. STREAM_TYPE 1=stdout, 2=stderr — both are just
     * forwarded to the terminal here, since the browser doesn't need to distinguish them.
     */
    private void readFrames(InputStream in, Consumer<String> onOutput, Runnable onExit) {
        try {
            byte[] header = new byte[8];
            while (true) {
                if (!readFully(in, header, 8)) break;
                int size = ((header[4] & 0xff) << 24) | ((header[5] & 0xff) << 16)
                        | ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                if (size < 0 || size > 10 * 1024 * 1024) {
                    log.warn("Implausible frame size {} from container attach stream, stopping", size);
                    break;
                }
                byte[] payload = new byte[size];
                if (!readFully(in, payload, size)) break;
                onOutput.accept(new String(payload, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            if (!closed) log.warn("Terminal attach stream ended with an error", e);
        } finally {
            onExit.run();
        }
    }

    private boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n == -1) return false; // stream closed — container exited or attach ended
            total += n;
        }
        return true;
    }

    public void sendInput(String text) {
        if (closed) return;
        try {
            synchronized (rawOut) {
                rawOut.write(text.getBytes(StandardCharsets.UTF_8));
                rawOut.flush();
            }
        } catch (IOException e) {
            log.warn("Failed to write terminal input to container attach socket", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}
