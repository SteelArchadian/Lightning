package dev.lightning.heap;

import dev.lightning.analysis.ModIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Works out which mod keeps which memory, from a HotSpot heap dump.
 *
 * <p>A class histogram cannot say who owns a {@code long[]}. Here every object is charged to the
 * closest object that keeps it alive and belongs to a mod: the nearest mod-owned dominator in the
 * dominator tree. "X dominates Y" means every path from the GC roots to Y goes through X, so if
 * X were freed, Y would be freed too. Retained size = everything an object dominates.
 *
 * <p>Runs in its own JVM (see {@link #main}) so the game's heap is never used: arrays are
 * int-indexed and sized from the dump, roughly 100 bytes per object.
 */
public final class HeapAnalyzer {
    private static final String[] BASE = {"java", "library", "minecraft", "neoforge", "lwjgl", "unknown", ""};

    private final ModIndex mods;
    private final Consumer<String> progress;

    // Names and class layouts.
    private final Map<Long, String> strings = new HashMap<>();
    private final Map<Long, Long> classNameIds = new HashMap<>();
    private final Map<Long, ClassDef> classes = new HashMap<>();
    private final List<String> typeNames = new ArrayList<>();
    private final Map<String, Integer> typeIndex = new HashMap<>();
    private int idSize;

    // Objects, first in file order, then re-indexed by sorted id.
    private int n;
    private long[] ids = new long[1 << 20];
    private int[] type = new int[1 << 20];
    private int[] size = new int[1 << 20];
    private int[] refCount = new int[1 << 20];
    private long[] roots = new long[1 << 12];
    private int rootCount;

    private long[] sortedIds;
    private long[] offsets;
    private int[] edges;
    private byte[] scratch = new byte[1 << 16];

    // Field values kept for describing file-system holders (which jar a zip file system is).
    private static final java.util.Set<String> KEEP = java.util.Set.of("jdk.nio.zipfs.ZipFileSystem", "jdk.nio.zipfs.ZipPath",
            "sun.nio.fs.WindowsPath", "sun.nio.fs.WindowsPath$WindowsPathWithAttributes", "sun.nio.fs.UnixPath",
            "jdk.nio.zipfs.ByteArrayChannel", "net.neoforged.jarjar.nio.pathfs.PathPath", "net.neoforged.jarjar.nio.pathfs.PathFileSystem",
            "cpw.mods.niofs.union.UnionPath", "cpw.mods.niofs.union.UnionFileSystem");
    private final Map<Long, long[]> objectArrayData = new HashMap<>();
    private boolean fetchPass;
    private Path dumpPath;
    private final Map<Long, byte[]> keptData = new HashMap<>();
    private final Map<Long, Long> keptClass = new HashMap<>();
    private final Map<Long, byte[]> arrayData = new HashMap<>();
    private final java.util.Set<Long> wantInstances = new java.util.HashSet<>();
    private final java.util.Set<Long> wantArrays = new java.util.HashSet<>();

    private static final class ClassDef {
        long id, superId, loaderId;
        String name;
        int type, classObjectType;
        byte[] fieldTypes;
        long[] fieldNames;
        long[] staticRefs;
        int[] refOffsets; // offsets of reference fields in instance data, own fields first then superclasses
        int dataLength = -1;
    }

    public HeapAnalyzer(ModIndex mods, Consumer<String> progress) {
        this.mods = mods;
        this.progress = progress;
    }

    // --- entry point for the separate analysis process -------------------------------------

    /**
     * {@code java -Xmx<n>g -cp lightning.jar dev.lightning.heap.HeapAnalyzer dump.hprof report.txt [modmap.txt | modsFolder]}
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: HeapAnalyzer <dump.hprof> <report.txt> [modmap.txt | mods folder]");
            System.exit(2);
        }
        ModIndex mods = new ModIndex();
        if (args.length > 2) ModMap.load(Path.of(args[2]), mods);
        HeapAnalyzer a = new HeapAnalyzer(mods, msg -> System.out.println("[lightning-heap] " + msg));
        String report = a.analyze(Path.of(args[0]));
        Files.writeString(Path.of(args[1]), report);
        System.out.println("[lightning-heap] done " + args[1]);
    }

    // --- analysis -------------------------------------------------------------------------

    public String analyze(Path dump) throws IOException {
        long t0 = System.nanoTime();
        dumpPath = dump;
        progress.accept("reading objects (pass 1 of 2)");
        try (Hprof in = new Hprof(dump)) {
            in.header();
            idSize = in.idSize;
            readFile(in, false);
        }
        progress.accept(String.format(Locale.ROOT, "%,d objects, %,d roots, %,d classes", n, rootCount, classes.size()));
        index();
        progress.accept("reading references (pass 2 of 2)");
        try (Hprof in = new Hprof(dump)) {
            in.header();
            readFile(in, true);
        }
        scratch = null;
        progress.accept(String.format(Locale.ROOT, "%,d references; computing dominators", offsets[n]));
        return dominatorsAndReport(System.nanoTime() - t0);
    }

    private void readFile(Hprof in, boolean edgesPass) throws IOException {
        long lastReport = 0;
        while (!in.atEnd()) {
            int tag = in.u1();
            in.u4(); // time
            long length = in.u4() & 0xFFFFFFFFL;
            long end = in.position() + length;
            if (!edgesPass && tag == Hprof.UTF8) {
                long id = in.id();
                strings.put(id, new String(in.bytes((int) (length - idSize)), java.nio.charset.StandardCharsets.UTF_8));
            } else if (!edgesPass && tag == Hprof.LOAD_CLASS) {
                in.u4();
                long classId = in.id();
                in.u4();
                classNameIds.put(classId, in.id());
            } else if (tag == Hprof.HEAP_DUMP || tag == Hprof.HEAP_DUMP_SEGMENT) {
                while (in.position() < end) {
                    heapRecord(in, edgesPass);
                    if (in.position() - lastReport > (1L << 30)) {
                        lastReport = in.position();
                        progress.accept(String.format(Locale.ROOT, "  %.0f%%", 100.0 * in.position() / in.size));
                    }
                }
            } else {
                in.skip(end - in.position());
            }
        }
    }

    private int typeOf(String name) {
        Integer t = typeIndex.get(name);
        if (t != null) return t;
        typeNames.add(name);
        typeIndex.put(name, typeNames.size() - 1);
        return typeNames.size() - 1;
    }

    private String className(long classId) {
        Long nameId = classNameIds.get(classId);
        String raw = nameId == null ? null : strings.get(nameId);
        if (raw == null) return "class@" + Long.toHexString(classId);
        String name = raw.replace('/', '.');
        if (name.startsWith("[")) return dev.lightning.analysis.HeapHistogram.readable(name);
        return name;
    }

    private void heapRecord(Hprof in, boolean edgesPass) throws IOException {
        int sub = in.u1();
        switch (sub) {
            case Hprof.ROOT_UNKNOWN, Hprof.ROOT_STICKY_CLASS, Hprof.ROOT_MONITOR_USED -> root(in.id(), edgesPass);
            case Hprof.ROOT_JNI_GLOBAL -> {
                root(in.id(), edgesPass);
                in.id();
            }
            case Hprof.ROOT_JNI_LOCAL, Hprof.ROOT_JAVA_FRAME, Hprof.ROOT_THREAD_OBJECT -> {
                root(in.id(), edgesPass);
                in.u4();
                in.u4();
            }
            case Hprof.ROOT_NATIVE_STACK, Hprof.ROOT_THREAD_BLOCK -> {
                root(in.id(), edgesPass);
                in.u4();
            }
            case Hprof.CLASS_DUMP -> classDump(in, edgesPass);
            case Hprof.INSTANCE_DUMP -> instance(in, edgesPass);
            case Hprof.OBJ_ARRAY_DUMP -> objectArray(in, edgesPass);
            case Hprof.PRIM_ARRAY_DUMP -> primitiveArray(in, edgesPass);
            default -> throw new IOException("Unknown heap dump record 0x" + Integer.toHexString(sub) + " at " + in.position());
        }
    }

    private void root(long id, boolean edgesPass) {
        if (edgesPass || fetchPass) return;
        if (rootCount == roots.length) roots = Arrays.copyOf(roots, rootCount * 2);
        roots[rootCount++] = id;
    }

    private int add(long id, int t, long bytes, int refs) {
        if (n == ids.length) {
            int cap = n * 2;
            ids = Arrays.copyOf(ids, cap);
            type = Arrays.copyOf(type, cap);
            size = Arrays.copyOf(size, cap);
            refCount = Arrays.copyOf(refCount, cap);
        }
        ids[n] = id;
        type[n] = t;
        size[n] = (int) Math.min(Integer.MAX_VALUE, (bytes + 7) & ~7L);
        refCount[n] = refs;
        return n++;
    }

    /** Edges of the node with this id are written here during pass 2. */
    private void writeEdges(long id, long[] targets, int count) {
        int node = lookup(id);
        if (node < 0) return;
        long at = offsets[node];
        int max = (int) (offsets[node + 1] - at);
        int k = 0;
        for (int i = 0; i < count && k < max; i++) {
            int t = lookup(targets[i]);
            if (t >= 0) edges[(int) (at + k++)] = t;
        }
        while (k < max) edges[(int) (at + k++)] = -1;
    }

    private long[] refBuffer = new long[1024];

    private long[] refs(int count) {
        if (refBuffer.length < count) refBuffer = new long[Math.max(count, refBuffer.length * 2)];
        return refBuffer;
    }

    private void classDump(Hprof in, boolean edgesPass) throws IOException {
        ClassDef c = new ClassDef();
        c.id = in.id();
        in.u4();
        c.superId = in.id();
        c.loaderId = in.id();
        in.id();
        in.id();
        in.id();
        in.id();
        in.u4(); // instance size (in the live JVM)
        int cp = in.u2();
        for (int i = 0; i < cp; i++) {
            in.u2();
            in.skip(in.typeSize(in.u1()));
        }
        int statics = in.u2();
        long[] staticRefs = new long[statics];
        int refCountStatic = 0;
        long staticBytes = 0;
        for (int i = 0; i < statics; i++) {
            in.id();
            int t = in.u1();
            if (t == Hprof.T_OBJECT) {
                long ref = in.id();
                if (ref != 0) staticRefs[refCountStatic++] = ref;
            } else {
                in.skip(in.typeSize(t));
            }
            staticBytes += Hprof.heapTypeSize(t);
        }
        int fields = in.u2();
        c.fieldTypes = new byte[fields];
        c.fieldNames = new long[fields];
        for (int i = 0; i < fields; i++) {
            c.fieldNames[i] = in.id();
            c.fieldTypes[i] = (byte) in.u1();
        }
        if (fetchPass) return;
        if (c.loaderId != 0) staticRefs = append(staticRefs, refCountStatic++, c.loaderId);
        c.staticRefs = Arrays.copyOf(staticRefs, refCountStatic);
        if (edgesPass) {
            writeEdges(c.id, c.staticRefs, c.staticRefs.length);
            return;
        }
        c.name = className(c.id);
        c.type = typeOf(c.name);
        c.classObjectType = typeOf("class object of " + c.name);
        classes.put(c.id, c);
        add(c.id, c.classObjectType, 16 + staticBytes, c.staticRefs.length);
    }

    private static long[] append(long[] a, int at, long v) {
        if (at == a.length) a = Arrays.copyOf(a, a.length + 1);
        a[at] = v;
        return a;
    }

    /** Reference field offsets of a class's instance data (own fields first, then superclasses). */
    private ClassDef layout(long classId) {
        ClassDef c = classes.get(classId);
        if (c == null || c.dataLength >= 0) return c;
        List<Integer> offs = new ArrayList<>();
        int off = 0;
        for (ClassDef k = c; k != null; k = classes.get(k.superId)) {
            for (byte t : k.fieldTypes) {
                if (t == Hprof.T_OBJECT) offs.add(off);
                off += t == Hprof.T_OBJECT ? idSize : typeSizeStatic(t);
            }
            if (k.superId == 0) break;
        }
        c.refOffsets = offs.stream().mapToInt(Integer::intValue).toArray();
        c.dataLength = off;
        return c;
    }

    private static int typeSizeStatic(int t) {
        return switch (t) {
            case Hprof.T_BOOLEAN, Hprof.T_BYTE -> 1;
            case Hprof.T_CHAR, Hprof.T_SHORT -> 2;
            case Hprof.T_FLOAT, Hprof.T_INT -> 4;
            default -> 8;
        };
    }

    private long readId(byte[] data, int off) {
        if (idSize == 8) {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (data[off + i] & 0xFF);
            return v;
        }
        return ((data[off] & 0xFFL) << 24) | ((data[off + 1] & 0xFFL) << 16) | ((data[off + 2] & 0xFFL) << 8) | (data[off + 3] & 0xFFL);
    }

    private void instance(Hprof in, boolean edgesPass) throws IOException {
        long id = in.id();
        in.u4();
        long classId = in.id();
        int length = in.u4();
        if (fetchPass) {
            if (wantInstances.contains(id)) {
                keptData.put(id, in.bytes(length));
                keptClass.put(id, classId);
            } else {
                in.skip(length);
            }
            return;
        }
        if (scratch.length < length) scratch = new byte[Math.max(length, scratch.length * 2)];
        byte[] data = scratch;
        readInto(in, data, length);
        ClassDef c = layout(classId);
        if (edgesPass && c != null && KEEP.contains(c.name)) {
            keptData.put(id, Arrays.copyOf(data, length));
            keptClass.put(id, classId);
        }
        int count = 0;
        long[] r = null;
        if (c != null) {
            r = refs(c.refOffsets.length);
            for (int off : c.refOffsets) {
                if (off + idSize > length) break;
                long ref = readId(data, off);
                if (ref != 0) r[count++] = ref;
            }
        }
        if (edgesPass) {
            if (r != null) writeEdges(id, r, count);
            return;
        }
        // Live size: header + fields, with references compressed to 4 bytes.
        long bytes = 12 + (c == null ? length : length - (long) c.refOffsets.length * (idSize - 4));
        add(id, c == null ? typeOf("class@" + Long.toHexString(classId)) : c.type, bytes, count);
    }

    private void readInto(Hprof in, byte[] dst, int length) throws IOException {
        in.readFully(dst, length);
    }

    private void objectArray(Hprof in, boolean edgesPass) throws IOException {
        long id = in.id();
        in.u4();
        int count = in.u4();
        long classId = in.id();
        if (fetchPass) {
            if (wantArrays.contains(id) && count < 4096) {
                long[] elements = new long[count];
                for (int i = 0; i < count; i++) elements[i] = in.id();
                objectArrayData.put(id, elements);
            } else {
                in.skip((long) count * idSize);
            }
            return;
        }
        long[] r = refs(count);
        int k = 0;
        for (int i = 0; i < count; i++) {
            long ref = in.id();
            if (ref != 0) r[k++] = ref;
        }
        if (edgesPass) {
            writeEdges(id, r, k);
            return;
        }
        ClassDef c = classes.get(classId);
        int t = c != null ? c.type : typeOf(className(classId));
        add(id, t, 16 + 4L * count, k);
    }

    private void primitiveArray(Hprof in, boolean edgesPass) throws IOException {
        long id = in.id();
        in.u4();
        int count = in.u4();
        int elementType = in.u1();
        if (fetchPass && wantArrays.contains(id) && (long) count * in.typeSize(elementType) < (1 << 20)) {
            arrayData.put(id, in.bytes(count * in.typeSize(elementType)));
            return;
        }
        in.skip((long) count * in.typeSize(elementType));
        if (edgesPass || fetchPass) return;
        add(id, typeOf(Hprof.primitiveArrayName(elementType)), 16 + (long) count * Hprof.heapTypeSize(elementType), 0);
    }

    // --- indexing ----------------------------------------------------------------------------

    /** Re-indexes objects by sorted id so ids can be looked up by binary search. */
    private void index() {
        progress.accept("indexing");
        sortedIds = Arrays.copyOf(ids, n);
        Arrays.parallelSort(sortedIds);
        int[] newType = new int[n], newSize = new int[n], newRefs = new int[n];
        for (int i = 0; i < n; i++) {
            int p = Arrays.binarySearch(sortedIds, ids[i]);
            newType[p] = type[i];
            newSize[p] = size[i];
            newRefs[p] = refCount[i];
        }
        ids = null;
        type = newType;
        size = newSize;
        refCount = null;
        offsets = new long[n + 1];
        for (int i = 0; i < n; i++) offsets[i + 1] = offsets[i] + newRefs[i];
        if (offsets[n] > Integer.MAX_VALUE - 16) throw new IllegalStateException("too many references: " + offsets[n]);
        edges = new int[(int) offsets[n]];
    }

    private int lookup(long id) {
        int p = Arrays.binarySearch(sortedIds, 0, n, id);
        return p >= 0 ? p : -1;
    }

    // --- dominators and report ---------------------------------------------------------------

    private String dominatorsAndReport(long readNanos) {
        long t0 = System.nanoTime();
        int root = n; // virtual root above all GC roots
        int[] rootNodes = new int[rootCount];
        int rc = 0;
        for (int i = 0; i < rootCount; i++) {
            int v = lookup(roots[i]);
            if (v >= 0) rootNodes[rc++] = v;
        }
        rootNodes = Arrays.stream(Arrays.copyOf(rootNodes, rc)).distinct().toArray();

        // Depth-first search from the virtual root: preorder numbers (dfnum), parent in the DFS tree.
        int[] dfnum = new int[n + 1];
        Arrays.fill(dfnum, -1);
        int[] vertex = new int[n + 1]; // nodes by preorder number
        int[] parent = new int[n + 1];
        {
            int[] stack = new int[n + 1];
            long[] cursor = new long[n + 1];
            for (int v = 0; v < n; v++) cursor[v] = offsets[v];
            int sp = 0, counter = 0;
            stack[sp++] = root;
            dfnum[root] = counter;
            vertex[counter++] = root;
            parent[root] = -1;
            while (sp > 0) {
                int v = stack[sp - 1];
                int next = -1;
                if (v == root) {
                    while (cursor[root] < rootNodes.length) {
                        int w = rootNodes[(int) cursor[root]++];
                        if (dfnum[w] < 0) {
                            next = w;
                            break;
                        }
                    }
                } else {
                    while (cursor[v] < offsets[v + 1]) {
                        int w = edges[(int) cursor[v]++];
                        if (w >= 0 && dfnum[w] < 0) {
                            next = w;
                            break;
                        }
                    }
                }
                if (next >= 0) {
                    dfnum[next] = counter;
                    vertex[counter++] = next;
                    parent[next] = v;
                    stack[sp++] = next;
                } else {
                    sp--;
                }
            }
            reachableCount = counter;
        }
        int reachable = reachableCount;
        progress.accept(String.format(Locale.ROOT, "%,d of %,d objects reachable", reachable - 1, n));

        // Predecessor lists (reachable part only).
        int[] predCount = new int[n + 2];
        for (int v = 0; v < n; v++) {
            if (dfnum[v] < 0) continue;
            for (long e = offsets[v]; e < offsets[v + 1]; e++) {
                int w = edges[(int) e];
                if (w >= 0) predCount[w + 1]++;
            }
        }
        for (int w : rootNodes) predCount[w + 1]++;
        long[] predOff = new long[n + 2];
        for (int i = 0; i <= n; i++) predOff[i + 1] = predOff[i] + predCount[i + 1];
        int[] preds = new int[(int) predOff[n + 1]];
        long[] fill = Arrays.copyOf(predOff, n + 1);
        predCount = null;
        for (int v = 0; v < n; v++) {
            if (dfnum[v] < 0) continue;
            for (long e = offsets[v]; e < offsets[v + 1]; e++) {
                int w = edges[(int) e];
                if (w >= 0) preds[(int) fill[w]++] = v;
            }
        }
        for (int w : rootNodes) preds[(int) fill[w]++] = root;
        fill = null;
        edges = null; // no longer needed

        // Lengauer–Tarjan with path compression (iterative).
        int[] idom = new int[n + 1];
        Arrays.fill(idom, -1);
        int[] semi = new int[n + 1];
        int[] ancestor = new int[n + 1];
        int[] best = new int[n + 1];
        int[] samedom = new int[n + 1];
        int[] bucketHead = new int[n + 1];
        int[] bucketNext = new int[n + 1];
        Arrays.fill(semi, -1);
        Arrays.fill(ancestor, -1);
        Arrays.fill(samedom, -1);
        Arrays.fill(bucketHead, -1);
        for (int v = 0; v <= n; v++) best[v] = v;
        int[] path = new int[n + 1];
        for (int i = reachable - 1; i >= 1; i--) {
            int w = vertex[i];
            int p = parent[w];
            int s = p;
            for (long e = predOff[w]; e < predOff[w + 1]; e++) {
                int v = preds[(int) e];
                if (dfnum[v] < 0) continue;
                int s2 = dfnum[v] <= dfnum[w] ? v : semi[lowest(v, ancestor, best, semi, dfnum, path)];
                if (dfnum[s2] < dfnum[s]) s = s2;
            }
            semi[w] = s;
            bucketNext[w] = bucketHead[s];
            bucketHead[s] = w;
            ancestor[w] = p; // link
            for (int v = bucketHead[p]; v >= 0; v = bucketNext[v]) {
                int y = lowest(v, ancestor, best, semi, dfnum, path);
                if (semi[y] == semi[v]) idom[v] = p;
                else samedom[v] = y;
            }
            bucketHead[p] = -1;
        }
        for (int i = 1; i < reachable; i++) {
            int w = vertex[i];
            if (samedom[w] >= 0) idom[w] = idom[samedom[w]];
        }
        idom[root] = root;
        semi = ancestor = best = samedom = bucketHead = bucketNext = path = parent = null;
        preds = null;
        predOff = null;
        int rounds = 1;

        // Retained sizes: a node's dominator always has a smaller preorder number.
        long[] retained = new long[n + 1];
        for (int v = 0; v < n; v++) retained[v] = size[v];
        for (int i = reachable - 1; i >= 1; i--) {
            int v = vertex[i];
            if (idom[v] >= 0 && idom[v] != v) retained[idom[v]] += retained[v];
        }
        // Owners of types.
        int types = typeNames.size();
        String[] typeOwner = new String[types];
        boolean[] typeIsMod = new boolean[types];
        for (int t = 0; t < types; t++) {
            typeOwner[t] = ownerOfType(typeNames.get(t));
            typeIsMod[t] = !isBase(typeOwner[t]);
        }

        // Charge every object to its nearest mod-owned dominator (itself included).
        Map<String, Integer> modIds = new HashMap<>();
        List<String> modNames = new ArrayList<>();
        int[] owner = new int[n + 1];
        owner[root] = -1;
        // Without a mod above it, an object goes to the nearest identifiable non-JDK holder
        // (Minecraft, NeoForge, a library); only chains made entirely of JDK objects stay "JDK".
        List<String> labels = new ArrayList<>();
        Map<String, Integer> labelIds = new HashMap<>();
        int[] label = new int[n + 1];
        java.util.function.Function<String, Integer> labelId = name -> labelIds.computeIfAbsent(name, k -> {
            labels.add(k);
            return labels.size() - 1;
        });
        label[root] = labelId.apply("JDK (no mod or game object holds it)");
        Map<String, long[]> charged = new HashMap<>(); // owner → {bytes, objects}
        Map<Long, long[]> entries = new HashMap<>(); // (mod << 32 | type) → {retained, count, best node, best retained}
        for (int i = 1; i < reachable; i++) {
            int v = vertex[i];
            int t = type[v];
            int holder = idom[v] < 0 ? root : idom[v];
            if (typeIsMod[t]) {
                int m = modIds.computeIfAbsent(typeOwner[t], k -> {
                    modNames.add(k);
                    return modNames.size() - 1;
                });
                owner[v] = m;
            } else {
                owner[v] = owner[holder];
            }
            String typeOwnerName = typeOwner[t];
            boolean identifiable = !typeOwnerName.equals("java") && !typeOwnerName.equals("unknown") && !typeOwnerName.isEmpty();
            label[v] = owner[v] >= 0 ? labelId.apply(modNames.get(owner[v]))
                    : identifiable ? labelId.apply(baseLabel(typeOwnerName)) : label[holder];
            String who = labels.get(label[v]);
            // Where an owner's memory starts: the object whose holder belongs to someone else.
            if (label[v] != label[holder] || holder == root) {
                long key = ((long) label[v] << 32) | t;
                long[] agg = entries.computeIfAbsent(key, k -> new long[] {0, 0, -1, -1});
                agg[0] += retained[v];
                agg[1]++;
                if (retained[v] > agg[3]) {
                    agg[3] = retained[v];
                    agg[2] = v;
                }
            }
            long[] c = charged.computeIfAbsent(who, k -> new long[3]);
            c[0] += size[v];
            c[1]++;
        }
        long unreachableBytes = 0;
        for (int v = 0; v < n; v++) if (dfnum[v] < 0) unreachableBytes += size[v];
        long total = retained[root];

        // Plain arrays charged to each owner, for the "what kind of memory" column.
        Map<String, long[]> arraysByOwner = new HashMap<>();
        for (int v = 0; v < n; v++) {
            if (dfnum[v] < 0) continue;
            String name = typeNames.get(type[v]);
            if (!name.endsWith("[]")) continue;
            String who = labels.get(label[v]);
            arraysByOwner.computeIfAbsent(who, k -> new long[1])[0] += size[v];
        }

        progress.accept(String.format(Locale.ROOT, "dominators done in %.1f s", (System.nanoTime() - t0) / 1e9));
        String files;
        try {
            files = fileSystems(idom, retained);
        } catch (IOException | RuntimeException e) {
            files = "Could not describe file systems: " + e + "\n";
        }
        return report(total, unreachableBytes, charged, arraysByOwner, entries, labels, idom, retained, root, readNanos, System.nanoTime() - t0, rounds)
                + files;
    }

    // --- which files are held open ------------------------------------------------------------

    /** Offset and type of a named field in an instance's data, searching superclasses. */
    private int[] field(long classId, String name) {
        int off = 0;
        for (ClassDef k = classes.get(classId); k != null; k = classes.get(k.superId)) {
            for (int i = 0; i < k.fieldTypes.length; i++) {
                if (name.equals(strings.get(k.fieldNames[i]))) return new int[] {off, k.fieldTypes[i]};
                off += k.fieldTypes[i] == Hprof.T_OBJECT ? idSize : typeSizeStatic(k.fieldTypes[i]);
            }
            if (k.superId == 0) break;
        }
        return null;
    }

    private long refField(long obj, String name) {
        byte[] d = keptData.get(obj);
        if (d == null) return 0;
        int[] f = field(keptClass.get(obj), name);
        return f == null || f[1] != Hprof.T_OBJECT || f[0] + idSize > d.length ? 0 : readId(d, f[0]);
    }

    private String typeName(long obj) {
        int v = lookup(obj);
        return v < 0 ? "?" : typeNames.get(type[v]);
    }

    /** Text of a java.lang.String, or null (and the parts to fetch are requested). */
    private String string(long obj) {
        if (!keptData.containsKey(obj)) {
            wantInstances.add(obj);
            return null;
        }
        long value = refField(obj, "value");
        byte[] bytes = arrayData.get(value);
        if (bytes == null) {
            wantArrays.add(value);
            return null;
        }
        int[] coder = field(keptClass.get(obj), "coder");
        boolean utf16 = coder != null && keptData.get(obj)[coder[0]] == 1;
        return new String(bytes, utf16 ? java.nio.charset.StandardCharsets.UTF_16LE : java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** "outer.jar → META-INF/jarjar/inner.jar", or null while parts are still missing. */
    private String describePath(long path, int depth) {
        if (path == 0 || depth > 6) return "?";
        String t = typeName(path);
        if (t.equals("jdk.nio.zipfs.ZipPath")) {
            byte[] entry = arrayData.get(refField(path, "path"));
            if (entry == null) {
                wantArrays.add(refField(path, "path"));
                return null;
            }
            long fs = refField(path, "zfs");
            String outer = describePath(refField(fs, "zfpath"), depth + 1);
            return outer == null ? null : outer + " → " + new String(entry, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (t.equals("net.neoforged.jarjar.nio.pathfs.PathPath") || t.equals("cpw.mods.niofs.union.UnionPath")) {
            long fs = refField(path, "fileSystem");
            if (!keptData.containsKey(fs)) {
                wantInstances.add(fs);
                return null;
            }
            String base;
            if (typeName(fs).equals("net.neoforged.jarjar.nio.pathfs.PathFileSystem")) {
                base = describePath(refField(fs, "target"), depth + 1);
            } else {
                // A union file system's key is only the jar's own name; its first base path says
                // where that jar really is (often inside another mod's jar).
                base = firstListElementPath(refField(fs, "basepaths"), depth);
                if (base == null) return null;
                if (base == null || base.equals("?")) base = string(refField(fs, "key"));
            }
            String parts = joinParts(refField(path, "pathParts"));
            if (base == null || parts == null) return null;
            return parts.isEmpty() ? base : base + "!/" + parts;
        }
        if (t.contains("WindowsPath") || t.endsWith("UnixPath")) {
            if (t.endsWith("UnixPath")) {
                byte[] p = arrayData.get(refField(path, "path"));
                if (p == null) {
                    wantArrays.add(refField(path, "path"));
                    return null;
                }
                return shortPath(new String(p, java.nio.charset.StandardCharsets.UTF_8));
            }
            String text = string(refField(path, "path"));
            return text == null ? null : shortPath(text);
        }
        return t;
    }

    /** Describes the first Path in a java.util.List (ArrayList or immutable list), or null while data is missing. */
    private String firstListElementPath(long list, int depth) {
        if (list == 0) return "?";
        if (!keptData.containsKey(list)) {
            wantInstances.add(list);
            return null;
        }
        String t = typeName(list);
        long first;
        if (t.endsWith("$List12")) {
            first = refField(list, "e0");
        } else {
            long array = refField(list, t.endsWith("$ListN") ? "elements" : "elementData");
            long[] elements = objectArrayData.get(array);
            if (elements == null) {
                wantArrays.add(array);
                return null;
            }
            first = elements.length > 0 ? elements[0] : 0;
        }
        if (first != 0 && !keptData.containsKey(first) && !KEEP.contains(typeName(first))) {
            wantInstances.add(first);
            return null;
        }
        return describePath(first, depth + 1);
    }

    /** "a/b/c" from a String[] of path parts, or null while parts are missing. */
    private String joinParts(long array) {
        if (array == 0) return "";
        long[] elements = objectArrayData.get(array);
        if (elements == null) {
            wantArrays.add(array);
            return null;
        }
        StringBuilder b = new StringBuilder();
        boolean missing = false;
        for (long e : elements) {
            String part = e == 0 ? "" : string(e);
            if (part == null) missing = true;
            else if (!part.isEmpty()) b.append(b.length() > 0 ? "/" : "").append(part);
        }
        return missing ? null : b.toString();
    }

    private static String shortPath(String p) {
        int mods = p.replace('\\', '/').lastIndexOf("/mods/");
        if (mods >= 0) return p.substring(mods + 6);
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** Groups memory held by zip file systems (open jars) by which jar they are. */
    private String fileSystems(int[] idom, long[] retained) throws IOException {
        List<Long> systems = new ArrayList<>();
        for (Map.Entry<Long, Long> e : keptClass.entrySet()) {
            ClassDef c = classes.get(e.getValue());
            if (c != null && c.name.equals("jdk.nio.zipfs.ZipFileSystem")) systems.add(e.getKey());
        }
        if (systems.isEmpty()) return "";
        Map<Long, String> names = new HashMap<>();
        for (int round = 0; round < 16; round++) {
            wantInstances.clear();
            wantArrays.clear();
            for (long fs : systems) {
                if (names.containsKey(fs)) continue;
                String d = describePath(refField(fs, "zfpath"), 0);
                if (d != null) names.put(fs, d);
            }
            if (wantInstances.isEmpty() && wantArrays.isEmpty()) break;
            if (Boolean.getBoolean("lightning.heapDebug")) {
                Map<String, Integer> kinds = new HashMap<>();
                for (long id : wantInstances) kinds.merge("instance " + typeName(id) + (keptData.containsKey(id) ? " (have)" : ""), 1, Integer::sum);
                for (long id : wantArrays) kinds.merge("array " + typeName(id) + (arrayData.containsKey(id) || objectArrayData.containsKey(id) ? " (have)" : ""), 1, Integer::sum);
                progress.accept("wanted: " + kinds);
            }
            progress.accept(String.format(Locale.ROOT, "reading jar names (%d objects)", wantInstances.size() + wantArrays.size()));
            fetchPass = true;
            try (Hprof in = new Hprof(dumpPath)) {
                in.header();
                readFile(in, true);
            } finally {
                fetchPass = false;
            }
        }
        // Per jar: open file systems, memory they keep (own + in-memory channel), in-memory bytes.
        Map<String, long[]> byJar = new HashMap<>(); // {count, retained, inMemoryBytes, inMemoryCount}
        for (long fs : systems) {
            int v = lookup(fs);
            if (v < 0 || idom[v] < 0) continue;
            long held = retained[v];
            long ch = refField(fs, "ch");
            int chNode = lookup(ch);
            boolean inMemory = typeName(ch).equals("jdk.nio.zipfs.ByteArrayChannel");
            long memBytes = 0;
            if (chNode >= 0) {
                if (idom[chNode] != v) held += retained[chNode];
                if (inMemory) {
                    int buf = lookup(refField(ch, "buf"));
                    memBytes = buf >= 0 ? size[buf] : retained[chNode];
                }
            }
            long[] a = byJar.computeIfAbsent(names.getOrDefault(fs, "(name not found)"), k -> new long[4]);
            a[0]++;
            a[1] += held;
            a[2] += memBytes;
            if (inMemory) a[3]++;
        }
        StringBuilder b = new StringBuilder("\n## Open jar files held in memory\n");
        b.append("Zip file systems the mod loader keeps open, by jar. \"In memory\" means the whole inner jar was copied into the heap\n");
        b.append("(a ByteArrayChannel); otherwise it is read from disk and only the index is held.\n\n");
        long total = 0, totalMem = 0;
        for (long[] a : byJar.values()) {
            total += a[1];
            totalMem += a[2];
        }
        b.append(String.format(Locale.ROOT, "%,d open jar file systems hold %.0f MB; %.0f MB of that is jar contents copied into memory.%n%n",
                systems.size(), total / 1048576.0, totalMem / 1048576.0));
        b.append(String.format(Locale.ROOT, "%10s %10s  %s%n", "held MB", "in memory", "jar (outer → inner)"));
        byJar.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(80).forEach(e -> {
            long[] a = e.getValue();
            b.append(String.format(Locale.ROOT, "%10.1f %10s  %s%s%n", a[1] / 1048576.0,
                    a[3] > 0 ? String.format(Locale.ROOT, "%.1f MB", a[2] / 1048576.0) : "no (file)", e.getKey(), a[0] > 1 ? "  ×" + a[0] : ""));
        });
        // Per outer jar totals.
        Map<String, long[]> byOuter = new HashMap<>();
        for (var e : byJar.entrySet()) {
            // Outer jar = the first jar named: before " → " (zip paths) or "#n!/" / "#n_/" (loader paths).
            String outer = e.getKey().split(" → |!/|#\\d+[!_]/", 2)[0];
            long[] o = byOuter.computeIfAbsent(outer, k -> new long[3]);
            o[0] += e.getValue()[1];
            o[1] += e.getValue()[2];
            o[2] += e.getValue()[0];
        }
        b.append("\nBy outer jar (the mod that bundles them):\n");
        byOuter.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(30).forEach(e ->
                b.append(String.format(Locale.ROOT, "%10.1f MB  (%.1f MB in memory, %d file systems)  %s%n",
                        e.getValue()[0] / 1048576.0, e.getValue()[1] / 1048576.0, e.getValue()[2], e.getKey())));
        return b.toString();
    }

    private int reachableCount;

    /** Ancestor of v (in the linked forest) with the lowest semidominator; compresses the path. */
    private static int lowest(int v, int[] ancestor, int[] best, int[] semi, int[] dfnum, int[] path) {
        int k = 0;
        int u = v;
        while (ancestor[u] >= 0 && ancestor[ancestor[u]] >= 0) {
            path[k++] = u;
            u = ancestor[u];
        }
        // Process from the top of the chain down, as the recursive version would on return.
        for (int i = k - 1; i >= 0; i--) {
            int x = path[i];
            int a = ancestor[x];
            int b = best[a];
            ancestor[x] = ancestor[a];
            if (dfnum[semi[b]] < dfnum[semi[best[x]]]) best[x] = b;
        }
        return best[v];
    }

    private String ownerOfType(String name) {
        String n2 = name;
        if (n2.startsWith("class object of ")) n2 = n2.substring("class object of ".length());
        while (n2.endsWith("[]")) n2 = n2.substring(0, n2.length() - 2);
        if (!n2.contains(".")) return "java"; // primitive arrays
        int lambda = n2.indexOf("$$Lambda");
        if (lambda > 0) n2 = n2.substring(0, lambda);
        return mods.ownerOfClass(n2);
    }

    private static boolean isBase(String owner) {
        for (String b : BASE) if (b.equals(owner)) return true;
        return false;
    }

    private static String baseLabel(String owner) {
        return switch (owner) {
            case "java" -> "JDK (no mod or game object holds it)";
            case "minecraft" -> "Minecraft (held by the game itself, no mod above it)";
            case "neoforge" -> "NeoForge";
            case "unknown", "" -> "unattributed";
            default -> owner;
        };
    }

    private String report(long total, long unreachable, Map<String, long[]> charged, Map<String, long[]> arrays,
                          Map<Long, long[]> entries, List<String> modNames, int[] idom, long[] retained, int root,
                          long readNanos, long domNanos, int rounds) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "Lightning heap analysis: %,d objects, %.0f MB reachable%s.%n",
                n, total / 1048576.0, unreachable > 0 ? String.format(Locale.ROOT, " (%.0f MB unreachable, ignored)", unreachable / 1048576.0) : ""));
        b.append(String.format(Locale.ROOT, "Read in %.0f s, dominators in %.0f s.%n%n", readNanos / 1e9, domNanos / 1e9));
        b.append("Each object is charged to the nearest mod-owned object that keeps it alive (its nearest mod-owned dominator).\n");
        b.append("Memory with no mod object above it goes to the nearest game or library object holding it.\n");
        b.append("'Reached via' is the chain of sole holders (dominators); a short chain means several roots reach it.\n\n");
        b.append("## Memory by owner\n");
        b.append(String.format(Locale.ROOT, "%-46s %10s %7s %12s %14s%n", "owner", "MB", "share", "objects", "of which arrays MB"));
        charged.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(40).forEach(e -> {
            long arr = arrays.getOrDefault(e.getKey(), new long[1])[0];
            b.append(String.format(Locale.ROOT, "%-46s %10.1f %6.1f%% %,12d %14.1f%n", e.getKey(), e.getValue()[0] / 1048576.0,
                    100.0 * e.getValue()[0] / Math.max(1, total), e.getValue()[1], arr / 1048576.0));
        });

        b.append("\n## Where each mod's memory is held (top holders, with how they are reached)\n");
        Map<Integer, List<long[]>> byMod = new HashMap<>();
        for (var e : entries.entrySet()) {
            int m = (int) (e.getKey() >>> 32);
            int t = (int) (long) e.getKey();
            byMod.computeIfAbsent(m, k -> new ArrayList<>()).add(new long[] {t, e.getValue()[0], e.getValue()[1], e.getValue()[2]});
        }
        charged.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).forEach(e -> {
            int m = modNames.indexOf(e.getKey());
            if (m < 0 || e.getValue()[0] < (8L << 20)) return;
            b.append(String.format(Locale.ROOT, "%n### %s — %.0f MB%n", e.getKey(), e.getValue()[0] / 1048576.0));
            List<long[]> list = byMod.getOrDefault(m, List.of());
            list.sort((x, y) -> Long.compare(y[1], x[1]));
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                long[] h = list.get(i);
                b.append(String.format(Locale.ROOT, "- %s ×%,d retains %.0f MB%n", typeNames.get((int) h[0]), h[2], h[1] / 1048576.0));
                b.append("  reached via: ").append(path((int) h[3], idom, root)).append('\n');
                b.append("  largest one holds: ").append(contents((int) h[3], idom, retained)).append('\n');
            }
        });

        b.append("\n## Largest single holders overall\n");
        int[] top = topRetainers(retained, idom, 20, root);
        for (int v : top) {
            b.append(String.format(Locale.ROOT, "- %.0f MB  %s [%s]%n  via: %s%n", retained[v] / 1048576.0, typeNames.get(type[v]),
                    ownerOfType(typeNames.get(type[v])), path(v, idom, root)));
        }
        return b.toString();
    }

    /** The dominator chain from the roots down to v, as class names. */
    private String path(int v, int[] idom, int root) {
        List<String> parts = new ArrayList<>();
        for (int x = v, guard = 0; x != root && x >= 0 && guard < 200; x = idom[x], guard++) parts.add(shortName(typeNames.get(type[x])));
        java.util.Collections.reverse(parts);
        if (parts.size() > 10) {
            List<String> cut = new ArrayList<>(parts.subList(0, 4));
            cut.add("…");
            cut.addAll(parts.subList(parts.size() - 5, parts.size()));
            parts = cut;
        }
        return "GC root → " + String.join(" → ", parts);
    }

    /** The biggest kinds of objects directly dominated (held) by v. */
    private String contents(int v, int[] idom, long[] retained) {
        Map<Integer, long[]> kids = new HashMap<>();
        for (int w = 0; w < n; w++) {
            if (idom[w] == v && w != v) {
                long[] k = kids.computeIfAbsent(type[w], x -> new long[2]);
                k[0] += retained[w];
                k[1]++;
            }
        }
        StringBuilder b = new StringBuilder();
        kids.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(3)
                .forEach(e -> b.append(String.format(Locale.ROOT, "%s ×%,d %.0f MB; ", shortName(typeNames.get(e.getKey())), e.getValue()[1], e.getValue()[0] / 1048576.0)));
        return b.length() == 0 ? "(only itself)" : b.toString();
    }

    private int[] topRetainers(long[] retained, int[] idom, int k, int root) {
        // Largest objects that are not inside a bigger one of the same list: children of the root
        // and of huge holders are both interesting, so simply take the k largest non-root nodes
        // whose dominator holds noticeably more (avoids listing a chain of wrappers).
        java.util.PriorityQueue<int[]> heap = new java.util.PriorityQueue<>((a, b) -> Long.compare(retained[a[0]], retained[b[0]]));
        for (int v = 0; v < n; v++) {
            if (idom[v] < 0) continue;
            int p = idom[v];
            if (p != root && retained[p] < retained[v] * 1.1) continue; // a thin wrapper around the same memory
            heap.add(new int[] {v});
            if (heap.size() > k) heap.poll();
        }
        int[] out = new int[heap.size()];
        for (int i = out.length - 1; i >= 0; i--) out[i] = heap.poll()[0];
        return out;
    }

    private static String shortName(String name) {
        String prefix = name.startsWith("class object of ") ? "class " : "";
        String core = prefix.isEmpty() ? name : name.substring("class object of ".length());
        int dot = core.lastIndexOf('.', core.length() - 1);
        int dot2 = dot > 0 ? core.lastIndexOf('.', dot - 1) : -1;
        return prefix + (dot2 >= 0 ? core.substring(dot2 + 1) : core);
    }
}
