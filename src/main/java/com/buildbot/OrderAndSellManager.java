package com.buildbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

public class OrderAndSellManager {
    public enum TaskState {
        NONE,
        INV_OPEN, INV_PICK_FROM_BAG, INV_PUT_TO_HOTBAR, INV_CLOSE,
        ORDER_OPEN, ORDER_YOUR_ORDERS, ORDER_FIND_ITEM, ORDER_CHEST_ICON, ORDER_COLLECT,
        SELL_OPEN, SELL_DUMP
    }

    public TaskState state = TaskState.NONE;
    private Item missingItem = null;
    private int amountNeeded = 0, pagesChecked = 0;
    private int bagSlotToFetch = -1, hotbarDestSlot = 0;

    // Bien dieu khien con tro chuot that trong GUI
    private int tickWait = 0;
    private double curMouseX = -1, curMouseY = -1;
    private int pendingSlotId = -1;
    private SlotActionType pendingAction = SlotActionType.PICKUP;
    private TaskState nextStateAfterClick = TaskState.NONE;

    public boolean isBusy() {
        return state != TaskState.NONE;
    }

    private int randTicks(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Mo kho do that (InventoryScreen) va dung chuot lay do tu Balo xuong Hotbar
     */
    public void startInventoryFetch(MinecraftClient client, int bagSlot, int hotbarSlot) {
        if (client.player == null || isBusy()) return;
        this.bagSlotToFetch = bagSlot;
        this.hotbarDestSlot = hotbarSlot;
        this.pendingSlotId = -1;
        this.state = TaskState.INV_OPEN;
        this.tickWait = randTicks(3, 6); // Dung yen truoc khi mo tui do
    }

    public void startOrderFetch(MinecraftClient client, Item item, int countNeeded) {
        if (client.player == null || isBusy()) return;
        this.missingItem = item;
        this.amountNeeded = countNeeded;
        this.pagesChecked = 0;
        this.pendingSlotId = -1;
        this.state = TaskState.ORDER_OPEN;
        this.tickWait = randTicks(10, 16);
        client.player.networkHandler.sendChatCommand("order");
        client.player.sendMessage(Text.literal("§e[BuildBot] Mo /order lay: " + item.getName().getString()), false);
    }

    public void checkAndStartAutoSell(MinecraftClient client, BuildBotConfig config) {
        if (!config.autoSellEnabled || isBusy() || client.player == null) return;
        int emptyBuckets = countItemInInventory(client.player, Items.BUCKET);
        if (emptyBuckets >= config.emptyBucketSellThreshold) {
            this.pendingSlotId = -1;
            this.state = TaskState.SELL_OPEN;
            this.tickWait = randTicks(10, 16);
            client.player.networkHandler.sendChatCommand("sell");
        }
    }

    public void tick(MinecraftClient client, BuildBotConfig config) {
        if (state == TaskState.NONE || client.player == null) return;
        ClientPlayerEntity player = client.player;

        // Nhan vat phai dung im hoan toan khi thao tac GUI de tranh Flag InventoryMove
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
        }

        // Neu dang trong qua trinh luot con tro chuot toi o Slot -> Di chuyen chuot muot tung tick
        if (pendingSlotId != -1) {
            if (stepMouseAndClickSlot(client, player)) {
                pendingSlotId = -1;
                state = nextStateAfterClick;
                tickWait = randTicks(4, 8);
            }
            return;
        }

        if (tickWait > 0) {
            tickWait--;
            return;
        }

        // --- QUY TRINH MO KHO DO THAT LAY VAT PHAM ---
        if (state == TaskState.INV_OPEN) {
            client.setScreen(new InventoryScreen(player));
            curMouseX = client.getWindow().getScaledWidth() / 2.0;
            curMouseY = client.getWindow().getScaledHeight() / 2.0;
            state = TaskState.INV_PICK_FROM_BAG;
            tickWait = randTicks(4, 7);
            return;
        }
        if (state == TaskState.INV_PICK_FROM_BAG) {
            if (!(client.currentScreen instanceof InventoryScreen)) { state = TaskState.NONE; return; }
            queueMouseClick(bagSlotToFetch, SlotActionType.PICKUP, TaskState.INV_PUT_TO_HOTBAR);
            return;
        }
        if (state == TaskState.INV_PUT_TO_HOTBAR) {
            if (!(client.currentScreen instanceof InventoryScreen)) { state = TaskState.NONE; return; }
            int guiHotbarSlot = 36 + (hotbarDestSlot % 9);
            queueMouseClick(guiHotbarSlot, SlotActionType.PICKUP, TaskState.INV_CLOSE);
            return;
        }
        if (state == TaskState.INV_CLOSE) {
            if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
                queueMouseClick(bagSlotToFetch, SlotActionType.PICKUP, TaskState.INV_CLOSE);
                return;
            }
            player.closeHandledScreen();
            client.setScreen(null);
            state = TaskState.NONE;
            return;
        }

