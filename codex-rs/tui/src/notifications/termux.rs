//! Best-effort Android progress notifications. A watch channel bounds queued
//! work to the latest state; the terminal never waits for Termux:API.

use std::io;
use std::path::PathBuf;
use std::process::Stdio;
use std::time::Duration;

use tokio::process::Command;
use tokio::sync::watch;
use tokio::task::JoinHandle;

const UPDATE_INTERVAL: Duration = Duration::from_secs(1);
const COMMAND_TIMEOUT: Duration = Duration::from_secs(2);

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Activity {
    Running,
    NeedsInput,
    Idle,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Progress {
    pub(crate) activity: Activity,
    pub(crate) title: String,
    pub(crate) status: String,
    pub(crate) tasks: Option<(usize, usize)>,
}

impl Progress {
    fn args(&self, id: &str) -> Vec<String> {
        let mut content = self.status.clone();
        if let Some((completed, total)) = self.tasks
            && total > 0
        {
            content.push_str(&format!(" · Tasks {completed}/{total}"));
        }
        let mut args: Vec<String> = [
            "--id",
            id,
            "--title",
            &display_text(&self.title),
            "--content",
            &display_text(&content),
            "--priority",
            "low",
            "--alert-once",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect();
        if self.activity == Activity::Running {
            args.push("--ongoing".to_string());
        }
        args
    }
}

pub(crate) struct TermuxProgress {
    sender: watch::Sender<Option<Progress>>,
    worker: JoinHandle<()>,
}

impl TermuxProgress {
    pub(crate) fn from_env() -> Option<Self> {
        if !cfg!(target_os = "android")
            || std::env::var_os("TERMUX_VERSION").is_none()
            || std::env::var("CODEX_TERMUX_PROGRESS").is_ok_and(|value| value == "0")
        {
            return None;
        }
        let prefix = std::env::var_os("PREFIX")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("/data/data/com.termux/files/usr"));
        let notify = prefix.join("bin/termux-notification");
        let remove = prefix.join("bin/termux-notification-remove");
        if !notify.is_file() || !remove.is_file() {
            tracing::debug!("Termux progress needs the termux-api package");
            return None;
        }
        Some(Self::start(notify, remove))
    }

    fn start(notify: PathBuf, remove: PathBuf) -> Self {
        let (sender, mut receiver) = watch::channel::<Option<Progress>>(None);
        // One notification per interactive process, including when switching threads.
        let id = format!("codex-progress-{}", std::process::id());
        let worker = tokio::spawn(async move {
            let mut last = None;
            let mut attempted = false;
            while receiver.changed().await.is_ok() {
                let progress = receiver.borrow_and_update().clone();
                let Some(progress) = progress else {
                    continue;
                };
                if last.as_ref() == Some(&progress)
                    || (last.is_none() && progress.activity == Activity::Idle)
                {
                    continue;
                }
                attempted = true;
                if let Err(error) = run_command(&notify, &progress.args(&id)).await {
                    tracing::debug!(%error, "disabling unavailable Termux progress notifications");
                    break;
                }
                last = Some(progress);
                // Intermediate updates are replaced, including during this delay.
                // A final idle state is still delivered even if no more events arrive.
                tokio::time::sleep(UPDATE_INTERVAL).await;
            }
            if attempted {
                let _ = run_command(&remove, &[id]).await;
            }
        });
        Self { sender, worker }
    }

    pub(crate) fn update(&self, progress: Progress) {
        self.sender.send_if_modified(|current| {
            if current.as_ref() == Some(&progress) {
                return false;
            }
            *current = Some(progress);
            true
        });
    }

    pub(crate) async fn shutdown(self) {
        drop(self.sender);
        let _ = self.worker.await;
    }
}

fn display_text(text: &str) -> String {
    text.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .filter(|ch| !ch.is_control())
        .take(240)
        .collect()
}

async fn run_command(program: &PathBuf, args: &[String]) -> io::Result<()> {
    let mut command = Command::new(program);
    command
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    // Termux scripts spawn a helper process. Isolate the whole group so a hung
    // Android API call cannot leave that helper behind after its shell is killed.
    #[cfg(unix)]
    command.process_group(0);
    let mut child = command.spawn()?;
    match tokio::time::timeout(COMMAND_TIMEOUT, child.wait()).await {
        Ok(Ok(status)) if status.success() => Ok(()),
        Ok(Ok(status)) => Err(io::Error::other(format!(
            "notification command exited {status}"
        ))),
        Ok(Err(error)) => Err(error),
        Err(_) => {
            #[cfg(unix)]
            if let Some(pid) = child.id() {
                // SAFETY: the child is still owned and unreaped, and process_group
                // above assigned a dedicated group with the child's PID.
                unsafe { libc::kill(-(pid as i32), libc::SIGKILL) };
            }
            let _ = child.kill().await;
            Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "Termux:API timed out",
            ))
        }
    }
}

#[cfg(test)]
#[path = "termux_tests.rs"]
mod tests;
