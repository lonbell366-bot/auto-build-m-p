package com.buildbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BuildBotClient implements ClientModInitializer {
    public static BuildBotClient INSTANCE;
    public static BuildBotConfig config;

    public SchematicData schematic = null;
    public String botStatusText = "Dang cho lenh (Idle)";
    public int currentLayerY = 0;

    private final CustomPathfinder pathfinder = new CustomPathfinder();
    private final OrderAndSellManager orderSell = new OrderAndSellManager();
    private int retryRound = 0, delayTimer = 0, slotSyncCooldown = 0, easyPlaceCooldown = 0;
    private boolean openGui = false, wasF10 = false, isBotEating = false, isPlacing = false;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        config = BuildBotConfig.load();

        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            String s = msg.trim().toLowerCase();
            if (s.equals(".bb") || s.equals("#bb") || s.equals(".ae")) { openGui = true; return false; }
            if (s.startsWith("/buildbot automine ")) { config.autoMineEnabled = s.endsWith("on"); config.save(); return false; }
            return true;
        });

        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> {
            if (config.autoResumeEnabled && config.hasSavedOrigin && schematic == null) {
                try { schematic = SchematicData.loadFromFile(config.activeSchematicName); } catch (Exception ignored) {}
            }
        });

        // Da bo world.isClient (field_9236) va them khoa isPlacing de khong bao gio crash tren 1.21.11
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (isPlacing || !config.easyPlaceEnabled || hand != Hand.MAIN_HAND || easyPlaceCooldown > 0) {
                return ActionResult.PASS;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return ActionResult.PASS;

            BlockPos pos = hit.getBlockPos().offset(hit.getSide());
            BlockState st = getExpectedState(pos);
            if (st != null && !st.isAir() && client.world.getBlockState(pos).isAir()) {
                if (equipItem(client, st) && slotSyncCooldown == 0) {
                    float[] rot = AntiSusRotator.getRequiredRotationForState(pos, st, client.player);
                    AntiSusRotator.stepSmoothRotation(client, rot[0], rot[1], 0.75f);
                    isPlacing = true;
                    boolean ok = AntiSusRotator.placeBlockSmart(client, pos, st, Hand.MAIN_HAND);
                    isPlacing = false;
                    if (ok) {
                        easyPlaceCooldown = 4;
                        return ActionResult.FAIL;
                    }
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        if (slotSyncCooldown > 0) slotSyncCooldown--;
        if (easyPlaceCooldown > 0) easyPlaceCooldown--;

        try {
            long win = GLFW.glfwGetCurrentContext();
            if (win != 0L) {
                boolean p = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F10) == GLFW.GLFW_PRESS;
                if (p && !wasF10 && client.currentScreen == null) client.setScreen(new BuildBotScreen(null));
                wasF10 = p;
            }
        } catch (Throwable ignored) {}

        if (openGui && client.currentScreen == null) { openGui = false; client.setScreen(new BuildBotScreen(null)); return; }
        if (orderSell.isBusy()) { botStatusText = "Dang lay do /order hoac /sell"; orderSell.tick(client, config); return; }
        if (client.currentScreen != null) return;
        if (config.autoEatEnabled && handleAutoEat(client, player)) { botStatusText = "Dang tu dong an..."; return; }

        orderSell.checkAndStartAutoSell(client, config);

        if (config.autoBuildEnabled && schematic == null) {
            syncFromLitematica(client);
            if (schematic == null) { config.autoBuildEnabled = false; return; }
        }
        if (!config.autoBuildEnabled || schematic == null) {
            botStatusText = "Dang tam dung (Idle)";
            pathfinder.stopMovement(client);
            return;
        }
        if (delayTimer > 0) { delayTimer--; return; }

        runBuildTick(client, player);
    }

    private void runBuildTick(MinecraftClient client, ClientPlayerEntity player) {
        if (currentLayerY >= schematic.height) {
            config.autoBuildEnabled = false; config.save(); pathfinder.stopMovement(client);
            botStatusText = "Hoan thanh 100%!";
            player.sendMessage(Text.literal("§a[BuildBot] Da hoan thanh 100% cong trinh!"), false);
            return;
        }

        List list = new ArrayList();
        for (int dy = 0; dy < config.layerHeight && (currentLayerY + dy) < schematic.height; dy++) {
            int y = currentLayerY + dy;
            for (int x = 0; x < schematic.width; x++) {
                for (int z = 0; z < schematic.length; z++) {
                    BlockPos wp = new BlockPos(config.originX + x, config.originY + y, config.originZ + z);
                    BlockState exp = getExpectedState(wp);
                    if (!isMatching(client.world.getBlockState(wp), exp)) {
                        if (!exp.isAir() && AntiSusRotator.findValidPlaceHit(client, wp, exp) == null) continue;
                        list.add(wp);
                    }
                }
            }
        }

        if (list.isEmpty()) {
            currentLayerY += config.layerHeight; retryRound = 0;
            botStatusText = "Chuyen len tang Y = " + currentLayerY;
            player.sendMessage(Text.literal("§b[BuildBot] Xong tang! Len tang Y = " + currentLayerY), true);
            return;
        }

        list.sort(Comparator.comparingInt(p -> SchematicData.getDependencyOrder(getExpectedState((BlockPos) p)))
                .thenComparingDouble(p -> player.squaredDistanceTo(Vec3d.ofCenter((BlockPos) p))));

        BlockPos pos = (BlockPos) list.get(0);
        BlockState exp = getExpectedState(pos);
        BlockState act = client.world.getBlockState(pos);

        if (!pathfinder.tickMoveToTarget(client, pos, config)) {
            botStatusText = "Dang di chuyen toi " + pos.toShortString();
            if (pathfinder.isTimedOut(config.moveTimeoutSeconds)) retryRound++;
            return;
        }

        if (!act.isAir() && !act.getFluidState().isStill()) {
            if (act.isOf(exp.getBlock()) && ((act.getBlock() instanceof RepeaterBlock && !Objects.equals(act.get(Properties.DELAY), exp.get(Properties.DELAY)))
                    || (act.getBlock() instanceof ComparatorBlock && act.get(Properties.COMPARATOR_MODE) != exp.get(Properties.COMPARATOR_MODE)))) {
                botStatusText = "Dang chinh Repeater/Comparator";
                isPlacing = true;
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
                isPlacing = false;
                resetDelay(); return;
            }
            if (config.autoMineEnabled && client.interactionManager != null) {
                botStatusText = "Dang dao khoi sai tai " + pos.toShortString();
                client.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
                player.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        if (exp.isAir()) return;
        if (!equipItem(client, exp)) {
            if (config.autoOrderEnabled && getItem(exp) != Items.AIR) orderSell.startOrderFetch(client, getItem(exp), 32);
            return;
        }

        if (slotSyncCooldown > 0) return;

        float[] rot = AntiSusRotator.getRequiredRotationForState(pos, exp, player);
        if (config.smoothLookAntiSus && !AntiSusRotator.stepSmoothRotation(client, rot[0], rot[1], config.smoothRotationSpeed)) {
            botStatusText = "Dang xoay goc nhin chuan...";
            return;
        }

        botStatusText = "Dang dat: " + exp.getBlock().getName().getString();
        isPlacing = true;
        boolean placed = AntiSusRotator.placeBlockSmart(client, pos, exp, Hand.MAIN_HAND);
        if (placed && exp.contains(Properties.WATERLOGGED) && exp.get(Properties.WATERLOGGED) && equipRawItem(client, Items.WATER_BUCKET)) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
        }
        isPlacing = false;

        if (placed) resetDelay();
    }

    public static int getSelectedSlot(PlayerEntity p) {
        try {
            for (Field f : p.getInventory().getClass().getDeclaredFields()) {
                if (f.getType() == int.class && (f.getName().equals("field_7545") || f.getName().equals("selectedSlot"))) {
                    f.setAccessible(true); return f.getInt(p.getInventory());
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    public static void setSelectedSlot(PlayerEntity p, int slot) {
        try {
            for (Field f : p.getInventory().getClass().getDeclaredFields()) {
                if (f.getType() == int.class && (f.getName().equals("field_7545") || f.getName().equals("selectedSlot"))) {
                    f.setAccessible(true); f.setInt(p.getInventory(), slot); return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private Item getItem(BlockState s) {
        if (s.isOf(Blocks.WATER)) return Items.WATER_BUCKET;
        if (s.isOf(Blocks.LAVA)) return Items.LAVA_BUCKET;
        if (s.isOf(Blocks.REDSTONE_WIRE)) return Items.REDSTONE;
        return s.getBlock().asItem();
    }

    private boolean equipItem(MinecraftClient c, BlockState s) {
        Item i = getItem(s); return i != Items.AIR && equipRawItem(c, i);
    }

    private boolean equipRawItem(MinecraftClient c, Item item) {
        ClientPlayerEntity p = c.player;
        if (p == null) return false;
        int cur = getSelectedSlot(p);
        if (p.getInventory().getStack(cur).isOf(item)) return true;

        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) {
                setSelectedSlot(p, i);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                slotSyncCooldown = 2;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(item) && c.interactionManager != null) {
                c.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, cur, SlotActionType.SWAP, p);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(cur));
                slotSyncCooldown = 3;
                return true;
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
            if (s.contains(DataComponentTypes.FOOD) && !s.isOf(Items.ROTTEN_FLESH) && !s.isOf(Items.SPIDER_EYE)) {
                isBotEating = true; setSelectedSlot(p, i); c.options.useKey.setPressed(true);
                if (!p.isUsingItem() && c.interactionManager != null) c.interactionManager.interactItem(p, Hand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    public void syncFromLitematica(MinecraftClient c) {
        if (c == null || c.player == null) return;
        SchematicData cap = SchematicData.captureFromLitematica(c, config);
        if (cap != null) {
            schematic = cap; currentLayerY = 0; retryRound = 0;
            botStatusText = "Da dong bo Litematica!";
            c.player.sendMessage(Text.literal("§a[BuildBot] Da nhan Litematica: §e" + SchematicData.getActiveLitematicaName()), false);
            printMaterialList(c);
        } else {
            botStatusText = "Chua thay Litematica quanh ban";
            c.player.sendMessage(Text.literal("§c[BuildBot] Khong thay Schem trong Litematica quanh ban!"), false);
        }
    }

    public void loadSchematicAtPlayer(MinecraftClient c) { syncFromLitematica(c); }

    public void printMaterialList(MinecraftClient c) {
        if (c == null || c.player == null || schematic == null) return;
        c.player.sendMessage(Text.literal("§6=== Danh Sach Vat Lieu Can Dung ==="), false);
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

    private boolean isMatching(BlockState a, BlockState e) {
        if (e == null || e.isAir()) return a.isAir();
        if (!a.isOf(e.getBlock())) return false;
        if (e.contains(Properties.FACING) && a.get(Properties.FACING) != e.get(Properties.FACING)) return false;
        if (e.contains(Properties.HORIZONTAL_FACING) && a.get(Properties.HORIZONTAL_FACING) != e.get(Properties.HORIZONTAL_FACING)) return false;
        return true;
    }

    private void resetDelay() {
        delayTimer = ThreadLocalRandom.current().nextInt(config.minPlaceDelayTicks, config.maxPlaceDelayTicks + 1);
    }
}
