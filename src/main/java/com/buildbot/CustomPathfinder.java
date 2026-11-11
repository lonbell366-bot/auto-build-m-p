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

        // 1. Neu dang dung de len chinh o can dat:
        // Neu xung quanh co khoi dat san thi buoc sang khoi ben canh de dat, neu la vien cuoi cung thi nhay len!
        if (player.getBoundingBox().intersects(new Box(targetPos).contract(0.04))) {
            isBotMoving = true;
            BlockPos sideStand = findBestAdjacentStandPos(client, targetPos, layerY);
            if (sideStand != null) {
                walkTowards(client, player, new Vec3d(sideStand.getX() + 0.5, sideStand.getY() + 1.0, sideStand.getZ() + 0.5));
                client.options.sneakKey.setPressed(true);
            } else {
                client.options.forwardKey.setPressed(false);
                client.options.backKey.setPressed(false);
                client.options.sneakKey.setPressed(false);
                client.options.jumpKey.setPressed(true);
                if (player.isOnGround()) player.jump();
            }
            return false;
        }

        // 2. Neu nhan vat dang o duoi thap hon mat khoi da xay -> Leo len dung TREN mat khoi (Y + 1)
        BlockPos anyStand = findNearestPlacedBlockInLayer(client, player.getBlockPos(), layerY);
        if (anyStand != null && player.getBlockY() <= layerY) {
            isBotMoving = true;
            Vec3d topVec = new Vec3d(anyStand.getX() + 0.5, anyStand.getY() + 1.0, anyStand.getZ() + 0.5);
            walkTowards(client, player, topVec);
            if (player.isOnGround() && (player.horizontalCollision || player.squaredDistanceTo(topVec) <= 4.0)) {
                player.jump();
                client.options.jumpKey.setPressed(true);
            } else {
                client.options.jumpKey.setPressed(false);
            }
            return false;
        }

        // 3. DI CHUYEN TOI SAT TUNG BLOCK CAN DAT (Khoang cach ngang <= 1.65 blocks)
        double dx = (targetPos.getX() + 0.5) - player.getX();
        double dz = (targetPos.getZ() + 0.5) - player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double eyeDistSq = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos));

        // Tim vi tri dung sat canh khoi can dat nhat
        BlockPos standNextToTarget = findBestAdjacentStandPos(client, targetPos, layerY);
        boolean reachedClose = (horizDist <= 1.68 && eyeDistSq <= 14.0)
                || (standNextToTarget != null && player.getBlockPos().equals(standNextToTarget.up()))
                || (standNextToTarget == null && eyeDistSq <= 16.0);

        if (reachedClose) {
            stopMovement(client);
            if (player.isOnGround() && client.world.getBlockState(player.getBlockPos().down()).isAir()) {
                client.options.sneakKey.setPressed(true);
                isBotMoving = true;
            }
            return true;
        }

        // 4. Buoc di bo truc tiep toi khoi can dat
        isBotMoving = true;
        Vec3d walkGoal = (standNextToTarget != null)
                ? new Vec3d(standNextToTarget.getX() + 0.5, standNextToTarget.getY() + 1.0, standNextToTarget.getZ() + 0.5)
                : new Vec3d(targetPos.getX() + 0.5, layerY + 1.0, targetPos.getZ() + 0.5);

        walkTowards(client, player, walkGoal);

        // Giu Sneak khi di sat mep de khong bi roi khoi mat block
        boolean nearEdge = player.isOnGround() && client.world.getBlockState(player.getBlockPos().down()).isAir();
        client.options.sneakKey.setPressed(nearEdge);

        if (player.horizontalCollision && player.isOnGround()) {
            player.jump();
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }

        return false;
    }

    /**
     * Tim khoi da dat nam sat canh targetPos de nhan vat di toi dung ngay sat targetPos
     */
    private BlockPos findBestAdjacentStandPos(MinecraftClient client, BlockPos targetPos, int layerY) {
        if (client.player == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                BlockPos cand = new BlockPos(targetPos.getX() + ox, layerY, targetPos.getZ() + oz);
                if (AntiSusRotator.isSolidSupport(client, cand)
                        && client.world.getBlockState(cand.up()).isAir()
                        && client.world.getBlockState(cand.up(2)).isAir()) {
                    double d = client.player.squaredDistanceTo(Vec3d.ofCenter(cand.up()));
                    if (d < bestDist) {
                        bestDist = d;
                        best = cand;
                    }
                }
            }
        }
        return best;
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

    private void walkTowards(MinecraftClient client, ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;

        // Xoay muot theo huong buoc di de nhan vat di thang bang nut W muot ma
        float diffYaw = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(diffYaw * 0.45f, -35.0f, 35.0f));

        float rem = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        client.options.forwardKey.setPressed(rem >= -75.0f && rem <= 75.0f);
        client.options.backKey.setPressed(rem >= 115.0f || rem <= -115.0f);
        client.options.rightKey.setPressed(rem >= 30.0f && rem <= 150.0f);
        client.options.leftKey.setPressed(rem <= -30.0f && rem >= -150.0f);
    }
}
