package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import net.minecraft.block.BlockState;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public final class MiningController {
    private static final int MAX_TICKS = 600;

    private final BlockPos pos;
    private final Direction face;
    private boolean started;
    private boolean stopSent;
    private BlockState targetState;
    private float progress;
    private int elapsed;

    public MiningController(BlockPos pos, Direction face) {
        this.pos = pos;
        this.face = face;
    }

    public ActionResult tick(ActionPack pack) {
        AIPlayerEntity player = pack.player();
        var world = player.getServerWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            resetProgress(player);
            AStarPathfinder.invalidateCache("block_break");
            return ActionResult.SUCCESS;
        }
        if (targetState != null && !state.equals(targetState)) {
            resetProgress(player);
        }

        LookAction.lookAtBlock(player, pos, face);
        double reach = player.getAttributeValue(EntityAttributes.BLOCK_INTERACTION_RANGE);
        if (player.getEyePos().distanceTo(pos.toCenterPos()) > reach + 0.5D) {
            abort(player);
            return ActionResult.failed("out_of_reach");
        }

        // STOP can arrive just before ServerPlayerInteractionManager has advanced its own
        // break progress for this server tick. In that case vanilla defers the physical break
        // to update(); keep this action alive until the world confirms air instead of reporting
        // success and letting BlockMiner immediately restart on the still-solid same block.
        if (stopSent) {
            elapsed++;
            if (elapsed > MAX_TICKS) {
                abort(player);
                return ActionResult.failed("break_not_committed");
            }
            return ActionResult.IN_PROGRESS;
        }

        if (!started) {
            ToolSelector.equipBestTool(player, state);
            BotLog.action(player, "mine_start", "pos", LogFields.pos(pos), "face", face);
            player.interactionManager.processBlockBreakingAction(
                    pos,
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                    face,
                    World.MAX_Y,
                    -1);
            state.onBlockBreakStart(world, pos, player);
            started = true;
            targetState = state;
        }

        progress += state.calcBlockBreakingDelta(player, world, pos);
        world.setBlockBreakingInfo(player.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
        player.swingHand(Hand.MAIN_HAND);
        player.updateLastActionTime();

        if (progress >= 1.0F) {
            player.interactionManager.processBlockBreakingAction(
                    pos,
                    PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                    face,
                    World.MAX_Y,
                    -1);
            world.setBlockBreakingInfo(player.getId(), pos, -1);
            if (world.getBlockState(pos).isAir()) {
                AStarPathfinder.invalidateCache("block_break");
                return ActionResult.SUCCESS;
            }
            stopSent = true;
            return ActionResult.IN_PROGRESS;
        }

        elapsed++;
        if (elapsed > MAX_TICKS) {
            abort(player);
            return ActionResult.failed("timeout");
        }
        return ActionResult.IN_PROGRESS;
    }

    public void abort(AIPlayerEntity player) {
        if (!started) {
            return;
        }
        player.interactionManager.processBlockBreakingAction(
                pos,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                face,
                World.MAX_Y,
                -1);
        player.getServerWorld().setBlockBreakingInfo(player.getId(), pos, -1);
        started = false;
        stopSent = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
    }

    private void resetProgress(AIPlayerEntity player) {
        if (started) {
            player.interactionManager.processBlockBreakingAction(
                    pos,
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                    face,
                    World.MAX_Y,
                    -1);
        }
        player.getServerWorld().setBlockBreakingInfo(player.getId(), pos, -1);
        started = false;
        stopSent = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
    }
}
