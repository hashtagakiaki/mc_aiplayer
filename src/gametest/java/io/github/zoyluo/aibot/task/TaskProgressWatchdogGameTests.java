package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.util.Set;

/** Regression tests for task-owned progress evidence and shared stuck detection. */
public final class TaskProgressWatchdogGameTests implements FabricGameTest {
    private static final int WATCHDOG_MARGIN_TICKS = 5;
    private static final int EVIDENCE_SETTLE_TICKS = 5;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "taskProgressWatchdogLive", tickLimit = 260)
    public void cosmeticChangesDoNotResetFiniteWait(TestContext context) {
        Fixture fixture = fixture(context, "TaskEvidenceStaticGT");
        AIPlayerEntity bot = fixture.bot();
        OscillatingTask task = new OscillatingTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "task_evidence_static"));
        int[] ticks = {0};

        context.runAtEveryTick(() -> {
            // Position out-and-back, display progress oscillation, and unrelated inventory
            // changes used to keep the old watcher alive despite no task result.
            BlockPos offset = fixture.start().add((ticks[0]++ & 1) == 0 ? 1 : 0, 0, 0);
            bot.teleport(bot.getServerWorld(), offset.getX() + 0.5D, offset.getY(),
                    offset.getZ() + 0.5D, Set.of(), bot.getYaw(), bot.getPitch(), true);
            if ((ticks[0] & 1) == 0) {
                InventoryAction.giveItem(bot, new ItemStack(Items.DIRT));
            } else {
                InventoryAction.removeItems(bot, Items.DIRT, 1);
            }
            StuckWatcher.INSTANCE.tickBot(bot.getServer(), bot);
            if (task.state() == TaskState.FAILED) {
                require(context, task.failureReason().equals("stuck:oscillating"),
                        "cosmetic activity produced wrong failure: " + task.failureReason());
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "taskProgressWatchdogLive", tickLimit = 260)
    public void explicitOngoingTaskIsNotTimedOut(TestContext context) {
        Fixture fixture = fixture(context, "TaskEvidenceOngoingGT");
        AIPlayerEntity bot = fixture.bot();
        HoldTask task = new HoldTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "task_evidence_ongoing"));
        int[] ticks = {0};

        context.runAtEveryTick(() -> {
            StuckWatcher.INSTANCE.tickBot(bot.getServer(), bot);
            if (++ticks[0] < AIBotConfig.get().watchdog().stuckWindowTicks() + WATCHDOG_MARGIN_TICKS) {
                return;
            }
            require(context, task.state() == TaskState.RUNNING,
                    "explicit ongoing task was stopped: " + task.failureReason());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "taskProgressWatchdogLive", tickLimit = 320)
    public void completedBlockBreakIsUsefulEvidence(TestContext context) {
        Fixture fixture = fixture(context, "TaskEvidenceMineGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos target = fixture.start().east(2).up();
        bot.getServerWorld().setBlockState(target, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        MineTask task = new MineTask(Blocks.STONE, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "task_evidence_real_break"));

        int[] airObservedTicks = {-1};
        context.runAtEveryTick(() -> {
            StuckWatcher.INSTANCE.tickBot(bot.getServer(), bot);
            if (!bot.getServerWorld().getBlockState(target).isAir()) {
                return;
            }
            if (airObservedTicks[0] < 0) {
                airObservedTicks[0] = 0;
            } else {
                airObservedTicks[0]++;
            }
            if (task.progressEvidence() == 0 && airObservedTicks[0] < EVIDENCE_SETTLE_TICKS) {
                return;
            }
            require(context, task.progressEvidence() == 1,
                    "verified block destruction should increment evidence once, got " + task.progressEvidence());
            require(context, task.state() == TaskState.RUNNING || task.state() == TaskState.COMPLETED,
                    "real mining progress was stopped: " + task.failureReason());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "taskProgressWatchdogLive", tickLimit = 700)
    public void moveDigTravelRecordsOnlyCompletedBlocks(TestContext context) {
        Fixture fixture = fixture(context, "TaskEvidenceMoveDigGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos goal = start.east(3);
        // Box in the route and close the divider so path failure selects MoveTask's real DigNav fallback.
        for (int dx = -1; dx <= 4; dx++) {
            for (int dz = -1; dz <= 3; dz++) {
                bot.getServerWorld().setBlockState(start.add(dx, -1, dz),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                bot.getServerWorld().setBlockState(start.add(dx, 3, dz),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                if (dx == -1 || dx == 4 || dz == -1 || dz == 3) {
                    for (int dy = 0; dy < 3; dy++) {
                        bot.getServerWorld().setBlockState(start.add(dx, dy, dz),
                                Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                    }
                }
            }
        }
        for (int dz = -1; dz <= 3; dz++) {
            for (int dx : new int[]{1, 2}) {
                for (int dy = 0; dy < 3; dy++) {
                    bot.getServerWorld().setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        MoveTask task = new MoveTask(start, goal);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "task_evidence_move_dig"));

        context.runAtEveryTick(() -> {
            StuckWatcher.INSTANCE.tickBot(bot.getServer(), bot);
            if (task.state() == TaskState.FAILED) {
                context.throwGameTestException("MoveTask tunnel failed: " + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getServerWorld().getBlockState(start.add(1, 0, 0)).isAir()
                            || bot.getServerWorld().getBlockState(start.add(1, 1, 0)).isAir(),
                    "MoveTask completed without physically clearing the divider");
            require(context, bot.getBlockPos().isWithinDistance(goal, 2.0D),
                    "MoveTask completed outside its target area: " + bot.getBlockPos());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "taskProgressWatchdogLive", tickLimit = 270)
    public void observedImmatureCropWaitIsNotSharedStuck(TestContext context) {
        Fixture fixture = fixture(context, "TaskEvidenceFarmWaitGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos soil = fixture.start().east();
        bot.getServerWorld().setBlockState(soil, Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(soil.up(), Blocks.WHEAT.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS));
        int oldRandomTickSpeed = bot.getServerWorld().getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);
        bot.getServerWorld().getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(0, bot.getServer());
        FarmTask task = new FarmTask(soil, 1, Items.WHEAT_SEEDS, Blocks.WHEAT,
                false, false, Items.WHEAT, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "task_evidence_farm_wait"));
        int[] waitingTicks = {0};

        context.runAtEveryTick(() -> {
            StuckWatcher.INSTANCE.tickBot(bot.getServer(), bot);
            if (task.isWaiting()) {
                waitingTicks[0]++;
            }
            if (waitingTicks[0] < AIBotConfig.get().watchdog().stuckWindowTicks() + WATCHDOG_MARGIN_TICKS) {
                return;
            }
            require(context, task.watchdogPolicy() == Task.WatchdogPolicy.TASK_MANAGED,
                    "observed crop maturity wait lost its task-owned quota deadline");
            require(context, task.state() == TaskState.RUNNING,
                    "legitimate crop wait was stopped: " + task.failureReason());
            require(context, bot.getServerWorld().getBlockState(soil.up()).get(CropBlock.AGE) == 0,
                    "crop fixture unexpectedly grew during the wait test");
            bot.getServerWorld().getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(oldRandomTickSpeed, bot.getServer());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "taskProgressWatchdogLive", tickLimit = 500)
    public void furnaceSmeltingWaitIsTaskManaged(TestContext context) {
        Fixture fixture = fixture(context, "TaskEvidenceSmeltWaitGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos furnacePos = fixture.start().east();
        bot.getServerWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        SmeltTask task = new SmeltTask(Items.RAW_IRON, Items.IRON_INGOT, 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "task_evidence_smelt_wait"));
        int[] waitingTicks = {0};

        context.runAtEveryTick(() -> {
            StuckWatcher.INSTANCE.tickBot(bot.getServer(), bot);
            if (task.isWaiting() && task.describe().contains("phase=SMELTING")) {
                waitingTicks[0]++;
            }
            if (waitingTicks[0] < AIBotConfig.get().watchdog().stuckWindowTicks() + WATCHDOG_MARGIN_TICKS) {
                return;
            }
            require(context, task.watchdogPolicy() == Task.WatchdogPolicy.TASK_MANAGED,
                    "smelting wait did not retain its target-count deadline");
            require(context, task.state() == TaskState.RUNNING,
                    "legitimate smelting wait was stopped: " + task.failureReason());
            require(context, task.describe().contains("phase=SMELTING"),
                    "fixture did not exercise the actual SMELTING phase: " + task.describe());
            require(context, bot.getServerWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity,
                    "smelting fixture lost its furnace");
            finish(context, fixture);
        });
    }

    private static Fixture fixture(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(world.getServer(), name, world,
                        Vec3d.ofBottomCenter(start), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return new Fixture(bot, start, name);
    }

    private static void finish(TestContext context, Fixture fixture) {
        StuckWatcher.INSTANCE.reset(fixture.bot());
        TaskManager.INSTANCE.cancelIntentTasks(fixture.bot(), "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }

    private static final class OscillatingTask extends AbstractTask {
        @Override public String name() { return "oscillating"; }
        @Override public String describe() { return "oscillating"; }
        @Override public double progress() { return elapsed % 2; }
        @Override public boolean isWaiting() { return true; }
        @Override protected void onStart(AIPlayerEntity bot) { }
        @Override protected void onTick(AIPlayerEntity bot) { }
    }
}
