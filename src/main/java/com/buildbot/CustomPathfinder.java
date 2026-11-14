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

        // 1. CHI khi nguoi choi thuc su dung LOT VAO TRONG long o can dat (playerY < layerY + 0.85) moi buoc ra hoac nhay len!
        // Dung .contract(0.06) de khi dung TREN mat khoi (playerY == layerY + 1) KHONG bao gio bi tinh nham!
        if (player.getY() < layerY + 0.85 && player.getBoundingBox().intersects(new Box(targetPos).contract(0.06))) {
            isBotMoving = true;
            BlockPos sideStand = findBestAdjacentStandPos(client, targetPos, layerY);
            if (sideStand != null) {
                walkTowards(client, player, new Vec3d(sideStand.getX() + 0.5, sideStand.getY() + 1.0, sideStand.getZ() + 0.5));
                if (player.isOnGround() && player.horizontalCollision) player.jump();
            } else {
                client.options.forwardKey.setPressed(false);
                client.options.jumpKey.setPressed(true);
                if (player.isOnGround()) player.jump();
            }
            return false;
        }

        // 2. Neu trong tang da co khoi dat ma nhan vat van dung o duoi dat (playerY <= layerY) -> Nhay len dung TREN mat khoi!
        BlockPos anyStand = findNearestPlacedBlockInLayer(client, player.getBlockPos(), layerY);
        if (anyStand != null && player.getBlockY() <= layerY) {
            isBotMoving = true;
            Vec3d topVec = new Vec3d(anyStand.getX() + 0.5, anyStand.getY() + 1.0, anyStand.getZ() + 0.5);
            walkTowards(client, player, topVec);
            if (player.isOnGround() && (player.horizontalCollision || player.squaredDistanceTo(topVec) <= 3.5)) {
                player.jump();
                client.options.jumpKey.setPressed(true);
            } else {
                client.options.jumpKey.setPressed(false);
            }
            return false;
        }

        // 3. Tim diem dung ly tuong ngay tren mep khoi da xay sat canh targetPos (Cach targetPos ~0.65 - 1.35 blocks)
        BlockPos standBlock = findBestAdjacentStandPos(client, targetPos, layerY);
        Vec3d idealStandSpot;
        if (standBlock != null) {
            double shiftX = MathHelper.clamp(targetPos.getX() - standBlock.getX(), -1, 1) * 0.36;
            double shiftZ = MathHelper.clamp(targetPos.getZ() - standBlock.getZ(), -1, 1) * 0.36;
            idealStandSpot = new Vec3d(standBlock.getX() + 0.5 + shiftX, standBlock.getY() + 1.0, standBlock.getZ() + 0.5 + shiftZ);
        } else {
            idealStandSpot = new Vec3d(targetPos.getX() + 0.5, player.getY(), targetPos.getZ() + 0.5);
        }

        double distToStand = Math.hypot(player.getX() - idealStandSpot.x, player.getZ() - idealStandSpot.z);
        double distToTarget = Math.hypot(player.getX() - (targetPos.getX() + 0.5), player.getZ() - (targetPos.getZ() + 0.5));

        // Khi da buoc toi sat mep khoi can dat (distToStand <= 0.45 hoac distToTarget <= 1.35) -> DUNG HAN LAI!
        if ((standBlock != null && distToStand <= 0.45) || (distToTarget <= 1.38 && distToTarget >= 0.45)) {
            stopMovement(client);
            return true;
        }

        // 4. Di bo thang toi diem dung sat khoi can dat
        isBotMoving = true;
        walkTowards(client, player, idealStandSpot);

        // Chi giu Sneak neu ban chan dang nho ra ngoai mep khong khi
        boolean overhangingEdge = player.isOnGround() && client.world.getBlockState(player.getBlockPos().down()).isAir();
        client.options.sneakKey.setPressed(overhangingEdge);

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
        double bestScore = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                BlockPos cand = new BlockPos(targetPos.getX() + ox, layerY, targetPos.getZ() + oz);
                if (AntiSusRotator.isConfirmedSolidSupport(client, cand)
                        && client.world.getBlockState(cand.up()).isAir()
                        && client.world.getBlockState(cand.up(2)).isAir()) {
                    double d = client.player.squaredDistanceTo(Vec3d.ofCenter(cand.up()));
                    // Uu tien 4 khoi sat canh (NORTH/SOUTH/EAST/WEST) hon khoi cheo
                    if (ox == 0 || oz == 0) d -= 3.0;
                    if (d < bestScore) {
                        bestScore = d;
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
                if (AntiSusRotator.isConfirmedSolidSupport(client, p)
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
        player.setYaw(player.getYaw() + MathHelper.clamp(diffYaw * 0.55f, -40.0f, 40.0f));

        float rem = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        client.options.forwardKey.setPressed(rem >= -65.0f && rem <= 65.0f);
        client.options.backKey.setPressed(rem >= 120.0f || rem <= -120.0f);
        client.options.rightKey.setPressed(rem >= 30.0f && rem <= 150.0f);
        client.options.leftKey.setPressed(rem <= -30.0f && rem >= -150.0f);
    }
}
