package com.buildbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomPathfinder {
    private boolean isBotMoving = false;
    private long moveStartTime = 0;
    private BlockPos currentGoal = null;

    public void startMovingTo(BlockPos target) {
        this.currentGoal = target;
        this.moveStartTime = System.currentTimeMillis();
    }

    public boolean isTimedOut(int timeoutSeconds) {
        return moveStartTime > 0 && (System.currentTimeMillis() - moveStartTime) > timeoutSeconds * 1000L;
    }

    public void stopMovement(MinecraftClient client) {
        if (isBotMoving && client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            isBotMoving = false;
        }
        currentGoal = null;
        moveStartTime = 0;
    }

    public boolean tickMoveToTarget(MinecraftClient client, BlockPos targetPos, BuildBotConfig config) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return false;

        if (currentGoal == null || !currentGoal.equals(targetPos)) {
            startMovingTo(targetPos);
        }

        Box shrinkBox = new Box(targetPos).contract(0.1);
        if (player.getBoundingBox().intersects(shrinkBox)) {
            isBotMoving = true;
            moveSmoothlyTowards(client, player, Vec3d.ofCenter(targetPos).add(1.0, 0.0, 0.0));
            return false;
        }

        double distSq = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos));
        if (distSq <= 17.5) {
            stopMovement(client);
            return true;
        }

        isBotMoving = true;
        moveSmoothlyTowards(client, player, Vec3d.ofCenter(targetPos));

        if (player.horizontalCollision && player.isOnGround()) {
            player.jump();
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }

        BlockPos below = player.getBlockPos().down();
        if (config.autoBridgeEnabled && client.world.getBlockState(below).isAir()) {
            client.options.sneakKey.setPressed(true);
            int slot = findCobblestone(player);
            if (slot != -1 && client.interactionManager != null) {
                BuildBotClient.setSelectedSlot(player, slot);
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(below), Direction.UP, below, false));
                player.swingHand(Hand.MAIN_HAND);
            }
        } else {
            client.options.sneakKey.setPressed(false);
        }
        return false;
    }

    private void moveSmoothlyTowards(MinecraftClient client, ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float diff = MathHelper.wrapDegrees(targetYaw - player.getYaw());

        client.options.forwardKey.setPressed(diff >= -67.5f && diff <= 67.5f);
        client.options.backKey.setPressed(diff >= 112.5f || diff <= -112.5f);
        client.options.rightKey.setPressed(diff >= 22.5f && diff <= 157.5f);
        client.options.leftKey.setPressed(diff <= -22.5f && diff >= -157.5f);
    }

    private int findCobblestone(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(Items.COBBLESTONE)) return i;
        return -1;
    }
}
