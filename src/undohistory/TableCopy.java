package undohistory;

import ij.measure.ResultsTable;

import java.util.Arrays;

/**
 * An immutable copy of a Results table, stored per column in blocks of {@link #CHUNK} rows. A block equal to the
 * previous copy's block is shared, not copied, so adding a measurement to a large table costs one block per column,
 * not the whole table.
 */
final class TableCopy {

	static final int CHUNK = 1024;

	final int rows;
	final String[] headings;   // the table's columns, in order
	final double[][][] values; // [column][block] numbers (NaN where a cell holds text)
	final String[][][] texts;  // [column][block] text cells, or null for a block with no text
	final String[][] labels;   // [block] row labels, or null
	final long sig;

	private TableCopy(int rows, String[] headings, double[][][] values, String[][][] texts, String[][] labels, long sig) {
		this.rows = rows; this.headings = headings; this.values = values; this.texts = texts; this.labels = labels; this.sig = sig;
	}

	static TableCopy of(ResultsTable rt, TableCopy prev, long sig) {
		int n = rt.size();
		int last = rt.getLastColumn();
		int blocks = (n + CHUNK - 1) / CHUNK;
		java.util.List<String> heads = new java.util.ArrayList<String>();
		java.util.List<double[][]> vals = new java.util.ArrayList<double[][]>();
		java.util.List<String[][]> txts = new java.util.ArrayList<String[][]>();
		for (int c = 0; c <= last; c++) {
			if (!rt.columnExists(c)) continue;
			String h = rt.getColumnHeading(c);
			double[] col = rt.getColumnAsDoubles(c);
			int p = prev == null ? -1 : Arrays.asList(prev.headings).indexOf(h);
			double[][] vb = new double[blocks][];
			String[][] tb = new String[blocks][];
			for (int b = 0; b < blocks; b++) {
				int from = b * CHUNK, to = Math.min(n, from + CHUNK);
				double[] v = Arrays.copyOfRange(col, from, to);
				String[] t = null;
				for (int i = from; i < to; i++) {
					if (!Double.isNaN(col[i])) continue;
					String s = rt.getStringValue(c, i);
					if (s != null && !s.equals("NaN")) {
						if (t == null) t = new String[to - from];
						t[i - from] = s;
					}
				}
				if (p >= 0 && b < prev.values[p].length && Arrays.equals(prev.values[p][b], v) && Arrays.equals(prev.texts[p][b], t)) {
					vb[b] = prev.values[p][b]; // unchanged block: shared
					tb[b] = prev.texts[p][b];
				} else {
					vb[b] = v;
					tb[b] = t;
				}
			}
			heads.add(h);
			vals.add(vb);
			txts.add(tb);
		}
		String[][] lb = null;
		for (int b = 0; b < blocks; b++) {
			int from = b * CHUNK, to = Math.min(n, from + CHUNK);
			String[] l = null;
			for (int i = from; i < to; i++) {
				String s = rt.getLabel(i);
				if (s == null) continue;
				if (l == null) l = new String[to - from];
				l[i - from] = s;
			}
			if (l == null) continue;
			if (lb == null) lb = new String[blocks][];
			lb[b] = prev != null && prev.labels != null && b < prev.labels.length && Arrays.equals(prev.labels[b], l) ? prev.labels[b] : l;
		}
		return new TableCopy(n, heads.toArray(new String[0]), vals.toArray(new double[0][][]), txts.toArray(new String[0][][]), lb, sig);
	}

	ResultsTable toTable() {
		ResultsTable rt = new ResultsTable();
		for (int i = 0; i < rows; i++) rt.incrementCounter();
		for (int c = 0; c < headings.length; c++) {
			for (int b = 0; b < values[c].length; b++) {
				double[] v = values[c][b];
				String[] t = texts[c][b];
				for (int i = 0; i < v.length; i++) {
					int row = b * CHUNK + i;
					if (t != null && t[i] != null) rt.setValue(headings[c], row, t[i]);
					else rt.setValue(headings[c], row, v[i]);
				}
			}
		}
		if (labels != null)
			for (int b = 0; b < labels.length; b++)
				if (labels[b] != null)
					for (int i = 0; i < labels[b].length; i++)
						if (labels[b][i] != null) rt.setLabel(labels[b][i], b * CHUNK + i);
		return rt;
	}

	/** Bytes this copy holds that are not shared with prev. */
	long ownBytes(TableCopy prev) {
		java.util.Set<Object> shared = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
		if (prev != null) for (double[][] col : prev.values) shared.addAll(Arrays.asList(col));
		long b = 0;
		for (double[][] col : values) for (double[] v : col) if (!shared.contains(v)) b += 16 + 8L * v.length;
		return b;
	}
}
