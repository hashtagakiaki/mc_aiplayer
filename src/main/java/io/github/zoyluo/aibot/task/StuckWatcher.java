package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StuckWatcher {
    public static final StuckWatcher INSTANCE = new StuckWatcher();

    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();

    private StuckWatcher() {
    }

    public void tick(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            tickBot(server, bot);
        }
    }

    public void tickBot(MinecraftServer server, AIPlayerEntity bot) {
        int now = server.getTicks();
        int window = AIBotConfig.get().watchdog().stuckWindowTicks();
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (active.isEmpty() || active.get().state() != TaskState.RUNNING) {
            samples.remove(bot.getUuid());
            return;
        }

        Task task = active.get();
        Task.WatchdogPolicy policy = task.watchdogPolicy();
        if (policy != Task.WatchdogPolicy.MONITOR_EVIDENCE) {
            samples.remove(bot.getUuid());
            return;
        }

        Sample current = new Sample(task, task.progressEvidence(), now);
        Sample previous = samples.get(bot.getUuid());
        if (previous == null || previous.changed(current)) {
            samples.put(bot.getUuid(), current);
            return;
        }

        if (now - previous.sinceTick() < window) {
            return;
        }

        String reason = "stuck:" + task.name();
        if (TaskManager.INSTANCE.failActive(bot, reason, now).isEmpty()) {
            samples.remove(bot.getUuid());
            return;
        }
        samples.remove(bot.getUuid());
        BotLog.warn(LogCategory.TASK, bot, "task_stuck_aborted",
                "name", task.name(),
                "reason", reason,
                "window_ticks", window,
                "progress", task.progress(),
                "evidence", current.progressEvidence(),
                "pos", bot.getBlockPos().toShortString());
    }

    public boolean reset(AIPlayerEntity bot) {
        return samples.remove(bot.getUuid()) != null;
    }

    public void clearAll() {
        samples.clear();
    }

    private record Sample(Task task, long progressEvidence, int sinceTick) {
        private boolean changed(Sample other) {
            return task != other.task || other.progressEvidence > progressEvidence;
        }
    }
}
