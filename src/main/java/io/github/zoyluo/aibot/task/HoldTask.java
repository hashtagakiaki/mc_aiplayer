package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;

public final class HoldTask extends AbstractTask {
    @Override
    public String name() {
        return "hold";
    }

    @Override
    public String describe() {
        return "Holding position";
    }

    @Override
    public double progress() {
        return 0.5D;
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    public WatchdogPolicy watchdogPolicy() {
        return WatchdogPolicy.INTENTIONALLY_ONGOING;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        bot.getActionPack().stopMovement();
    }
}
