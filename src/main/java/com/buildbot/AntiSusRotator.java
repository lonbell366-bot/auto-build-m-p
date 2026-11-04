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
import net.minecraft.util.math.*;

import java.util.concurrent.ThreadLocalRandom;

public class AntiSusRotator {

    /**
     * Xoay dau nhanh (Snap-Smooth) + GCD Fix: Vay tam trong 1-2 ticks ma khong bi GrimAC bat
     */
    public static boolean stepSmoothRotation(MinecraftClient client, float targetY, float targetP, float speed) {
        ClientPlayerEntity player = client.player;
        if (player == null) return true;

        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        float diffYaw = MathHelper.wrapDegrees(targetY - currentYaw);
        float diffPitch = targetP - currentPitch;

        if (Math.abs(diffYaw) < 2.5f && Math.abs(diffPitch) < 2.5f) {
            return true;
        }

        // Cho phep vay tam nhanh toi da 95 do / tick + nhieu vi mo (Micro-Jitter) nhu chuot that
        float maxStep = 95.0f;
        float jitter = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.4f;
        float stepYaw = MathHelper.clamp(diffYaw * 0.85f, -maxStep, maxStep) + jitter;
        float stepPitch = MathHelper.clamp(diffPitch * 0.85f, -maxStep, maxStep) + jitter;

        float gcd = getMouseGCD(client);
        if (gcd > 0.0001f) {
            stepYaw -= (stepYaw % gcd);
            stepPitch -= (stepPitch % gcd);
        }

        player.setYaw(currentYaw + stepYaw);
        player.setPitch(MathHelper.clamp(currentPitch + stepPitch, -90.0f, 90.0f));
        return Math.abs(MathHelper.wrapDegrees(targetY - player.getYaw())) < 3.0f
                && Math.abs(targetP - player.getPitch()) < 3.0f;
    }

    private static float getMouseGCD(MinecraftClient client) {
        double s = client.options.getMouseSensitivity().getValue();
        double f = s * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    public static BlockHitResult findValidPlaceHit(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        if (client.world == null || client.player == null) return null;
        Block block = targetState.getBlock();

        // 1. Hopper: Bam truc tiep vao mat khoi ma day phễu (output) tro vao
        if (block instanceof HopperBlock && targetState.contains(Properties.HOPPER_FACING)) {
            Direction outDir = targetState.get(Properties.HOPPER_FACING);
            BlockPos support = targetPos.offset(outDir);
            if (isSolidSupport(client, support)) {
                return buildHitResult(support, outDir.getOpposite(), targetState);
            }
        }

        // 2. Tim khoi ran xung quanh lam diem tua
        Direction[] order = new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        BlockPos bestSupport = null;
        Direction bestFace = Direction.UP;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = client.player.getEyePos();

        for (Direction dir : order) {
            BlockPos neighbor = targetPos.offset(dir);
            if (!isSolidSupport(client, neighbor)) continue;

            Direction clickFace = dir.getOpposite();
            Vec3d faceCenter = Vec3d.ofCenter(neighbor).add(Vec3d.of(clickFace.getVector()).multiply(0.5));
            double d = eye.squaredDistanceTo(faceCenter);
            if (d > 21.0) continue; // Ngoai tam voi 4.5 blocks

            if (!isInteractiveBlock(client.world.getBlockState(neighbor).getBlock())) {
                d -= 8.0;
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
            if (targetState.contains(Properties.SLAB_TYPE) && targetState.get(Properties.SLAB_TYPE) == SlabType.TOP) yOff = 0.85;
            else if (targetState.contains(Properties.BLOCK_HALF) && targetState.get(Properties.BLOCK_HALF) == BlockHalf.TOP) yOff = 0.85;
            else if (targetState.contains(Properties.SLAB_TYPE) || targetState.contains(Properties.BLOCK_HALF)) yOff = 0.15;
        }
        Vec3d center = new Vec3d(supportPos.getX() + 0.5, supportPos.getY() + yOff, supportPos.getZ() + 0.5);
        Vec3d hitVec = center.add(Vec3d.of(clickFace.getVector()).multiply(0.5));
        return new BlockHitResult(hitVec, clickFace, supportPos, false);
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

    public static float[] getRequiredRotationForState(BlockPos pos, BlockState targetState, ClientPlayerEntity player) {
        Block block = targetState.getBlock();

        if (targetState.contains(Properties.FACING) && !(block instanceof HopperBlock)) {
            Direction facing = targetState.get(Properties.FACING);
            Direction lookDir = (block instanceof ObserverBlock) ? facing : facing.getOpposite();
            return directionToAngles(lookDir);
        }

        if (targetState.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = targetState.get(Properties.HORIZONTAL_FACING);
            Direction lookDir = (block instanceof AbstractRedstoneGateBlock || block instanceof ChestBlock || block instanceof AbstractFurnaceBlock)
                    ? facing.getOpposite() : facing;
            return directionToAngles(lookDir);
        }

        BlockHitResult hit = findValidPlaceHit(MinecraftClient.getInstance(), pos, targetState);
        Vec3d targetVec = (hit != null) ? hit.getPos() : Vec3d.ofCenter(pos);
        Vec3d eye = player.getEyePos();
        double dx = targetVec.x - eye.x;
        double dy = targetVec.y - eye.y;
        double dz = targetVec.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));
        return new float[]{yaw, pitch};
    }

    public static boolean placeBlockSmart(MinecraftClient client, BlockPos targetPos, BlockState targetState, Hand hand) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.world == null) return false;

        // Khong dat khoi neu nhan vat dang dung de len chinh o do (tranh Ghost Block)
        if (!targetState.getCollisionShape(client.world, targetPos).isEmpty()
                && player.getBoundingBox().intersects(new Box(targetPos))) {
            return false;
        }

        BlockHitResult hitResult = findValidPlaceHit(client, targetPos, targetState);
        if (hitResult == null) return false;

        ActionResult res = client.interactionManager.interactBlock(player, hand, hitResult);
        player.swingHand(hand);
        return res.isAccepted() || !client.world.getBlockState(targetPos).isAir();
    }

    private static float[] directionToAngles(Direction dir) {
        return switch (dir) {
            case UP -> new float[]{0.0f, -90.0f};
            case DOWN -> new float[]{0.0f, 90.0f};
            case SOUTH -> new float[]{0.0f, 0.0f};
            case WEST -> new float[]{90.0f, 0.0f};
            case NORTH -> new float[]{180.0f, 0.0f};
            case EAST -> new float[]{-90.0f, 0.0f};
        };
    }
}
