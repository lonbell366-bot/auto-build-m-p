
package com.buildbot;

import net.minecraft.client.MinecraftClient;
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

    /**
     * BANG TIEN TRINH GOC TRAI TREN CUNG MAN HINH (TOP-LEFT PROGRESS HUD)
     */
    public static void renderProgressHud(DrawContext ctx) {
        MinecraftClient c = MinecraftClient.getInstance();
        BuildBotClient bot = BuildBotClient.INSTANCE;
        if (bot == null || !bot.hudEnabled || c.player == null || c.world == null || c.options.hudHidden) return;

        int x = 6, y = 6, w = 186, h = 66;
        int cx = x + w / 2;

        // Nen kinh toi + Vien Neon sang
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x4400E5FF);
        ctx.fill(x, y, x + w, y + h, 0xCC0A0E17);
        ctx.fill(x, y, x + 3, y + h, BuildBotClient.config.autoBuildEnabled ? 0xFF00E676 : 0xFF00E5FF);
        ctx.fill(x, y, x + w, y + 1, 0x9900E5FF);
        ctx.fill(x, y + h - 1, x + w, y + h, 0x9900E5FF);

        String stateBadge = BuildBotClient.config.autoBuildEnabled ? "§a[AUTO ON]" : "§e[STANDBY]";
        ctx.drawCenteredTextWithShadow(c.textRenderer, Text.literal("§b§l⚡ BUILDBOT PRO " + stateBadge), cx, y + 5, 0xFFFFFFFF);

        int total = Math.max(1, bot.totalBlocksCount);
        int pct = (bot.totalBlocksCount > 0) ? Math.min(100, (bot.placedBlocksCount * 100) / total) : 0;
        int maxLayer = (bot.schematic != null) ? bot.schematic.height : 0;

        ctx.drawCenteredTextWithShadow(c.textRenderer,
                Text.literal("§7Tang Y: §b" + bot.currentLayerY + "/" + maxLayer + " §8| §7Con lai: §e" + bot.layerRemainingCount),
                cx, y + 17, 0xFFE0E0E0);

        ctx.drawCenteredTextWithShadow(c.textRenderer,
                Text.literal("§7Tien do: §a" + bot.placedBlocksCount + "/" + bot.totalBlocksCount + " khoi §f(" + pct + "%)"),
                cx, y + 29, 0xFFE0E0E0);

        // Thanh Progress Bar mau Xanh Ngọc
        int barX = x + 8, barY = y + 41, barW = w - 16;
        ctx.fill(barX, barY, barX + barW, barY + 6, 0xFF1E293B);
        int fillW = (int) (barW * (pct / 100.0f));
        if (fillW > 0) {
            ctx.fill(barX, barY, barX + fillW, barY + 6, 0xFF00E676);
        }

        ctx.drawCenteredTextWithShadow(c.textRenderer, Text.literal("§6▸ §f" + bot.botStatusText), cx, y + 51, 0xFFFFFFFF);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int topBtnY = 58;
        litName = SchematicData.getActiveLitematicaName();

        // Hang nut dieu khien Tang Y & Quet Litematica
        this.addDrawableChild(ButtonWidget.builder(Text.literal("§b⟳ Quet Litematica"), btn -> {
            BuildBotClient.INSTANCE.syncFromLitematica(this.client);
            litName = SchematicData.getActiveLitematicaName();
        }).dimensions(cx - 156, topBtnY, 102, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§e◀ Y-"), btn -> {
            if (BuildBotClient.INSTANCE.currentLayerY > 0) BuildBotClient.INSTANCE.currentLayerY--;
        }).dimensions(cx - 50, topBtnY, 32, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§6Y=0"), btn -> {
            BuildBotClient.INSTANCE.currentLayerY = 0;
        }).dimensions(cx - 16, topBtnY, 32, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§eY+ ▶"), btn -> {
            BuildBotClient.INSTANCE.currentLayerY++;
        }).dimensions(cx + 18, topBtnY, 32, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§d📋 In Vat Lieu"), btn -> {
            BuildBotClient.INSTANCE.printMaterialList(this.client);
            this.close();
        }).dimensions(cx + 54, topBtnY, 102, 20).build());

        // COT TRAI: XAY DUNG & BANG TIEN TRINH
        int leftX = cx - 156;
        int rowY = 96;

        this.addDrawableChild(ButtonWidget.builder(badge("Auto Build", config.autoBuildEnabled), btn -> {
            config.autoBuildEnabled = !config.autoBuildEnabled;
            if (config.autoBuildEnabled && BuildBotClient.INSTANCE.schematic == null) {
                BuildBotClient.INSTANCE.syncFromLitematica(this.client);
            }
            btn.setMessage(badge("Auto Build", config.autoBuildEnabled));
            config.save();
        }).dimensions(leftX, rowY, 152, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Easy Place", config.easyPlaceEnabled), btn -> {
            config.easyPlaceEnabled = !config.easyPlaceEnabled;
            btn.setMessage(badge("Easy Place", config.easyPlaceEnabled));
            config.save();
        }).dimensions(leftX, rowY + 22, 152, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu Dao Khoi Sai", config.autoMineEnabled), btn -> {
            config.autoMineEnabled = !config.autoMineEnabled;
            btn.setMessage(badge("Tu Dao Khoi Sai", config.autoMineEnabled));
            config.save();
        }).dimensions(leftX, rowY + 44, 152, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("HUD Goc Trai", BuildBotClient.INSTANCE.hudEnabled), btn -> {
            BuildBotClient.INSTANCE.hudEnabled = !BuildBotClient.INSTANCE.hudEnabled;
            btn.setMessage(badge("HUD Goc Trai", BuildBotClient.INSTANCE.hudEnabled));
        }).dimensions(leftX, rowY + 66, 152, 20).build());

        // COT PHAI: TU DONG HOA & ANTI-SUS
        int rightX = cx + 4;

        this.addDrawableChild(ButtonWidget.builder(badge("Chong Ghost & Sus", config.smoothLookAntiSus), btn -> {
            config.smoothLookAntiSus = !config.smoothLookAntiSus;
            btn.setMessage(badge("Chong Ghost & Sus", config.smoothLookAntiSus));
            config.save();
        }).dimensions(rightX, rowY, 152, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu Lay Do (/order)", config.autoOrderEnabled), btn -> {
            config.autoOrderEnabled = !config.autoOrderEnabled;
            btn.setMessage(badge("Tu Lay Do (/order)", config.autoOrderEnabled));
            config.save();
        }).dimensions(rightX, rowY + 22, 152, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu Ban Xo (/sell)", config.autoSellEnabled), btn -> {
            config.autoSellEnabled = !config.autoSellEnabled;
            btn.setMessage(badge("Tu Ban Xo (/sell)", config.autoSellEnabled));
            config.save();
        }).dimensions(rightX, rowY + 44, 152, 20).build());

        this.addDrawableChild(ButtonWidget.builder(badge("Tu An (AutoEat)", config.autoEatEnabled), btn -> {
            config.autoEatEnabled = !config.autoEatEnabled;
            btn.setMessage(badge("Tu An (AutoEat)", config.autoEatEnabled));
            config.save();
        }).dimensions(rightX, rowY + 66, 152, 20).build());

        // THANH TRUOT RANDOM DELAY & /SELL
        int sliderY = rowY + 92;
        this.addDrawableChild(new SliderWidget(leftX, sliderY, 152, 20,
                Text.literal("§fRandom Delay: §b" + config.minPlaceDelayTicks + "-" + config.maxPlaceDelayTicks + "t"),
                (config.minPlaceDelayTicks - 1) / 10.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("§fRandom Delay: §b" + config.minPlaceDelayTicks + "-" + config.maxPlaceDelayTicks + "t"));
            }
            @Override
            protected void applyValue() {
                config.minPlaceDelayTicks = 1 + (int) Math.round(this.value * 10);
                config.maxPlaceDelayTicks = config.minPlaceDelayTicks + 3;
                config.save();
            }
        });

        this.addDrawableChild(new SliderWidget(rightX, sliderY, 152, 20,
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

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§a✔ Luu Cai Dat & Dong Menu (.bb / F10)"), btn -> this.close())
                .dimensions(cx - 110, sliderY + 24, 220, 20).build());
    }

    private Text badge(String name, boolean on) {
        return Text.literal((on ? "§a✔ " : "§c✖ ") + "§f" + name + ": " + (on ? "§aBAT" : "§7TAT"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int boxW = 332, boxH = 232;
        int boxX = cx - boxW / 2, boxY = 6;

        ctx.fill(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, 0x5500E5FF);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xEC0B0F19);
        drawBorder(ctx, boxX, boxY, boxW, boxH, 0xFF00E5FF);

        ctx.fill(boxX + 4, boxY + 4, boxX + boxW - 4, boxY + 48, 0xCC141E30);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§b§l✦ DONUTSMP BUILDBOT & EASYPLACE PRO ✦"), cx, boxY + 8, 0xFFFFFFFF);

        int placed = BuildBotClient.INSTANCE.placedBlocksCount;
        int total = Math.max(1, BuildBotClient.INSTANCE.totalBlocksCount);
        int pct = (BuildBotClient.INSTANCE.totalBlocksCount > 0) ? Math.min(100, (placed * 100) / total) : 0;
        int totalLayers = (BuildBotClient.INSTANCE.schematic != null) ? BuildBotClient.INSTANCE.schematic.height : 0;

        String infoLine = "§7Schem: §e" + litName + " §8| §7Tang Y: §b" + BuildBotClient.INSTANCE.currentLayerY + "/" + totalLayers
                + " §8| §7Tien do: §a" + placed + "/" + BuildBotClient.INSTANCE.totalBlocksCount + " (" + pct + "%)";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(infoLine), cx, boxY + 21, 0xFFE0E0E0);

        int barW = 290, barX = cx - barW / 2, barY = boxY + 34;
        ctx.fill(barX, barY, barX + barW, barY + 8, 0xFF1F2937);
        int fillW = (int) ((barW - 2) * (pct / 100.0f));
        if (fillW > 0) ctx.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + 7, 0xFF00E676);
        drawBorder(ctx, barX, barY, barW, 8, 0xFF374151);

        ctx.fill(cx - 156, 82, cx - 4, 94, 0x6600B4D8);
        ctx.fill(cx + 4, 82, cx + 156, 94, 0x66F59E0B);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§b§l⚙ XAY DUNG & HIEN THI"), cx - 80, 84, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§6§l🛡 TU DONG & ANTI-SUS"), cx + 80, 84, 0xFFFFFFFF);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
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
    
