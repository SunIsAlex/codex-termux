package dev.codex.phonebridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Model-provided fields used to construct an Android activity Intent. */
public final class IntentSpec {
    private final String action;
    private final String data;
    private final String packageName;
    private final String component;
    private final Map<String, Object> extras;

    public IntentSpec(
            String action,
            String data,
            String packageName,
            String component,
            Map<String, Object> extras) {
        this.action = action;
        this.data = data;
        this.packageName = packageName;
        this.component = component;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    public String action() {
        return action;
    }

    public String data() {
        return data;
    }

    public String packageName() {
        return packageName;
    }

    public String component() {
        return component;
    }

    public Map<String, Object> extras() {
        return extras;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IntentSpec)) {
            return false;
        }
        IntentSpec spec = (IntentSpec) other;
        return Objects.equals(action, spec.action)
                && Objects.equals(data, spec.data)
                && Objects.equals(packageName, spec.packageName)
                && Objects.equals(component, spec.component)
                && extras.equals(spec.extras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, data, packageName, component, extras);
    }

    @Override
    public String toString() {
        return "IntentSpec{" + action + ", " + data + ", " + packageName + ", " + component + "}";
    }
}
