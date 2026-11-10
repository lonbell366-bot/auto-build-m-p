package com.buildbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.*;

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
            client.options.sprintKey.setPressed(false);
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

        int layerY = targetPos.getY();

        // 1. Neu nhan vat dang dung trung vao o can dat -> Nhay thang len de dat khoi duoi chan (Leo len Y + 1)
        if (player.getBoundingBox().intersects(new Box(targetPos).contract(0.05))) {
            isBotMoving = true;
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.jumpKey.setPressed(true);
            if (player.isOnGround()) player.jump();
            return false;
        }

        // 2. Neu trong tang da co khoi dat roi ma nhan vat van dung o duoi (playerY <= layerY) -> Leo len dung TREN mat khoi do!
        BlockPos standBlock = findNearestPlacedBlockInLayer(client, player.getBlockPos(), layerY);
        if (standBlock != null && player.getBlockY() <= layerY) {
            isBotMoving = true;
            Vec3d topCenter = new Vec3d(standBlock.getX() + 0.5, standBlock.getY() + 1.0, standBlock.getZ() + 0.5);
            moveSmoothlyTowards(client, player, topCenter);
            if (player.isOnGround() && (player.horizontalCollision || player.squaredDistanceTo(topCenter) <= 4.5)) {
                player.jump();
                client.options.jumpKey.setPressed(true);
            } else {
                client.options.jumpKey.setPressed(false);
            }
            return false;
        }

        // 3. Khi da dung TREN mat khoi (playerY == layerY + 1) hoac trong tam voi -> Dung lai de dat
        double distSq = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos));
        boolean hasHit = AntiSusRotator.findValidPlaceHit(client, targetPos, client.world.getBlockState(targetPos)) != null;

        if (distSq <= 19.5 && (hasHit || distSq <= 10.0)) {
            stopMovement(client);
            if (player.isOnGround() && client.world.getBlockState(player.getBlockPos().down()).isAir()) {
                client.options.sneakKey.setPressed(true);
                isBotMoving = true;
            }
            return true;
        }

        // 4. Di chuyen TREN mat cac khoi da xay va giu Sneak o mep
        isBotMoving = true;
        Vec3d targetVec = new Vec3d(targetPos.getX() + 0.5, layerY + 1.0, targetPos.getZ() + 0.5);
        moveSmoothlyTowards(client, player, targetVec);

        boolean atEdge = player.isOnGround() && client.world.getBlockState(player.getBlockPos().down()).isAir();
        client.options.sneakKey.setPressed(atEdge);

        if (player.horizontalCollision && player.isOnGround()) {
            player.jump();
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }

        return false;
    }

    private BlockPos findNearestPlacedBlockInLayer(MinecraftClient client, BlockPos playerPos, int layerY) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos p = new BlockPos(playerPos.getX() + dx, layerY, playerPos.getZ() + dz);
                if (AntiSusRotator.isSolidSupport(client, p)
                        && client.world.getBlockState(p.up()).isAir()
                        && client.world.getBlockState(p.up(2)).isAir()) {
                    double d = playerPos.getSquaredDistance(p);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private void moveSmoothlyTowards(MinecraftClient client, ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float diff = MathHelper.wrapDegrees(targetYaw - player.getYaw());

        client.options.forwardKey.setPressed(diff >= -70.0f && diff <= 70.0f);
        client.options.backKey.setPressed(diff >= 110.0f || diff <= -110.0f);
        client.options.rightKey.setPressed(diff >= 25.0f && diff <= 155.0f);
        client.options.leftKey.setPressed(diff <= -25.0f && diff >= -155.0f);
    }
}
