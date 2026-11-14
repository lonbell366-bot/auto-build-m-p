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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings({"unchecked", "rawtypes"})
public class AntiSusRotator {

    public static final Map recentlyPlaced = new ConcurrentHashMap();

    public static boolean stepSmoothRotation(MinecraftClient client, float targetY, float targetP, float speed) {
        ClientPlayerEntity player = client.player;
        if (player == null) return true;

        float diffYaw = MathHelper.wrapDegrees(targetY - player.getYaw());
        float diffPitch = targetP - player.getPitch();

        if (Math.abs(diffYaw) < 1.2f && Math.abs(diffPitch) < 1.2f) return true;

        float factor = MathHelper.clamp(speed, 0.5f, 1.0f);
        float stepY = diffYaw * factor;
        float stepP = diffPitch * factor;

        float gcd = getMouseGCD(client);
        if (gcd > 0.0001f) {
            stepY -= (stepY % gcd);
            stepP -= (stepP % gcd);
        }

        player.setYaw(player.getYaw() + stepY);
        player.setPitch(MathHelper.clamp(player.getPitch() + stepP, -90.0f, 90.0f));
        return Math.abs(MathHelper.wrapDegrees(targetY - player.getYaw())) < 2.0f
                && Math.abs(targetP - player.getPitch()) < 2.0f;
    }

    private static float getMouseGCD(MinecraftClient client) {
        double s = client.options.getMouseSensitivity().getValue();
        double f = s * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    public static boolean needsStrictLookRotation(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof HopperBlock) return false;
        return state.contains(Properties.FACING) || state.contains(Properties.HORIZONTAL_FACING);
    }

    public static BlockHitResult findValidPlaceHit(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        if (client.world == null || client.player == null || targetState == null) return null;
        Vec3d eye = client.player.getEyePos();
        Block block = targetState.getBlock();

        // HOPPER: Bam chinh xac vao khoi ma voi Hopper chi vao
        if (block instanceof HopperBlock) {
            Direction outDir = targetState.contains(Properties.HOPPER_FACING)
                    ? targetState.get(Properties.HOPPER_FACING) : Direction.DOWN;

            BlockPos primarySupport = targetPos.offset(outDir);
            if (isConfirmedSolidSupport(client, primarySupport) && eye.squaredDistanceTo(Vec3d.ofCenter(primarySupport)) <= 21.0) {
                return buildHitResult(client, primarySupport, outDir.getOpposite(), targetState);
            }
            if (outDir == Direction.DOWN) {
                BlockPos above = targetPos.up();
                if (isConfirmedSolidSupport(client, above) && eye.squaredDistanceTo(Vec3d.ofCenter(above)) <= 21.0) {
                    return buildHitResult(client, above, Direction.DOWN, targetState);
                }
            }
            return null;
        }

        Direction[] order = new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        BlockPos bestSupport = null;
        Direction bestFace = Direction.UP;
        double bestScore = Double.MAX_VALUE;

        for (Direction dir : order) {
            BlockPos neighbor = targetPos.offset(dir);
            if (!isConfirmedSolidSupport(client, neighbor)) continue;

            Direction clickFace = dir.getOpposite();
            Vec3d faceCenter = Vec3d.ofCenter(neighbor).add(Vec3d.of(clickFace.getVector()).multiply(0.5));
            double d = eye.squaredDistanceTo(faceCenter);
            if (d > 21.0) continue;

            if (dir == Direction.DOWN) d -= 4.0;
            if (!isInteractiveBlock(client.world.getBlockState(neighbor).getBlock())) d -= 5.0;

            if (d < bestScore) {
                bestScore = d;
                bestSupport = neighbor;
                bestFace = clickFace;
            }
        }

        return bestSupport != null ? buildHitResult(client, bestSupport, bestFace, targetState) : null;
    }

    /**
     * Neu khoi diem tua la Hopper va bam mat ngang -> Bam vao vanh tren (yOff = 0.80) de khong bam hut vao khoang rong duoi day Hopper!
     */
    private static BlockHitResult buildHitResult(MinecraftClient client, BlockPos supportPos, Direction clickFace, BlockState targetState) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double rx = rnd.nextDouble(-0.06, 0.06);
        double rz = rnd.nextDouble(-0.06, 0.06);
        double yOff = 0.5 + rnd.nextDouble(-0.04, 0.04);