        // --- QUY TRINH /ORDER VA /SELL DUNG CHUOT THAT ---
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null || handler == player.playerScreenHandler) {
            state = TaskState.NONE;
            return;
        }

        switch (state) {
            case ORDER_OPEN -> {
                int slot = findSlotByName(handler, "Your Orders");
                if (slot != -1) queueMouseClick(slot, SlotActionType.PICKUP, TaskState.ORDER_FIND_ITEM);
                else state = TaskState.NONE;
            }
            case ORDER_FIND_ITEM -> {
                String id = Registries.ITEM.getId(missingItem).toString();
                String targetName = config.orderItemDisplayNames.getOrDefault(id, missingItem.getName().getString());
                int itemSlot = findSlotByNameOrType(handler, targetName, missingItem);
                if (itemSlot != -1) {
                    queueMouseClick(itemSlot, SlotActionType.PICKUP, TaskState.ORDER_CHEST_ICON);
                } else {
                    int nextSlot = findSlotByName(handler, "Next Page");
                    if (nextSlot != -1 && pagesChecked++ < 5) {
                        queueMouseClick(nextSlot, SlotActionType.PICKUP, TaskState.ORDER_FIND_ITEM);
                    } else {
                        player.closeHandledScreen();
                        state = TaskState.NONE;
                    }
                }
            }
            case ORDER_CHEST_ICON -> {
                int chestSlot = findSlotByItem(handler, Items.CHEST);
                if (chestSlot != -1) queueMouseClick(chestSlot, SlotActionType.PICKUP, TaskState.ORDER_COLLECT);
                else { player.closeHandledScreen(); state = TaskState.NONE; }
            }
            case ORDER_COLLECT -> {
                if (countItemInInventory(player, missingItem) >= amountNeeded) {
                    player.closeHandledScreen();
                    state = TaskState.NONE;
                    return;
                }
                int collectSlot = findSlotByItemInTopGui(handler, missingItem);
                if (collectSlot != -1) queueMouseClick(collectSlot, SlotActionType.QUICK_MOVE, TaskState.ORDER_COLLECT);
                else { player.closeHandledScreen(); state = TaskState.NONE; }
            }
            case SELL_OPEN -> {
                int bucketSlot = findSlotInPlayerInventory(handler, Items.BUCKET);
                if (bucketSlot != -1) queueMouseClick(bucketSlot, SlotActionType.QUICK_MOVE, TaskState.SELL_OPEN);
                else { player.closeHandledScreen(); state = TaskState.NONE; }
            }
            default -> state = TaskState.NONE;
        }
    }

    private void queueMouseClick(int slotId, SlotActionType action, TaskState nextState) {
        this.pendingSlotId = slotId;
        this.pendingAction = action;
        this.nextStateAfterClick = nextState;
        this.tickWait = randTicks(2, 4); // Nghi nhip nho khi chuot vua cham vao o
    }

    /**
     * Luot con tro chuot that tren man hinh toi dung o Slot, doi ngau nhien roi moi click
     */
    private boolean stepMouseAndClickSlot(MinecraftClient client, ClientPlayerEntity player) {
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null || pendingSlotId < 0 || pendingSlotId >= handler.slots.size()) return true;

        Slot targetSlot = handler.slots.get(pendingSlotId);
        int[] guiOrigin = getHandledScreenOrigin(client, handler);
        double targetX = guiOrigin[0] + targetSlot.x + 8 + ThreadLocalRandom.current().nextDouble(-3.0, 3.0);
        double targetY = guiOrigin[1] + targetSlot.y + 8 + ThreadLocalRandom.current().nextDouble(-3.0, 3.0);

        if (curMouseX < 0 || curMouseY < 0) {
            curMouseX = client.getWindow().getScaledWidth() / 2.0;
            curMouseY = client.getWindow().getScaledHeight() / 2.0;
        }

        double dx = targetX - curMouseX;
        double dy = targetY - curMouseY;
        double dist = Math.sqrt(dx * dx + dy * dy);

        // Di chuyen con tro chuot muot ma (Toc do ngau nhien 35% - 55% moi tick)
        if (dist > 4.0) {
            double speed = ThreadLocalRandom.current().nextDouble(0.38, 0.58);
            curMouseX += dx * speed + ThreadLocalRandom.current().nextDouble(-0.8, 0.8);
            curMouseY += dy * speed + ThreadLocalRandom.current().nextDouble(-0.8, 0.8);
            movePhysicalCursor(client, curMouseX, curMouseY);
            return false;
        }

        // Chuot da nam tren o Slot -> Cho nhip phan xa cua nguoi that (tickWait) roi moi click
        movePhysicalCursor(client, targetX, targetY);
        if (tickWait > 0) {
            tickWait--;
            return false;
        }

        if (client.interactionManager != null) {
            client.interactionManager.clickSlot(handler.syncId, pendingSlotId, 0, pendingAction, player);
        }
        return true;
    }

    private void movePhysicalCursor(MinecraftClient client, double scaledX, double scaledY) {
        try {
            long windowHandle = client.getWindow().getHandle();
            double scale = client.getWindow().getScaleFactor();
            GLFW.glfwSetCursorPos(windowHandle, scaledX * scale, scaledY * scale);
        } catch (Throwable ignored) {}
    }

    private int[] getHandledScreenOrigin(MinecraftClient client, ScreenHandler handler) {
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        if (client.currentScreen instanceof HandledScreen hs) {
            try {
                int gx = -1, gy = -1;
                for (Field f : HandledScreen.class.getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        String n = f.getName();
                        if (n.equals("field_2776") || n.equals("x")) gx = f.getInt(hs);
                        else if (n.equals("field_2777") || n.equals("y")) gy = f.getInt(hs);
                    }
                }
                if (gx >= 0 && gy >= 0) return new int[]{gx, gy};
            } catch (Throwable ignored) {}
        }
        int rows = Math.max(0, (handler.slots.size() - 36) / 9);
        int bgH = (rows > 0) ? (114 + rows * 18) : 166;
        return new int[]{(sw - 176) / 2, (sh - bgH) / 2};
    }

    private int findSlotByName(ScreenHandler h, String kw) {
        for (Slot s : h.slots) {
            ItemStack st = s.getStack();
            if (!st.isEmpty() && st.getName().getString().toLowerCase().contains(kw.toLowerCase())) return s.id;
        }
        return -1;
    }

    private int findSlotByNameOrType(ScreenHandler h, String name, Item item) {
        for (Slot s : h.slots) {
            ItemStack st = s.getStack();
            if (!st.isEmpty() && (st.isOf(item) || st.getName().getString().toLowerCase().contains(name.toLowerCase()))) return s.id;
        }
        return -1;
    }

    private int findSlotByItem(ScreenHandler h, Item item) {
        for (Slot s : h.slots) if (s.getStack().isOf(item)) return s.id;
        return -1;
    }

    private int findSlotByItemInTopGui(ScreenHandler h, Item item) {
        int top = Math.max(0, h.slots.size() - 36);
        for (int i = 0; i < top; i++) if (h.slots.get(i).getStack().isOf(item)) return i;
        return -1;
    }

    private int findSlotInPlayerInventory(ScreenHandler h, Item item) {
        int start = Math.max(0, h.slots.size() - 36);
        for (int i = start; i < h.slots.size(); i++) if (h.slots.get(i).getStack().isOf(item)) return i;
        return -1;
    }

    public static int countItemInInventory(ClientPlayerEntity p, Item item) {
        int total = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item)) total += s.getCount();
        }
        return total;
    }
            }
