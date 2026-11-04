package com.buildbot;

import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings({"unchecked", "rawtypes"})
public class AntiSusRotator {

    // Luu thoi gian cac khoi vua dat de khong bam chong len Ghost Block chua duoc Server xac nhan
    public static final Map recentlyPlaced = new ConcurrentHashMap();

    public static boolean stepSmoothRotation(MinecraftClient client, float targetY, float targetP, float speed) {
        ClientPlayerEntity player = client.player;
        if (player == null) return true;

        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        float diffYaw = MathHelper.wrapDegrees(targetY - currentYaw);
        float diffPitch = targetP - currentPitch;

        if (Math.abs(diffYaw) < 2.0f && Math.abs(diffPitch) < 2.0f) {
            return true;
        }

        float maxStep = 85.0f;
        float jitter = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.3f;
        float stepYaw = MathHelper.clamp(diffYaw * 0.8f, -maxStep, maxStep) + jitter;
        float stepPitch = MathHelper.clamp(diffPitch * 0.8f, -maxStep, maxStep) + jitter;

        float gcd = getMouseGCD(client);
        if (gcd > 0.0001f) {
            stepYaw -= (stepYaw % gcd);
            stepPitch -= (stepPitch % gcd);
        }

        player.setYaw(currentYaw + stepYaw);
        player.setPitch(MathHelper.clamp(currentPitch + stepPitch, -90.0f, 90.0f));
        return Math.abs(MathHelper.wrapDegrees(targetY - player.getYaw())) < 2.5f
                && Math.abs(targetP - player.getPitch()) < 2.5f;
    }

    private static float getMouseGCD(MinecraftClient client) {
        double s = client.options.getMouseSensitivity().getValue();
        double f = s * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    /**
     * Kiem tra mat clickFace co thuc su nhin thay duoc tu mat nguoi choi hay khong (Chong Ghost Block do click xuyen tuong)
     */
    public static boolean isFaceVisibleFromEyes(Vec3d eye, BlockPos supportPos, Direction clickFace) {
        return switch (clickFace) {
            case UP -> eye.y > supportPos.getY() + 0.95;
            case DOWN -> eye.y < supportPos.getY() + 0.05;
            case NORTH -> eye.z < supportPos.getZ() + 0.05;
            case SOUTH -> eye.z > supportPos.getZ() + 0.95;
            case WEST -> eye.x < supportPos.getX() + 0.05;
            case EAST -> eye.x > supportPos.getX() + 0.95;
        };
    }

    public static BlockHitResult findValidPlaceHit(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        if (client.world == null || client.player == null) return null;
        Vec3d eye = client.player.getEyePos();
        Block block = targetState.getBlock();

        // 1. Hopper: Phai bam vao mat khoi ma day Hopper tro vao
        if (block instanceof HopperBlock && targetState.contains(Properties.HOPPER_FACING)) {
            Direction outDir = targetState.get(Properties.HOPPER_FACING);
            BlockPos support = targetPos.offset(outDir);
            Direction clickFace = outDir.getOpposite();
            if (isConfirmedSolidSupport(client, support) && isFaceVisibleFromEyes(eye, support, clickFace)) {
                return buildHitResult(support, clickFace, targetState);
            }
        }

        // 2. Quet 6 huong tim khoi diem tua da duoc Server xac nhan va mat do phai nhin thay duoc tu mat
        Direction[] order = new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        BlockPos bestSupport = null;
        Direction bestFace = Direction.UP;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : order) {
            BlockPos neighbor = targetPos.offset(dir);
            if (!isConfirmedSolidSupport(client, neighbor)) continue;

            Direction clickFace = dir.getOpposite();
            if (!isFaceVisibleFromEyes(eye, neighbor, clickFace)) continue;

            Vec3d faceCenter = Vec3d.ofCenter(neighbor).add(Vec3d.of(clickFace.getVector()).multiply(0.5));
            double d = eye.squaredDistanceTo(faceCenter);
            if (d > 19.5) continue; // Gioi han tam voi an toan 4.4 blocks

            if (!isInteractiveBlock(client.world.getBlockState(neighbor).getBlock())) {
                d -= 6.0;
            }
            if (d < bestDist) {
                bestDist = d;
                bestSupport = neighbor;
                bestFace = clickFace;
            }
        }

        return bestSupport != null ? buildHitResult(bestSupport, bestFace, targetState) : null;
    }

    private static BlockHitResult buildHitResult(BlockPos supportPos, Direction clickFace, BlockState targetState) {
        double yOff = 0.5;
        if (clickFace.getAxis().isHorizontal()) {
            if (targetState.contains(Properties.SLAB_TYPE) && targetState.get(Properties.SLAB_TYPE) == SlabType.TOP) yOff = 0.8;
            else if (targetState.contains(Properties.BLOCK_HALF) && targetState.get(Properties.BLOCK_HALF) == BlockHalf.TOP) yOff = 0.8;
            else if (targetState.contains(Properties.SLAB_TYPE) || targetState.contains(Properties.BLOCK_HALF)) yOff = 0.2;
        }
        Vec3d center = new Vec3d(supportPos.getX() + 0.5, supportPos.getY() + yOff, supportPos.getZ() + 0.5);
        Vec3d hitVec = center.add(Vec3d.of(clickFace.getVector()).multiply(0.5));
        return new BlockHitResult(hitVec, clickFace, supportPos, false);
    }

