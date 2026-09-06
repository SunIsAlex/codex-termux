use super::*;
use pretty_assertions::assert_eq;

fn progress(activity: Activity, status: &str) -> Progress {
    Progress {
        activity,
        title: "Codex · demo".to_string(),
        status: status.to_string(),
        tasks: Some((2, 5)),
    }
}

#[test]
fn notification_lifecycle_snapshot() {
    let snapshots = [
        progress(Activity::Running, "Thinking"),
        progress(Activity::Running, "Running tests"),
        progress(Activity::NeedsInput, "Waiting for your input"),
        progress(Activity::Idle, "Ready"),
    ]
    .map(|state| state.args("test-session").join("\n"))
    .join("\n---\n");
    insta::assert_snapshot!(snapshots, @r"
    --id
    test-session
    --title
    Codex · demo
    --content
    Thinking · Tasks 2/5
    --priority
    low
    --alert-once
    --ongoing
    ---
    --id
    test-session
    --title
    Codex · demo
    --content
    Running tests · Tasks 2/5
    --priority
    low
    --alert-once
    --ongoing
    ---
    --id
    test-session
    --title
    Codex · demo
    --content
    Waiting for your input · Tasks 2/5
    --priority
    low
    --alert-once
    ---
    --id
    test-session
    --title
    Codex · demo
    --content
    Ready · Tasks 2/5
    --priority
    low
    --alert-once
    ");
}

#[test]
fn display_is_bounded_and_unknown_plan_is_omitted() {
    assert_eq!(display_text(" \n思考\t中\0 "), "思考 中");
    assert_eq!(display_text(&"思".repeat(300)), "思".repeat(240));
    let mut state = progress(Activity::Running, "Working");
    state.tasks = None;
    let without_plan = state.args("session");
    state.tasks = Some((0, 0));
    assert_eq!(state.args("session"), without_plan);
}

#[cfg(unix)]
fn script(directory: &std::path::Path, name: &str, body: &str) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let path = directory.join(name);
    let shell = if cfg!(target_os = "android") {
        "/data/data/com.termux/files/usr/bin/sh"
    } else {
        "/bin/sh"
    };
    std::fs::write(&path, format!("#!{shell}\n{body}\n")).unwrap();
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700)).unwrap();
    path
}

#[cfg(unix)]
async fn wait_for_lines(path: &std::path::Path, count: usize) -> String {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let output = std::fs::read_to_string(path).unwrap_or_default();
            if output.lines().count() >= count {
                return output;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("notification worker made progress")
}

#[cfg(unix)]
#[tokio::test]
async fn worker_coalesces_updates_delivers_idle_and_cleans_up() {
    let directory = tempfile::tempdir().unwrap();
    // Resolve output relative to the script, without depending on the test cwd.
    let notify = script(
        directory.path(),
        "notify",
        "printf '%s\\n' \"$6\" >> \"$0.log\"",
    );
    let remove = script(
        directory.path(),
        "remove",
        "printf '%s\\n' \"$1\" >> \"$0.log\"",
    );
    let log = directory.path().join("notify.log");
    let notifier = TermuxProgress::start(notify, remove);
    notifier.update(progress(Activity::Idle, "Ready"));
    tokio::time::sleep(Duration::from_millis(30)).await;
    assert!(!log.exists());
    let running = progress(Activity::Running, "Thinking");
    notifier.update(running.clone());
    wait_for_lines(&log, 1).await;
    notifier.update(running);
    notifier.update(progress(Activity::Running, "Transient"));
    notifier.update(progress(Activity::NeedsInput, "Waiting for your input"));
    wait_for_lines(&log, 2).await;
    notifier.update(progress(Activity::Idle, "Ready"));
    assert_eq!(
        wait_for_lines(&log, 3).await,
        "Thinking · Tasks 2/5\nWaiting for your input · Tasks 2/5\nReady · Tasks 2/5\n"
    );
    notifier.shutdown().await;
    assert_eq!(
        std::fs::read_to_string(directory.path().join("remove.log")).unwrap(),
        format!("codex-progress-{}\n", std::process::id())
    );
}

#[cfg(unix)]
#[tokio::test]
async fn command_passes_model_text_literally() {
    let directory = tempfile::tempdir().unwrap();
    let notify = script(
        directory.path(),
        "notify",
        "printf '%s\\n' \"$6\" > \"$0.log\"",
    );
    let state = progress(Activity::Running, "$(exit 99); `exit 88` 'quoted'");
    run_command(&notify, &state.args("session")).await.unwrap();
    assert_eq!(
        std::fs::read_to_string(directory.path().join("notify.log")).unwrap(),
        "$(exit 99); `exit 88` 'quoted' · Tasks 2/5\n"
    );
}

#[cfg(unix)]
#[tokio::test]
async fn missing_failed_and_hung_helpers_do_not_block() {
    let directory = tempfile::tempdir().unwrap();
    let missing = directory.path().join("missing");
    assert_eq!(
        run_command(&missing, &[]).await.unwrap_err().kind(),
        io::ErrorKind::NotFound
    );
    let failed = script(directory.path(), "failed", "exit 1");
    assert_eq!(
        run_command(&failed, &[]).await.unwrap_err().kind(),
        io::ErrorKind::Other
    );
    let hung = script(directory.path(), "hung", "sleep 30");
    assert_eq!(
        run_command(&hung, &[]).await.unwrap_err().kind(),
        io::ErrorKind::TimedOut
    );
}
