package dev.codex.nativeapp;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Authenticated loopback transport. Never retries a submitted RPC automatically. */
public final class RpcClient {
    public interface Listener {
        void state(String value);
        void event(JSONObject message);
    }
    public interface Reply { void complete(JSONObject result, String error); }
    private final Listener listener;
    private final String token;
    private final int port;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<Long, Reply> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile Socket socket;
    private volatile boolean closed;

    public RpcClient(String token, int port, Listener listener) {
        this.token = token; this.port = port; this.listener = listener;
    }
    public static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
        return value;
    }
    public void start() {
        new Thread(() -> {
            int attempt = 0;
            while (!closed) {
                Socket current = new Socket();
                try {
                    listener.state(attempt == 0 ? "Connecting…" : "Reconnecting…");
                    current.connect(new InetSocketAddress("127.0.0.1", port), 4000);
                    current.setTcpNoDelay(true);
                    current.setSoTimeout(45000);
                    socket = current;
                    write(object("token", token));
                    InputStream input = current.getInputStream();
                    ByteArrayOutputStream frame = new ByteArrayOutputStream();
                    byte[] bytes = new byte[8192]; int size;
                    while (!closed && (size = input.read(bytes)) != -1) {
                        for (int i = 0; i < size; i++) {
                            if (bytes[i] != '\n') {
                                frame.write(bytes[i]);
                                if (frame.size() > 8 * 1024 * 1024) throw new Exception("Reply too large");
                                continue;
                            }
                            JSONObject message = new JSONObject(new String(frame.toByteArray(), StandardCharsets.UTF_8));
                            frame.reset();
                            if (message.has("event")) { attempt = 0; listener.event(message.getJSONObject("event")); }
                            else if (message.has("id")) {
                                Reply callback = pending.remove(message.getLong("id"));
                                JSONObject error = message.optJSONObject("error");
                                if (callback != null) callback.complete(message.optJSONObject("result"),
                                    error == null ? null : error.optString("message", "Request failed"));
                            } else if (message.has("error")) {
                                listener.state(message.optString("error")); closed = true; break;
                            }
                        }
                    }
                } catch (Exception error) {
                    if (!closed) listener.state("Disconnected · " + error.getMessage());
                } finally {
                    try { current.close(); } catch (Exception ignored) {}
                    if (socket == current) socket = null;
                    if (!closed) listener.state("Disconnected · reconnecting…");
                    failPending("Connection lost. Check the conversation before sending again.");
                }
                if (!closed) {
                    try { Thread.sleep(Math.min(15000, 1000L << Math.min(attempt++, 4))); }
                    catch (InterruptedException ignored) { break; }
                }
            }
        }, "codex-reader").start();
        writer.scheduleAtFixedRate(() -> {
            if (!closed && socket != null) call("native/ping", object(), (result, error) -> {});
        }, 10, 10, TimeUnit.SECONDS);
    }
    public void call(String method, JSONObject params, Reply callback) {
        if (closed) { callback.complete(null, "Connection closed"); return; }
        long id = sequence.incrementAndGet();
        pending.put(id, callback);
        writer.execute(() -> {
            try { write(object("id", id, "method", method, "params", params)); }
            catch (Exception error) {
                Reply entry = pending.remove(id);
                if (entry != null) entry.complete(null, error.getMessage());
            }
        });
        writer.schedule(() -> {
            Reply entry = pending.remove(id);
            if (entry != null) entry.complete(null, "Request timed out. Reconnect to check its outcome; it was not resent.");
        }, 125, TimeUnit.SECONDS);
    }
    private synchronized void write(JSONObject message) throws Exception {
        if (socket == null || socket.isClosed()) throw new Exception("Service disconnected");
        socket.getOutputStream().write((message.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private void failPending(String error) {
        for (Long id : pending.keySet()) {
            Reply entry = pending.remove(id);
            if (entry != null) entry.complete(null, error);
        }
    }
    public void close() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        writer.shutdownNow(); failPending("Connection closed");
    }
}
