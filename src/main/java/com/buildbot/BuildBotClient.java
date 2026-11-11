package com.buildbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BuildBotClient implements ClientModInitializer {
    public static BuildBotClient INSTANCE;
    public static BuildBotConfig config;
    public SchematicData schematic = null;
    public String botStatusText = "Idle";
    public int currentLayerY = 0;

    private final CustomPathfinder pathfinder = new CustomPathfinder();
    private final OrderAndSellManager orderSell = new OrderAndSellManager();
    private BlockPos easyPendingPos = null, preparedPos = null;
    private float savedYaw = 0f, savedPitch = 0f;
    private boolean hasSavedRot = false, openGui = false, wasF10 = false, isBotEating = false;
    private int retryRound = 0, delayTimer = 0, slotSync = 0, easyCd = 0;
    private static Field verifiedSlotField = null;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        config = BuildBotConfig.load();
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            String s = msg.trim().toLowerCase();
            if (s.equals(".bb") || s.equals("#bb") || s.equals(".ae")) { openGui = true; return false; }
            return true;
        });
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> {
            if (config.autoResumeEnabled && config.hasSavedOrigin && schematic == null) {
                try { schematic = SchematicData.loadFromFile(config.activeSchematicName); } catch (Exception ignored) {}
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) return;
        if (slotSync > 0) slotSync--;
        if (easyCd > 0) easyCd--;
        if (delayTimer > 0) delayTimer--;

        // Hoan tat dat khoi can xoay huong (Observer/Piston/Repeater) sau khi Server da nhan goc nhin
        if (easyPendingPos != null) {
            if (slotSync > 0) return;
            BlockState exp = getExpectedState(easyPendingPos);
            if (exp != null && !exp.isAir()) {
                boolean snk = AntiSusRotator.requiresSneakToPlace(c, easyPendingPos, exp);
                if (snk) c.options.sneakKey.setPressed(true);
                AntiSusRotator.placeBlockSmart(c, easyPendingPos, exp, Hand.MAIN_HAND);
                if (snk) c.options.sneakKey.setPressed(false);
            }
            if (hasSavedRot) { p.setYaw(savedYaw); p.setPitch(savedPitch); hasSavedRot = false; }
            easyPendingPos = null;
            easyCd = ThreadLocalRandom.current().nextInt(1, 3);
            return;
        }

        try {
            long win = GLFW.glfwGetCurrentContext();
            if (win != 0L) {
                boolean k = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F10) == GLFW.GLFW_PRESS;
                if (k && !wasF10 && c.currentScreen == null) c.setScreen(new BuildBotScreen(null));
                wasF10 = k;
            }
        } catch (Throwable ignored) {}

        if (openGui && c.currentScreen == null) { openGui = false; c.setScreen(new BuildBotScreen(null)); return; }

        if (orderSell.isBusy()) {
            pathfinder.stopMovement(c);
            botStatusText = "Dang thao tac chuot GUI...";
            orderSell.tick(c, config);
            return;
        }

        if (c.currentScreen != null) return;
        if (config.autoEatEnabled && handleAutoEat(c, p)) return;
        orderSell.checkAndStartAutoSell(c, config);

        if (config.easyPlaceEnabled && c.options.useKey.isPressed() && easyCd == 0 && tryAccurateEasyPlace(c, p)) return;

        if (config.autoBuildEnabled && schematic == null) {
            syncFromLitematica(c);
            if (schematic == null) { config.autoBuildEnabled = false; return; }
        }
        if (!config.autoBuildEnabled || schematic == null) { pathfinder.stopMovement(c); return; }

        runBuildTick(c, p);
    }

    /**
     * EASY PLACE CHUAN XAC: Uu tien o ngay tai tam ngam Crosshair va doi dong bo dung Slot 100%
     */
    private boolean tryAccurateEasyPlace(MinecraftClient c, ClientPlayerEntity p) {
        List candidates = new ArrayList();

        // Uu tien 1: O nam ngay tren mat khoi ma tam ngam dang chi vao
        if (c.crosshairTarget instanceof BlockHitResult hit) {
            candidates.add(hit.getBlockPos().offset(hit.getSide()));
            candidates.add(hit.getBlockPos());
        }

        // Uu tien 2: Quet doc theo tia nhin
        Vec3d eye = p.getEyePos(), look = p.getRotationVec(1.0F);
        for (double d = 1.0; d <= 4.4; d += 0.25) {
            BlockPos rp = BlockPos.ofFloored(eye.add(look.multiply(d)));
            if (!candidates.contains(rp)) candidates.add(rp);
        }

        for (Object obj : candidates) {
            BlockPos pos = (BlockPos) obj;
            BlockState act = c.world.getBlockState(pos), exp = getExpectedState(pos);
            if (exp == null || exp.isAir()) continue;

            if (needsWater(act, exp)) {
                if (equipRaw(c, Items.WATER_BUCKET) && slotSync == 0) {
                    AntiSusRotator.placeWaterOrLava(c, pos, exp);
                    easyCd = 2;
                }
                return true;
            }

            if (!act.isAir() || AntiSusRotator.findValidPlaceHit(c, pos, exp) == null) continue;
            if (!equipItem(c, exp) || slotSync > 0) return true; // Cho tick sau khi vua doi slot xong!

            if (AntiSusRotator.needsStrictLookRotation(exp)) {
                savedYaw = p.getYaw(); savedPitch = p.getPitch(); hasSavedRot = true;
                float[] r = AntiSusRotator.getRequiredRotationForState(pos, exp, p);
                AntiSusRotator.stepSmoothRotation(c, r[0], r[1], 1.0f);
                easyPendingPos = pos;
                return true;
            }

            boolean snk = AntiSusRotator.requiresSneakToPlace(c, pos, exp);
            if (snk) c.options.sneakKey.setPressed(true);
            boolean ok = AntiSusRotator.placeBlockSmart(c, pos, exp, Hand.MAIN_HAND);
            if (snk) c.options.sneakKey.setPressed(false);
            if (ok) { easyCd = ThreadLocalRandom.current().nextInt(1, 3); return true; }
        }
        return false;
    }

    private void runBuildTick(MinecraftClient c, ClientPlayerEntity p) {
        if (currentLayerY >= schematic.height) {
            config.autoBuildEnabled = false; config.save(); pathfinder.stopMovement(c);
            p.sendMessage(Text.literal("§a[BuildBot] Hoan thanh 100%!"), false);
            return;
        }

        long now = System.currentTimeMillis();
        List solidMiss = new ArrayList(), solidReady = new ArrayList(), fluidMiss = new ArrayList();

        for (int dy = 0; dy < config.layerHeight && (currentLayerY + dy) < schematic.height; dy++) {
            int y = currentLayerY + dy;
            for (int x = 0; x < schematic.width; x++) {
                for (int z = 0; z < schematic.length; z++) {
                    BlockPos wp = new BlockPos(config.originX + x, config.originY + y, config.originZ + z);
                    BlockState exp = getExpectedState(wp);
                    if (exp == null || exp.isAir()) continue;
                    Long last = (Long) AntiSusRotator.recentlyPlaced.get(wp);
                    if (last != null && (now - last) < 220L) continue;

                    BlockState act = c.world.getBlockState(wp);
                    if (exp.isOf(Blocks.WATER) || exp.isOf(Blocks.LAVA)) {
                        if (!act.isOf(exp.getBlock())) fluidMiss.add(wp);
                        continue;
                    }
                    if (!isSolidMatch(act, exp)) {
                        if (hasItem(p, getSolidItem(exp))) {
                            solidMiss.add(wp);
                            if (AntiSusRotator.findValidPlaceHit(c, wp, exp) != null) solidReady.add(wp);
                        }
                    } else if (needsWater(act, exp) && hasItem(p, Items.WATER_BUCKET)) {
                        fluidMiss.add(wp);
                    }
                }
            }
        }

        if (solidMiss.isEmpty() && fluidMiss.isEmpty()) {
            currentLayerY += config.layerHeight; retryRound = 0; preparedPos = null;
            p.sendMessage(Text.literal("§b[BuildBot] Xong tang! Len Y = " + currentLayerY), true);
            if (p.isOnGround()) p.jump();
            delayTimer = ThreadLocalRandom.current().nextInt(3, 6);
            return;
        }

        // GIAI DOAN 2: Xay het block trong tang roi moi di do Nuoc / Lava
        if (solidMiss.isEmpty()) {
            fluidMiss.sort(Comparator.comparingDouble(o -> p.squaredDistanceTo(Vec3d.ofCenter((BlockPos) o))));
            BlockPos fp = (BlockPos) fluidMiss.get(0);
            BlockState fe = getExpectedState(fp);
            if (!pathfinder.tickMoveToTarget(c, fp, config) || delayTimer > 0) return;
            if (equipRaw(c, fe.isOf(Blocks.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET) && slotSync == 0) {
                AntiSusRotator.placeWaterOrLava(c, fp, fe);
                resetDelay();
            }
            return;
        }

        // GIAI DOAN 1: Sap xep theo thu tu phu thuoc (Khoi ran -> Hopper gan dich -> Hopper xa dich -> Redstone)
        List pool = solidReady.isEmpty() ? solidMiss : solidReady;
        pool.sort(Comparator.comparingInt(o -> getBuildOrderPriority((BlockPos) o))
                .thenComparingDouble(o -> p.squaredDistanceTo(Vec3d.ofCenter((BlockPos) o))));

        BlockPos pos = (BlockPos) pool.get(0);
        BlockState exp = getExpectedState(pos), act = c.world.getBlockState(pos);

        if (solidReady.isEmpty() && AntiSusRotator.findValidPlaceHit(c, pos, exp) == null && c.world.getBlockState(pos.down()).isAir()) {
            pos = pos.down(); exp = Blocks.COBBLESTONE.getDefaultState();
        }

        // Neu dat sai khoi hoac Hopper sai huong -> Tu dap khoi sai
        if (!act.isAir() && !act.getFluidState().isStill() && !act.isReplaceable()) {
            if (config.autoMineEnabled && c.interactionManager != null) {
                botStatusText = "Sua khoi sai: " + pos.toShortString();
                c.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
                p.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        // Buoc di toi sat khoi can dat
        if (!pathfinder.tickMoveToTarget(c, pos, config)) {
            botStatusText = "Di toi -> " + pos.toShortString();
            if (pathfinder.isTimedOut(config.moveTimeoutSeconds)) retryRound++;
            return;
        }
        if (delayTimer > 0 || !equipItem(c, exp) || slotSync > 0) return;

        c.options.sneakKey.setPressed(AntiSusRotator.requiresSneakToPlace(c, pos, exp));

        if (AntiSusRotator.needsStrictLookRotation(exp)) {
            float[] r = AntiSusRotator.getRequiredRotationForState(pos, exp, p);
            float oy = p.getYaw(), op = p.getPitch();
            AntiSusRotator.stepSmoothRotation(c, r[0], r[1], 1.0f);
            if (!pos.equals(preparedPos)) {
                preparedPos = pos; savedYaw = oy; savedPitch = op; hasSavedRot = true;
                return;
            }
        }

        botStatusText = "Dat: " + exp.getBlock().getName().getString();
        if (AntiSusRotator.placeBlockSmart(c, pos, exp, Hand.MAIN_HAND)) resetDelay();
        if (hasSavedRot) { p.setYaw(savedYaw); p.setPitch(savedPitch); hasSavedRot = false; }
        preparedPos = null;
    }

    /**
     * Tinh thu tu uu tien chuoi Hopper:
     * Khoi thuong/Ruong = 10 | Hopper cam vao Ruong = 21 | Hopper cam vao Hopper 1 = 22 | Hopper 3 = 23...
     */
    private int getBuildOrderPriority(BlockPos wp) {
        BlockState st = getExpectedState(wp);
        if (st == null || st.isAir()) return 100;
        if (st.getBlock() instanceof HopperBlock && st.contains(Properties.HOPPER_FACING)) {
            int depth = 0;
            BlockPos cur = wp;
            BlockState curSt = st;
            while (depth < 25 && curSt != null && curSt.getBlock() instanceof HopperBlock && curSt.contains(Properties.HOPPER_FACING)) {
                cur = cur.offset(curSt.get(Properties.HOPPER_FACING));
                curSt = getExpectedState(cur);
                depth++;
            }
            return 20 + depth;
        }
        return SchematicData.getDependencyOrder(st) * 10;
    }

    private boolean needsWater(BlockState a, BlockState e) {
        return e != null && e.contains(Properties.WATERLOGGED) && e.get(Properties.WATERLOGGED)
                && a.isOf(e.getBlock()) && a.contains(Properties.WATERLOGGED) && !a.get(Properties.WATERLOGGED);
    }

    /**
     * Kiem tra khop ca huong HOPPER_FACING de neu Hopper bi dat sai huong thi bot biet va sua lai!
     */
    private boolean isSolidMatch(BlockState a, BlockState e) {
        if (e == null || e.isAir()) return true;
        if (!a.isOf(e.getBlock())) return false;
        if (e.contains(Properties.HOPPER_FACING) && a.get(Properties.HOPPER_FACING) != e.get(Properties.HOPPER_FACING)) return false;
        if (e.contains(Properties.FACING) && a.get(Properties.FACING) != e.get(Properties.FACING)) return false;
        return !e.contains(Properties.HORIZONTAL_FACING) || a.get(Properties.HORIZONTAL_FACING) == e.get(Properties.HORIZONTAL_FACING);
    }

    private boolean hasItem(ClientPlayerEntity p, Item item) {
        if (item == Items.AIR) return false;
        for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isOf(item)) return true;
        return false;
    }

    public static int getSelectedSlot(PlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i) == p.getMainHandStack() && !p.getMainHandStack().isEmpty()) return i;
        }
        try {
            if (verifiedSlotField != null) return verifiedSlotField.getInt(p.getInventory());
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * Doi chieu truc tiep p.getMainHandStack() == p.getInventory().getStack(slot) de khong bao gio bi 155 mods danh lua!
     */
    public static void setSelectedSlot(PlayerEntity p, int slot) {
        if (slot < 0 || slot > 8) return;
        Object inv = p.getInventory();
        try {
            if (verifiedSlotField != null) {
                verifiedSlotField.setInt(inv, slot);
                return;
            }
            for (Field f : inv.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true);
                    int old = f.getInt(inv);
                    if (old >= 0 && old <= 8) {
                        f.setInt(inv, slot);
                        if (p.getMainHandStack() == p.getInventory().getStack(slot)) {
                            verifiedSlotField = f;
                            return;
                        }
                        f.setInt(inv, old);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private Item getSolidItem(BlockState s) {
        if (s.isOf(Blocks.WATER)) return Items.WATER_BUCKET;
        if (s.isOf(Blocks.LAVA)) return Items.LAVA_BUCKET;
        if (s.isOf(Blocks.REDSTONE_WIRE)) return Items.REDSTONE;
        return s.getBlock().asItem();
    }

    private boolean equipItem(MinecraftClient c, BlockState s) { return equipRaw(c, getSolidItem(s)); }

    private boolean equipRaw(MinecraftClient c, Item item) {
        if (item == Items.AIR || c.player == null) return false;
        ClientPlayerEntity p = c.player;
        if (p.getMainHandStack().isOf(item)) return true;

        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) {
                setSelectedSlot(p, i);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                slotSync = ThreadLocalRandom.current().nextInt(1, 3);
                return true;
            }
        }
        int cur = getSelectedSlot(p);
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(item)) {
                pathfinder.stopMovement(c);
                orderSell.startInventoryFetch(c, i, cur);
                return false;
            }
        }
        return false;
    }

    private boolean handleAutoEat(MinecraftClient c, ClientPlayerEntity p) {
        if (p.getHungerManager().getFoodLevel() > config.hungerThreshold) {
            if (isBotEating) { c.options.useKey.setPressed(false); isBotEating = false; }
            return false;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.contains(DataComponentTypes.FOOD) && !s.isOf(Items.ROTTEN_FLESH)) {
                isBotEating = true; setSelectedSlot(p, i); c.options.useKey.setPressed(true);
                if (!p.isUsingItem() && c.interactionManager != null) c.interactionManager.interactItem(p, Hand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    public void syncFromLitematica(MinecraftClient c) {
        SchematicData cap = SchematicData.captureFromLitematica(c, config);
        if (cap != null && c.player != null) {
            schematic = cap; currentLayerY = 0; retryRound = 0;
            c.player.sendMessage(Text.literal("§a[BuildBot] Da nhan Litematica: §e" + SchematicData.getActiveLitematicaName()), false);
        }
    }

    public void loadSchematicAtPlayer(MinecraftClient c) { syncFromLitematica(c); }

    public void printMaterialList(MinecraftClient c) {
        if (c == null || c.player == null || schematic == null) return;
        c.player.sendMessage(Text.literal("§6=== Vat Lieu ==="), false);
        for (Object o : schematic.totalMaterials.entrySet()) {
            Map.Entry e = (Map.Entry) o; Block b = (Block) e.getKey(); int need = (Integer) e.getValue();
            int have = OrderAndSellManager.countItemInInventory(c.player, b.asItem());
            c.player.sendMessage(Text.literal("§f- " + b.getName().getString() + ": " + (have >= need ? "§a" : "§c") + have + "/" + need), false);
        }
    }

    private BlockState getExpectedState(BlockPos wp) {
        World lw = SchematicData.getLitematicaWorld();
        if (lw != null) {
            BlockState ls = lw.getBlockState(wp);
            if (ls != null && !ls.isAir()) return ls;
        }
        return schematic == null ? Blocks.AIR.getDefaultState() : schematic.getBlockState(wp.getX() - config.originX, wp.getY() - config.originY, wp.getZ() - config.originZ);
    }

    private void resetDelay() {
        int min = Math.max(1, config.minPlaceDelayTicks);
        int max = Math.max(min + 1, config.maxPlaceDelayTicks);
        delayTimer = ThreadLocalRandom.current().nextInt(min, max + 2);
    }
}

