package dev.codex.phonebridge;

import java.net.URI;
import java.net.URISyntaxException;

/** Strictly validates links to ordinary ChatGPT conversations. */
public final class DedicatedChatUrl {
    private DedicatedChatUrl() {}

    public static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException error) {
            throw invalidLink();
        }

        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || (!host.equalsIgnoreCase("chatgpt.com")
                        && !host.equalsIgnoreCase("www.chatgpt.com"))) {
            throw new IllegalArgumentException("请输入 chatgpt.com 的对话链接");
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && rawPath.startsWith("/share/")) {
            throw new IllegalArgumentException(
                    "这是只读分享链接；请打开原始对话，从浏览器地址栏复制 /c/ 链接");
        }
        if (uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw invalidLink();
        }

        if (rawPath == null || !rawPath.matches("/c/[A-Za-z0-9_-]{8,128}")) {
            throw invalidLink();
        }
        return "https://chatgpt.com" + rawPath;
    }

    private static IllegalArgumentException invalidLink() {
        return new IllegalArgumentException("链接必须是 https://chatgpt.com/c/对话ID");
    }
}
