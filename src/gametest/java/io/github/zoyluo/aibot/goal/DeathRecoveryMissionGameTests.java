package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.brain.DecisionLease;
import io.github.zoyluo.aibot.brain.DecisionSession;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.persist.MissionRuntimeRecord;
import io.github.zoyluo.aibot.persist.MissionRecord;
import io.github.zoyluo.aibot.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.RecoverDropsTask;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

/** Deterministic death suspension coverage for active and queued mining missions. */
public final class DeathRecoveryMissionGameTests implements FabricGameTest {
    private static final int SUSPENDED_ASSERT_TICK = 10;
    private static final int RESUMED_ASSERT_TICK = 115;
    private static final int ACTIVE_COMPLETE_ASSERT_TICK = 135;
    private static final int ALL_COMPLETE_ASSERT_TICK = 155;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void mineOreSurvivesDeathRecoveryAndPreservesQueuedHaveItem(TestContext context) {
        runScenario(
                context,
                "DeathMineGT",
                new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1),
                Items.RAW_IRON,
                new Goal.HaveItem(Items.SWEET_BERRIES, 1),
                Items.SWEET_BERRIES,
                -1);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void haveItemSurvivesDeathRecoveryAndPreservesQueuedMineOre(TestContext context) {
        runScenario(
                context,
                "DeathItemGT",
                new Goal.HaveItem(Items.SWEET_BERRIES, 1),
                Items.SWEET_BERRIES,
                new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1),
                Items.RAW_IRON,
                32);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void missionRetryLedgerSurvivesRestoreAndResetsOnlyAtReplace(TestContext context) {
        String botName = "MissionRetryGT";
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        Goal original = new Goal.HaveItem(Items.SWEET_BERRIES, 1);
        Goal replacement = new Goal.HaveItem(Items.RAW_IRON, 1);
        require(context, GoalExecutor.INSTANCE.submit(bot, original), "original mission submit failed");
        require(context, GoalExecutor.INSTANCE.recordRecoveryAttempt(
                bot, "missing_prerequisite", "gather_berries"), "first attempt not recorded");
        MissionRuntimeRecord beforeRestart = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, beforeRestart.active() != null, "active mission checkpoint missing");
        MissionRecord.RecoveryState saved = MissionRecord.decodeRecoveryState(
                beforeRestart.active().checkpoint()).orElseThrow();
        require(context, saved.attemptsUsed() == 1, "attempt count was not persisted");

        GoalExecutor.INSTANCE.unload(bot);
        GoalExecutor.INSTANCE.restoreRuntime(bot, beforeRestart);
        require(context, GoalExecutor.INSTANCE.remainingRecoveryAttempts(bot) == 2,
                "restart reset the mission retry budget");
        require(context, !GoalExecutor.INSTANCE.recordRecoveryAttempt(
                bot, "missing_prerequisite", "gather_berries"),
                "restart forgot an already tried recovery method");

        require(context, GoalExecutor.INSTANCE.cancelCurrent(bot, "gametest_replace"),
                "mission cancellation failed");
        require(context, GoalExecutor.INSTANCE.captureRuntime(bot).active() == null,
                "cancelled mission remained persistent");
        require(context, GoalExecutor.INSTANCE.submit(bot, replacement), "replacement submit failed");
        require(context, GoalExecutor.INSTANCE.remainingRecoveryAttempts(bot) == 3,
                "replacement inherited the old mission's retry budget");
        require(context, GoalExecutor.INSTANCE.recordRecoveryAttempt(
                bot, "missing_prerequisite", "gather_berries"),
                "replacement incorrectly inherited tried methods");
        GoalExecutor.INSTANCE.cancelAll(bot);
        AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void satisfiedRecoveryProposalResumesOriginalMissionUnderSameIdentity(TestContext context) {
        String botName = "MissionRecoveryGT";
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        Goal original = new Goal.HaveItem(Items.IRON_INGOT, 1);
        Goal alreadySatisfied = new Goal.HaveItem(Items.SWEET_BERRIES, 1);
        InventoryAction.giveItem(bot, new ItemStack(Items.SWEET_BERRIES, 1));
        require(context, GoalExecutor.INSTANCE.submit(bot, original), "original mission submit failed");
        MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
        UUID missionId = UUID.fromString(before.active().missionId());

        require(context, GoalExecutor.INSTANCE.applyMissionRecoveryProposal(
                        bot, missionId, alreadySatisfied, "missing_iron|no_resource_nearby",
                        "no_resource_nearby: unobserved") ,
                "fixed auxiliary proposal was not accepted");
        MissionRuntimeRecord after = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, after.active() != null, "recovery discarded the original mission");
        require(context, after.active().missionId().equals(missionId.toString()),
                "recovery allocated a new mission identity");
        require(context, after.active().spec().toGoal().orElseThrow().equals(original),
                "recovery replaced the original goal with the auxiliary proposal");
        require(context, GoalExecutor.INSTANCE.remainingRecoveryAttempts(bot) == 2,
                "accepted recovery proposal did not consume one bounded attempt");
        GoalExecutor.INSTANCE.cancelAll(bot);
        AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void recoveryStageRestartReplansOriginalAndKeepsRetryLedger(TestContext context) {
        String botName = "MissionStageRestartGT";
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        Goal original = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1);
        Goal auxiliary = new Goal.HaveItem(Items.SWEET_BERRIES, 1);
        require(context, GoalExecutor.INSTANCE.submit(bot, original), "original mission submit failed");
        MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
        UUID missionId = UUID.fromString(before.active().missionId());

