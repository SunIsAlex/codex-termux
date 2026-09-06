use super::*;
use crate::chatwidget::tests::make_chatwidget_manual_with_sender;
use codex_protocol::plan_tool::PlanItemArg;
use codex_protocol::plan_tool::StepStatus;
use codex_protocol::plan_tool::UpdatePlanArgs;
use pretty_assertions::assert_eq;

#[tokio::test]
async fn approval_overrides_running_status_without_terminal_title() {
    let (mut chat, _sender, _events, _operations) = make_chatwidget_manual_with_sender().await;
    chat.config.tui_terminal_title = Some(Vec::new());
    chat.on_task_started();
    chat.on_exec_approval_request(
        "request".to_string(),
        crate::approval_events::ExecApprovalRequestEvent {
            kind: Default::default(),
            call_id: "call".to_string(),
            approval_id: Some("approval".to_string()),
            turn_id: "turn".to_string(),
            environment_id: None,
            command: vec!["echo".to_string(), "hello".to_string()],
            cwd: chat.config.cwd.clone(),
            reason: None,
            network_approval_context: None,
            proposed_execpolicy_amendment: None,
            proposed_network_policy_amendments: None,
            additional_permissions: None,
            available_decisions: None,
        },
    );
    assert_eq!(
        chat.termux_progress(),
        Progress {
            activity: Activity::NeedsInput,
            title: chat.termux_progress().title,
            status: "Waiting for your input".to_string(),
            tasks: None,
        }
    );
}

#[tokio::test]
async fn progress_follows_turn_and_plan_with_title_and_animation_disabled() {
    let (mut chat, _sender, _events, _operations) = make_chatwidget_manual_with_sender().await;
    chat.config.tui_terminal_title = Some(Vec::new());
    chat.config.animations = false;
    chat.on_task_started();
    chat.set_status_header("Running tests".to_string());
    chat.on_plan_update(UpdatePlanArgs {
        explanation: None,
        plan: vec![
            PlanItemArg {
                step: "Implement".to_string(),
                status: StepStatus::Completed,
            },
            PlanItemArg {
                step: "Test".to_string(),
                status: StepStatus::InProgress,
            },
        ],
    });
    let title = chat.termux_progress().title;
    assert_eq!(
        chat.termux_progress(),
        Progress {
            activity: Activity::Running,
            title: title.clone(),
            status: "Running tests".to_string(),
            tasks: Some((1, 2)),
        }
    );
    chat.on_task_complete(
        /*last_agent_message*/ None, /*duration_ms*/ None, /*from_replay*/ false,
    );
    assert_eq!(
        chat.termux_progress(),
        Progress {
            activity: Activity::Idle,
            title: title.clone(),
            status: "Ready".to_string(),
            tasks: Some((1, 2)),
        }
    );
    chat.on_task_started();
    chat.set_status_header("Thinking".to_string());
    assert_eq!(
        chat.termux_progress(),
        Progress {
            activity: Activity::Running,
            title,
            status: "Thinking".to_string(),
            tasks: None,
        }
    );
}
