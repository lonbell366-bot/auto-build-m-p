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

        // 1. Neu nhan vat dang dung de len chinh o can dat Hopper/Block:
        // Neu co khoi ben canh thi lui sang khoi ben canh, neu la o cuoi cung duoi chan thi nhay len!
        if (player.getBoundingBox().intersects(new Box(targetPos).expand(0.05))) {
            isBotMoving = true;
            BlockPos sideStand = findBestAdjacentStandPos(client, targetPos, layerY);
            if (sideStand != null) {
                walkTowards(client, player, new Vec3d(sideStand.getX() + 0.5, sideStand.getY() + 1.0, sideStand.getZ() + 0.5));
                client.options.sneakKey.setPressed(true);
            } else if (targetPos.equals(player.getBlockPos())) {
                client.options.forwardKey.setPressed(false);
                client.options.backKey.setPressed(true);
                client.options.jumpKey.setPressed(true);
                if (player.isOnGround()) player.jump();
            } else {
                // Lui nhe ra ngoai de khong ket Hitbox khi dat Hopper
                double dx = player.getX() - (targetPos.getX() + 0.5);
                double dz = player.getZ() - (targetPos.getZ() + 0.5);
                if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) dx = 1.0;
                walkTowards(client, player, new Vec3d(player.getX() + dx * 2.0, player.getY(), player.getZ() + dz * 2.0));
            }
            return false;
        }

        // 2. Neu trong tang da co khoi dat va phia tren khong bi chan -> Uu tien leo len dung TREN mat khoi (Y + 1)
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

        // 3. Kiem tra khoang cach: Toi gan khoang 1.15 - 2.45 blocks (khong dung de len khoi) la dung lai de Ngoi xuong dat!
        double dx = (targetPos.getX() + 0.5) - player.getX();
        double dz = (targetPos.getZ() + 0.5) - player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double eyeDistSq = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos));

        if (horizDist <= 2.45 && eyeDistSq <= 16.5) {
            stopMovement(client);
            return true;
        }

        // 4. Di bo toi gan khoi can dat (Khi di chuyen thi dung thang, chi giu Sneak neu sat mep vuc)
        isBotMoving = true;
        BlockPos standNext = findBestAdjacentStandPos(client, targetPos, layerY);
        Vec3d walkGoal = (standNext != null)
                ? new Vec3d(standNext.getX() + 0.5, standNext.getY() + 1.0, standNext.getZ() + 0.5)
                : new Vec3d(targetPos.getX() + 0.5, player.getY(), targetPos.getZ() + 0.5);

        walkTowards(client, player, walkGoal);

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

        float diffYaw = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(diffYaw * 0.45f, -35.0f, 35.0f));

        float rem = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        client.options.forwardKey.setPressed(rem >= -75.0f && rem <= 75.0f);
        client.options.backKey.setPressed(rem >= 115.0f || rem <= -115.0f);
        client.options.rightKey.setPressed(rem >= 30.0f && rem <= 150.0f);
        client.options.leftKey.setPressed(rem <= -30.0f && rem >= -150.0f);
    }
}
