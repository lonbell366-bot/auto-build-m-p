package com.buildbot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

public class SchematicData {
    public int width, height, length;
    private final BlockState[][][] blocks;
    public final Map<Block, Integer> totalMaterials = new HashMap<>();

    public SchematicData(int width, int height, int length) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.blocks = new BlockState[width][height][length];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Arrays.fill(this.blocks[x][y], Blocks.AIR.getDefaultState());
            }
        }
    }

    public BlockState getBlockState(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
            return Blocks.AIR.getDefaultState();
        }
        return blocks[x][y][z];
    }

    public void setBlockState(int x, int y, int z, BlockState state) {
        if (x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < length) {
            blocks[x][y][z] = state;
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                totalMaterials.merge(state.getBlock(), 1, Integer::sum);
            }
        }
    }

    public static int getDependencyOrder(BlockState state) {
        if (state == null) return 1;
        Block b = state.getBlock();
        if (b instanceof AbstractRedstoneGateBlock || b instanceof RedstoneWireBlock
                || b instanceof TorchBlock || b instanceof FluidBlock
                || b instanceof LeverBlock || b instanceof ButtonBlock) {
            return 3;
        }
        if (b instanceof PistonBlock || b instanceof ObserverBlock
                || b instanceof HopperBlock || b instanceof DispenserBlock) {
            return 2;
        }
        return 1;
    }

    public static SchematicData loadFromFile(String fileName) throws Exception {
        File schemDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "schematics");
        if (!schemDir.exists()) schemDir.mkdirs();

        Path path = new File(schemDir, fileName).toPath();
        NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());

        if (root.contains("Schematic", NbtElement.COMPOUND_TYPE)) {
            root = root.getCompound("Schematic");
        }

        // 1. Structure Block (.nbt)
        if (root.contains("size") && root.contains("palette") && root.contains("blocks")) {
            return parseVanillaNbt(root);
        }
        // 2. Sponge Schematic (.schem)
        if (root.contains("Width") && root.contains("Palette") && root.contains("BlockData")) {
            return parseSpongeSchem(root);
        }
        // 3. Litematica (.litematic)
        if (root.contains("Regions")) {
            return parseLitematic(root);
        }
        // 4. MCEdit Legacy (.schematic)
        if (root.contains("Width") && root.contains("Blocks")) {
            return parseLegacySchematic(root);
        }
        throw new IllegalArgumentException("Dinh dang khong ho tro: " + fileName);
    }

    private static SchematicData parseVanillaNbt(NbtCompound root) {
        NbtList sizeList = root.getList("size", NbtElement.INT_TYPE);
        int w = sizeList.getInt(0);
        int h = sizeList.getInt(1);
        int l = sizeList.getInt(2);
        SchematicData data = new SchematicData(w, h, l);

        NbtList paletteNbt = root.getList("palette", NbtElement.COMPOUND_TYPE);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteNbt.size(); i++) {
            palette.add(readStateFromNbt(paletteNbt.getCompound(i)));
        }

        NbtList blocksNbt = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < blocksNbt.size(); i++) {
            NbtCompound b = blocksNbt.getCompound(i);
            NbtList pos = b.getList("pos", NbtElement.INT_TYPE);
            int stateIdx = b.getInt("state");
            if (stateIdx >= 0 && stateIdx < palette.size()) {
                data.setBlockState(pos.getInt(0), pos.getInt(1), pos.getInt(2), palette.get(stateIdx));
            }
        }
        return data;
    }

    private static SchematicData parseSpongeSchem(NbtCompound root) {
        int w = root.getShort("Width");
        int h = root.getShort("Height");
        int l = root.getShort("Length");
        SchematicData data = new SchematicData(w, h, l);

        NbtCompound paletteTag = root.getCompound("Palette");
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteTag.getKeys()) {
            palette.put(paletteTag.getInt(key), parseBlockStateString(key));
        }

        byte[] blockData = root.getByteArray("BlockData");
        int index = 0;
        int i = 0;
        while (i < blockData.length && index < w * h * l) {
            int value = 0;
            int varintLength = 0;
            while (true) {
                byte b = blockData[i++];
                value |= (b & 127) << (varintLength++ * 7);
                if (varintLength > 5) break;
                if ((b & 128) != 128) break;
            }
            int y = index / (w * l);
            int z = (index % (w * l)) / w;
            int x = (index % (w * l)) % w;
            BlockState state = palette.getOrDefault(value, Blocks.AIR.getDefaultState());
            data.setBlockState(x, y, z, state);
            index++;
        }
        return data;
    }

    private static SchematicData parseLitematic(NbtCompound root) {
        NbtCompound regions = root.getCompound("Regions");
        String firstRegionKey = regions.getKeys().iterator().next();
        NbtCompound region = regions.getCompound(firstRegionKey);

        NbtCompound sizeTag = region.getCompound("Size");
        int w = Math.abs(sizeTag.getInt("x"));
        int h = Math.abs(sizeTag.getInt("y"));
        int l = Math.abs(sizeTag.getInt("z"));
        SchematicData data = new SchematicData(w, h, l);

        NbtList paletteNbt = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteNbt.size(); i++) {
            palette.add(readStateFromNbt(paletteNbt.getCompound(i)));
        }

        long[] blockStates = region.getLongArray("BlockStates");
        int bitsPerBlock = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
        long maxEntryValue = (1L << bitsPerBlock) - 1L;
        int totalBlocks = w * h * l;

        for (int index = 0; index < totalBlocks; index++) {
            int startBit = index * bitsPerBlock;
            int startLong = startBit >>> 6;
            int endLong = ((index + 1) * bitsPerBlock - 1) >>> 6;
            int startOffset = startBit & 63;

            if (startLong >= blockStates.length) break;
            long val;
            if (startLong == endLong) {
                val = (blockStates[startLong] >>> startOffset) & maxEntryValue;
            } else {
                int endOffset = 64 - startOffset;
                val = ((blockStates[startLong] >>> startOffset) | (blockStates[endLong] << endOffset)) & maxEntryValue;
            }

            int y = index / (w * l);
            int z = (index % (w * l)) / w;
            int x = (index % (w * l)) % w;
            if ((int) val >= 0 && (int) val < palette.size()) {
                data.setBlockState(x, y, z, palette.get((int) val));
            }
        }
        return data;
    }

    private static SchematicData parseLegacySchematic(NbtCompound root) {
        int w = root.getShort("Width");
        int h = root.getShort("Height");
        int l = root.getShort("Length");
        SchematicData data = new SchematicData(w, h, l);
        byte[] blockIds = root.getByteArray("Blocks");
        for (int index = 0; index < blockIds.length && index < w * h * l; index++) {
            int id = blockIds[index] & 0xFF;
            int y = index / (w * l);
            int z = (index % (w * l)) / w;
            int x = (index % (w * l)) % w;
            BlockState state = (id == 0) ? Blocks.AIR.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
            data.setBlockState(x, y, z, state);
        }
        return data;
    }

    private static BlockState readStateFromNbt(NbtCompound tag) {
        String name = tag.getString("Name");
        Block block = Registries.BLOCK.get(Identifier.of(name));
        BlockState state = block.getDefaultState();
        if (tag.contains("Properties", NbtElement.COMPOUND_TYPE)) {
            NbtCompound props = tag.getCompound("Properties");
            StateManager<Block, BlockState> manager = block.getStateManager();
            for (String key : props.getKeys()) {
                Property<?> prop = manager.getProperty(key);
                if (prop != null) {
                    state = withProperty(state, prop, props.getString(key));
                }
            }
        }
        return state;
    }

    private static BlockState parseBlockStateString(String raw) {
        int bracket = raw.indexOf('[');
        String blockId = bracket == -1 ? raw : raw.substring(0, bracket);
        Block block = Registries.BLOCK.get(Identifier.of(blockId));
        BlockState state = block.getDefaultState();
        if (bracket != -1 && raw.endsWith("]")) {
            String propStr = raw.substring(bracket + 1, raw.length() - 1);
            StateManager<Block, BlockState> manager = block.getStateManager();
            for (String pair : propStr.split(",")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    Property<?> prop = manager.getProperty(kv[0].trim());
                    if (prop != null) {
                        state = withProperty(state, prop, kv[1].trim());
                    }
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> prop, String value) {
        Optional<T> parsed = prop.parse(value);
        return parsed.map(t -> state.with(prop, t)).orElse(state);
    }
            }
                          
