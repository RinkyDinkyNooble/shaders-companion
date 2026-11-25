package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.DataFormatException;

/**
 * The far terrain's mesher (research/lod/M2-PLAN.md, "Meshing"): one thread for the game ("Shaders Companion far
 * mesher", a daemon at the lowest priority) that turns {@link MeshJob}s into {@link NodeMesh}es and hands them back to
 * the job's {@link FarResidency}. It never touches the store's nodes: a job carries copies, blobs or face layers.
 * <p>
 * <b>What counts as solid for faces</b> (per voxel a mesh class, {@link FarNodes#meshClass}):
 * <ul>
 * <li>{@link #AIR}: air, and decorations without collision (grass, flowers, torches: the store's THIN): never drawn,
 * hides nothing.</li>
 * <li>{@link #WATER}: water and the plants standing in it (kelp, seagrass; drawn as water): faces only against
 * air, at the voxel's own height (level 0 water is a full voxel too).</li>
 * <li>{@link #SHEER}: see-through solids with collision (leaves, glass, fences, panes): drawn, faces against air and
 * water; they hide each other's faces (a canopy is a shell, as vanilla culls glass against glass) but not the faces
 * of opaque solids behind them (a trunk inside leaves, a room behind a window).</li>
 * <li>{@link #SOLID}: everything else that isn't air (opaque blocks, slabs, stairs, snow layers, lava): faces against
 * air, water and see-through solids; hides everything.</li>
 * <li>{@link #FILL}: from level 1 up ({@link #fillFromLevel}), air with sky light 0 (caves, closed rooms) is solid: it hides the cave's walls and
 * draws stone-coloured faces where it meets lit air, so a cave mouth is closed rather than a hole. Only in a dimension
 * with sky light and no ceiling ({@link FarNodes#caveFill}): in the Nether and the End all air is dark, so their far
 * terrain keeps its caves and open spaces at every level, the Nether's roof included (seen from below as the ceiling it
 * is, fogged as the chunk terrain's roof is).</li>
 * <li>{@link #UNKNOWN} (no data, inside a node or a whole neighbour node not stored): never drawn and hides every face,
 * at every level, so the edge of explored terrain and a node beside a missing one grow no walls into the void (they
 * would face away from anyone inside the explored area anyway). The world's top counts as open sky, its bottom as
 * unknown.</li>
 * </ul>
 * <b>Border faces</b>: see {@link NodeMesh}. A face on the node's side whose neighbour voxel (same level) hides it is
 * a seam face, kept only where that neighbour voxel is within {@link #SKIRT_REACH} voxels of an open one (air or
 * water) in the neighbour's face layer, so a solid underground border has no seam faces at all.
 * <p>
 * <b>Greedy merging</b> per direction and slice: neighbouring faces with the same palette entry, light and corner
 * shading become one rectangle. Corner shading ({@link #cornerShading}, off) would take the three voxels around each
 * corner in front of the face; faces whose four corners differ are then not merged at all.
 */
final class FarMesher {
    // Mesh classes (the low 3 bits of FarNodes.meshClass)
    static final int UNKNOWN = 0;
    static final int AIR = 1;
    static final int WATER = 2;
    static final int SHEER = 3;
    static final int SOLID = 4;
    static final int FILL = 5;
    /** FarNodes.meshClass: the state has a tint provider, so its palette entries keep their biome. */
    static final int TINTED = 8;
    /** FarNodes.meshClass: drawn in the translucent pass (water, stained glass, ice): its quads follow a range's opaque ones. */
    static final int TRANSLUCENT = 16;
    /** FarNodes.meshClass: a plant without collision (grass, ferns, flowers, crops; class AIR). */
    static final int PLANT = 32;
    /** A face layer value's bit for a plant voxel ({@link #layerValue}). */
    static final int LAYER_PLANT = 1 << 11;
    /**
     * Quad word 1's top bit (NodeMesh): a level-0 up face with a plant on it, which the far terrain leaves out; the
     * shaders darken it ({@code far_terrain.vsh}'s u_PlantShade), as the plants darken the chunk terrain's ground.
     */
    static final int PLANTED = 1 << 31;
    /** Seam faces only where the neighbour's voxel is this close (in its face layer) to an open one. */
    static final int SKIRT_REACH = 2;
    /** Corner shading in the quads (2 bits a corner); off: every corner 3 (unshaded), which merges best. */
    static volatile boolean cornerShading;
    /** The first level whose dark air is filled (1, the plan's; 0 fills level 0's caves too: a dev check, research/lod/M2.md). */
    static volatile int fillFromLevel = 1;
    /** The mesher's cache of neighbours' face layers, by node and change stamp. */
    static final long LAYER_CACHE_BYTES = 16L << 20;

