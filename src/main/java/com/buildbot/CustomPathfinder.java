package com.buildbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

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

        // 1. PILLAR UP (NHAY LEN XAY DUOI CHAN DE LEN TANG):
        // Neu khoi can dat nam ngay tai vi tri chan nguoi choi -> Nhay thang len de dat khoi duoi chan!
        Box blockBox = new Box(targetPos).contract(0.05);
        if (player.getBoundingBox().intersects(blockBox)) {
            isBotMoving = true;
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.jumpKey.setPressed(true);
            if (player.isOnGround()) {
                player.jump();
            }
            return false;
        }

        double distSq = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetPos));
        boolean hasSupportHit = AntiSusRotator.findValidPlaceHit(client, targetPos, client.world.getBlockState(targetPos)) != null;

        // Neu da o trong tam dat (<= 4.2 blocks) VA khong bi ket -> Dung di chuyen de dat luon
        if (distSq <= 17.5 && (hasSupportHit || distSq <= 9.0)) {
            stopMovement(client);
            // Neu khoi can dat o tang cao hon tam mat va dang dung sat -> Nhay len de co goc dat
            if (targetPos.getY() > player.getBlockY() + 1 && player.isOnGround()) {
                player.jump();
            }
            return true;
        }

        isBotMoving = true;
        Vec3d targetVec = Vec3d.ofCenter(targetPos);
        double dx = targetVec.x - player.getX();
        double dz = targetVec.z - player.getZ();
        float moveYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;

        // Xoay muot theo huong di chuyen va chay toi
        AntiSusRotator.stepSmoothRotation(client, moveYaw, 15.0f, 0.75f);
        moveSmoothlyTowards(client, player, targetVec);
        client.options.sprintKey.setPressed(true);

        Direction moveDir = Direction.fromHorizontalDegrees(moveYaw);
        BlockPos ahead1 = player.getBlockPos().offset(moveDir);
        boolean gapAhead = client.world.getBlockState(ahead1).isAir() && client.world.getBlockState(ahead1.down()).isAir();

        // 2. NHAY QUA HO (PARKOUR JUMP) HOAC NHAY LEN TANG CAO:
        boolean shouldJump = false;
        if (player.horizontalCollision && player.isOnGround()) {
            shouldJump = true; // Gap bac cao 1 block truoc mat -> Nhay len
        } else if (targetPos.getY() > player.getBlockY() && distSq <= 25.0 && player.isOnGround()) {
            shouldJump = true; // Can leo len tang tren -> Nhay len
        } else if (gapAhead && canJumpAcrossGap(client, player.getBlockPos(), moveDir)) {
            shouldJump = true; // Co ho 1-3 block nhung co dat o bo ben kia -> Nhay vot qua!
        }

        if (shouldJump && player.isOnGround()) {
            player.jump();
            client.options.jumpKey.setPressed(true);
            client.options.sneakKey.setPressed(false);
        } else {
            client.options.jumpKey.setPressed(false);
            // Chi giu Sneak bac cau khi duoi chan la ho sau khong co bo ben kia
            BlockPos below = player.getBlockPos().down();
            if (config.autoBridgeEnabled && client.world.getBlockState(below).isAir() && !canJumpAcrossGap(client, player.getBlockPos(), moveDir)) {
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
        }
        return false;
    }

    /**
     * Kiem tra xem phia truoc 2-4 blocks co mat dat de nhay qua ho hay khong
     */
    private boolean canJumpAcrossGap(MinecraftClient client, BlockPos start, Direction dir) {
        for (int dist = 2; dist <= 4; dist++) {
            BlockPos landPos = start.offset(dir, dist);
            if (!client.world.getBlockState(landPos.down()).isAir()
                    || !client.world.getBlockState(landPos).isAir()
                    || !client.world.getBlockState(landPos.down(2)).isAir()) {
                return true;
            }
        }
        return false;
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

    private int findCobblestone(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(Items.COBBLESTONE)) return i;
        return -1;
    }
}
