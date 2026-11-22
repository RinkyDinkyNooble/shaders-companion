package com.rinkynooble.shaderscompanion.client;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Closed doors and trapdoors keep the light out of the rooms they close. Minecraft's light engine lets light through them,
 * as through every block that isn't a full one (vanilla looks the same), so a closed room with a door is lit from outside:
 * by the sky in the day, by a lamp outside the door at night. While the light leak fix is on ({@link LightLeaks}), the
 * client works out near each closed door the light as it would be with the door solid, and chunk meshes
 * ({@code WorldSliceLightMixin}), entities, block entities and the shader pack's eye brightness ({@code LightEngineMixin})
 * take that light. The server's light, and so mob spawning, is untouched.
 * <p>
 * How: the light engine's own way of taking light out when a block turns solid. From each closed door the light that
 * could have come through it is taken away (every cell darker than the cell it was reached from, out to where the light
 * runs out), and then the cells around that area light it again from what is left, never through a closed door. Doors
 * within 30 blocks of each other are worked out together (their reaches touch); each group again only when light or
 * blocks change within its reach. Only cells that end up darker are kept.
 */
public final class DoorLight {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** No cell corrected (published when nothing is, read by every light lookup). */
    private static final Long2ByteOpenHashMap NONE = cellMap(0);

    /** Corrected light, packed block position -> sky << 4 | block (only cells darker than the engine's). Read anywhere. */
    private static volatile Long2ByteOpenHashMap cells = NONE;
    /** Sections holding corrected cells (packed section positions), published with {@link #cells}. */
    private static volatile LongOpenHashSet cellSections = new LongOpenHashSet();

    /** While the groups are worked out, light lookups give the engine's own light. Main thread only. */
    public static boolean raw;

    // Main thread from here
    private static ClientLevel level;
    /** The client level's light engines, told from the integrated server's (it runs the same classes). */
    private static Object blockEngine;
    private static Object skyEngine;
    /** Closed doors per section (packed section position -> packed block positions). */
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> doorsBySection = new Long2ObjectOpenHashMap<>();
    private static final LongLinkedOpenHashSet toScan = new LongLinkedOpenHashSet();
    /** Sections whose light or blocks changed since the last pass. */
    private static final LongOpenHashSet changed = new LongOpenHashSet();
    private static long ticks;
    private static long firstChange = -1;
    private static long lastChange = -1;
    private static List<Group> groups = List.of();
    private static final Map<Block, Boolean> doorBlocks = new IdentityHashMap<>();
    private static final BlockPos.MutableBlockPos from = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos to = new BlockPos.MutableBlockPos();
    private static String status = "no closed doors";

    /** Doors whose reaches touch, and the corrected cells worked out for them. */
    private static final class Group {
        final long[] doors;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        Long2ByteOpenHashMap cells;

        Group(long[] doors) {
            Arrays.sort(doors);
            this.doors = doors;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long door : doors) {
                x0 = Math.min(x0, BlockPos.getX(door));
                y0 = Math.min(y0, BlockPos.getY(door));
                z0 = Math.min(z0, BlockPos.getZ(door));
                x1 = Math.max(x1, BlockPos.getX(door));
                y1 = Math.max(y1, BlockPos.getY(door));
                z1 = Math.max(z1, BlockPos.getZ(door));
            }
            // A door's light reaches at most 15 cells; the cells that light those again one more
            minX = x0 - 16;
            minY = y0 - 16;
            minZ = z0 - 16;
            maxX = x1 + 16;
            maxY = y1 + 16;
            maxZ = z1 + 16;
        }

