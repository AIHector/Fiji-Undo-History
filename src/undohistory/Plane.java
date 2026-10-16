package undohistory;

/**
 * The pixels of one image plane as they were at some step. Immutable content,
 * shared by every state in which the plane did not change (reference counted).
 *
 * The data lives in memory as small chunks (so the JVM never has to place it in
 * "humongous" regions, which can double the real cost of an 8 MB array), or on
 * disk in a spill file, or briefly in both.
 */
final class Plane {
	static final int BYTE = 0, SHORT = 1, FLOAT = 2, INT = 3;

	final int type;
	final int length;
	Object[] chunks;       // in-memory copy, null when only on disk
	Store.SpillFile file;  // on-disk copy, null when only in memory
	long offset;
	long hash;             // valid when hasHash
	boolean hasHash;
	int refs;
	long lastUsed;

	Plane(int type, int length) {
		this.type = type;
		this.length = length;
	}

	boolean inRam() { return chunks != null; }
	boolean onDisk() { return file != null; }
	long bytes() { return (long) length * bytesPerElement(type); }

	static int typeOf(Object a) {
		if (a instanceof byte[]) return BYTE;
		if (a instanceof short[]) return SHORT;
		if (a instanceof float[]) return FLOAT;
		if (a instanceof int[]) return INT;
		throw new IllegalArgumentException("Unsupported pixel type: " + a);
	}

	static int bytesPerElement(int type) {
		return type == BYTE ? 1 : type == SHORT ? 2 : 4;
	}

	static int lengthOf(Object a) {
		switch (typeOf(a)) {
			case BYTE: return ((byte[]) a).length;
			case SHORT: return ((short[]) a).length;
			case FLOAT: return ((float[]) a).length;
			default: return ((int[]) a).length;
		}
	}

	static Object newArray(int type, int n) {
		switch (type) {
			case BYTE: return new byte[n];
			case SHORT: return new short[n];
			case FLOAT: return new float[n];
			default: return new int[n];
		}
	}
}
