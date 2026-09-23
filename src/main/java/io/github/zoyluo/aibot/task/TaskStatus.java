package io.github.zoyluo.aibot.task;

public record TaskStatus(
        String name,
        String description,
        TaskState state,
        double progress,
        String failureReason,
        int elapsedTicks
) {
    public static TaskStatus idle() {
        return new TaskStatus("idle", "No active task", TaskState.COMPLETED, 1.0D, "", 0);
    }

    public static TaskStatus from(Task task) {
        return new TaskStatus(task.name(), task.describe(), task.state(), task.progress(), task.failureReason(), task.elapsedTicks());
    }

    /**
     * Builds the terminal status for a manager-settled failure. The manager owns this
     * reason because it is also the reason delivered to the goal recovery path.
     */
    public static TaskStatus failed(Task task, String reason) {
        return new TaskStatus(task.name(), task.describe(), TaskState.FAILED,
                task.progress(), reason, task.elapsedTicks());
    }
}
