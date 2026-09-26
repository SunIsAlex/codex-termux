package dev.codex.nativeapp;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static dev.codex.nativeapp.RpcClient.object;

/** Exercises the exact Android transport on the host JVM, including interrupted writes. */
public final class RpcClientTest {
    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<RpcClient> ref = new AtomicReference<>();
        Thread backend = new Thread(() -> {
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(4000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        assert new JSONObject(reader.readLine()).getString("token").equals("test-token");
                        byte[] ready = (object("event", object("method", "native/ready")).toString() + "\n").getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(ready);
                        JSONObject call = new JSONObject(reader.readLine());
                        assert call.getString("method").equals("model/list") : "An old mutation was replayed";
                        if (attempt == 0) continue; // Drop an in-flight RPC; it must fail once, not replay.
                        byte[] reply = (object("id", call.getLong("id"), "result", object("text", "你好 🌱")).toString() + "\n").getBytes(StandardCharsets.UTF_8);
                        for (byte b : reply) socket.getOutputStream().write(b);
                        socket.getOutputStream().flush();
                        done.await(4, TimeUnit.SECONDS);
                    }
                }
            } catch (Throwable error) { failure.set(error); done.countDown(); }
        });
        backend.start();
        AtomicInteger failedRequests = new AtomicInteger();
        RpcClient client = new RpcClient("test-token", server.getLocalPort(), new RpcClient.Listener() {
            public void state(String text) {}
            public void event(JSONObject message) {
                int attempt = connections.incrementAndGet();
                ref.get().call("model/list", object(), (result, error) -> {
                    try {
                        if (attempt == 1) { assert error != null; failedRequests.incrementAndGet(); }
                        else { assert error == null : error; assert result.getString("text").equals("你好 🌱"); done.countDown(); }
                    } catch (Throwable exception) { failure.set(exception); done.countDown(); }
                });
            }
        });
        ref.set(client); client.start();
        try {
            assert done.await(12, TimeUnit.SECONDS) : "Transport did not reconnect";
            if (failure.get() != null) throw new AssertionError(failure.get());
            assert failedRequests.get() == 1;
            assert connections.get() == 2;
        } finally { client.close(); server.close(); backend.join(2000); }
        System.out.println("PASS: native Java framing, Unicode, failed-call isolation and reconnect");
    }
}
