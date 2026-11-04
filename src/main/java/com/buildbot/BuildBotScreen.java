package com.buildbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class BuildBotScreen extends Screen {
    private final Screen parent;
    private final BuildBotConfig config = BuildBotClient.config;
    private String litName = "";

    public BuildBotScreen(Screen parent) {
        super(Text.literal("DONUTSMP BUILDBOT PRO"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int topY = 48;
        litName = SchematicData.getActiveLitematicaName();

        // Thanh tren cung: Dong bo Litematica & Bao cao vat lieu
        this.addDrawableChild(ButtonWidget.builder(Text.literal("§b⟳ Quet Schem Litematica"), btn -> {
            BuildBotClient.INSTANCE.syncFromLitematica(this.client);
            litName = SchematicData.getActiveLitematicaName();
        }).dimensions(cx - 152, topY, 148, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§e📋 In Vat Lieu Can Dung"), btn -> {
            BuildBotClient.INSTANCE.printMaterialList(this.client);
            this.close();
        }).dimensions(cx + 4, topY, 148, 20).build());

        // COT TRAI: XAY DUNG & DI CHUYEN
        int leftX = cx - 152;
        int rowY = topY + 38;

        this.addDrawableChild(ButtonWidget.builder(badge("Auto Build", config.autoBuildEnabled), btn -> {
            config.autoBuildEnabled = !config.autoBuildEnabled;
            if (config.autoBuildEnabled) BuildBotClient.INSTANCE.syncFromLitematica(this.client);
            btn.setMessage(badge("Auto Build", config.autoBuildEnabled));
            config.save();
        }).dimensions(leftX, rowY, 148, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Easy Place", config.easyPlaceEnabled), btn -> {
            config.easyPlaceEnabled = !config.easyPlaceEnabled;
            btn.setMessage(badge("Easy Place", config.easyPlaceEnabled));
            config.save();
        }).dimensions(leftX, rowY + 22, 148, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu Dao Khoi Sai", config.autoMineEnabled), btn -> {
            config.autoMineEnabled = !config.autoMineEnabled;
            btn.setMessage(badge("Tu Dao Khoi Sai", config.autoMineEnabled));
            config.save();
        }).dimensions(leftX, rowY + 44, 148, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Bac Cau & Thu Hoi", config.autoBridgeEnabled), btn -> {
            config.autoBridgeEnabled = !config.autoBridgeEnabled;
            config.reclaimBridgeEnabled = config.autoBridgeEnabled;
            btn.setMessage(badge("Bac Cau & Thu Hoi", config.autoBridgeEnabled));
            config.save();
        }).dimensions(leftX, rowY + 66, 148, 20).build());

        // COT PHAI: TU DONG HOA & ANTI-SUS
        int rightX = cx + 4;

        this.addDrawableChild(ButtonWidget.builder(badge("Chong Sus & Ghost", config.smoothLookAntiSus), btn -> {
            config.smoothLookAntiSus = !config.smoothLookAntiSus;
            btn.setMessage(badge("Chong Sus & Ghost", config.smoothLookAntiSus));
            config.save();
        }).dimensions(rightX, rowY, 148, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu Lay /order", config.autoOrderEnabled), btn -> {
            config.autoOrderEnabled = !config.autoOrderEnabled;
            btn.setMessage(badge("Tu Lay /order", config.autoOrderEnabled));
            config.save();
        }).dimensions(rightX, rowY + 22, 148, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu Ban Xo /sell", config.autoSellEnabled), btn -> {
            config.autoSellEnabled = !config.autoSellEnabled;
            btn.setMessage(badge("Tu Ban Xo /sell", config.autoSellEnabled));
            config.save();
        }).dimensions(rightX, rowY + 44, 148, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu An (AutoEat)", config.autoEatEnabled), btn -> {
            config.autoEatEnabled = !config.autoEatEnabled;
            btn.setMessage(badge("Tu An (AutoEat)", config.autoEatEnabled));
            config.save();
        }).dimensions(rightX, rowY + 66, 148, 20).build());

        // THANH TRUOT TOC DO & /SELL
        int sliderY = rowY + 94;
        this.addDrawableChild(new SliderWidget(leftX, sliderY, 148, 20,
                Text.literal("§fNhip dat: §b" + config.minPlaceDelayTicks + "-" + config.maxPlaceDelayTicks + "t"),
                (config.minPlaceDelayTicks - 1) / 10.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("§fNhip dat: §b" + config.minPlaceDelayTicks + "-" + config.maxPlaceDelayTicks + "t"));
            }
            @Override
            protected void applyValue() {
                config.minPlaceDelayTicks = 1 + (int) Math.round(this.value * 10);
                config.maxPlaceDelayTicks = config.minPlaceDelayTicks + 3;
                config.save();
            }
        });

        this.addDrawableChild(new SliderWidget(rightX, sliderY, 148, 20,
                Text.literal("§fBan xo khi >= §e" + config.emptyBucketSellThreshold),
                (config.emptyBucketSellThreshold - 1) / 31.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("§fBan xo khi >= §e" + config.emptyBucketSellThreshold));
            }
            @Override
            protected void applyValue() {
                config.emptyBucketSellThreshold = 1 + (int) Math.round(this.value * 31);
                config.save();
            }
        });

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§a✔ Luu Cai Dat & Dong (F10)"), btn -> this.close())
                .dimensions(cx - 90, sliderY + 26, 180, 20).build());
    }

    private Text badge(String name, boolean on) {
        return Text.literal((on ? "§a● " : "§c○ ") + "§f" + name + ": " + (on ? "§aBAT" : "§7TAT"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int boxW = 324;
        int boxH = 228;
        int boxX = cx - boxW / 2;
        int boxY = 8;

        // Khung nen kinh toi & vien Cyan sang
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xD810141F);
        drawOutline(ctx, boxX, boxY, boxW, boxH, 0xFF00D4FF);

        // Thanh tieu de & The trang thai truc tiep
        ctx.fill(boxX + 4, boxY + 4, boxX + boxW - 4, boxY + 36, 0x991B2436);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§b§l⚡ DONUTSMP BUILDBOT & EASYPLACE ⚡"), cx, boxY + 8, 0xFFFFFFFF);

        String layerInfo = (BuildBotClient.INSTANCE.schematic != null)
                ? ("Tang Y: " + BuildBotClient.INSTANCE.currentLayerY + "/" + BuildBotClient.INSTANCE.schematic.height)
                : "Chua quet";
        String subTitle = "§7Schem: §e" + litName + " §8| §7" + layerInfo + " §8| §a" + BuildBotClient.INSTANCE.botStatusText;
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(subTitle), cx, boxY + 22, 0xFFCCCCCC);

        // Tieu de 2 cot
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§b§l[ XAY DUNG & DI CHUYEN ]"), cx - 78, 74, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§6§l[ TU DONG & ANTI-SUS ]"), cx + 78, 74, 0xFFFFFFFF);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawOutline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void close() {
        config.save();
        if (this.client != null) this.client.setScreen(this.parent);
    }
}
