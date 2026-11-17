package undohistory;

import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Two-tier storage for plane copies: memory first, then a spill folder on disk.
 *
 * Rules, in order, when a changed plane must be stored:
 * <ol>
 * <li>in memory, if it fits the memory budget and Java keeps a safety reserve of free heap;</li>
 * <li>otherwise older in-memory planes are moved to disk to make room;</li>
 * <li>otherwise the plane is written straight from the image to disk (no extra heap needed);</li>
 * <li>otherwise the oldest steps are dropped; if nothing can be dropped, the step is not recorded.</li>
 * </ol>
 * All methods must be called with the engine lock held.
 */
final class Store {

	static final int CHUNK_BYTES = 256 * 1024;      // far below the smallest G1 region (1 MB) / 2
	static final long SPILL_FILE_BYTES = 512L << 20; // roll to a new spill file after this

	/** Thrown when a plane cannot be stored anywhere. */
	static final class NoRoomException extends Exception {
		NoRoomException(String msg) { super(msg); }
	}

	/** Lets the store ask the engine to drop the oldest droppable step. */
	interface Dropper {
		boolean dropOldestStep();
	}

	final class SpillFile {
		final File file;
		final FileChannel channel;
		long size;
		int livePlanes;
		SpillFile(File f) throws IOException {
			file = f;
			channel = new RandomAccessFile(f, "rw").getChannel();
		}
	}

	// ---- budgets (set by the engine from preferences) --------------------

	long ramBudget;
	boolean diskEnabled;
	long diskBudget;
	long minFreeDisk;
	File folder;              // chosen spill folder (parent of the session folder)

	// ---- state ------------------------------------------------------------

