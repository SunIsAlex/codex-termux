package dev.codex.phonebridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Visible-UI-only bridge for the official ChatGPT Android app. */
public final class ChatGptAccessibilityService extends AccessibilityService {
    public static final String CHATGPT_PACKAGE = "com.openai.chatgpt";
    private static final long RESPONSE_TIMEOUT_MS = 60_000;
    private static final long INVALID_RESPONSE_GRACE_MS = 10_000;
    private static final long RESPONSE_IDLE_MS = 2_000;
    private static final long VOLUME_CHORD_WINDOW_MS = 500;
    private static final int MAX_SUBMIT_ATTEMPTS = 12;
    private static final String PREFERENCES = "controls";
    private static final String PHYSICAL_PTT_ENABLED = "physicalPttEnabled";
    private static volatile ChatGptAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PendingRequest pending;
    private ActionExecutor executor;
    private AudioManager audioManager;
    private SpeechInputController speechInput;
    private SessionConfig sessionConfig;
    private boolean physicalPttHeld;
    private boolean volumeChordActive;
    private boolean volumeUpHeld;
    private boolean volumeDownHeld;
    private long firstVolumeKeyDownAtMs;
    private int volumeBeforeChord = -1;

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean beginSpokenCommand(String command) {
        ChatGptAccessibilityService service = instance;
        return service != null && service.begin(command);
    }

    public static boolean initializeDedicatedChat() {
        ChatGptAccessibilityService service = instance;
        return service != null && service.beginBootstrap();
    }

    public static boolean cancelPendingRequest() {
        ChatGptAccessibilityService service = instance;
        return service != null && service.cancelPending();
    }

    public static boolean startTapToTalk() {
        ChatGptAccessibilityService service = instance;
        if (service == null || service.speechInput == null) {
            return false;
        }
        if (service.pending != null) {
            StatusBus.post("上一条命令仍在等待 ChatGPT 回复");
            return false;
        }
        return service.speechInput.start(false);
    }

