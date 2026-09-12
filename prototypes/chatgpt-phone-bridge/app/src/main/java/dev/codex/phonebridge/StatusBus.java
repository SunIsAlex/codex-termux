package dev.codex.phonebridge;

import java.util.concurrent.CopyOnWriteArrayList;

public final class StatusBus {
    public interface Listener {
        void onStatus(String status);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String current = "准备就绪";

    private StatusBus() {}

    public static void add(Listener listener) {
        LISTENERS.addIfAbsent(listener);
        listener.onStatus(current);
    }

    public static void remove(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void post(String status) {
        current = status;
        for (Listener listener : LISTENERS) {
            listener.onStatus(status);
        }
    }
}