	private final Dropper dropper;
	long ramBytes;
	long diskBytes;
	private final Set<Plane> ramPlanes = new HashSet<>();
	private final List<SpillFile> files = new ArrayList<>();
	private SpillFile current;
	private File sessionDir;
	private FileLock sessionLock;
	private final ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.nativeOrder());
	String lastProblem;

	// Diagnostics (benchmarks, the window's tooltip)
	long writeNanos, bytesWritten, readNanos, bytesRead;

	/**
	 * Chunk arrays freed by spilling or dropping, reused for the next copies, so that
	 * recording a big step with full memory does not churn gigabytes through the GC.
	 */
	private final java.util.ArrayDeque<Object> pool = new java.util.ArrayDeque<>();
	private long poolBytes;
	private static final long POOL_MAX = 64L << 20;

	private void recycle(Object[] chunks) {
		for (Object c : chunks) {
			long b = (long) Plane.lengthOf(c) * Plane.bytesPerElement(Plane.typeOf(c));
			if (poolBytes + b > POOL_MAX) return;
			pool.push(c);
			poolBytes += b;
		}
	}

	private Object newChunk(int type, int len) {
		for (java.util.Iterator<Object> it = pool.iterator(); it.hasNext(); ) {
			Object c = it.next();
			if (Plane.typeOf(c) == type && Plane.lengthOf(c) == len) {
				it.remove();
				poolBytes -= (long) len * Plane.bytesPerElement(type);
				return c;
			}
		}
		return Plane.newArray(type, len);
	}

	Store(Dropper dropper) { this.dropper = dropper; }

	// ---- capture ----------------------------------------------------------

	/** Returns {@code prev} if {@code live} has the same content, else stores a new plane. */
	Plane capture(Object live, Plane prev, long time) throws NoRoomException {
		int type = Plane.typeOf(live), n = Plane.lengthOf(live);
		if (prev != null && prev.type == type && prev.length == n && sameContent(live, prev)) {
			return prev;
		}
		Plane p = new Plane(type, n);
		p.lastUsed = time;
		long b = p.bytes();
		if (makeRoomInRam(b)) {
			p.chunks = toChunks(live, type, n, this);
			ramPlanes.add(p);
			ramBytes += b;
			return p;
		}
		if (diskEnabled && makeRoomOnDisk(b)) {
			writeToDisk(p, live, null);
			return p;
		}
		throw new NoRoomException(lastProblem != null ? lastProblem
				: "no room for the history (memory budget " + mb(ramBudget) + " MB"
				+ (diskEnabled ? ", disk budget " + mb(diskBudget) + " MB)" : ", disk use is off)"));
	}

	/** Frees a plane created by a capture that was then abandoned (refs still 0). */
	void discard(Plane p) {
		if (p.refs == 0) free(p);
	}

	void retain(Plane p, long time) {
		p.refs++;
		if (time > p.lastUsed) p.lastUsed = time;
	}

	void release(Plane p) {
		if (--p.refs <= 0) free(p);
	}

	private void free(Plane p) {
		if (p.inRam()) {
			ramPlanes.remove(p);
			ramBytes -= p.bytes();
			recycle(p.chunks);
			p.chunks = null;
		}
		if (p.onDisk()) {
			SpillFile f = p.file;
			p.file = null;
			if (--f.livePlanes <= 0 && f != current) deleteFile(f);
		}
	}

	private boolean sameContent(Object live, Plane prev) {
		if (prev.inRam()) return equalsChunks(live, prev.chunks, prev.type);
		if (!prev.hasHash) return false;
		return hash(live, prev.type, prev.length) == prev.hash;
	}

	// ---- making room -------------------------------------------------------

	/** Reserve of free heap that the history never eats into. */
	static long heapReserve() {
		long max = Runtime.getRuntime().maxMemory();
		return Math.max(256L << 20, max / 5);
	}

	static long heapHeadroom() {
		Runtime r = Runtime.getRuntime();
		return r.maxMemory() - (r.totalMemory() - r.freeMemory());
	}

	private boolean makeRoomInRam(long b) {
		if (b > ramBudget) return false;
		while (ramBytes + b > ramBudget) {
			if (!(diskEnabled && spillOldest()) && !dropper.dropOldestStep()) return false;
		}
		if (heapHeadroom() + poolBytes - b < heapReserve()) {
			System.gc(); // freed chunks are garbage until collected
			if (heapHeadroom() + poolBytes - b < heapReserve()) return false;
		}
		return true;
	}

	private boolean makeRoomOnDisk(long b) {
		if (!ensureSessionDir()) return false;
		while (diskBytes + b > diskBudget || folder.getUsableSpace() - b < minFreeDisk) {
			if (!dropper.dropOldestStep()) {
				lastProblem = diskBytes + b > diskBudget
						? "disk budget for history (" + mb(diskBudget) + " MB) is full"
						: "less than " + mb(minFreeDisk) + " MB would be left free on " + folder;
				return false;
			}
		}
		return true;
	}

	/** Moves the least recently used in-memory plane to disk. */
	private boolean spillOldest() {
		Plane oldest = null;
		for (Plane p : ramPlanes)
			if (p.refs > 0 && (oldest == null || p.lastUsed < oldest.lastUsed)) oldest = p;
		if (oldest == null) return false;
		if (!oldest.onDisk()) {
			if (!makeRoomOnDisk(oldest.bytes())) return false;
			if (oldest.refs <= 0 || !oldest.inRam()) return true; // freed while making room
			writeToDisk(oldest, null, oldest.chunks);
			if (!oldest.onDisk()) return false;
		}
		ramPlanes.remove(oldest);
		ramBytes -= oldest.bytes();
		recycle(oldest.chunks);
		oldest.chunks = null;
		return true;
	}

	/** Brings memory use back under budget (after the budget was lowered). */
	void enforce() {
		while (ramBytes > ramBudget) {
			if (!(diskEnabled && spillOldest()) && !dropper.dropOldestStep()) break;
		}
		while (diskBytes > diskBudget && dropper.dropOldestStep()) { }
	}

	// ---- restore ----------------------------------------------------------

	/** Copies the plane's pixels into an existing array of the same type and length. */
	void readInto(Plane p, Object dest) throws IOException {
		int per = CHUNK_BYTES / Plane.bytesPerElement(p.type);
		if (p.inRam()) {
			for (int c = 0, off = 0; c < p.chunks.length; c++, off += per)
				System.arraycopy(p.chunks[c], 0, dest, off, Math.min(per, p.length - off));
			return;
		}
		FileChannel ch = p.file.channel;
		long t0 = System.nanoTime();
		long pos = p.offset;
		for (int off = 0; off < p.length; off += per) {
			int n = Math.min(per, p.length - off);
			buffer.clear();
			buffer.limit(n * Plane.bytesPerElement(p.type));
			while (buffer.hasRemaining()) {
				int r = ch.read(buffer, pos);
				if (r < 0) throw new IOException("Undo History spill file is truncated: " + p.file.file);
				pos += r;
			}
			buffer.flip();
			switch (p.type) {
				case Plane.BYTE: buffer.get((byte[]) dest, off, n); break;
				case Plane.SHORT: buffer.asShortBuffer().get((short[]) dest, off, n); break;
				case Plane.FLOAT: buffer.asFloatBuffer().get((float[]) dest, off, n); break;
				default: buffer.asIntBuffer().get((int[]) dest, off, n);
			}
		}
		readNanos += System.nanoTime() - t0;
		bytesRead += p.bytes();
	}

	Object materialize(Plane p) throws IOException {
		Object a = Plane.newArray(p.type, p.length);
		readInto(p, a);
		return a;
	}

	// ---- disk -------------------------------------------------------------

	/** Writes from {@code live} (whole array) or from {@code chunks}; computes the hash on the way. */
	private void writeToDisk(Plane p, Object live, Object[] chunks) {
		try {
			if (current == null || current.size >= SPILL_FILE_BYTES) {
				SpillFile old = current;
				current = new SpillFile(File.createTempFile("history-", ".bin", sessionDir));
				files.add(current);
				if (old != null && old.livePlanes <= 0) deleteFile(old);
			}
			int bpe = Plane.bytesPerElement(p.type), per = CHUNK_BYTES / bpe;
			long t0 = System.nanoTime();
			long start = current.size, pos = start;
			Hasher h = new Hasher();
			for (int off = 0, c = 0; off < p.length; off += per, c++) {
				int n = Math.min(per, p.length - off);
				Object src = live != null ? live : chunks[c];
				int srcOff = live != null ? off : 0;
				h.update(src, p.type, srcOff, n);
				buffer.clear();
				switch (p.type) {
					case Plane.BYTE: buffer.put((byte[]) src, srcOff, n); break;
					case Plane.SHORT: buffer.asShortBuffer().put((short[]) src, srcOff, n); break;
					case Plane.FLOAT: buffer.asFloatBuffer().put((float[]) src, srcOff, n); break;
					default: buffer.asIntBuffer().put((int[]) src, srcOff, n);
				}
				buffer.position(0).limit(n * bpe);
				while (buffer.hasRemaining()) pos += current.channel.write(buffer, pos);
			}
			current.size = pos;
			diskBytes += pos - start;
			writeNanos += System.nanoTime() - t0;
			bytesWritten += pos - start;
			current.livePlanes++;
			p.file = current;
			p.offset = start;
			p.hash = h.value();
			p.hasHash = true;
		} catch (IOException e) {
			lastProblem = "could not write to " + sessionDir + ": " + e.getMessage();
			diskEnabled = false;
			IJ.log("Undo History: " + lastProblem + " - disk use switched off for this session.");
		}
	}

	private void deleteFile(SpillFile f) {
		try { f.channel.close(); } catch (IOException ignored) { }
		diskBytes -= f.size;
		files.remove(f);
		if (!f.file.delete()) f.file.deleteOnExit();
		if (f == current) current = null;
	}

	/** Creates this session's folder (locked, so other Fiji instances leave it alone). */
	private boolean ensureSessionDir() {
		if (sessionDir != null) return true;
		try {
			folder.mkdirs();
			cleanStaleSessions(folder);
			File d = new File(folder, "session-" + Long.toHexString(System.nanoTime()));
			if (!d.mkdirs()) throw new IOException("cannot create " + d);
			RandomAccessFile lock = new RandomAccessFile(new File(d, ".lock"), "rw");
			sessionLock = lock.getChannel().tryLock();
			sessionDir = d;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(d)));
			return true;
		} catch (IOException e) {
			lastProblem = "cannot use the disk folder " + folder + ": " + e.getMessage();
			diskEnabled = false;
			IJ.log("Undo History: " + lastProblem);
			return false;
		}
	}

	/** Deletes session folders left behind by Fiji instances that crashed. */
	static void cleanStaleSessions(File folder) {
		File[] dirs = folder.listFiles((dir, name) -> name.startsWith("session-"));
		if (dirs == null) return;
		for (File d : dirs) {
			File lockFile = new File(d, ".lock");
			try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
					FileChannel ch = raf.getChannel()) {
				FileLock l = ch.tryLock();
				if (l == null) continue; // a running Fiji owns it
				l.release();
			} catch (Exception e) {
				continue;
			}
			deleteTree(d);
		}
	}

	static void deleteTree(File d) {
		File[] fs = d.listFiles();
		if (fs != null) for (File f : fs) f.delete();
		d.delete();
	}

	String sessionDirPath() { return sessionDir == null ? null : sessionDir.getPath(); }

	// ---- chunks, comparison, hashing ----------------------------------------

	static Object[] toChunks(Object live, int type, int n, Store pool) {
		int per = CHUNK_BYTES / Plane.bytesPerElement(type);
		Object[] chunks = new Object[(n + per - 1) / per];
		for (int c = 0, off = 0; c < chunks.length; c++, off += per) {
			int len = Math.min(per, n - off);
			Object chunk = pool != null ? pool.newChunk(type, len) : Plane.newArray(type, len);
			System.arraycopy(live, off, chunk, 0, len);
			chunks[c] = chunk;
		}
		return chunks;
	}

	static boolean equalsChunks(Object live, Object[] chunks, int type) {
		int per = CHUNK_BYTES / Plane.bytesPerElement(type);
		for (int c = 0, off = 0; c < chunks.length; c++, off += per)
			if (!rangeEquals(live, off, chunks[c], type)) return false;
		return true;
	}

	private static final MethodHandle EQ_B = rangeEq(byte[].class), EQ_S = rangeEq(short[].class),
			EQ_F = rangeEq(float[].class), EQ_I = rangeEq(int[].class);

	/** Arrays.equals(a, from, to, b, from, to) exists from Java 9 on (vectorized); Java 8 falls back to a loop. */
	private static MethodHandle rangeEq(Class<?> c) {
		try {
			return MethodHandles.publicLookup().findStatic(Arrays.class, "equals",
					MethodType.methodType(boolean.class, c, int.class, int.class, c, int.class, int.class));
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean rangeEquals(Object live, int off, Object chunk, int type) {
		try {
			switch (type) {
				case Plane.BYTE: {
					byte[] a = (byte[]) live, b = (byte[]) chunk;
					if (EQ_B != null) return (boolean) EQ_B.invokeExact(a, off, off + b.length, b, 0, b.length);
					for (int i = 0; i < b.length; i++) if (a[off + i] != b[i]) return false;
					return true;
				}
				case Plane.SHORT: {
					short[] a = (short[]) live, b = (short[]) chunk;
					if (EQ_S != null) return (boolean) EQ_S.invokeExact(a, off, off + b.length, b, 0, b.length);
					for (int i = 0; i < b.length; i++) if (a[off + i] != b[i]) return false;
					return true;
				}
				case Plane.FLOAT: {
					float[] a = (float[]) live, b = (float[]) chunk;
					if (EQ_F != null) return (boolean) EQ_F.invokeExact(a, off, off + b.length, b, 0, b.length);
					for (int i = 0; i < b.length; i++)
						if (Float.floatToIntBits(a[off + i]) != Float.floatToIntBits(b[i])) return false;
					return true;
				}
				default: {
					int[] a = (int[]) live, b = (int[]) chunk;
					if (EQ_I != null) return (boolean) EQ_I.invokeExact(a, off, off + b.length, b, 0, b.length);
					for (int i = 0; i < b.length; i++) if (a[off + i] != b[i]) return false;
					return true;
				}
			}
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static long hash(Object a, int type, int n) {
		Hasher h = new Hasher();
		int per = CHUNK_BYTES / Plane.bytesPerElement(type);
		for (int off = 0; off < n; off += per) h.update(a, type, off, Math.min(per, n - off));
		return h.value();
	}

	/**
	 * 64-bit hash over plane elements, four independent lanes so the CPU can pipeline the
	 * multiplications. Updates must come in order, in pieces whose lengths are multiples of 4
	 * (except the last), which chunking guarantees. Used only to recognise unchanged planes
	 * whose previous copy is on disk.
	 */
	static final class Hasher {
		private static final long P = 0x9E3779B97F4A7C15L;
		private long h0 = 1, h1 = 2, h2 = 3, h3 = 4, count;

		void update(Object a, int type, int off, int n) {
			int i = 0;
			switch (type) {
				case Plane.BYTE: { byte[] x = (byte[]) a;
					for (; i + 3 < n; i += 4) { h0 = (h0 ^ x[off+i]) * P; h1 = (h1 ^ x[off+i+1]) * P; h2 = (h2 ^ x[off+i+2]) * P; h3 = (h3 ^ x[off+i+3]) * P; }
					for (; i < n; i++) h0 = (h0 ^ x[off+i]) * P; break; }
				case Plane.SHORT: { short[] x = (short[]) a;
					for (; i + 3 < n; i += 4) { h0 = (h0 ^ x[off+i]) * P; h1 = (h1 ^ x[off+i+1]) * P; h2 = (h2 ^ x[off+i+2]) * P; h3 = (h3 ^ x[off+i+3]) * P; }
					for (; i < n; i++) h0 = (h0 ^ x[off+i]) * P; break; }
				case Plane.FLOAT: { float[] x = (float[]) a;
					for (; i + 3 < n; i += 4) { h0 = (h0 ^ Float.floatToIntBits(x[off+i])) * P; h1 = (h1 ^ Float.floatToIntBits(x[off+i+1])) * P; h2 = (h2 ^ Float.floatToIntBits(x[off+i+2])) * P; h3 = (h3 ^ Float.floatToIntBits(x[off+i+3])) * P; }
					for (; i < n; i++) h0 = (h0 ^ Float.floatToIntBits(x[off+i])) * P; break; }
				default: { int[] x = (int[]) a;
					for (; i + 3 < n; i += 4) { h0 = (h0 ^ x[off+i]) * P; h1 = (h1 ^ x[off+i+1]) * P; h2 = (h2 ^ x[off+i+2]) * P; h3 = (h3 ^ x[off+i+3]) * P; }
					for (; i < n; i++) h0 = (h0 ^ x[off+i]) * P; }
			}
			count += n;
		}

		long value() {
			long h = h0 * 31 + Long.rotateLeft(h1, 17) * 37 + Long.rotateLeft(h2, 29) * 41 + Long.rotateLeft(h3, 43) + count;
			h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
			return h;
		}
	}

	static long mb(long bytes) { return bytes >> 20; }
}
