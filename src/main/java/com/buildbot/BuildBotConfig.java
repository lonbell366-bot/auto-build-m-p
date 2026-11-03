package com.buildbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

public class BuildBotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "donut_buildbot.json");

    public boolean autoBuildEnabled = false;
    public boolean easyPlaceEnabled = true;
    public boolean autoMineEnabled = true;
    public boolean autoBridgeEnabled = true;
    public boolean reclaimBridgeEnabled = true;
    public boolean autoOrderEnabled = true;
    public boolean autoSellEnabled = true;
    public boolean autoEatEnabled = true;
    public boolean autoResumeEnabled = true;
    public boolean smoothLookAntiSus = true;

    public int minPlaceDelayTicks = 3;
    public int maxPlaceDelayTicks = 7;
    public int layerHeight = 1;
    public int maxRetryRounds = 5;
    public int moveTimeoutSeconds = 20;
    public float smoothRotationSpeed = 0.35f;

    public int emptyBucketSellThreshold = 8;
    public int hungerThreshold = 15;

    public String activeSchematicName = "farm.schem";
    public int originX = 0;
    public int originY = 64;
    public int originZ = 0;
    public boolean hasSavedOrigin = false;

    public Map<String, String> orderItemDisplayNames = new HashMap<>();

    public BuildBotConfig() {
        orderItemDisplayNames.put("minecraft:redstone_wire", "Redstone Dust");
        orderItemDisplayNames.put("minecraft:repeater", "Redstone Repeater");
        orderItemDisplayNames.put("minecraft:comparator", "Redstone Comparator");
        orderItemDisplayNames.put("minecraft:piston", "Piston");
        orderItemDisplayNames.put("minecraft:sticky_piston", "Sticky Piston");
        orderItemDisplayNames.put("minecraft:observer", "Observer");
        orderItemDisplayNames.put("minecraft:hopper", "Hopper");
        orderItemDisplayNames.put("minecraft:cobblestone", "Cobblestone");
        orderItemDisplayNames.put("minecraft:quartz_block", "Block of Quartz");
    }

    public static BuildBotConfig load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                return GSON.fromJson(reader, BuildBotConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        BuildBotConfig cfg = new BuildBotConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
