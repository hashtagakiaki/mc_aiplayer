package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;

public interface Task {
    /** Who owns the timeout while this task is active. */
    enum WatchdogPolicy {
        /** The shared watchdog checks for changes to useful task evidence. */
        MONITOR_EVIDENCE,
        /** The task has its own bounded no-progress or elapsed-time guard. */
        TASK_MANAGED,
        /** The user explicitly requested ongoing work with no completion deadline. */
        INTENTIONALLY_ONGOING
    }

    String name();

    String describe();

    TaskState state();

    String failureReason();

    void start(AIPlayerEntity bot);

    void tick(AIPlayerEntity bot);

    void pause(AIPlayerEntity bot);

    void resume(AIPlayerEntity bot);

    void abort(AIPlayerEntity bot);

    void cancel(AIPlayerEntity bot, String reason);

    double progress();

    /**
     * Monotonic count of task-owned, verified useful outcomes. Display progress, elapsed time,
     * position and unrelated inventory changes must not be reported here.
     */
    default long progressEvidence() {
        return 0L;
    }

    /**
     * Timeout ownership is explicit: waiting is only an activity hint and never suspends the
     * shared watchdog. Tasks with verified local bounds and user-directed ongoing tasks must
     * override this policy.
     */
    default WatchdogPolicy watchdogPolicy() {
        return WatchdogPolicy.MONITOR_EVIDENCE;
    }

    int elapsedTicks();

    default boolean isWaiting() {
        return false;
    }
}
