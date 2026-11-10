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
    public String botStatusText = "Idle";
    public int currentLayerY = 0;

    private final CustomPathfinder pathfinder = new CustomPathfinder();
    private final OrderAndSellManager orderSell = new OrderAndSellManager();
    private BlockPos preparedPos = null;
    private int retryRound = 0, delayTimer = 0, slotSyncCooldown = 0, easyPlaceCooldown = 0;
    private boolean openGui = false, wasF10 = false, isBotEating = false;

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

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        if (slotSyncCooldown > 0) slotSyncCooldown--;
        if (easyPlaceCooldown > 0) easyPlaceCooldown--;
        if (delayTimer > 0) delayTimer--;

        try {
            long win = GLFW.glfwGetCurrentContext();
            if (win != 0L) {
                boolean p = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F10) == GLFW.GLFW_PRESS;
                if (p && !wasF10 && client.currentScreen == null) client.setScreen(new BuildBotScreen(null));
                wasF10 = p;
            }
        } catch (Throwable ignored) {}

        if (openGui && client.currentScreen == null) { openGui = false; client.setScreen(new BuildBotScreen(null)); return; }
        if (orderSell.isBusy()) { orderSell.tick(client, config); return; }
        if (client.currentScreen != null) return;
        if (config.autoEatEnabled && handleAutoEat(client, player)) return;

        orderSell.checkAndStartAutoSell(client, config);

        // EASY PLACE SIEU NHANH: Bam hoac giu chuot phai la dat khop ngay lap tuc
        if (config.easyPlaceEnabled && client.options.useKey.isPressed() && easyPlaceCooldown == 0) {
            if (tryFastEasyPlace(client, player)) return;
        }

        if (config.autoBuildEnabled && schematic == null) {
            syncFromLitematica(client);
            if (schematic == null) { config.autoBuildEnabled = false; return; }
        }
        if (!config.autoBuildEnabled || schematic == null) {
            pathfinder.stopMovement(client);
            return;
        }

        // Luon chay runBuildTick moi tick de nhan vat di chuyen muot ma khong bi ngat quang
        runBuildTick(client, player);
    }

    private boolean tryFastEasyPlace(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);

        for (double d = 0.8; d <= 4.5; d += 0.25) {
            BlockPos pos = BlockPos.ofFloored(eye.add(look.multiply(d)));
            if (!client.world.getBlockState(pos).isAir()) continue;

            BlockState exp = getExpectedState(pos);
            if (exp == null || exp.isAir()) continue;
            if (AntiSusRotator.findValidPlaceHit(client, pos, exp) == null) continue;
            if (!equipItem(client, exp)) continue;

            boolean needSneak = AntiSusRotator.requiresSneakToPlace(client, pos, exp);
            if (needSneak) client.options.sneakKey.setPressed(true);

            // Neu la Observer/Piston/Repeater: Xoay 1 tick roi dat ngay o tick ke tiep de 100% khop huong Server
            if (AntiSusRotator.needsStrictLookRotation(exp)) {
                float[] rot = AntiSusRotator.getRequiredRotationForState(pos, exp, player);
                AntiSusRotator.stepSmoothRotation(client, rot[0], rot[1], 1.0f);
                if (!pos.equals(preparedPos) || slotSyncCooldown > 0) {
                    preparedPos = pos;
                    return true;
                }
            } else if (slotSyncCooldown > 0) {
                return true;
            }

            if (AntiSusRotator.placeBlockSmart(client, pos, exp, Hand.MAIN_HAND)) {
                preparedPos = null;
                easyPlaceCooldown = 2;
                if (needSneak) client.options.sneakKey.setPressed(false);
                return true;
            }
            if (needSneak) client.options.sneakKey.setPressed(false);
        }
        return false;
    }

    private void runBuildTick(MinecraftClient client, ClientPlayerEntity player) {
        if (currentLayerY >= schematic.height) {
            config.autoBuildEnabled = false; config.save(); pathfinder.stopMovement(client);
            botStatusText = "Hoan thanh 100%!";
            player.sendMessage(Text.literal("§a[BuildBot] Da hoan thanh cong trinh!"), false);
            return;
        }

        long now = System.currentTimeMillis();
        List haveItemMissing = new ArrayList();
        List haveItemReady = new ArrayList();
        Item missingOrderItem = null;

        for (int dy = 0; dy < config.layerHeight && (currentLayerY + dy) < schematic.height; dy++) {
            int y = currentLayerY + dy;
            for (int x = 0; x < schematic.width; x++) {
                for (int z = 0; z < schematic.length; z++) {
                    BlockPos wp = new BlockPos(config.originX + x, config.originY + y, config.originZ + z);
                    BlockState exp = getExpectedState(wp);
                    // QUAN TRONG: Bo qua tat ca cac o khong khi (Air) de khong bao gio bi ket!
                    if (exp == null || exp.isAir()) continue;

                    Long last = (Long) AntiSusRotator.recentlyPlaced.get(wp);
                    if (last != null && (now - last) < 200L) continue;

                    if (!isMatching(client.world.getBlockState(wp), exp)) {
                        if (hasItemInInventory(player, getItem(exp))) {
                            haveItemMissing.add(wp);
                            if (AntiSusRotator.findValidPlaceHit(client, wp, exp) != null) {
                                haveItemReady.add(wp);
                            }
                        } else if (missingOrderItem == null) {
                            missingOrderItem = getItem(exp);
                        }
                    }
                }
            }
        }

        // Neu tang hien tai da dat het cac khoi ma ban co trong tui -> Tu dong len tang Y tiep theo!
        if (haveItemMissing.isEmpty()) {
            if (missingOrderItem != null && config.autoOrderEnabled) {
                orderSell.startOrderFetch(client, missingOrderItem, 32);
                return;
            }
            currentLayerY += config.layerHeight;
            retryRound = 0;
            preparedPos = null;
            botStatusText = "Len tang Y = " + currentLayerY;
            player.sendMessage(Text.literal("§b[BuildBot] Xong tang! Nhay len tang Y = " + currentLayerY), true);
            // Nhay len ngay khi chuyen tang
            if (player.isOnGround()) player.jump();
            return;
        }

        List pool = haveItemReady.isEmpty() ? haveItemMissing : haveItemReady;

        // Sap xep thong minh: Xay cac khoi xung quanh truoc, chua khoi ngay duoi chan (player.getBlockPos()) lai cuoi cung de nhay len dat!
        BlockPos playerFoot = player.getBlockPos();
        pool.sort(Comparator.comparingInt(p -> SchematicData.getDependencyOrder(getExpectedState((BlockPos) p)))
                .thenComparingDouble(p -> {
                    BlockPos bp = (BlockPos) p;
                    double d = player.squaredDistanceTo(Vec3d.ofCenter(bp));
                    return (bp.getX() == playerFoot.getX() && bp.getZ() == playerFoot.getZ()) ? d + 100.0 : d;
                }));

        BlockPos pos = (BlockPos) pool.get(0);
        BlockState exp = getExpectedState(pos);
        BlockState act = client.world.getBlockState(pos);

        // Neu o do bi dat sai khoi -> Tu dao khoi sai
        if (!act.isAir() && !act.getFluidState().isStill()) {
            if (config.autoMineEnabled && client.interactionManager != null) {
                botStatusText = "Dao khoi sai: " + pos.toShortString();
                client.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
                player.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        // Di chuyen muot ma (Hoac tu nhay len neu khoi nam ngay duoi chan)
        if (!pathfinder.tickMoveToTarget(client, pos, config)) {
            botStatusText = "Di chuyen -> " + pos.toShortString();
            if (pathfinder.isTimedOut(config.moveTimeoutSeconds)) retryRound++;
            return;
        }

        if (delayTimer > 0 || !equipItem(client, exp)) return;

        boolean needSneak = AntiSusRotator.requiresSneakToPlace(client, pos, exp);
        client.options.sneakKey.setPressed(needSneak);

        float[] rot = AntiSusRotator.getRequiredRotationForState(pos, exp, player);
        boolean rotDone = AntiSusRotator.stepSmoothRotation(client, rot[0], rot[1], 1.0f);

        // Neu la Observer/Piston/Repeater hoac vua doi slot: Cho 1 tick dong bo voi Server roi dat ngay
        if (slotSyncCooldown > 0 || (AntiSusRotator.needsStrictLookRotation(exp) && (!rotDone || !pos.equals(preparedPos)))) {
            preparedPos = pos;
            return;
        }

        botStatusText = "Dat: " + exp.getBlock().getName().getString();
        if (AntiSusRotator.placeBlockSmart(client, pos, exp, Hand.MAIN_HAND)) {
            resetDelay();
        }
        if (needSneak) client.options.sneakKey.setPressed(false);
        preparedPos = null;
    }

    private boolean hasItemInInventory(ClientPlayerEntity p, Item item) {
        if (item == Items.AIR) return false;
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(item)) return true;
        }
        return false;
    }

    public static int getSelectedSlot(PlayerEntity p) {
        try {
            for (Field f : p.getInventory().getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true); int v = f.getInt(p.getInventory());
                    if (v >= 0 && v <= 8) return v;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    public static void setSelectedSlot(PlayerEntity p, int slot) {
        if (slot < 0 || slot > 8) return;
        try {
            for (Field f : p.getInventory().getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true); int v = f.getInt(p.getInventory());
                    if (v >= 0 && v <= 8) { f.setInt(p.getInventory(), slot); return; }
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
        Item item = getItem(s);
        if (item == Items.AIR) return false;
        ClientPlayerEntity p = c.player;
        if (p.getMainHandStack().isOf(item)) return true;

        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) {
                setSelectedSlot(p, i);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                slotSyncCooldown = 1;
                return true;
            }
        }
        int cur = getSelectedSlot(p);
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(item) && c.interactionManager != null) {
                c.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, cur, SlotActionType.SWAP, p);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(cur));
                slotSyncCooldown = 2;
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
        if (cap != null) {
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

    private boolean isMatching(BlockState a, BlockState e) {
        if (e == null || e.isAir()) return true;
        if (!a.isOf(e.getBlock())) return false;
        if (e.contains(Properties.FACING) && a.get(Properties.FACING) != e.get(Properties.FACING)) return false;
        if (e.contains(Properties.HORIZONTAL_FACING) && a.get(Properties.HORIZONTAL_FACING) != e.get(Properties.HORIZONTAL_FACING)) return false;
        return true;
    }

    private void resetDelay() {
        delayTimer = ThreadLocalRandom.current().nextInt(1, Math.max(2, config.minPlaceDelayTicks) + 1);
    }
    }
