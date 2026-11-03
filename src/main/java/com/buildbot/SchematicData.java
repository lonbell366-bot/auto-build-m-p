package com.buildbot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

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

    @SuppressWarnings("unchecked")
    public static SchematicData loadFromFile(String fileName) throws Exception {
        File schemDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "schematics");
        if (!schemDir.exists()) schemDir.mkdirs();

        File file = new File(schemDir, fileName);
        Map<String, Object> root;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            byte rootType = dis.readByte();
            if (rootType != 10) throw new IOException("Root NBT khong phai Compound");
            dis.readUTF(); // Root name
            root = (Map<String, Object>) readTagPayload(dis, (byte) 10);
        }

        if (root.containsKey("Schematic") && root.get("Schematic") instanceof Map) {
            root = (Map<String, Object>) root.get("Schematic");
        }

        if (root.containsKey("size") && root.containsKey("palette") && root.containsKey("blocks")) {
            return parseVanillaNbt(root);
        }
        if (root.containsKey("Width") && root.containsKey("Palette") && root.containsKey("BlockData")) {
            return parseSpongeSchem(root);
        }
        if (root.containsKey("Regions")) {
            return parseLitematic(root);
        }
        if (root.containsKey("Width") && root.containsKey("Blocks")) {
            return parseLegacySchematic(root);
        }
        throw new IllegalArgumentException("Dinh dang khong ho tro: " + fileName);
    }

    private static Object readTagPayload(DataInputStream dis, byte type) throws IOException {
        return switch (type) {
            case 1 -> dis.readByte();
            case 2 -> dis.readShort();
            case 3 -> dis.readInt();
            case 4 -> dis.readLong();
            case 5 -> dis.readFloat();
            case 6 -> dis.readDouble();
            case 7 -> {
                int len = dis.readInt();
                byte[] arr = new byte[len];
                dis.readFully(arr);
                yield arr;
            }
            case 8 -> dis.readUTF();
            case 9 -> {
                byte elemType = dis.readByte();
                int len = dis.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    list.add(readTagPayload(dis, elemType));
                }
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    byte nextType = dis.readByte();
                    if (nextType == 0) break;
                    String key = dis.readUTF();
                    map.put(key, readTagPayload(dis, nextType));
                }
                yield map;
            }
            case 11 -> {
                int len = dis.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readInt();
                yield arr;
            }
            case 12 -> {
                int len = dis.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readLong();
                yield arr;
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        };
    }

    @SuppressWarnings("unchecked")
    private static SchematicData parseVanillaNbt(Map<String, Object> root) {
        List<Object> sizeList = (List<Object>) root.get("size");
        int w = ((Number) sizeList.get(0)).intValue();
        int h = ((Number) sizeList.get(1)).intValue();
        int l = ((Number) sizeList.get(2)).intValue();
        SchematicData data = new SchematicData(w, h, l);

        List> paletteList = (List>) root.get("palette");
        List<BlockState> palette = new ArrayList<>();
        for (Map<String, Object> entry : paletteList) {
            palette.add(readStateFromMap(entry));
        }

        List> blocksList = (List>) root.get("blocks");
        for (Map<String, Object> b : blocksList) {
            List<Object> pos = (List<Object>) b.get("pos");
            int stateIdx = ((Number) b.get("state")).intValue();
            if (stateIdx >= 0 && stateIdx < palette.size()) {
                data.setBlockState(((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(), ((Number) pos.get(2)).intValue(), palette.get(stateIdx));
            }
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static SchematicData parseSpongeSchem(Map<String, Object> root) {
        int w = ((Number) root.get("Width")).intValue();
        int h = ((Number) root.get("Height")).intValue();
        int l = ((Number) root.get("Length")).intValue();
        SchematicData data = new SchematicData(w, h, l);

        Map<String, Object> paletteTag = (Map<String, Object>) root.get("Palette");
        Map<Integer, BlockState> palette = new HashMap<>();
        for (Map.Entry<String, Object> entry : paletteTag.entrySet()) {
            palette.put(((Number) entry.getValue()).intValue(), parseBlockStateString(entry.getKey()));
        }

        byte[] blockData = (byte[]) root.get("BlockData");
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
            data.setBlockState(x, y, z, palette.getOrDefault(value, Blocks.AIR.getDefaultState()));
            index++;
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static SchematicData parseLitematic(Map<String, Object> root) {
        Map<String, Object> regions = (Map<String, Object>) root.get("Regions");
        Map<String, Object> region = (Map<String, Object>) regions.values().iterator().next();

        Map<String, Object> sizeTag = (Map<String, Object>) region.get("Size");
        int w = Math.abs(((Number) sizeTag.get("x")).intValue());
        int h = Math.abs(((Number) sizeTag.get("y")).intValue());
        int l = Math.abs(((Number) sizeTag.get("z")).intValue());
        SchematicData data = new SchematicData(w, h, l);

        List> paletteList = (List>) region.get("BlockStatePalette");
        List<BlockState> palette = new ArrayList<>();
        for (Map<String, Object> entry : paletteList) {
            palette.add(readStateFromMap(entry));
        }

        long[] blockStates = (long[]) region.get("BlockStates");
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

    private static SchematicData parseLegacySchematic(Map<String, Object> root) {
        int w = ((Number) root.get("Width")).intValue();
        int h = ((Number) root.get("Height")).intValue();
        int l = ((Number) root.get("Length")).intValue();
        SchematicData data = new SchematicData(w, h, l);
        byte[] blockIds = (byte[]) root.get("Blocks");
        for (int index = 0; index < blockIds.length && index < w * h * l; index++) {
            int id = blockIds[index] & 0xFF;
            int y = index / (w * l);
            int z = (index % (w * l)) / w;
            int x = (index % (w * l)) % w;
            data.setBlockState(x, y, z, (id == 0) ? Blocks.AIR.getDefaultState() : Blocks.COBBLESTONE.getDefaultState());
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static BlockState readStateFromMap(Map<String, Object> tag) {
        String name = (String) tag.get("Name");
        Block block = Registries.BLOCK.get(Identifier.of(name));
        BlockState state = block.getDefaultState();
        if (tag.containsKey("Properties") && tag.get("Properties") instanceof Map) {
            Map<String, Object> props = (Map<String, Object>) tag.get("Properties");
            StateManager<Block, BlockState> manager = block.getStateManager();
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                Property<?> prop = manager.getProperty(entry.getKey());
                if (prop != null) {
                    state = withProperty(state, prop, String.valueOf(entry.getValue()));
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
                          