    public static boolean isPhysicalPttEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(PHYSICAL_PTT_ENABLED, true);
    }

    public static void setPhysicalPttEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(PHYSICAL_PTT_ENABLED, enabled)
                .apply();
        StatusBus.post(enabled ? "已启用：同时按住音量加减键说话" : "已停用物理按键录音");
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        executor = new ActionExecutor(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        sessionConfig = new SessionConfig(this);
        speechInput = new SpeechInputController(
                this,
                new SpeechInputController.Callback() {
                    @Override
                    public void onStatus(String message) {
                        StatusBus.post(message);
                    }

                    @Override
                    public void onTranscript(String transcript) {
                        StatusBus.post("识别结果：" + transcript);
                        begin(transcript);
                    }
                });
        VoiceFeedback.initialize(this);
        StatusBus.post("无障碍桥已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Polling is intentionally bounded and nonce-based. Events merely allow an early poll.
        if (pending != null && pending.sent && CHATGPT_PACKAGE.contentEquals(event.getPackageName())) {
            handler.removeCallbacks(pollResponse);
            handler.postDelayed(pollResponse, 300);
        }
    }

    @Override
    public void onInterrupt() {
        if (speechInput != null) {
            speechInput.destroy();
        }
        fail("无障碍服务被中断");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (!volumeKey || !isPhysicalPttEnabled(this) || speechInput == null) {
            return super.onKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                if (!volumeUpHeld && !volumeDownHeld) {
                    firstVolumeKeyDownAtMs = event.getEventTime();
                    volumeBeforeChord = audioManager == null
                            ? -1
                            : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                }
                setVolumeKeyHeld(keyCode, true);
                if (!volumeChordActive
                        && volumeUpHeld
                        && volumeDownHeld
                        && event.getEventTime() - firstVolumeKeyDownAtMs
                                <= VOLUME_CHORD_WINDOW_MS) {
                    volumeChordActive = true;
                    restoreVolumeBeforeChord();
                    if (pending != null) {
                        StatusBus.post("上一条命令仍在等待 ChatGPT 回复");
                        physicalPttHeld = false;
                    } else {
                        physicalPttHeld = speechInput.start(true);
                    }
                }
            }
            return volumeChordActive || super.onKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean consume = volumeChordActive;
            setVolumeKeyHeld(keyCode, false);
            if (physicalPttHeld) {
                physicalPttHeld = false;
                speechInput.stop();
            }
            if (!volumeUpHeld && !volumeDownHeld) {
                volumeChordActive = false;
                volumeBeforeChord = -1;
            }
            if (consume) {
                return true;
            }
        }
        return super.onKeyEvent(event);
    }

    private void setVolumeKeyHeld(int keyCode, boolean held) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpHeld = held;
        } else {
            volumeDownHeld = held;
        }
    }

    private void restoreVolumeBeforeChord() {
        if (audioManager != null && volumeBeforeChord >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeChord, 0);
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (speechInput != null) {
            speechInput.destroy();
        }
        physicalPttHeld = false;
        volumeChordActive = false;
        volumeUpHeld = false;
        volumeDownHeld = false;
        pending = null;
        super.onDestroy();
    }

    private synchronized boolean begin(String command) {
        String cleaned = command == null ? "" : command.trim();
        if (cleaned.isEmpty() || cleaned.length() > 500) {
            report("语音命令为空或过长");
            return false;
        }
        if (pending != null) {
            report("上一条命令仍在等待 ChatGPT 回复");
            return false;
        }
        if (sessionConfig.chatUrl().isEmpty()) {
            report("请先在 Phone Bridge 中保存专用对话链接");
            return false;
        }
        if (!sessionConfig.isInitialized()) {
            report("请先发送一次专用对话初始化指令");
            return false;
        }
        String nonce = UUID.randomUUID().toString();
        boolean webSearch = ProtocolPrompt.requiresWebSearch(cleaned);
        boolean directOpen = webSearch && ProtocolPrompt.requiresDirectOpen(cleaned);
        pending = new PendingRequest(
                nonce,
                ProtocolPrompt.command(sessionConfig.sessionId(), nonce, cleaned),
                sessionConfig.chatUrl(),
                false,
                0,
                webSearch,
                directOpen,
                0);
        launchPendingRequest();
        return true;
    }

    private synchronized boolean cancelPending() {
        if (pending == null) {
            report("当前没有等待中的请求");
            return false;
        }
        pending = null;
        handler.removeCallbacks(pollResponse);
        report("已取消当前请求，可以发送下一条指令");
        return true;
    }

    private synchronized boolean beginBootstrap() {
        if (pending != null) {
            report("上一条命令仍在等待 ChatGPT 回复");
            return false;
        }
        String chatUrl = sessionConfig.chatUrl();
        if (chatUrl.isEmpty()) {
            report("请先保存专用对话链接");
            return false;
        }
        String sessionId = sessionConfig.sessionId();
        pending = new PendingRequest(
                sessionId,
                ProtocolPrompt.bootstrap(sessionId),
                chatUrl,
                true,
                0,
                false,
                false,
                0);
        launchPendingRequest();
        return true;
    }

    private void launchPendingRequest() {
        PendingRequest request = pending;
        if (request == null) {
            return;
        }
        StatusBus.post("正在打开 ChatGPT");

        Intent launch = new Intent(Intent.ACTION_VIEW, Uri.parse(request.chatUrl))
                .setPackage(CHATGPT_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (launch.resolveActivity(getPackageManager()) == null) {
            fail("未安装官方 ChatGPT App");
            return;
        }
        startActivity(launch);
        handler.postDelayed(() -> trySubmit(0), 1_200);
    }

    private void trySubmit(int attempt) {
        PendingRequest request = pending;
        if (request == null) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isChatGptRoot(root)) {
            retrySubmit(attempt, "等待 ChatGPT 界面");
            return;
        }
        AccessibilityNodeInfo editor = findEditor(root);
        if (editor == null) {
            retrySubmit(attempt, "等待 ChatGPT 输入框");
            return;
        }

        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.prompt);
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) {
            retrySubmit(attempt, "ChatGPT 输入框拒绝文本");
            return;
        }

        if (request.webSearch && !request.searchPrepared) {
            request.searchPrepared = true;
            handler.postDelayed(() -> enableWebSearchThenSubmit(attempt), 250);
        } else {
            handler.postDelayed(() -> clickSendOrRetry(attempt), 250);
        }
    }

    private void enableWebSearchThenSubmit(int attempt) {
        PendingRequest request = pending;
        if (request == null) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isWebSearchEnabled(root)) {
            StatusBus.post("ChatGPT 网页搜索已启用");
            clickSendOrRetry(attempt);
            return;
        }
        AccessibilityNodeInfo search = findWebSearchButton(root);
        if (search != null && search.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            StatusBus.post("已启用 ChatGPT 网页搜索");
            handler.postDelayed(() -> clickSendOrRetry(attempt), 350);
            return;
        }
        StatusBus.post("未找到网页搜索开关，依赖 ChatGPT 自动搜索");
        clickSendOrRetry(attempt);
    }

    private void clickSendOrRetry(int attempt) {
        PendingRequest request = pending;
        if (request == null) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo send = findSendButton(root);
        if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed(() -> verifyComposerCleared(attempt), 400);
            return;
        }

        AccessibilityNodeInfo editor = findEditor(root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && editor != null
                && editor.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
            handler.postDelayed(() -> verifyComposerCleared(attempt), 400);
            return;
        }
        retrySubmit(attempt, "没有找到可用的 ChatGPT 发送动作");
    }

    private void verifyComposerCleared(int attempt) {
        PendingRequest request = pending;
        if (request == null) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo editor = findEditor(root);
        String editorText = editor == null ? "" : safe(editor.getText());
        if (editor == null || !editorText.contains(request.nonce)) {
            markSubmitted(request);
            return;
        }
        retrySubmit(attempt, "ChatGPT 没有接受发送动作");
    }

    private void markSubmitted(PendingRequest request) {
        if (pending != request || request.sent) {
            return;
        }
        request.sent = true;
        request.sentAtMs = System.currentTimeMillis();
        if (request.bootstrap) {
            sessionConfig.markInitialized();
            pending = null;
            report("初始化指令已发送；等待 ChatGPT 回复 READY 后即可使用物理按键");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        request.lastVisibleText = root == null ? "" : collectVisibleText(root);
        request.lastVisibleChangeAtMs = request.sentAtMs;
        request.refusalCountAtSubmit = countTerminalRefusals(request.lastVisibleText);
        StatusBus.post("已发送，等待 ChatGPT 回复");
        handler.postDelayed(pollResponse, 700);
    }

    private void retrySubmit(int attempt, String status) {
        if (attempt + 1 >= MAX_SUBMIT_ATTEMPTS) {
            fail("ChatGPT 界面不兼容：" + status);
            return;
        }
        StatusBus.post(status);
        handler.postDelayed(() -> trySubmit(attempt + 1), 500);
    }

    private final Runnable pollResponse = new Runnable() {
        @Override
        public void run() {
            PendingRequest request = pending;
            if (request == null || !request.sent) {
                return;
            }
            if (System.currentTimeMillis() - request.sentAtMs > RESPONSE_TIMEOUT_MS) {
                fail("等待 ChatGPT 回复超时");
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (!isChatGptRoot(root)) {
                handler.postDelayed(this, 750);
                return;
            }
            String visibleText = collectVisibleText(root);
            long now = System.currentTimeMillis();
            if (!visibleText.equals(request.lastVisibleText)) {
                request.lastVisibleText = visibleText;
                request.lastVisibleChangeAtMs = now;
            }
            try {
                PhoneAction action = ActionParser.parse(visibleText, request.nonce);
                executeOnce(request, action);
            } catch (IllegalArgumentException notReady) {
                if (countTerminalRefusals(visibleText) > request.refusalCountAtSubmit) {
                    fail("ChatGPT 拒绝生成可执行动作，请修改指令后重试");
                    return;
                }
                boolean responseIdle = now - request.sentAtMs >= INVALID_RESPONSE_GRACE_MS
                        && now - request.lastVisibleChangeAtMs >= RESPONSE_IDLE_MS;
                if (responseIdle && findSendButton(root) != null) {
                    fail("ChatGPT 回复已结束，但没有返回 PHONE_ACTION；请求已自动取消");
                    return;
                }
                handler.postDelayed(this, 750);
            }
        }
    };

    private synchronized void executeOnce(PendingRequest request, PhoneAction action) {
        if (pending != request) {
            return;
        }
        if (request.directOpen
                && action.kind() != PhoneAction.Kind.LIST_APPS
                && !isConcreteViewIntent(action)) {
            handler.removeCallbacks(pollResponse);
            if (request.correctionDepth >= 1) {
                fail("模型仍未返回可直接打开的具体链接");
                return;
            }
            String nextNonce = UUID.randomUUID().toString();
            pending = new PendingRequest(
                    nextNonce,
                    ProtocolPrompt.directOpenCorrection(sessionConfig.sessionId(), nextNonce),
                    request.chatUrl,
                    false,
                    request.toolDepth,
                    true,
                    true,
                    request.correctionDepth + 1);
            StatusBus.post("模型返回了搜索动作，正在要求具体链接");
            launchPendingRequest();
            return;
        }
        if (action.kind() == PhoneAction.Kind.LIST_APPS) {
            handler.removeCallbacks(pollResponse);
            if (request.toolDepth >= 1) {
                fail("模型重复查询应用列表");
                return;
            }
            String nextNonce = UUID.randomUUID().toString();
            String result;
            try {
                result = executor.listApps(action.text());
            } catch (RuntimeException error) {
                fail("查询应用列表失败");
                return;
            }
            pending = new PendingRequest(
                    nextNonce,
                    ProtocolPrompt.appListResult(
                            sessionConfig.sessionId(), nextNonce, action.text(), result),
                    request.chatUrl,
                    false,
                    request.toolDepth + 1,
                    request.webSearch,
                    request.directOpen,
                    request.correctionDepth);
            StatusBus.post("已查询应用列表，正在返回给 ChatGPT");
            launchPendingRequest();
            return;
        }
        String previousNonce = getSharedPreferences("security", MODE_PRIVATE)
                .getString("lastExecutedNonce", "");
        if (request.nonce.equals(previousNonce)) {
            fail("已拒绝重复动作");
            return;
        }
        getSharedPreferences("security", MODE_PRIVATE)
                .edit()
                .putString("lastExecutedNonce", request.nonce)
                .apply();
        pending = null;
        handler.removeCallbacks(pollResponse);

        if (action.kind() == PhoneAction.Kind.CLARIFY) {
            report(action.text());
            return;
        }
        StatusBus.post("正在执行 " + action.kind().name().toLowerCase(Locale.ROOT));
        executor.execute(action, (success, message) -> {
            AuditLog.record(this, action.kind(), success);
            report(message);
        });
    }

    private static boolean isConcreteViewIntent(PhoneAction action) {
        if (action.kind() != PhoneAction.Kind.START_INTENT || action.intent() == null) {
            return false;
        }
        IntentSpec intent = action.intent();
        if (!Intent.ACTION_VIEW.equals(intent.action()) || intent.data() == null) {
            return false;
        }
        String data = intent.data().trim().toLowerCase(Locale.ROOT);
        return !data.isEmpty()
                && !data.contains("/results")
                && !data.contains("search_query=")
                && !data.contains("/search");
    }

    private AccessibilityNodeInfo findEditor(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence className = node.getClassName();
            boolean editText = className != null && className.toString().endsWith("EditText");
            if (node.isVisibleToUser() && node.isEnabled() && (node.isEditable() || editText)) {
                return node;
            }
            addChildren(node, queue);
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser() && node.isEnabled()) {
                String identity = nodeIdentity(node);
                if (isSendIdentity(identity)) {
                    AccessibilityNodeInfo clickable = clickableAncestor(node, 4);
                    if (clickable != null) {
                        return clickable;
                    }
                }
            }
            addChildren(node, queue);
        }
        return null;
    }

    private AccessibilityNodeInfo findWebSearchButton(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser()
                    && node.isEnabled()
                    && isWebSearchIdentity(nodeIdentity(node))) {
                AccessibilityNodeInfo clickable = clickableAncestor(node, 3);
                if (clickable != null) {
                    return clickable;
                }
            }
            addChildren(node, queue);
        }
        return null;
    }

    private boolean isWebSearchEnabled(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String identity = nodeIdentity(node);
            if ((node.isChecked() || node.isSelected()) && isWebSearchIdentity(identity)) {
                return true;
            }
            if (identity.equals("remove web results")
                    || identity.equals("移除网页搜索")
                    || identity.equals("移除网页结果")) {
                return true;
            }
            addChildren(node, queue);
        }
        return false;
    }

    private static boolean isWebSearchIdentity(String identity) {
        return identity.equals("search the web")
                || identity.equals("web search")
                || identity.equals("search web")
                || identity.equals("搜索网页")
                || identity.equals("网页搜索")
                || identity.equals("搜索网络")
                || identity.contains("web_search");
    }

    private static String nodeIdentity(AccessibilityNodeInfo node) {
        return (safe(node.getText()) + " "
                        + safe(node.getContentDescription()) + " "
                        + safe(node.getViewIdResourceName()))
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean isSendIdentity(String identity) {
        if (identity.contains("feedback")
                || identity.contains("email")
                || identity.contains("invite")
                || identity.contains("code")) {
            return false;
        }
        String normalized = identity.trim().replaceAll("\\s+", " ");
        return normalized.equals("send")
                || normalized.equals("send message")
                || normalized.equals("send input")
                || normalized.equals("send to chat")
                || normalized.equals("submit query")
                || normalized.equals("发送")
                || normalized.equals("发送消息")
                || identity.contains("send_button")
                || identity.contains("submit_button")
                || identity.contains("arrow_send")
                || identity.contains("arrow_up");
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node, int limit) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth <= limit; depth++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private String collectVisibleText(AccessibilityNodeInfo root) {
        Set<String> fragments = new LinkedHashSet<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int totalLength = 0;
        while (!queue.isEmpty() && totalLength < 128_000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser()) {
                totalLength += addFragment(fragments, node.getText());
                totalLength += addFragment(fragments, node.getContentDescription());
            }
            addChildren(node, queue);
        }
        StringBuilder result = new StringBuilder(Math.min(totalLength, 128_000));
        for (String fragment : fragments) {
            if (result.length() + fragment.length() + 1 > 128_000) {
                break;
            }
            result.append(fragment).append('\n');
        }
        return result.toString();
    }

    private static void addChildren(
            AccessibilityNodeInfo node, ArrayDeque<AccessibilityNodeInfo> queue) {
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                queue.addLast(child);
            }
        }
    }

    private static boolean isChatGptRoot(AccessibilityNodeInfo root) {
        return root != null && CHATGPT_PACKAGE.contentEquals(root.getPackageName());
    }

    private static int addFragment(Set<String> fragments, CharSequence value) {
        if (value == null || value.length() == 0) {
            return 0;
        }
        String fragment = value.toString();
        return fragments.add(fragment) ? fragment.length() + 1 : 0;
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static int countTerminalRefusals(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String[] markers = {
            "无法生成符合要求的 start_intent",
            "不能编造 datauri",
            "无法生成规范视频链接",
            "cannot fabricate datauri",
            "unable to generate a valid start_intent"
        };
        int count = 0;
        for (String marker : markers) {
            int offset = 0;
            while ((offset = normalized.indexOf(marker, offset)) >= 0) {
                count++;
                offset += marker.length();
            }
        }
        return count;
    }

    private synchronized void fail(String message) {
        pending = null;
        handler.removeCallbacksAndMessages(null);
        report(message);
    }

    private void report(String message) {
        StatusBus.post(message);
        VoiceFeedback.speak(this, message);
    }

    private static final class PendingRequest {
        private final String nonce;
        private final String prompt;
        private final String chatUrl;
        private final boolean bootstrap;
        private final int toolDepth;
        private final boolean webSearch;
        private final boolean directOpen;
        private final int correctionDepth;
        private boolean sent;
        private boolean searchPrepared;
        private long sentAtMs;
        private String lastVisibleText = "";
        private long lastVisibleChangeAtMs;
        private int refusalCountAtSubmit;

        private PendingRequest(
                String nonce,
                String prompt,
                String chatUrl,
                boolean bootstrap,
                int toolDepth,
                boolean webSearch,
                boolean directOpen,
                int correctionDepth) {
            this.nonce = nonce;
            this.prompt = prompt;
            this.chatUrl = chatUrl;
            this.bootstrap = bootstrap;
            this.toolDepth = toolDepth;
            this.webSearch = webSearch;
            this.directOpen = directOpen;
            this.correctionDepth = correctionDepth;
        }
    }
}