    /**
     * Chi dung khoi lam diem tua neu khoi do la khoi ran VA da dat cach day it nhat 250ms (tranh bam vao Ghost Block)
     */
    public static boolean isConfirmedSolidSupport(MinecraftClient client, BlockPos pos) {
        if (!isSolidSupport(client, pos)) return false;
        Long time = (Long) recentlyPlaced.get(pos);
        if (time != null && (System.currentTimeMillis() - time) < 250L) {
            return false;
        }
        return true;
    }

    public static boolean isSolidSupport(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState s = client.world.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof FireBlock);
    }

    public static boolean isInteractiveBlock(Block b) {
        return b instanceof BlockWithEntity || b instanceof AbstractRedstoneGateBlock
                || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock || b instanceof CraftingTableBlock
                || b instanceof AnvilBlock || b instanceof LeverBlock || b instanceof ButtonBlock
                || b instanceof HopperBlock || b instanceof ObserverBlock;
    }

    public static boolean requiresSneakToPlace(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        BlockHitResult hit = findValidPlaceHit(client, targetPos, targetState);
        if (hit == null || client.world == null) return false;
        Block supportBlock = client.world.getBlockState(hit.getBlockPos()).getBlock();
        return isInteractiveBlock(supportBlock) || (targetState.getBlock() instanceof HopperBlock);
    }

    /**
     * Luon nhin truc tiep vao mat khoi diem tua de GrimAC khong bao gio chan Raytrace
     */
    public static float[] getRequiredRotationForState(BlockPos pos, BlockState targetState, ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        BlockHitResult hit = findValidPlaceHit(client, pos, targetState);
        Vec3d targetVec = (hit != null) ? hit.getPos() : Vec3d.ofCenter(pos);
        Vec3d eye = player.getEyePos();

        double dx = targetVec.x - eye.x;
        double dy = targetVec.y - eye.y;
        double dz = targetVec.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float aimYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float aimPitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));

        Block block = targetState.getBlock();
        if (targetState.contains(Properties.FACING) && !(block instanceof HopperBlock)) {
            Direction facing = targetState.get(Properties.FACING);
            Direction lookDir = (block instanceof ObserverBlock) ? facing : facing.getOpposite();
            float[] dirAngles = directionToAngles(lookDir);
            if (lookDir.getAxis().isVertical()) {
                aimPitch = dirAngles[1];
            } else {
                // Giữ tia nhìn hướng về khối nhưng nghiêng góc Yaw khớp hướng đặt
                float diff = MathHelper.wrapDegrees(dirAngles[0] - aimYaw);
                if (Math.abs(diff) <= 65.0f) aimYaw = dirAngles[0];
            }
        } else if (targetState.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = targetState.get(Properties.HORIZONTAL_FACING);
            Direction lookDir = (block instanceof AbstractRedstoneGateBlock || block instanceof ChestBlock || block instanceof AbstractFurnaceBlock)
                    ? facing.getOpposite() : facing;
            float[] dirAngles = directionToAngles(lookDir);
            float diff = MathHelper.wrapDegrees(dirAngles[0] - aimYaw);
            if (Math.abs(diff) <= 65.0f) aimYaw = dirAngles[0];
        }

        return new float[]{aimYaw, aimPitch};
    }

    public static boolean placeBlockSmart(MinecraftClient client, BlockPos targetPos, BlockState targetState, Hand hand) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.world == null) return false;

        if (!targetState.getCollisionShape(client.world, targetPos).isEmpty()
                && player.getBoundingBox().expand(0.05).intersects(new Box(targetPos))) {
            return false;
        }

        BlockHitResult hitResult = findValidPlaceHit(client, targetPos, targetState);
        if (hitResult == null) return false;

        // Uu tien dung ket qua Raycast that tu mat nguoi choi neu trung khop khoi diem tua (100% Legit voi GrimAC)
        HitResult realRay = player.raycast(4.5, 1.0F, false);
        if (realRay instanceof BlockHitResult realBlockHit) {
            if (realBlockHit.getBlockPos().offset(realBlockHit.getSide()).equals(targetPos)) {
                hitResult = realBlockHit;
            }
        }

        ActionResult res = client.interactionManager.interactBlock(player, hand, hitResult);
        player.swingHand(hand);
        recentlyPlaced.put(targetPos.toImmutable(), System.currentTimeMillis());
        return res.isAccepted();
    }

    private static float[] directionToAngles(Direction dir) {
        return switch (dir) {
            case UP -> new float[]{0.0f, -89.0f};
            case DOWN -> new float[]{0.0f, 89.0f};
            case SOUTH -> new float[]{0.0f, 0.0f};
            case WEST -> new float[]{90.0f, 0.0f};
            case NORTH -> new float[]{180.0f, 0.0f};
            case EAST -> new float[]{-90.0f, 0.0f};
        };
    }
}
