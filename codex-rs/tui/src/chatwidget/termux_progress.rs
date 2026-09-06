//! Progress for the Android notification follows the displayed conversation.

use super::ChatWidget;
use crate::notifications::termux::Activity;
use crate::notifications::termux::Progress;

impl ChatWidget {
    pub(crate) fn termux_progress(&self) -> Progress {
        let activity = if self.bottom_pane.terminal_title_requires_action() {
            Activity::NeedsInput
        } else if self.bottom_pane.is_task_running() {
            Activity::Running
        } else {
            Activity::Idle
        };
        let status = match activity {
            Activity::NeedsInput => "Waiting for your input".to_string(),
            Activity::Idle => "Ready".to_string(),
            Activity::Running => self.status_state.current_status.header.clone(),
        };
        let cwd = self
            .current_cwd
            .as_deref()
            .unwrap_or(self.config.cwd.as_path());
        let project = cwd.file_name().unwrap_or(cwd.as_os_str()).to_string_lossy();
        Progress {
            activity,
            title: format!("Codex · {project}"),
            status,
            // The transcript retains the previous plan between turns. Only show
            // progress once this turn has supplied a plan of its own.
            tasks: self
                .transcript
                .saw_plan_update_this_turn
                .then_some(self.transcript.last_plan_progress)
                .flatten(),
        }
    }
}

#[cfg(test)]
#[path = "termux_progress_tests.rs"]
mod tests;