        Block supportBlock = client.world != null ? client.world.getBlockState(supportPos).getBlock() : Blocks.STONE;
        if (clickFace.getAxis().isHorizontal()) {
            if (supportBlock instanceof HopperBlock) {
                yOff = 0.80; // Vanh dac phia tren cua Hopper
            } else if (targetState.contains(Properties.SLAB_TYPE) && targetState.get(Properties.SLAB_TYPE) == SlabType.TOP) {
                yOff = 0.82;
            } else if (targetState.contains(Properties.BLOCK_HALF) && targetState.get(Properties.BLOCK_HALF) == BlockHalf.TOP) {
                yOff = 0.82;
            } else if (targetState.contains(Properties.SLAB_TYPE) || targetState.contains(Properties.BLOCK_HALF)) {
                yOff = 0.18;
            }
        }
        Vec3d center = new Vec3d(supportPos.getX() + 0.5 + rx, supportPos.getY() + yOff, supportPos.getZ() + 0.5 + rz);
        Vec3d hitVec = center.add(Vec3d.of(clickFace.getVector()).multiply(0.5));
        return new BlockHitResult(hitVec, clickFace, supportPos, false);
    }

    public static boolean isConfirmedSolidSupport(MinecraftClient client, BlockPos pos) {
        if (!isSolidSupport(client, pos)) return false;
        Long t = (Long) recentlyPlaced.get(pos);
        return t == null || (System.currentTimeMillis() - t) >= 260L;
    }

    public static boolean isSolidSupport(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState s = client.world.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && !s.isReplaceable();
    }

    public static boolean isInteractiveBlock(Block b) {
        return b instanceof BlockWithEntity || b instanceof AbstractRedstoneGateBlock
                || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock || b instanceof CraftingTableBlock
                || b instanceof AnvilBlock || b instanceof LeverBlock || b instanceof ButtonBlock
                || b instanceof HopperBlock;
    }

    public static boolean requiresSneakToPlace(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        BlockHitResult hit = findValidPlaceHit(client, targetPos, targetState);
        if (hit == null || client.world == null) return true;
        Block supportBlock = client.world.getBlockState(hit.getBlockPos()).getBlock();
        return isInteractiveBlock(supportBlock) || (targetState.getBlock() instanceof HopperBlock);
    }

    public static float[] getExactAnglesForHit(BlockPos pos, BlockState targetState, BlockHitResult hit, ClientPlayerEntity player) {
        Vec3d targetVec = (hit != null) ? hit.getPos() : Vec3d.ofCenter(pos);
        Vec3d eye = player.getEyePos();
        double dx = targetVec.x - eye.x, dy = targetVec.y - eye.y, dz = targetVec.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float aimYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float aimPitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));

        Block block = targetState.getBlock();
        if (targetState.contains(Properties.FACING) && !(block instanceof HopperBlock)) {
            Direction facing = targetState.get(Properties.FACING);
            Direction lookDir = (block instanceof ObserverBlock) ? facing : facing.getOpposite();
            float[] dirAngles = directionToAngles(lookDir);
            if (lookDir == Direction.UP) return new float[]{aimYaw, -85.0f};
            if (lookDir == Direction.DOWN) return new float[]{aimYaw, 85.0f};
            // Giu Pitch nhin xuong khoi (< 42 do) de GrimAC khong chan ma van dat dung huong ngang
            return new float[]{dirAngles[0], MathHelper.clamp(aimPitch, -40.0f, 40.0f)};
        }

        if (targetState.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = targetState.get(Properties.HORIZONTAL_FACING);
            Direction lookDir = (block instanceof AbstractRedstoneGateBlock || block instanceof ChestBlock || block instanceof AbstractFurnaceBlock)
                    ? facing.getOpposite() : facing;
            float[] dirAngles = directionToAngles(lookDir);
            return new float[]{dirAngles[0], MathHelper.clamp(aimPitch, -40.0f, 40.0f)};
        }

        return new float[]{aimYaw, aimPitch};
    }

    public static float[] getRequiredRotationForState(BlockPos pos, BlockState targetState, ClientPlayerEntity player) {
        BlockHitResult hit = findValidPlaceHit(MinecraftClient.getInstance(), pos, targetState);
        return getExactAnglesForHit(pos, targetState, hit, player);
    }

    public static boolean executeLockedPlace(MinecraftClient client, BlockPos targetPos, BlockState targetState, BlockHitResult lockedHit) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.world == null) return false;

        if (!targetState.getCollisionShape(client.world, targetPos).isEmpty()
                && player.getBoundingBox().intersects(new Box(targetPos).contract(0.06))) {
            return false;
        }

        BlockHitResult hitResult = (lockedHit != null) ? lockedHit : findValidPlaceHit(client, targetPos, targetState);
        if (hitResult == null) return false;

        player.setSprinting(false);
        ActionResult res = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
        player.swingHand(hand(Hand.MAIN_HAND));
        recentlyPlaced.put(targetPos.toImmutable(), System.currentTimeMillis());
        return res.isAccepted() || !client.world.getBlockState(targetPos).isAir();
    }

    private static Hand hand(Hand h) { return h; }

    public static boolean placeBlockSmart(MinecraftClient client, BlockPos targetPos, BlockState targetState, Hand hand) {
        return executeLockedPlace(client, targetPos, targetState, null);
    }

    public static boolean placeWaterOrLava(MinecraftClient client, BlockPos targetPos, BlockState expectedState) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.world == null) return false;

        BlockState actual = client.world.getBlockState(targetPos);
        BlockHitResult hit;

        if (!actual.isAir() && expectedState.contains(Properties.WATERLOGGED)) {
            hit = new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
        } else {
            hit = findValidPlaceHit(client, targetPos, expectedState);
            if (hit == null) hit = new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
        }

        Vec3d hitVec = hit.getPos();
        Vec3d eye = player.getEyePos();
        double dx = hitVec.x - eye.x, dy = hitVec.y - eye.y, dz = hitVec.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));

        stepSmoothRotation(client, yaw, pitch, 1.0f);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        recentlyPlaced.put(targetPos.toImmutable(), System.currentTimeMillis());
        return true;
    }

    private static float[] directionToAngles(Direction dir) {
        return switch (dir) {
            case UP -> new float[]{0.0f, -88.0f};
            case DOWN -> new float[]{0.0f, 88.0f};
            case SOUTH -> new float[]{0.0f, 0.0f};
            case WEST -> new float[]{90.0f, 0.0f};
            case NORTH -> new float[]{180.0f, 0.0f};
            case EAST -> new float[]{-90.0f, 0.0f};
        };
    }
}
