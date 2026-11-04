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
    private List currentPath = new ArrayList();
    private final Deque placedBridgeBlocks = new ArrayDeque();
    private long moveStartTime = 0;
    private BlockPos currentGoal = null;
    private boolean isBotMoving = false;

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
        currentPath.clear();
        currentGoal = null;
        moveStartTime = 0;
    }

    public boolean tickMoveToTarget(MinecraftClient client, BlockPos targetPos, BuildBotConfig config) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return false;

        if (currentGoal == null || !currentGoal.equals(targetPos)) {
            startMovingTo(targetPos);
        }

        // Thu nho Box 0.08 de khi dung TREN mat khoi hoac sat canh khoi khong bi tinh nham la ket trong khoi
        Box shrinkBox = new Box(targetPos).contract(0.08);
        boolean standingInsideBlock = player.getBoundingBox().intersects(shrinkBox);

        if (standingInsideBlock) {
            isBotMoving = true;
            // Tim o trong xung quanh de buoc ra muot ma
            BlockPos escapePos = findSafeStepOutPos(client, targetPos);
            moveSmoothlyTowards(client, player, Vec3d.ofCenter(escapePos));
            return false;
        }

        double distSq = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos));
        // Trong tam dat khoi (<= 4.2 blocks) -> Dung di chuyen de dat luon
        if (distSq <= 17.5) {
            stopMovement(client);
            return true;
        }

        isBotMoving = true;
        moveSmoothlyTowards(client, player, Vec3d.ofCenter(targetPos));

        // Tu nhay khi gap vat can 1 block truoc mat
        if (player.horizontalCollision && player.isOnGround()) {
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }

        // Bac cau tam khi di qua ho
        BlockPos belowPlayer = player.getBlockPos().down();
        if (config.autoBridgeEnabled && client.world.getBlockState(belowPlayer).isAir()) {
            client.options.sneakKey.setPressed(true);
            int cobbleSlot = findCobblestoneInHotbar(player);
            if (cobbleSlot != -1 && client.interactionManager != null) {
                BuildBotClient.setSelectedSlot(player, cobbleSlot);
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(belowPlayer), Direction.UP, belowPlayer, false);
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
                placedBridgeBlocks.addLast(belowPlayer);
            }
        } else {
            client.options.sneakKey.setPressed(false);
        }

        return false;
    }

    /**
     * Phoi hop 4 phim W, A, S, D theo goc nhin hien tai de di chuyen muot ma khong can giat camera
     */
    private void moveSmoothlyTowards(MinecraftClient client, ClientPlayerEntity player, Vec3d targetVec) {
        double dx = targetVec.x - player.getX();
        double dz = targetVec.z - player.getZ();
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float diff = MathHelper.wrapDegrees(targetYaw - player.getYaw());

        boolean w = false, s = false, a = false, d = false;
        if (diff >= -67.5f && diff <= 67.5f) w = true;
        else if (diff >= 112.5f || diff <= -112.5f) s = true;

        if (diff >= 22.5f && diff <= 157.5f) d = true;
        else if (diff <= -22.5f && diff >= -157.5f) a = true;

        client.options.forwardKey.setPressed(w);
        client.options.backKey.setPressed(s);
        client.options.leftKey.setPressed(a);
        client.options.rightKey.setPressed(d);
    }

    private BlockPos findSafeStepOutPos(MinecraftClient client, BlockPos blockedPos) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos adj = blockedPos.offset(dir);
            if (client.world.getBlockState(adj).isAir() && !client.world.getBlockState(adj.down()).isAir()) {
                return adj;
            }
        }
        return blockedPos.north();
    }

    private int findCobblestoneInHotbar(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(Items.COBBLESTONE)) return i;
        }
        return -1;
    }
}
