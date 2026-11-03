package com.buildbot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.zip.GZIPInputStream;

@SuppressWarnings({"unchecked", "rawtypes"})
public class SchematicData {
    public int width, height, length;
    private final BlockState[][][] blocks;
    public final Map totalMaterials = new HashMap();

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
                Block b = state.getBlock();
                int count = totalMaterials.containsKey(b) ? (Integer) totalMaterials.get(b) : 0;
                totalMaterials.put(b, count + 1);
            }
        }
    }

    /**
     * Lay truc tiep the gioi Schematic cua Litematica neu dang mo
     */
    public static World getLitematicaWorld() {
        try {
            Class handlerClass = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            Method getWorld = handlerClass.getMethod("getSchematicWorld");
            Object worldObj = getWorld.invoke(null);
            if (worldObj instanceof World) {
                return (World) worldObj;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Lay ten Schematic ma nguoi choi dang load trong Litematica
     */
    public static String getActiveLitematicaName() {
        try {
            Class dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object pm = dmClass.getMethod("getSchematicPlacementManager").invoke(null);
            Collection list = (Collection) pm.getClass().getMethod("getAllSchematicsPlacements").invoke(pm);
            if (list != null && !list.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Object placement : list) {
                    String name = String.valueOf(placement.getClass().getMethod("getName").invoke(placement));
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(name);
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {}
        World w = getLitematicaWorld();
        return (w != null) ? "Litematica (Dang hoat dong)" : "Chua load trong Litematica";
    }

    /**
     * Quet truc tiep cac khoi tu Litematica quanh nguoi choi (ban kinh 8 chunks = 128x128 blocks)
     */
    public static SchematicData captureFromLitematica(MinecraftClient client, BuildBotConfig config) {
        World litematicaWorld = getLitematicaWorld();
        if (litematicaWorld == null || client.player == null) return null;

        BlockPos center = client.player.getBlockPos();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int radiusChunks = 6; // Quet vung 192x192 quanh nguoi choi

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean foundAny = false;

        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx++) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz++) {
                Chunk chunk = litematicaWorld.getChunk(cx, cz);
                if (chunk == null) continue;
                ChunkSection[] sections = chunk.getSectionArray();
                for (int sIdx = 0; sIdx < sections.length; sIdx++) {
                    ChunkSection sec = sections[sIdx];
                    if (sec == null || sec.isEmpty()) continue;
                    int baseY = chunk.sectionIndexToCoord(sIdx) << 4;
                    int baseX = cx << 4;
                    int baseZ = cz << 4;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState st = sec.getBlockState(x, y, z);
                                if (!st.isAir()) {
                                    foundAny = true;
                                    int wx = baseX + x;
                                    int wy = baseY + y;
                                    int wz = baseZ + z;
                                    if (wx < minX) minX = wx;
                                    if (wy < minY) minY = wy;
                                    if (wz < minZ) minZ = wz;
                                    if (wx > maxX) maxX = wx;
                                    if (wy > maxY) maxY = wy;
                                    if (wz > maxZ) maxZ = wz;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!foundAny) return null;

        int w = (maxX - minX) + 1;
        int h = (maxY - minY) + 1;
        int l = (maxZ - minZ) + 1;

        config.originX = minX;
        config.originY = minY;
        config.originZ = minZ;
        config.hasSavedOrigin = true;
        config.activeSchematicName = getActiveLitematicaName();
        config.save();

        SchematicData data = new SchematicData(w, h, l);
        BlockPos.Mutable mpos = new BlockPos.Mutable();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < l; z++) {
                    mpos.set(minX + x, minY + y, minZ + z);
                    BlockState st = litematicaWorld.getBlockState(mpos);
                    if (!st.isAir()) {
                        data.setBlockState(x, y, z, st);
                    }
                }
            }
        }
        return data;
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

        File file = new File(schemDir, fileName);
        Map root;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            byte rootType = dis.readByte();
            if (rootType != 10) throw new IOException("Root NBT khong phai Compound");
            dis.readUTF();
            root = (Map) readTagPayload(dis, (byte) 10);
        }

        if (root.containsKey("Schematic") && root.get("Schematic") instanceof Map) {
            root = (Map) root.get("Schematic");
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
                List list = new ArrayList(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    list.add(readTagPayload(dis, elemType));
                }
                yield list;
            }
            case 10 -> {
                Map map = new HashMap();
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

    private static SchematicData parseVanillaNbt(Map root) {
        List sizeList = (List) root.get("size");
        int w = ((Number) sizeList.get(0)).intValue();
        int h = ((Number) sizeList.get(1)).intValue();
        int l = ((Number) sizeList.get(2)).intValue();
        SchematicData data = new SchematicData(w, h, l);

        List paletteList = (List) root.get("palette");
        List palette = new ArrayList();
        for (Object obj : paletteList) {
            palette.add(readStateFromMap((Map) obj));
        }

        List blocksList = (List) root.get("blocks");
        for (Object obj : blocksList) {
            Map b = (Map) obj;
            List pos = (List) b.get("pos");
            int stateIdx = ((Number) b.get("state")).intValue();
            if (stateIdx >= 0 && stateIdx < palette.size()) {
                BlockState st = (BlockState) palette.get(stateIdx);
                data.setBlockState(((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(), ((Number) pos.get(2)).intValue(), st);
            }
        }
        return data;
    }

    private static SchematicData parseSpongeSchem(Map root) {
        int w = ((Number) root.get("Width")).intValue();
        int h = ((Number) root.get("Height")).intValue();
        int l = ((Number) root.get("Length")).intValue();
        SchematicData data = new SchematicData(w, h, l);

        Map paletteTag = (Map) root.get("Palette");
        Map palette = new HashMap();
        for (Object k : paletteTag.keySet()) {
            String keyStr = String.valueOf(k);
            int val = ((Number) paletteTag.get(k)).intValue();
            palette.put(val, parseBlockStateString(keyStr));
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
            BlockState st = (BlockState) palette.getOrDefault(value, Blocks.AIR.getDefaultState());
            data.setBlockState(x, y, z, st);
            index++;
        }
        return data;
    }

    private static SchematicData parseLitematic(Map root) {
        Map regions = (Map) root.get("Regions");
        Map region = (Map) regions.values().iterator().next();

        Map sizeTag = (Map) region.get("Size");
        int w = Math.abs(((Number) sizeTag.get("x")).intValue());
        int h = Math.abs(((Number) sizeTag.get("y")).intValue());
        int l = Math.abs(((Number) sizeTag.get("z")).intValue());
        SchematicData data = new SchematicData(w, h, l);

        List paletteList = (List) region.get("BlockStatePalette");
        List palette = new ArrayList();
        for (Object obj : paletteList) {
            palette.add(readStateFromMap((Map) obj));
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
                data.setBlockState(x, y, z, (BlockState) palette.get((int) val));
            }
        }
        return data;
    }

    private static SchematicData parseLegacySchematic(Map root) {
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

    private static BlockState readStateFromMap(Map tag) {
        String name = String.valueOf(tag.get("Name"));
        Block block = Registries.BLOCK.get(Identifier.of(name));
        BlockState state = block.getDefaultState();
        if (tag.containsKey("Properties") && tag.get("Properties") instanceof Map) {
            Map props = (Map) tag.get("Properties");
            StateManager manager = block.getStateManager();
            for (Object k : props.keySet()) {
                String propName = String.valueOf(k);
                Property prop = manager.getProperty(propName);
                if (prop != null) {
                    state = withProperty(state, prop, String.valueOf(props.get(k)));
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
            StateManager manager = block.getStateManager();
            for (String pair : propStr.split(",")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    Property prop = manager.getProperty(kv[0].trim());
                    if (prop != null) {
                        state = withProperty(state, prop, kv[1].trim());
                    }
                }
            }
        }
        return state;
    }

    private static BlockState withProperty(BlockState state, Property prop, String value) {
        Optional parsed = prop.parse(value);
        return parsed.isPresent() ? (BlockState) state.with(prop, (Comparable) parsed.get()) : state;
    }
                    }