    static final int[] STEP_X = {0, 0, 0, 0, -1, 1};
    static final int[] STEP_Y = {-1, 1, 0, 0, 0, 0};
    static final int[] STEP_Z = {0, 0, -1, 1, 0, 0};
    static final int[] OPPOSITE = {1, 0, 3, 2, 5, 4};
    static final short[] SKY_LAYER = {(short) (AIR << 8 | 0xF0)};
    static final short[] UNKNOWN_LAYER = {(short) (UNKNOWN << 8)};

    // Per axis (direction >> 1: 0 y, 1 z, 2 x): the voxel index stride along it, and along the in-plane u and v
    private static final int[] SA = {1024, 32, 1};
    private static final int[] SU = {1, 1, 32};
    private static final int[] SV = {32, 1024, 1024};
    /** Per side (a direction): the index of the layer's voxel (0, 0). */
    private static final int[] SIDE_BASE = {0, 31 * 1024, 0, 31 * 32, 0, 31};
    private static final int FULL_SHADE = 0xFF;
    private static final int NO_MERGE = 1 << 30;

    /** Per mesh class pair (voxel << 3 | the voxel in front): whether the face is drawn, whether it is a seam face at a border. */
    private static final boolean[] FACE = new boolean[64];
    private static final boolean[] SKIRT = new boolean[64];

    static {
        for (int s = 0; s < 8; s++) {
            for (int n = 0; n < 8; n++) {
                boolean face = switch (s) {
                    case WATER -> n == AIR;
                    case SHEER -> n == AIR || n == WATER;
                    case SOLID, FILL -> n == AIR || n == WATER || n == SHEER;
                    default -> false;
                };
                FACE[s << 3 | n] = face;
                SKIRT[s << 3 | n] = draws(s) && !face && (s != WATER || n == WATER);
            }
        }
    }

    final Thread thread;
    private final LinkedBlockingQueue<MeshJob> jobs = new LinkedBlockingQueue<>();
    final Pool pool = new Pool();
    private boolean loggedFailure;
    /** Jobs that threw (the node gets an empty mesh). */
    volatile long failures;
    /** The mesher thread's blobs decoded, and neighbours' face layers found in its cache or not. */
    volatile long decodes;
    volatile long layerHits;
    volatile long layerMisses;