        require(context, GoalExecutor.INSTANCE.applyMissionRecoveryProposal(
                        bot, missionId, auxiliary, "missing_ore|no_resource_nearby",
                        "no_resource_nearby: unobserved"),
                "auxiliary recovery proposal was not accepted");
        MissionRuntimeRecord staged = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, "true".equals(staged.active().checkpoint().get("recovery_stage")),
                "active auxiliary stage was not marked in checkpoint");
        require(context, staged.active().checkpoint().containsKey("task_kind"),
                "auxiliary task checkpoint fixture was not established");
        require(context, GoalExecutor.INSTANCE.remainingRecoveryAttempts(bot) == 2,
                "accepted proposal did not consume exactly one retry");

        GoalExecutor.INSTANCE.unload(bot);
        GoalExecutor.INSTANCE.restoreRuntime(bot, staged);
        MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, restored.active() != null, "original mission was lost on restore");
        require(context, restored.active().missionId().equals(missionId.toString()),
                "restore changed original mission identity");
        require(context, restored.active().spec().toGoal().orElseThrow().equals(original),
                "restore applied the auxiliary goal to the original mission");
        require(context, !restored.active().checkpoint().containsKey("recovery_stage"),
                "interrupted recovery stage marker was not cleared");
        require(context, GoalExecutor.INSTANCE.remainingRecoveryAttempts(bot) == 2,
                "restart reset or consumed the retry ledger");
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(origin -> origin.kind() == TaskOrigin.Kind.MISSION
                                && missionId.equals(origin.missionId()))
                        .orElse(false),
                "fresh task is not owned by the original mission");
        GoalExecutor.INSTANCE.cancelAll(bot);
        AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void pendingRecoveryRestartSuppressesOneImmediateRequest(TestContext context) {
        String botName = "MissionPendingRestartGT";
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        require(context, GoalExecutor.INSTANCE.submit(
                        bot, new Goal.HaveItem(Items.IRON_INGOT, 1)),
                "mission submit failed");
        MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
        UUID missionId = UUID.fromString(before.active().missionId());
        Object active = activePlanForTest(bot);
        setField(active, "recoveryPending", true);
        setField(active, "recoveryRequestId", UUID.randomUUID());

        MissionRuntimeRecord pending = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, "true".equals(pending.active().checkpoint().get("recovery_pending")),
                "pending recovery marker was not persisted");
        GoalExecutor.INSTANCE.unload(bot);
        GoalExecutor.INSTANCE.restoreRuntime(bot, pending);
        Object restoredPlan = activePlanForTest(bot);
        require(context, (boolean) getField(restoredPlan, "recoveryCancelled"),
                "restore did not arm one-request suppression");
        require(context, !((boolean) getField(restoredPlan, "recoveryPending")),
                "pending remote request remained stuck after restart");
        boolean requested = requestMissionRecoveryForTest(bot, restoredPlan, "no_path");
        require(context, !requested, "first post-restart request was not suppressed");
        require(context, !((boolean) getField(restoredPlan, "recoveryCancelled")),
                "one-shot suppression was not consumed");
        require(context, GoalExecutor.INSTANCE.captureRuntime(bot).active().missionId()
                        .equals(missionId.toString()),
                "pending recovery restore changed mission identity");

        GoalExecutor.INSTANCE.cancelAll(bot);
        AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
        context.complete();
    }

    private static boolean requestMissionRecoveryForTest(
            AIPlayerEntity bot, Object plan, String reason) {
        try {
            var method = GoalExecutor.class.getDeclaredMethod(
                    "requestMissionRecovery", AIPlayerEntity.class, plan.getClass(), String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(GoalExecutor.INSTANCE, bot, plan, reason);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void cancellingRecoveryLeaseReleasesPendingMissionAndRejectsLateResponse(TestContext context) {
        String botName = "MissionRecoveryCancelGT";
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        require(context, GoalExecutor.INSTANCE.submit(
                        bot, new Goal.HaveItem(Items.IRON_INGOT, 1)),
                "mission submit failed");
        UUID missionId = UUID.fromString(
                GoalExecutor.INSTANCE.captureRuntime(bot).active().missionId());
        UUID requestId = UUID.randomUUID();
        Object active = activePlanForTest(bot);
        setField(active, "recoveryPending", true);
        setField(active, "recoveryRequestId", requestId);

        DecisionSession session = new DecisionSession(bot.getUuid());
        DecisionLease delayed = session.beginEpoch();
        Runnable releasePending = () -> GoalExecutor.INSTANCE.abandonMissionRecovery(
                bot, missionId, requestId);
        registerRecoveryDecisionForTest(bot, session, releasePending);
        BrainCoordinator.INSTANCE.cancelMissionRecovery(bot);

        require(context, !((boolean) getField(active, "recoveryPending")),
                "cancellation left the original mission pending forever");
        require(context, getField(active, "recoveryRequestId") == null,
                "cancellation retained the stale request identity");
        require(context, (boolean) getField(active, "recoveryCancelled"),
                "cancellation did not suppress an immediate duplicate LLM request");
        require(context, !session.tryAcceptResponse(delayed),
                "late response from the cancelled recovery lease was accepted");

        GoalExecutor.INSTANCE.cancelAll(bot);
        AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
        context.complete();
    }

    private static Object activePlanForTest(AIPlayerEntity bot) {
        try {
            Field field = GoalExecutor.class.getDeclaredField("activePlans");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(GoalExecutor.INSTANCE)).get(bot.getUuid());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerRecoveryDecisionForTest(AIPlayerEntity bot,
                                                        DecisionSession session,
                                                        Runnable onCancel) {
        try {
            Class<?> type = Class.forName(
                    "io.github.zoyluo.aibot.brain.BrainCoordinator$RecoveryDecision");
            Constructor<?> constructor = type.getDeclaredConstructor(
                    DecisionSession.class, Runnable.class);
            constructor.setAccessible(true);
            Object pending = constructor.newInstance(session, onCancel);
            Field field = BrainCoordinator.class.getDeclaredField("recoveryDecisions");
            field.setAccessible(true);
            ((Map<UUID, Object>) field.get(BrainCoordinator.INSTANCE)).put(bot.getUuid(), pending);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object getField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void runScenario(TestContext context,
                                    String botName,
                                    Goal activeGoal,
                                    Item activeReward,
                                    Goal queuedGoal,
                                    Item queuedReward,
                                    int surfaceReturnStartY) {
        Probe probe = startSuspendedMission(
                context, botName, activeGoal, queuedGoal, surfaceReturnStartY);

        context.runAtTick(SUSPENDED_ASSERT_TICK, () -> assertSuspended(probe));
        context.runAtTick(RESUMED_ASSERT_TICK, () -> {
            assertResumed(probe);
            InventoryAction.giveItem(probe.bot(), new ItemStack(activeReward, 1));
        });
        for (int tick = RESUMED_ASSERT_TICK + 1; tick <= ALL_COMPLETE_ASSERT_TICK; tick++) {
            context.runAtTick(tick, () -> captureScenarioResult(probe, queuedReward));
        }
        context.runAtTick(ACTIVE_COMPLETE_ASSERT_TICK, () -> {
            assertActiveMissionCompletedAndQueuePromoted(probe);
            if (!(queuedGoal instanceof Goal.MineOre)) {
                InventoryAction.giveItem(probe.bot(), new ItemStack(queuedReward, 1));
            }
        });
        context.runAtTick(ALL_COMPLETE_ASSERT_TICK, () -> {
            assertAllCompleted(probe);
            cleanup(probe);
            context.complete();
        });
    }

    private static Probe startSuspendedMission(TestContext context,
                                               String botName,
                                               Goal activeGoal,
                                               Goal queuedGoal,
                                               int surfaceReturnStartY) {
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        if (surfaceReturnStartY >= 0) {
            cell = cell.withY(surfaceReturnStartY);
        }
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        bot.teleport(world, cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setVelocity(Vec3d.ZERO);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));

        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot).map(GoalResult::sequence).orElse(0L);
        require(context, GoalExecutor.INSTANCE.submit(bot, activeGoal), "active goal setup failed: " + activeGoal);
        require(context, GoalExecutor.INSTANCE.submit(bot, queuedGoal), "queued goal setup failed: " + queuedGoal);
        if (surfaceReturnStartY == 32) {
            // Preserve the Y=32 mission origin/exit anchor, then model a one-block downward
            // displacement before death. On resume the strict surface gate must make the bot
            // climb back to Y=32 instead of accepting the adjacent Y=31 egress.
            BlockPos displaced = cell.down();
            world.setBlockState(displaced.down(), Blocks.STONE.getDefaultState(),
                    Block.NOTIFY_LISTENERS);
            world.setBlockState(displaced, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            prepareShortSurfaceExit(world, displaced);
            bot.teleport(world, displaced.getX() + 0.5D, displaced.getY(),
                    displaced.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            bot.setVelocity(Vec3d.ZERO);
        }
        MissionRuntimeRecord initial = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, initial.active() != null, "active mission was not captured");
        UUID missionId = UUID.fromString(initial.active().missionId());
        require(context, initial.queue().size() == 1, "queue was not established before death");

        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        MissionRuntimeRecord suspended = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, suspended.active() != null
                        && missionId.toString().equals(suspended.active().missionId()),
                "death suspension changed mission identity");
        require(context, suspended.queue().size() == 1, "death suspension dropped queued mission");
        require(context, GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).isEmpty(),
                "death suspension published a terminal result");

        TaskManager.INSTANCE.assign(bot,
                new RecoverDropsTask(bot.getBlockPos(), bot.getServer().getTicks()),
                TaskOrigin.safety("gametest_death_recovery"));
        return new Probe(context, botName, bot, activeGoal, queuedGoal, missionId, resultBaseline,
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(), new AtomicBoolean());
    }

    private static void assertSuspended(Probe probe) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(probe.bot());
        require(probe, GoalExecutor.INSTANCE.hasActivePlan(probe.bot()), "suspended mission is no longer visible");
        require(probe, runtime.active() != null
                        && probe.missionId().toString().equals(runtime.active().missionId()),
                "suspended missionId changed");
        require(probe, runtime.queue().size() == 1, "suspended queue was lost");
        require(probe, TaskManager.INSTANCE.getActive(probe.bot())
                        .filter(RecoverDropsTask.class::isInstance).isPresent(),
                "RecoverDropsTask no longer owns the safety slot");
        require(probe, TaskManager.INSTANCE.activeOrigin(probe.bot()).map(TaskOrigin::safety).orElse(false),
                "recovery task does not have SAFETY origin");
        require(probe, GoalExecutor.INSTANCE.resultAfter(probe.bot(), probe.resultBaseline()).isEmpty(),
                "suspended mission published FAILED/CANCELLED");
    }

    private static void assertResumed(Probe probe) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(probe.bot());
        require(probe, runtime.active() != null
                        && probe.missionId().toString().equals(runtime.active().missionId()),
                "resumed mission did not keep its missionId");
        require(probe, GoalExecutor.INSTANCE.isActiveGoal(probe.bot(), probe.activeGoal()),
                "original goal was not restored after recovery");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 1,
                "queued mission was not restored after recovery");
        require(probe, TaskManager.INSTANCE.activeOrigin(probe.bot())
                        .map(origin -> origin.kind() == TaskOrigin.Kind.MISSION
                                && probe.missionId().equals(origin.missionId()))
                        .orElse(false),
                "restored task is not owned by the original mission");
        require(probe, GoalExecutor.INSTANCE.resultAfter(probe.bot(), probe.resultBaseline()).isEmpty(),
                "mission published a terminal result before resumed work completed");
    }

    private static void assertActiveMissionCompletedAndQueuePromoted(Probe probe) {
        if (probe.activeGoal() instanceof Goal.HaveItem) {
            BlockPos completionPosition = probe.activeCompletionPosition().get();
            require(probe, completionPosition != null && completionPosition.getY() >= 32,
                    "surface return did not reach the surface band before mission completion");
        }
        GoalResult result = probe.activeResult().get();
        if (result == null) {
            result = GoalExecutor.INSTANCE.lastResult(probe.bot())
                    .filter(candidate -> candidate.missionId().equals(probe.missionId()))
                    .orElseThrow(() -> failure(probe, "missing result for resumed mission"));
        }
        require(probe, result.sequence() > probe.resultBaseline(), "result sequence did not advance");
        require(probe, result.missionId().equals(probe.missionId()), "completed result changed missionId");
        require(probe, result.goal().equals(probe.activeGoal()), "wrong goal completed after recovery");
        require(probe, result.status() == GoalResult.Status.COMPLETED,
                "resumed mission ended as " + result.status() + ": " + result.reason());
        GoalResult queuedResult = probe.queuedResult().get();
        if (queuedResult == null) {
            require(probe, GoalExecutor.INSTANCE.isActiveGoal(probe.bot(), probe.queuedGoal()),
                    "preserved queued mission was not promoted");
        } else {
            require(probe, queuedResult.goal().equals(probe.queuedGoal()),
                    "wrong queued goal completed before active assertion");
            require(probe, queuedResult.status() == GoalResult.Status.COMPLETED,
                    "queued mission ended as " + queuedResult.status() + ": " + queuedResult.reason());
        }
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 0,
                "promoted queue still contains a duplicate mission");
    }

    private static void assertAllCompleted(Probe probe) {
        GoalResult result = probe.queuedResult().get();
        if (result == null) {
            result = GoalExecutor.INSTANCE.lastResult(probe.bot())
                    .filter(candidate -> candidate.goal().equals(probe.queuedGoal()))
                    .orElseThrow(() -> failure(probe, "missing result for preserved queued mission"));
        }
        require(probe, result.goal().equals(probe.queuedGoal()), "wrong queued goal completed");
        require(probe, result.status() == GoalResult.Status.COMPLETED,
                "queued mission ended as " + result.status() + ": " + result.reason());
        require(probe, !GoalExecutor.INSTANCE.hasActivePlan(probe.bot()), "mission remained active after completion");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 0, "queue was not drained");
    }

    private static void prepareCell(net.minecraft.server.world.ServerWorld world, BlockPos center) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlockState(center.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        world.setBlockState(center, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        world.setBlockState(center.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    private static void captureScenarioResult(Probe probe, Item queuedReward) {
        GoalResult result = GoalExecutor.INSTANCE.lastResult(probe.bot()).orElse(null);
        if (result == null || result.sequence() <= probe.resultBaseline()) return;
        if (result.missionId().equals(probe.missionId())) {
            if (probe.activeResult().compareAndSet(null, result)) {
                probe.activeCompletionPosition().set(probe.bot().getBlockPos());
            }
            if (probe.queuedGoal() instanceof Goal.MineOre
                    && probe.queuedRewardGranted().compareAndSet(false, true)) {
                // Supply the queued mine output only after the active mission result is recorded.
                InventoryAction.giveItem(probe.bot(), new ItemStack(queuedReward, 1));
            }
        } else if (result.goal().equals(probe.queuedGoal())) {
            probe.queuedResult().compareAndSet(null, result);
        }
    }

    private static void prepareShortSurfaceExit(
            net.minecraft.server.world.ServerWorld world, BlockPos start) {
        // The recovered bot starts one block below the deterministic surface threshold. Leave
        // its north stair and the adjacent supported exit open; the task must physically reach
        // Y=32 and prove a reusable surface edge before the original berry mission can finish.
        for (int north = 0; north <= 2; north++) {
            for (int dy = 1; dy <= 3; dy++) {
                world.setBlockState(start.add(0, dy, -north),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    private static void cleanup(Probe probe) {
        AIPlayerManager.INSTANCE.despawn(probe.bot().getServer(), probe.botName());
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static void require(Probe probe, boolean condition, String message) {
        if (!condition) {
            cleanup(probe);
            probe.context().throwGameTestException(message);
        }
    }

    private static RuntimeException failure(Probe probe, String message) {
        cleanup(probe);
        return new IllegalStateException(message);
    }

    private record Probe(TestContext context,
                         String botName,
                         AIPlayerEntity bot,
                         Goal activeGoal,
                         Goal queuedGoal,
                         UUID missionId,
                         long resultBaseline,
                         AtomicReference<GoalResult> activeResult,
                         AtomicReference<GoalResult> queuedResult,
                         AtomicReference<BlockPos> activeCompletionPosition,
                         AtomicBoolean queuedRewardGranted) {
    }
}
