package com.buildbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class BuildBotScreen extends Screen {
    private final Screen parent;
    private final BuildBotConfig config = BuildBotClient.config;
    private TextFieldWidget schemNameField;

    public BuildBotScreen(Screen parent) {
        super(Text.literal("DonutSMP BuildBot & EasyPlace (Menu F10 / .bb)"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 22;

        schemNameField = new TextFieldWidget(this.textRenderer, cx - 155, y, 195, 20, Text.literal("Ten file Schem"));
        schemNameField.setText(config.activeSchematicName);
        this.addDrawableChild(schemNameField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("§bNap Schem & Neo"), btn -> {
            config.activeSchematicName = schemNameField.getText().trim();
            config.save();
            BuildBotClient.INSTANCE.loadSchematicAtPlayer(this.client);
        }).dimensions(cx + 45, y, 110, 20).build());

        y += 24;
        this.addDrawableChild(ButtonWidget.builder(toggleText("Auto Build", config.autoBuildEnabled), btn -> {
            config.autoBuildEnabled = !config.autoBuildEnabled;
            btn.setMessage(toggleText("Auto Build", config.autoBuildEnabled));
            config.save();
        }).dimensions(cx - 155, y, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(toggleText("Easy Place (Tu xoay)", config.easyPlaceEnabled), btn -> {
            config.easyPlaceEnabled = !config.easyPlaceEnabled;
            btn.setMessage(toggleText("Easy Place (Tu xoay)", config.easyPlaceEnabled));
            config.save();
        }).dimensions(cx + 5, y, 150, 20).build());

        y += 22;
        this.addDrawableChild(ButtonWidget.builder(toggleText("Tu dao khoi sai", config.autoMineEnabled), btn -> {
            config.autoMineEnabled = !config.autoMineEnabled;
            btn.setMessage(toggleText("Tu dao khoi sai", config.autoMineEnabled));
            config.save();
        }).dimensions(cx - 155, y, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(toggleText("Bac cau & Thu hoi", config.autoBridgeEnabled), btn -> {
            config.autoBridgeEnabled = !config.autoBridgeEnabled;
            config.reclaimBridgeEnabled = config.autoBridgeEnabled;
            btn.setMessage(toggleText("Bac cau & Thu hoi", config.autoBridgeEnabled));
            config.save();
        }).dimensions(cx + 5, y, 150, 20).build());

        y += 22;
        this.addDrawableChild(ButtonWidget.builder(toggleText("Tu lay do (/order)", config.autoOrderEnabled), btn -> {
            config.autoOrderEnabled = !config.autoOrderEnabled;
            btn.setMessage(toggleText("Tu lay do (/order)", config.autoOrderEnabled));
            config.save();
        }).dimensions(cx - 155, y, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(toggleText("Tu ban xo (/sell)", config.autoSellEnabled), btn -> {
            config.autoSellEnabled = !config.autoSellEnabled;
            btn.setMessage(toggleText("Tu ban xo (/sell)", config.autoSellEnabled));
            config.save();
        }).dimensions(cx + 5, y, 150, 20).build());

        y += 22;
        this.addDrawableChild(ButtonWidget.builder(toggleText("Tu an (AutoEat)", config.autoEatEnabled), btn -> {
            config.autoEatEnabled = !config.autoEatEnabled;
            btn.setMessage(toggleText("Tu an (AutoEat)", config.autoEatEnabled));
            config.save();
        }).dimensions(cx - 155, y, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(toggleText("Xoay muot Chong Sus", config.smoothLookAntiSus), btn -> {
            config.smoothLookAntiSus = !config.smoothLookAntiSus;
            btn.setMessage(toggleText("Xoay muot Chong Sus", config.smoothLookAntiSus));
            config.save();
        }).dimensions(cx + 5, y, 150, 20).build());

        y += 24;
        this.addDrawableChild(new SliderWidget(cx - 155, y, 150, 20,
                Text.literal("Nhip dat: " + config.minPlaceDelayTicks + "-" + config.maxPlaceDelayTicks + " ticks"),
                (config.minPlaceDelayTicks - 1) / 10.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("Nhip dat: " + config.minPlaceDelayTicks + "-" + config.maxPlaceDelayTicks + " ticks"));
            }
            @Override
            protected void applyValue() {
                config.minPlaceDelayTicks = 1 + (int) Math.round(this.value * 10);
                config.maxPlaceDelayTicks = config.minPlaceDelayTicks + 4;
                config.save();
            }
        });

        this.addDrawableChild(new SliderWidget(cx + 5, y, 150, 20,
                Text.literal("Nguong /sell xo: " + config.emptyBucketSellThreshold),
                (config.emptyBucketSellThreshold - 1) / 31.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("Nguong /sell xo: " + config.emptyBucketSellThreshold));
            }
            @Override
            protected void applyValue() {
                config.emptyBucketSellThreshold = 1 + (int) Math.round(this.value * 31);
                config.save();
            }
        });

        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("§eBao Cao Vat Lieu"), btn -> {
            BuildBotClient.INSTANCE.printMaterialList(this.client);
            this.close();
        }).dimensions(cx - 155, y, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Luu & Dong"), btn -> this.close())
                .dimensions(cx + 5, y, 150, 20).build());
    }

    private Text toggleText(String label, boolean state) {
        return Text.literal(label + ": " + (state ? "§aBAT" : "§cTAT"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        config.save();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