    FarMesher() {
        thread = new Thread(this::run, "Shaders Companion far mesher");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    void submit(MeshJob job) {
        jobs.add(job);
    }

    /** Drops the jobs of a residency that closed. */
    void forget(FarResidency sink) {
        jobs.removeIf(job -> {
            if (job.sink == sink) {
                pool.give(job);
                return true;
            }
            return false;
        });
    }

    private void run() {
        Mesher mesher = new Mesher(LAYER_CACHE_BYTES);
        while (true) {
            MeshJob job;
            try {
                job = jobs.take();
            } catch (InterruptedException e) {
                continue;
            }
            NodeMesh mesh;
            try {
                mesh = mesher.mesh(job);
            } catch (Throwable t) {
                failures++;
                if (!loggedFailure) {
                    loggedFailure = true;
                    ShadersCompanion.LOGGER.error("Shaders Companion far mesher: meshing node {} {} {} at level {} failed (it stays empty)", Node.keyX(job.key),
                            Node.keyY(job.key), Node.keyZ(job.key), job.level, t);
                }
                mesh = Mesher.emptyMesh(job, 0);
            }
            pool.give(job);
            decodes = mesher.decodes;
            layerHits = mesher.layerHits;
            layerMisses = mesher.layerMisses;
            FarResidency sink = job.sink;
            if (sink != null) {
                sink.meshed(mesh);
            }
        }
    }

    static boolean draws(int meshClass) {
        return meshClass >= WATER;
    }

    /** Whether a voxel of this class hides every face towards it. */
    static boolean hides(int meshClass) {
        return meshClass == SOLID || meshClass == FILL || meshClass == UNKNOWN;
    }

    /** Whether a voxel of this class shows a face towards a voxel of that class. */
    static boolean face(int self, int front) {
        return FACE[self << 3 | front];
    }

    /** A voxel's mesh class (cave filling applied). */
    static int voxelClass(int id, int light, byte[] classes, int classCount, boolean fill) {
        int c = id == StateIds.UNKNOWN ? UNKNOWN : id < classCount ? classes[id] & 7 : SOLID;
        if (fill && c == AIR && (light & 0xF0) == 0) {
            c = FILL;
        }
        return c;
    }

    /** A face layer's value for one voxel: its mesh class << 8 | its light, and {@link #LAYER_PLANT} for a plant. */
    static short layerValue(int id, int light, byte[] classes, int classCount, boolean fill) {
        boolean plant = id >= 0 && id < classCount && (classes[id] & PLANT) != 0;
        return (short) (voxelClass(id, light, classes, classCount, fill) << 8 | light & 0xFF | (plant ? LAYER_PLANT : 0));
    }

    /**
     * One side of a node with arrays as a face layer: index v * 32 + u with the side's in-plane axes (NodeMesh), one
     * value when they are all the same.
     */
    static short[] layer(int[] state, byte[] light, int side, byte[] classes, int classCount, boolean fill) {
        int axis = side >> 1;
        int base = SIDE_BASE[side];
        int su = SU[axis];
        int sv = SV[axis];
        short[] out = new short[1024];
        boolean same = true;
        short first = 0;
        for (int v = 0; v < 32; v++) {
            for (int u = 0; u < 32; u++) {
                int i = base + u * su + v * sv;
                short value = layerValue(state[i], light[i], classes, classCount, fill);
                int m = v * 32 + u;
                out[m] = value;
                if (m == 0) {
                    first = value;
                } else if (value != first) {
                    same = false;
                }
            }
        }
        return same ? new short[]{first} : out;
    }

    /** Arrays for the jobs' copies of nodes in memory, given back once meshed (a full node's arrays are 160 KB). */
    static final class Pool {
        private final ConcurrentLinkedQueue<int[]> states = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<byte[]> lights = new ConcurrentLinkedQueue<>();

        int[] takeState() {
            int[] a = states.poll();
            return a != null ? a : new int[Node.VOXELS];
        }

        byte[] takeLight() {
            byte[] a = lights.poll();
            return a != null ? a : new byte[Node.VOXELS];
        }

        void give(MeshJob job) {
            if (job.state != null && states.size() < 8) {
                states.add(job.state);
            }
            if (job.light != null && lights.size() < 8) {
                lights.add(job.light);
            }
            job.state = null;
            job.light = null;
        }
    }

    /**
     * The meshing itself, with its scratch arrays and the cache of neighbours' face layers: one per thread (the mesher
     * thread, and the worker for the mesh map).
     */
    static final class Mesher {
        private final NodeCodec codec = new NodeCodec(NodeCodec.DEFAULT_LEVEL);
        private final int[] ownState = new int[Node.VOXELS];
        private final byte[] ownLight = new byte[Node.VOXELS];
        private final short[] ownBiome = new short[Node.BIOME_CELLS];
        private final int[] otherState = new int[Node.VOXELS];
        private final byte[] otherLight = new byte[Node.VOXELS];
        private final short[] otherBiome = new short[Node.BIOME_CELLS];
        private final byte[] cls = new byte[Node.VOXELS];
        /** Per voxel its palette index + 1 (0: none yet). */
        private final int[] paletteOf = new int[Node.VOXELS];
        /** Drawable voxels per slice, by axis * 32 + slice. */
        private final int[] slices = new int[96];
        /** Voxels per slice that hide every face in front of them (solid, filled, unknown), by axis * 32 + slice. */
        private final int[] hiders = new int[96];
        private final short[][] layers = new short[6][];
        private final int[] mask = new int[1024];
        private final int[] skirt = new int[1024];
        private final int[] exposed = new int[1024];
        private final int[] exposedTmp = new int[1024];
        private int[] out = new int[1 << 14];
        private int[] sorted = new int[1 << 14];
        private int quads;
        private final Int2IntOpenHashMap paletteIndex = new Int2IntOpenHashMap();
        private int[] palette = new int[256];
        private int paletteSize;
        private final Long2ObjectLinkedOpenHashMap<CachedLayers> cache = new Long2ObjectLinkedOpenHashMap<>();
        private final long maxCacheBytes;
        private long cacheBytes;
        // The job at hand
        private int[] state;
        private byte[] light;
        private short[] biome;
        private short uniformBiome;
        private byte[] classes;
        private int classCount;
        private int waterId;
        private int stoneId;
        private boolean fill;
        private int level;
        private boolean exposedReady;
        private boolean skirtAny;
        private int minX;
        private int minY;
        private int minZ;
        private int maxX;
        private int maxY;
        private int maxZ;
        private int dark;
        // Statistics
        long decodes;
        long layerHits;
        long layerMisses;

        private record CachedLayers(long stamp, short[][] layers, int bytes) {
        }

        Mesher(long maxCacheBytes) {
            this.maxCacheBytes = maxCacheBytes;
            paletteIndex.defaultReturnValue(-1);
        }

        static NodeMesh emptyMesh(MeshJob job, long nanos) {
            return new NodeMesh(job.key, job.version, new int[0], 0, new int[NodeMesh.RANGES], new int[NodeMesh.RANGES], new int[NodeMesh.RANGES], new int[0], new int[6],
                    nanos, 0);
        }

        NodeMesh mesh(MeshJob job) throws DataFormatException {
            long start = System.nanoTime();
            classes = job.classes;
            classCount = job.classCount;
            waterId = job.waterId;
            stoneId = job.stoneId;
            level = job.level;
            fill = job.caveFill();
            boolean uniform;
            int uniformState = job.uniformState;
            byte uniformLight = job.uniformLight;
            biome = job.biome;
            uniformBiome = job.uniformBiome;
            if (job.state != null) {
                state = job.state;
                light = job.light;
                uniform = false;
            } else if (job.blob != null) {
                Node node = new Node(job.level, Node.keyX(job.key), Node.keyY(job.key), Node.keyZ(job.key));
                codec.decodeInto(job.blob.blob(), node, ownState, ownLight, ownBiome);
                decodes++;
                biome = node.biome;
                uniformBiome = node.uniformBiome;
                if (node.state != null) {
                    state = node.state;
                    light = node.light;
                    uniform = false;
                } else {
                    uniform = true;
                    uniformState = node.uniformState;
                    uniformLight = node.uniformLight;
                }
                // Its faces for the neighbours meshed after it
                keepLayers(job.blob.key(), job.blob.stamp(), uniform ? null : state, uniform ? null : light, uniformState, uniformLight);
            } else {
                uniform = true;
            }
            if (uniform) {
                Arrays.fill(ownState, uniformState);
                Arrays.fill(ownLight, uniformLight);
                state = ownState;
                light = ownLight;
                int c = voxelClass(uniformState, uniformLight, classes, classCount, fill);
                Arrays.fill(cls, (byte) c);
                Arrays.fill(slices, draws(c) ? 1024 : 0);
                Arrays.fill(hiders, hides(c) ? 1024 : 0);
            } else {
                classify();
            }
            for (int d = 0; d < 6; d++) {
                layers[d] = resolve(job.neighbours[d], OPPOSITE[d]);
            }
            Arrays.fill(paletteOf, 0);
            paletteIndex.clear();
            paletteSize = 0;
            quads = 0;
            dark = 0;
            minX = minY = minZ = Integer.MAX_VALUE;
            maxX = maxY = maxZ = Integer.MIN_VALUE;
            int[] rangeStart = new int[NodeMesh.RANGES];
            int[] rangeCount = new int[NodeMesh.RANGES];
            int[] rangeOpaque = new int[NodeMesh.RANGES];
            for (int d = 0; d < 6; d++) {
                rangeStart[2 * d] = quads;
                faces(d, uniform);
                rangeCount[2 * d] = quads - rangeStart[2 * d];
                rangeOpaque[2 * d] = opaqueFirst(rangeStart[2 * d], quads);
                rangeStart[2 * d + 1] = quads;
                if (skirtAny) {
                    greedy(skirt, d, (d & 1) == 1 ? 31 : 0);
                }
                rangeCount[2 * d + 1] = quads - rangeStart[2 * d + 1];
                rangeOpaque[2 * d + 1] = opaqueFirst(rangeStart[2 * d + 1], quads);
            }
            int[] box = quads == 0 ? new int[6] : new int[]{minX, minY, minZ, maxX, maxY, maxZ};
            NodeMesh mesh = new NodeMesh(job.key, job.version, Arrays.copyOf(out, quads * 2), quads, rangeStart, rangeCount, rangeOpaque, Arrays.copyOf(palette, paletteSize),
                    box, System.nanoTime() - start, dark);
            state = null;
            light = null;
            biome = null;
            return mesh;
        }

        /**
         * Puts a range's opaque quads first, then its translucent ones (water, stained glass), each in their order;
         * returns the opaque count.
         */
        private int opaqueFirst(int from, int to) {
            int opaque = 0;
            boolean any = false;
            for (int q = from; q < to; q++) {
                if (translucent(out[q * 2 + 1])) {
                    any = true;
                } else {
                    opaque++;
                }
            }
            if (!any) {
                return to - from;
            }
            if (sorted.length < out.length) {
                sorted = new int[out.length];
            }
            int o = from * 2;
            int t = (from + opaque) * 2;
            for (int q = from; q < to; q++) {
                int at = translucent(out[q * 2 + 1]) ? t : o;
                sorted[at] = out[q * 2];
                sorted[at + 1] = out[q * 2 + 1];
                if (at == t) {
                    t += 2;
                } else {
                    o += 2;
                }
            }
            System.arraycopy(sorted, from * 2, out, from * 2, (to - from) * 2);
            return opaque;
        }

        private boolean translucent(int word1) {
            int s = NodeMesh.paletteState(palette[NodeMesh.quadPalette(word1)]);
            return s < classCount && (classes[s] & TRANSLUCENT) != 0;
        }

        /** Mesh classes of a node with arrays, and the drawable voxels per slice. */
        private void classify() {
            Arrays.fill(slices, 0);
            Arrays.fill(hiders, 0);
            int[] s = state;
            byte[] l = light;
            byte[] c = cls;
            for (int i = 0; i < Node.VOXELS; i++) {
                int k = voxelClass(s[i], l[i], classes, classCount, fill);
                c[i] = (byte) k;
                if (k >= WATER) {
                    // Slices by axis: y, z, x
                    slices[i >> 10]++;
                    slices[32 + (i >> 5 & 31)]++;
                    slices[64 + (i & 31)]++;
                }
                if (k == SOLID || k == FILL || k == UNKNOWN) {
                    hiders[i >> 10]++;
                    hiders[32 + (i >> 5 & 31)]++;
                    hiders[64 + (i & 31)]++;
                }
            }
        }

        /** The faces of one direction: the main range's quads now, the border slice's seam faces into {@link #skirt}. */
        private void faces(int d, boolean uniform) {
            int axis = d >> 1;
            int sa = SA[axis];
            int su = SU[axis];
            int sv = SV[axis];
            int sign = (d & 1) == 1 ? 1 : -1;
            int border = sign > 0 ? 31 : 0;
            short[] layer = layers[d];
            boolean shading = cornerShading;
            // Level 0's up faces: whether a plant stands on the face (the voxel in front)
            boolean plants = d == 1 && level == 0;
            skirtAny = false;
            exposedReady = false;
            byte[] c = cls;
            for (int slice = 0; slice < 32; slice++) {
                if (slices[axis * 32 + slice] == 0) {
                    continue;
                }
                boolean atBorder = slice == border;
                if (uniform && !atBorder) {
                    // One class throughout: no faces inside
                    continue;
                }
                if (!atBorder && hiders[axis * 32 + slice + sign] == 1024) {
                    // The slice in front hides every face (solid rock, filled caves)
                    continue;
                }
                if (atBorder && layer.length == 1 && hides(layer[0] >> 8 & 7)) {
                    // A neighbour face that hides everything, and no open voxel in it for seam faces
                    continue;
                }
                if (atBorder) {
                    Arrays.fill(skirt, -1);
                }
                boolean any = false;
                int base = slice * sa;
                for (int v = 0; v < 32; v++) {
                    int row = base + v * sv;
                    for (int u = 0; u < 32; u++) {
                        int i = row + u * su;
                        int m = v * 32 + u;
                        mask[m] = -1;
                        int sc = c[i];
                        if (sc < WATER) {
                            continue;
                        }
                        int nc;
                        int nl;
                        boolean planted;
                        if (!atBorder) {
                            int j = i + sign * sa;
                            nc = c[j];
                            nl = light[j] & 0xFF;
                            int ns = state[j];
                            planted = plants && ns >= 0 && ns < classCount && (classes[ns] & PLANT) != 0;
                        } else {
                            int lv = layer.length == 1 ? layer[0] : layer[m];
                            nc = lv >> 8 & 7;
                            nl = lv & 0xFF;
                            planted = plants && (lv & LAYER_PLANT) != 0;
                        }
                        if (FACE[sc << 3 | nc]) {
                            int shade = shading ? shade(d, i) : FULL_SHADE;
                            mask[m] = key(i, sc, nl, shade) | (planted ? PLANTED : 0);
                            any = true;
                        } else if (atBorder && SKIRT[sc << 3 | nc]) {
                            if (!exposedReady) {
                                exposure(layer);
                                exposedReady = true;
                            }
                            int el = exposed[m];
                            if (el >= 0) {
                                skirt[m] = key(i, sc, el, FULL_SHADE);
                                skirtAny = true;
                            }
                        }
                    }
                }
                if (any) {
                    greedy(mask, d, slice);
                }
            }
        }

        /** A face's merge key and second quad word: palette index, light, corner shading (and the no-merge bit). */
        private int key(int i, int meshClass, int faceLight, int shade) {
            int p = paletteOf[i] - 1;
            if (p < 0) {
                p = paletteFor(i, meshClass);
                paletteOf[i] = p + 1;
            }
            int k = NodeMesh.word1(p, faceLight, shade & 0xFF);
            if ((shade & NO_MERGE) != 0) {
                k |= NO_MERGE;
            }
            return k;
        }

        private int paletteFor(int i, int meshClass) {
            int s = meshClass == WATER ? waterId : meshClass == FILL ? stoneId : state[i];
            boolean tinted = s >= 0 && s < classCount && (classes[s] & TINTED) != 0;
            int b = 0;
            if (tinted) {
                int x = i & 31;
                int z = i >> 5 & 31;
                int y = i >> 10;
                b = biome != null ? biome[Node.biomeIndex(x >> 2, y >> 2, z >> 2)] : uniformBiome;
            }
            int entry = NodeMesh.paletteEntry(s, b & 0xFFF);
            int index = paletteIndex.get(entry);
            if (index < 0) {
                if (paletteSize == 1 << 14) {
                    // More pairs than the quad's 14 bits (never in practice): the first entry
                    return 0;
                }
                index = paletteSize;
                if (paletteSize == palette.length) {
                    palette = Arrays.copyOf(palette, palette.length * 2);
                }
                palette[paletteSize++] = entry;
                paletteIndex.put(entry, index);
            }
            return index;
        }

        /** Greedy rectangles over a slice's face keys (cleared as they are used), appended as quads. */
        private void greedy(int[] keys, int d, int slice) {
            for (int v = 0; v < 32; v++) {
                for (int u = 0; u < 32; u++) {
                    int k = keys[v * 32 + u];
                    // -1: no face (a planted face's key has its top bit set too)
                    if (k == -1) {
                        continue;
                    }
                    int w = 1;
                    int h = 1;
                    if ((k & NO_MERGE) == 0) {
                        while (u + w < 32 && keys[v * 32 + u + w] == k) {
                            w++;
                        }
                        grow:
                        while (v + h < 32) {
                            int row = (v + h) * 32 + u;
                            for (int q = 0; q < w; q++) {
                                if (keys[row + q] != k) {
                                    break grow;
                                }
                            }
                            h++;
                        }
                    }
                    for (int dv = 0; dv < h; dv++) {
                        int row = (v + dv) * 32 + u;
                        Arrays.fill(keys, row, row + w, -1);
                    }
                    emit(d, slice, u, v, w, h, k & ~NO_MERGE);
                    u += w - 1;
                }
            }
        }

        private void emit(int d, int slice, int u, int v, int w, int h, int key) {
            int axis = d >> 1;
            int plane = slice + (d & 1);
            int x;
            int y;
            int z;
            if (axis == 0) {
                x = u;
                y = slice;
                z = v;
                box(u, plane, v, u + w, plane, v + h);
            } else if (axis == 1) {
                x = u;
                y = v;
                z = slice;
                box(u, v, plane, u + w, v + h, plane);
            } else {
                x = slice;
                y = v;
                z = u;
                box(plane, v, u, plane, v + h, u + w);
            }
            if (quads * 2 + 2 > out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            out[quads * 2] = NodeMesh.word0(x, y, z, w, h, d);
            out[quads * 2 + 1] = key;
            quads++;
            if ((NodeMesh.quadLight(key) & 0xF0) == 0) {
                dark++;
            }
        }

        private void box(int x0, int y0, int z0, int x1, int y1, int z1) {
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x1);
            maxY = Math.max(maxY, y1);
            maxZ = Math.max(maxZ, z1);
        }

        /**
         * Per voxel of a neighbour's face layer: the brightest light of an open voxel (air or water) within
         * {@link #SKIRT_REACH} of it, or -1 when there is none (no seam face there).
         */
        private void exposure(short[] layer) {
            if (layer.length == 1) {
                int c = layer[0] >> 8 & 7;
                Arrays.fill(exposed, c == AIR || c == WATER ? layer[0] & 0xFF : -1);
                return;
            }
            int[] a = exposedTmp;
            for (int m = 0; m < 1024; m++) {
                int c = layer[m] >> 8 & 7;
                a[m] = c == AIR || c == WATER ? layer[m] & 0xFF : -1;
            }
            // A max filter along u, then along v
            for (int v = 0; v < 32; v++) {
                for (int u = 0; u < 32; u++) {
                    int best = -1;
                    for (int q = Math.max(0, u - SKIRT_REACH); q <= Math.min(31, u + SKIRT_REACH); q++) {
                        best = Math.max(best, a[v * 32 + q]);
                    }
                    exposed[v * 32 + u] = best;
                }
            }
            System.arraycopy(exposed, 0, a, 0, 1024);
            for (int v = 0; v < 32; v++) {
                for (int u = 0; u < 32; u++) {
                    int best = -1;
                    for (int q = Math.max(0, v - SKIRT_REACH); q <= Math.min(31, v + SKIRT_REACH); q++) {
                        best = Math.max(best, a[q * 32 + u]);
                    }
                    exposed[v * 32 + u] = best;
                }
            }
        }

        /**
         * Corner shading of voxel i's face in direction d: per corner of the face, 3 minus the solid voxels among the
         * two beside it and the one diagonal to it in the layer in front (0 when both sides are solid), corners
         * (u0, v0), (u1, v0), (u1, v1), (u0, v1) from the low bits; NO_MERGE when the four differ. Voxels in diagonal
         * nodes count as open.
         */
        private int shade(int d, int i) {
            int fx = (i & 31) + STEP_X[d];
            int fy = (i >> 10) + STEP_Y[d];
            int fz = (i >> 5 & 31) + STEP_Z[d];
            int axis = d >> 1;
            // The in-plane unit steps (u, v) per axis: y: (x, z); z: (x, y); x: (z, y)
            int ux = axis == 2 ? 0 : 1;
            int uz = axis == 2 ? 1 : 0;
            int vy = axis == 0 ? 0 : 1;
            int vz = axis == 0 ? 1 : 0;
            int shade = 0;
            int first = -1;
            boolean same = true;
            for (int corner = 0; corner < 4; corner++) {
                int cu = corner == 1 || corner == 2 ? 1 : -1;
                int cv = corner >= 2 ? 1 : -1;
                boolean s1 = occludes(fx + cu * ux, fy, fz + cu * uz);
                boolean s2 = occludes(fx, fy + cv * vy, fz + cv * vz);
                boolean cn = occludes(fx + cu * ux, fy + cv * vy, fz + cu * uz + cv * vz);
                int ao = s1 && s2 ? 0 : 3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (cn ? 1 : 0));
                shade |= ao << (2 * corner);
                if (first < 0) {
                    first = ao;
                } else if (ao != first) {
                    same = false;
                }
            }
            return same ? shade : shade | NO_MERGE;
        }

        private boolean occludes(int x, int y, int z) {
            int c = classAt(x, y, z);
            return c == SOLID || c == FILL || c == SHEER;
        }

        /** A voxel's class in the node or, one step outside it, in a neighbour's face layer (two steps out: open). */
        private int classAt(int x, int y, int z) {
            boolean ox = x < 0 || x > 31;
            boolean oy = y < 0 || y > 31;
            boolean oz = z < 0 || z > 31;
            int outside = (ox ? 1 : 0) + (oy ? 1 : 0) + (oz ? 1 : 0);
            if (outside == 0) {
                return cls[x + 32 * (z + 32 * y)];
            }
            if (outside > 1) {
                return AIR;
            }
            int d;
            int u;
            int v;
            if (ox) {
                d = x < 0 ? 4 : 5;
                u = z;
                v = y;
            } else if (oy) {
                d = y < 0 ? 0 : 1;
                u = x;
                v = z;
            } else {
                d = z < 0 ? 2 : 3;
                u = x;
                v = y;
            }
            short[] layer = layers[d];
            return (layer.length == 1 ? layer[0] : layer[v * 32 + u]) >> 8 & 7;
        }

        /** A neighbour's face layer (the given side of it) from the job: as it came, or from the cache, or decoded. */
        private short[] resolve(Object source, int side) throws DataFormatException {
            if (source instanceof short[] layer) {
                return layer;
            }
            MeshJob.Blob blob = (MeshJob.Blob) source;
            CachedLayers cached = cache.getAndMoveToLast(blob.key());
            if (cached != null && cached.stamp == blob.stamp()) {
                layerHits++;
                return cached.layers[side];
            }
            layerMisses++;
            Node node = new Node(Node.keyLevel(blob.key()), Node.keyX(blob.key()), Node.keyY(blob.key()), Node.keyZ(blob.key()));
            codec.decodeInto(blob.blob(), node, otherState, otherLight, otherBiome);
            decodes++;
            return keepLayers(blob.key(), blob.stamp(), node.state, node.light, node.uniformState, node.uniformLight)[side];
        }

        /** A node's six face layers into the cache (state null: a uniform node). */
        private short[][] keepLayers(long key, long stamp, int[] nodeState, byte[] nodeLight, int uniformState, byte uniformLight) {
            short[][] all = new short[6][];
            int bytes = 64;
            for (int side = 0; side < 6; side++) {
                all[side] = nodeState == null ? new short[]{layerValue(uniformState, uniformLight, classes, classCount, fill)}
                        : layer(nodeState, nodeLight, side, classes, classCount, fill);
                bytes += 16 + all[side].length * 2;
            }
            CachedLayers old = cache.remove(key);
            if (old != null) {
                cacheBytes -= old.bytes;
            }
            cache.putAndMoveToLast(key, new CachedLayers(stamp, all, bytes));
            cacheBytes += bytes;
            while (cacheBytes > maxCacheBytes && !cache.isEmpty()) {
                cacheBytes -= cache.removeFirst().bytes;
            }
            return all;
        }
    }
}