        boolean touches(LongOpenHashSet sections) {
            for (long section : sections) {
                int x = SectionPos.x(section) << 4;
                int y = SectionPos.y(section) << 4;
                int z = SectionPos.z(section) << 4;
                if (x + 15 >= minX && x <= maxX && y + 15 >= minY && y <= maxY && z + 15 >= minZ && z <= maxZ) {
                    return true;
                }
            }
            return false;
        }
    }

    private DoorLight() {
    }

    private static Long2ByteOpenHashMap cellMap(int size) {
        Long2ByteOpenHashMap map = new Long2ByteOpenHashMap(size);
        map.defaultReturnValue((byte) -1);
        return map;
    }

    /** Whether any cell is corrected: a quick test before a lookup. */
    public static boolean any() {
        return !cells.isEmpty();
    }

    /** The published cells, for callers that cache {@link #hasSection} per section. */
    public static Object snapshot() {
        return cells;
    }

    /** Whether the section (packed) holds corrected cells. */
    public static boolean hasSection(long section) {
        return cellSections.contains(section);
    }

    /** The light of a cell on a layer, given the light engine's. */
    public static int light(LightLayer layer, long pos, int value) {
        byte packed = cells.get(pos);
        if (packed == -1) {
            return value;
        }
        return Math.min(value, layer == LightLayer.SKY ? (packed >> 4) & 15 : packed & 15);
    }

    /** A light engine's answer for a cell: the client level's engines give the corrected light. */
    public static int engineLight(Object engine, BlockPos pos, int value) {
        if (raw || cells.isEmpty()) {
            return value;
        }
        if (engine == blockEngine) {
            return light(LightLayer.BLOCK, pos.asLong(), value);
        }
        if (engine == skyEngine) {
            return light(LightLayer.SKY, pos.asLong(), value);
        }
        return value;
    }

    /** New chunk data or light in a section (the client level's and light engine's hooks). */
    public static void sectionChanged(int x, int y, int z) {
        if (level != null) {
            long section = SectionPos.asLong(x, y, z);
            toScan.add(section);
            changed.add(section);
            noteChange();
        }
    }

    /** A block changed on the client. */
    public static void blockChanged(BlockPos pos, BlockState before, BlockState now) {
        if (level != null) {
            long section = SectionPos.asLong(pos);
            if (isDoor(before) || isDoor(now)) {
                toScan.add(section);
            }
            changed.add(section);
            noteChange();
        }
    }

    private static void noteChange() {
        if (firstChange < 0) {
            firstChange = ticks;
        }
        lastChange = ticks;
    }

    /** Every client tick, after {@link LightLeaks#tick}. */
    static void tick(Minecraft mc) {
        ticks++;
        ClientLevel wanted = LightLeaks.active ? mc.level : null;
        if (wanted != level) {
            // A new world, or the fix turned on or off (which rebuilds every chunk mesh by itself)
            level = wanted;
            doorsBySection.clear();
            toScan.clear();
            changed.clear();
            groups = List.of();
            publish(NONE, new LongOpenHashSet());
            firstChange = lastChange = -1;
            blockEngine = skyEngine = null;
            status = "no closed doors";
            if (level != null) {
                blockEngine = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
                skyEngine = level.getLightEngine().getLayerListener(LightLayer.SKY);
                scanLoaded(mc);
            }
            return;
        }
        if (level == null) {
            return;
        }
        for (int budget = 512; budget > 0 && !toScan.isEmpty(); budget--) {
            scan(toScan.removeFirstLong());
        }
        // Light settles a few ticks after a change (the client's light engine, then the server's update): wait until
        // nothing has changed for 3 ticks, at most a second
        if (firstChange < 0 || !toScan.isEmpty() || ticks - lastChange < 3 && ticks - firstChange < 20) {
            return;
        }
        pass(mc);
    }

    /** Every loaded section near the camera, when the fix starts in a world already loaded. */
    private static void scanLoaded(Minecraft mc) {
        int radius = mc.options.getEffectiveRenderDistance() + 1;
        SectionPos centre = SectionPos.of(mc.player != null ? mc.player.blockPosition() : mc.gameRenderer.getMainCamera().getBlockPosition());
        for (int x = centre.x() - radius; x <= centre.x() + radius; x++) {
            for (int z = centre.z() - radius; z <= centre.z() + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (!sections[i].hasOnlyAir()) {
                        toScan.add(SectionPos.asLong(x, level.getSectionYFromSectionIndex(i), z));
                    }
                }
            }
        }
        noteChange();
    }

    /** The closed doors in a section. */
    private static void scan(long key) {
        int sx = SectionPos.x(key);
        int sy = SectionPos.y(key);
        int sz = SectionPos.z(key);
        LongOpenHashSet found = null;
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
        int index = level.getSectionIndexFromSectionY(sy);
        if (chunk != null && index >= 0 && index < chunk.getSections().length) {
            LevelChunkSection section = chunk.getSection(index);
            if (!section.hasOnlyAir() && section.maybeHas(DoorLight::isDoor)) {
                for (int i = 0; i < 4096; i++) {
                    int x = i & 15;
                    int z = (i >> 4) & 15;
                    int y = i >> 8;
                    if (isClosedDoor(section.getBlockState(x, y, z))) {
                        if (found == null) {
                            found = new LongOpenHashSet();
                        }
                        found.add(BlockPos.asLong((sx << 4) + x, (sy << 4) + y, (sz << 4) + z));
                    }
                }
            }
        }
        LongOpenHashSet before = found == null ? doorsBySection.remove(key) : doorsBySection.put(key, found);
        if (before == null ? found != null : !before.equals(found)) {
            changed.add(key);
            noteChange();
        }
    }

    /** A door or trapdoor (open or closed): vanilla's and modded ones built on them or named so, glass doors left out. */
    private static boolean isDoor(BlockState state) {
        return doorBlocks.computeIfAbsent(state.getBlock(), DoorLight::isDoorBlock);
    }

    private static boolean isDoorBlock(Block block) {
        BlockState state = block.defaultBlockState();
        if (!state.hasProperty(BlockStateProperties.OPEN)) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (!(block instanceof DoorBlock || block instanceof TrapDoorBlock || path.contains("door"))) {
            return false;
        }
        // Doors one sees through let the light in
        @SuppressWarnings("deprecation")
        SoundType sound = state.getSoundType();
        return sound != SoundType.GLASS && !path.contains("glass") && !path.contains("window") && !path.contains("screen")
                && !path.contains("bars") && !path.contains("mesh");
    }

    private static boolean isClosedDoor(BlockState state) {
        return isDoor(state) && !state.getValue(BlockStateProperties.OPEN);
    }

    /** Works out again the groups whose reach saw a change, publishes the cells and rebuilds the meshes that changed. */
    private static void pass(Minecraft mc) {
        long start = System.nanoTime();
        // Doors in chunks that have unloaded since are forgotten (their chunks are scanned again when they come back)
        doorsBySection.keySet().removeIf((long section) -> !level.hasChunk(SectionPos.x(section), SectionPos.z(section)));
        LongOpenHashSet allDoors = new LongOpenHashSet();
        for (LongOpenHashSet doors : doorsBySection.values()) {
            allDoors.addAll(doors);
        }
        List<Group> next = new ArrayList<>();
        int worked = 0;
        for (long[] doors : group(allDoors)) {
            Group group = new Group(doors);
            Group old = null;
            for (Group candidate : groups) {
                if (Arrays.equals(candidate.doors, group.doors)) {
                    old = candidate;
                    break;
                }
            }
            if (old != null && !old.touches(changed)) {
                group.cells = old.cells;
            } else {
                group.cells = work(group.doors, allDoors);
                worked++;
            }
            next.add(group);
        }
        groups = next;
        int size = 0;
        for (Group group : next) {
            size += group.cells.size();
        }
        Long2ByteOpenHashMap merged = cellMap(size);
        LongOpenHashSet sections = new LongOpenHashSet();
        for (Group group : next) {
            for (Long2ByteMap.Entry entry : group.cells.long2ByteEntrySet()) {
                merged.put(entry.getLongKey(), entry.getByteValue());
                sections.add(SectionPos.blockToSection(entry.getLongKey()));
            }
        }
        // Meshes read a cell's light for the faces around it, so a changed cell rebuilds the sections it touches
        Long2ByteOpenHashMap old = cells;
        LongOpenHashSet rebuild = new LongOpenHashSet();
        for (Long2ByteMap.Entry entry : merged.long2ByteEntrySet()) {
            if (old.get(entry.getLongKey()) != entry.getByteValue()) {
                markAround(rebuild, entry.getLongKey());
            }
        }
        for (Long2ByteMap.Entry entry : old.long2ByteEntrySet()) {
            if (!merged.containsKey(entry.getLongKey())) {
                markAround(rebuild, entry.getLongKey());
            }
        }
        publish(merged, sections);
        for (long section : rebuild) {
            mc.levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
        changed.clear();
        firstChange = lastChange = -1;
        status = String.format("%d closed doors in %d groups, %d cells kept darker; last pass %d groups worked out in %.1f ms, %d sections rebuilt",
                allDoors.size(), next.size(), merged.size(), worked, (System.nanoTime() - start) / 1e6, rebuild.size());
    }

    private static void publish(Long2ByteOpenHashMap map, LongOpenHashSet sections) {
        cellSections = sections;
        cells = map;
    }

    private static void markAround(LongOpenHashSet sections, long pos) {
        int x = BlockPos.getX(pos);
        int y = BlockPos.getY(pos);
        int z = BlockPos.getZ(pos);
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dy = -1; dy <= 1; dy += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    sections.add(SectionPos.asLong((x + dx) >> 4, (y + dy) >> 4, (z + dz) >> 4));
                }
            }
        }
    }

    /** Doors whose reaches touch (no more than 30 blocks apart on any axis), joined. */
    private static List<long[]> group(LongOpenHashSet doors) {
        long[] all = doors.toLongArray();
        int[] parent = new int[all.length];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        // Buckets of 32 blocks: a door's partners are in its bucket or the 26 around it
        Long2ObjectOpenHashMap<LongArrayList> buckets = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < all.length; i++) {
            buckets.computeIfAbsent(bucket(BlockPos.getX(all[i]) >> 5, BlockPos.getY(all[i]) >> 5, BlockPos.getZ(all[i]) >> 5),
                    k -> new LongArrayList()).add(i);
        }
        for (int i = 0; i < all.length; i++) {
            int x = BlockPos.getX(all[i]);
            int y = BlockPos.getY(all[i]);
            int z = BlockPos.getZ(all[i]);
            for (int bx = (x >> 5) - 1; bx <= (x >> 5) + 1; bx++) {
                for (int by = (y >> 5) - 1; by <= (y >> 5) + 1; by++) {
                    for (int bz = (z >> 5) - 1; bz <= (z >> 5) + 1; bz++) {
                        LongArrayList bucket = buckets.get(bucket(bx, by, bz));
                        if (bucket == null) {
                            continue;
                        }
                        for (int b = 0; b < bucket.size(); b++) {
                            int j = (int) bucket.getLong(b);
                            if (j > i && Math.abs(BlockPos.getX(all[j]) - x) <= 30 && Math.abs(BlockPos.getY(all[j]) - y) <= 30
                                    && Math.abs(BlockPos.getZ(all[j]) - z) <= 30) {
                                parent[find(parent, i)] = find(parent, j);
                            }
                        }
                    }
                }
            }
        }
        Map<Integer, LongArrayList> joined = new java.util.HashMap<>();
        for (int i = 0; i < all.length; i++) {
            joined.computeIfAbsent(find(parent, i), k -> new LongArrayList()).add(all[i]);
        }
        List<long[]> result = new ArrayList<>();
        for (LongArrayList list : joined.values()) {
            result.add(list.toLongArray());
        }
        return result;
    }

    private static long bucket(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** The corrected cells of one group: sky and block light with its doors solid, where darker than the engine's. */
    private static Long2ByteOpenHashMap work(long[] doors, LongOpenHashSet allDoors) {
        raw = true;
        try {
            Long2ByteOpenHashMap block = flood(doors, allDoors, LightLayer.BLOCK);
            Long2ByteOpenHashMap sky = level.dimensionType().hasSkyLight() ? flood(doors, allDoors, LightLayer.SKY) : cellMap(0);
            // A closed door fills the side of its cell away from its open part: the faces Minecraft draws inside the cell
            // (the door's inner face) take the cell's light, which is the light of the space on the open side
            for (long door : doors) {
                BlockState state = level.getBlockState(from.set(door));
                Direction open = null;
                if (state.getBlock() instanceof DoorBlock) {
                    open = state.getValue(DoorBlock.FACING);
                } else if (state.getBlock() instanceof TrapDoorBlock) {
                    open = state.getValue(TrapDoorBlock.HALF) == Half.BOTTOM ? Direction.UP : Direction.DOWN;
                }
                if (open != null) {
                    long side = BlockPos.offset(door, open);
                    if (!allDoors.contains(side)) {
                        block.put(door, (byte) Math.min(base(LightLayer.BLOCK, door), get(block, LightLayer.BLOCK, side)));
                        if (level.dimensionType().hasSkyLight()) {
                            sky.put(door, (byte) Math.min(base(LightLayer.SKY, door), get(sky, LightLayer.SKY, side)));
                        }
                    }
                }
            }
            LongOpenHashSet touched = new LongOpenHashSet(block.keySet());
            touched.addAll(sky.keySet());
            Long2ByteOpenHashMap result = cellMap(touched.size());
            for (long pos : touched) {
                int baseBlock = base(LightLayer.BLOCK, pos);
                int baseSky = level.dimensionType().hasSkyLight() ? base(LightLayer.SKY, pos) : 0;
                int newBlock = Math.min(baseBlock, get(block, LightLayer.BLOCK, pos));
                int newSky = level.dimensionType().hasSkyLight() ? Math.min(baseSky, get(sky, LightLayer.SKY, pos)) : 0;
                if (newBlock < baseBlock || newSky < baseSky) {
                    result.put(pos, (byte) (newSky << 4 | newBlock));
                }
            }
            return result;
        } finally {
            raw = false;
        }
    }

    /**
     * One layer's light with the group's doors solid, as the cells that differ from the engine's (packed position ->
     * light): the light that may have come through a door taken out, then lit again from the cells around.
     */
    private static Long2ByteOpenHashMap flood(long[] doors, LongOpenHashSet allDoors, LightLayer layer) {
        Long2ByteOpenHashMap light = cellMap(256);
        LongArrayFIFOQueue darker = new LongArrayFIFOQueue();
        IntArrayFIFOQueue darkerLevels = new IntArrayFIFOQueue();
        LongArrayFIFOQueue brighter = new LongArrayFIFOQueue();
        for (long door : doors) {
            int value = base(layer, door);
            if (value > 0) {
                darker.enqueue(door);
                darkerLevels.enqueue(value);
            }
        }
        // Take out: a neighbour darker than the cell it was reached from may have had its light from it, so it goes too
        // (down to its own emission); a neighbour as bright or brighter has light of its own and lights the area again
        while (!darker.isEmpty()) {
            long pos = darker.dequeueLong();
            int value = darkerLevels.dequeueInt();
            for (Direction direction : DIRECTIONS) {
                long next = BlockPos.offset(pos, direction);
                if (allDoors.contains(next) || !loaded(next)) {
                    continue;
                }
                int nextValue = get(light, layer, next);
                if (nextValue == 0) {
                    continue;
                }
                if (nextValue >= value) {
                    brighter.enqueue(next);
                    continue;
                }
                if (!passes(pos, next, direction)) {
                    continue;
                }
                int emission = layer == LightLayer.BLOCK ? level.getBlockState(to.set(next)).getLightEmission(level, to) : 0;
                light.put(next, (byte) emission);
                darker.enqueue(next);
                darkerLevels.enqueue(nextValue);
                if (emission > 0) {
                    brighter.enqueue(next);
                }
            }
        }
        // Light again: as the light engine spreads it, losing at least 1 a cell, never into a closed door
        while (!brighter.isEmpty()) {
            long pos = brighter.dequeueLong();
            int value = get(light, layer, pos);
            if (value <= 1) {
                continue;
            }
            for (Direction direction : DIRECTIONS) {
                long next = BlockPos.offset(pos, direction);
                if (allDoors.contains(next) || !loaded(next)) {
                    continue;
                }
                BlockState state = level.getBlockState(to.set(next));
                int nextValue = value - Math.max(1, state.getLightBlock(level, to));
                if (nextValue <= 0 || nextValue <= get(light, layer, next) || !passes(pos, next, direction)) {
                    continue;
                }
                light.put(next, (byte) nextValue);
                brighter.enqueue(next);
            }
        }
        return light;
    }

    /** A cell's light in a flood: its new value, or the engine's where it has none. */
    private static int get(Long2ByteOpenHashMap light, LightLayer layer, long pos) {
        byte value = light.get(pos);
        return value >= 0 ? value : base(layer, pos);
    }

    private static int base(LightLayer layer, long pos) {
        return level.getBrightness(layer, to.set(pos));
    }

    private static boolean loaded(long pos) {
        int y = BlockPos.getY(pos);
        return !level.isOutsideBuildHeight(y) && level.hasChunk(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
    }

    /** Whether light crosses from one cell to the next: the faces between them don't close it by their shapes. */
    private static boolean passes(long pos, long next, Direction direction) {
        BlockState fromState = level.getBlockState(from.set(pos));
        BlockState toState = level.getBlockState(to.set(next));
        return !Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(level, from, fromState, direction),
                LightEngine.getOcclusionShape(level, to, toState, direction.getOpposite()));
    }

    static String status() {
        return LightLeaks.active ? status : "off";
    }
}
