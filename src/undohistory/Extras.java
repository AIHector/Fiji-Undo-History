package undohistory;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import ij.process.LUT;

import java.awt.Color;

/**
 * What a step records besides pixels: the selection, the overlay, the display (range, LUTs), the calibration, the ROI
 * Manager's list and the Results table. Each part is compared through a signature (a hash of its content) and copied
 * only when it changed; otherwise the step shares the previous step's copy, so an unchanged part costs nothing.
 */
final class Extras {

	/** The last copies made, shared by every image whose step sees the same ROIs or table. */
	private static Roi[] lastRm;
	private static long[] lastRmSigs;
	private static TableCopy lastRt;

	final Roi roi;
	final long roiSig, overlaySig, displaySig, calSig;
	final Roi[] rm;          // the ROI Manager's list; null when no ROI Manager was open
	final long[] rmSigs;     // the signature of each of those ROIs
	final long rmSig;
	final TableCopy rt;      // the Results table; null when there was none
	final long rtSig;

	private Extras(Roi roi, long roiSig, long overlaySig, long displaySig, long calSig,
			Roi[] rm, long[] rmSigs, long rmSig, TableCopy rt, long rtSig) {
		this.roi = roi; this.roiSig = roiSig; this.overlaySig = overlaySig; this.displaySig = displaySig; this.calSig = calSig;
		this.rm = rm; this.rmSigs = rmSigs; this.rmSig = rmSig; this.rt = rt; this.rtSig = rtSig;
	}

	/** Captures these parts of the image's state, sharing the unchanged ones with prev. */
	static Extras capture(ImagePlus imp, Extras prev) {
		Roi live = Engine.isPasting(imp) ? null : imp.getRoi();
		long rs = sig(live);
		Roi roi = live == null ? null : prev != null && prev.roiSig == rs && prev.roi != null ? prev.roi : (Roi) live.clone();
		long os = sig(imp.getOverlay());
		long ds = displaySig(imp);
		long cs = calSig(imp.getCalibration());

		Roi[] rm = null;
		long[] rmSigs = null;
		long ms = 0;
		RoiManager m = RoiManager.getRawInstance();
		if (m != null) {
			Roi[] list = m.getRoisAsArray();
			rmSigs = new long[list.length];
			for (int i = 0; i < list.length; i++) rmSigs[i] = sig(list[i]);
			ms = sig(rmSigs);
			if (prev != null && prev.rmSig == ms && prev.rm != null) { rm = prev.rm; rmSigs = prev.rmSigs; }
			else if (lastRm != null && ms == sig(lastRmSigs)) { rm = lastRm; rmSigs = lastRmSigs; } // already copied for another image
			else {
				// each unchanged ROI is shared with the previous copy: adding one ROI costs one ROI
				java.util.Map<Long, Roi> known = new java.util.HashMap<Long, Roi>();
				if (lastRm != null) for (int i = 0; i < lastRm.length; i++) known.put(lastRmSigs[i], lastRm[i]);
				if (prev != null && prev.rm != null) for (int i = 0; i < prev.rm.length; i++) known.put(prev.rmSigs[i], prev.rm[i]);
				rm = new Roi[list.length];
				for (int i = 0; i < list.length; i++) {
					Roi k = known.get(rmSigs[i]);
					rm[i] = k != null ? k : (Roi) list[i].clone();
				}
				lastRm = rm;
				lastRmSigs = rmSigs;
			}
		}
		TableCopy rt = null;
		long ts = 0;
		ResultsTable live2 = Analyzer.getResultsTable();
		if (live2 != null && (live2.size() > 0 || live2.getLastColumn() >= 0)) {
			ts = sig(live2);
			if (prev != null && prev.rtSig == ts) rt = prev.rt;
			else if (lastRt != null && lastRt.sig == ts) rt = lastRt;
			else {
				rt = TableCopy.of(live2, prev != null && prev.rt != null ? prev.rt : lastRt, ts); // unchanged blocks shared
				lastRt = rt;
			}
		}
		return new Extras(roi, rs, os, ds, cs, rm, rmSigs, ms, rt, ts);
	}

	/** What differs from another state, as a step name ("Selection", "Overlay", ...), or null if nothing does. */
	String changeFrom(Extras o) {
		if (o == null) return null;
		if (overlaySig != o.overlaySig) return "Overlay";
		if (rm != null && rmSig != o.rmSig) return "ROI Manager";
		if (rtSig != o.rtSig) return "Results";
		if (calSig != o.calSig) return "Calibration";
		if (displaySig != o.displaySig) return "Display";
		if (roiSig != o.roiSig) return "Selection";
		return null;
	}

	/**
	 * Puts this state's selection back, and the ROI Manager list and Results table when they differ from the state
	 * the image is leaving (from): the ROI Manager and Results are shared by all images, so an image's undo only
	 * touches them when they changed in that image's own history. Overlay, display and calibration are restored by
	 * the snapshot itself.
	 */
	void restore(ImagePlus imp, Extras from) {
		if (roi != null) imp.setRoi((Roi) roi.clone());
		else if (imp.getRoi() != null) imp.deleteRoi();
		if (rm != null && from != null && from.rmSig != rmSig) {
			RoiManager m = RoiManager.getRoiManager();
			m.reset();
			for (Roi r : rm) m.addRoi((Roi) r.clone());
		}
		if (from != null && from.rtSig != rtSig) {
			ResultsTable t = rt != null ? rt.toTable() : new ResultsTable();
			if (rt == null && !IJ.isResultsWindow()) Analyzer.setResultsTable(t);
			else t.show("Results"); // becomes the system Results table, with its window
		}
	}

	// ---------------------------------------------------------------- signatures (content hashes)

	static long sig(Roi r) {
		if (r == null) return 0;
		long h = 17 + r.getType();
		h = h * 31 + r.getBounds().hashCode();
		FloatPolygon p = r.getFloatPolygon();
		if (p != null) {
			int step = Math.max(1, p.npoints / 4096); // very long outlines: a sample of their points is enough
			for (int i = 0; i < p.npoints; i += step)
				h = h * 31 + Float.floatToIntBits(p.xpoints[i]) * 7 + Float.floatToIntBits(p.ypoints[i]);
			h = h * 31 + p.npoints;
		}
		h = h * 31 + color(r.getStrokeColor());
		h = h * 31 + color(r.getFillColor());
		h = h * 31 + Float.floatToIntBits(r.getStrokeWidth());
		h = h * 31 + r.getPosition() * 1000003L + r.getCPosition() * 1009L + r.getZPosition() * 101L + r.getTPosition();
		String n = r.getName();
		if (n != null) h = h * 31 + n.hashCode();
		if (r instanceof TextRoi) h = h * 31 + ((TextRoi) r).getText().hashCode();
		return h == 0 ? 1 : h;
	}

	static long sig(Overlay o) {
		if (o == null) return 0;
		long h = 23 + o.size();
		for (int i = 0; i < o.size(); i++) h = h * 31 + sig(o.get(i));
		h = h * 31 + (o.getDrawLabels() ? 1 : 0) + (o.getDrawNames() ? 2 : 0);
		return h == 0 ? 1 : h;
	}

	static long sig(long[] roiSigs) {
		long h = 29 + roiSigs.length;
		for (long s : roiSigs) h = h * 31 + s;
		return h == 0 ? 1 : h;
	}

	static long sig(ResultsTable rt) {
		long h = 31 + rt.size();
		String heads = rt.getColumnHeadings();
		h = h * 31 + (heads == null ? 0 : heads.hashCode());
		int last = rt.getLastColumn();
		int n = rt.size();
		// every value counts, read column by column
		for (int c = 0; c <= last; c++) {
			if (!rt.columnExists(c)) continue;
			double[] col = rt.getColumnAsDoubles(c);
			if (col == null) continue;
			for (int i = 0; i < n && i < col.length; i++) h = h * 31 + Double.doubleToLongBits(col[i]);
		}
		for (int i = 0; i < n; i++) {
			String l = rt.getLabel(i);
			if (l != null) h = h * 31 + l.hashCode();
		}
		return h == 0 ? 1 : h;
	}

	static long displaySig(ImagePlus imp) {
		long h = 37;
		if (imp.isComposite()) {
			CompositeImage ci = (CompositeImage) imp;
			h = h * 31 + ci.getMode();
			String active = ci.getActiveChannels() == null ? "" : java.util.Arrays.toString(ci.getActiveChannels());
			h = h * 31 + active.hashCode();
			LUT[] luts = ci.getLuts();
			if (luts != null) for (LUT l : luts) h = h * 31 + lutSig(l) * 7 + Double.doubleToLongBits(l.min) + Double.doubleToLongBits(l.max);
		} else {
			ImageProcessor ip = imp.getProcessor();
			if (ip == null) return h; // an image being closed
			h = h * 31 + Double.doubleToLongBits(imp.getDisplayRangeMin());
			h = h * 31 + Double.doubleToLongBits(imp.getDisplayRangeMax());
			if (ip != null && imp.getBitDepth() != 24) h = h * 31 + lutSig(ip.getLut());
		}
		return h == 0 ? 1 : h;
	}

	static long lutSig(LUT l) {
		if (l == null) return 0;
		byte[] r = new byte[256], g = new byte[256], b = new byte[256];
		l.getReds(r); l.getGreens(g); l.getBlues(b);
		return java.util.Arrays.hashCode(r) * 961L + java.util.Arrays.hashCode(g) * 31L + java.util.Arrays.hashCode(b);
	}

	static long calSig(Calibration c) {
		long h = 41;
		h = h * 31 + Double.doubleToLongBits(c.pixelWidth);
		h = h * 31 + Double.doubleToLongBits(c.pixelHeight);
		h = h * 31 + Double.doubleToLongBits(c.pixelDepth);
		h = h * 31 + Double.doubleToLongBits(c.frameInterval);
		h = h * 31 + Double.doubleToLongBits(c.xOrigin) + Double.doubleToLongBits(c.yOrigin) * 3 + Double.doubleToLongBits(c.zOrigin) * 7;
		h = h * 31 + String.valueOf(c.getUnit()).hashCode();
		h = h * 31 + c.getFunction();
		double[] co = c.getCoefficients();
		if (co != null) for (double v : co) h = h * 31 + Double.doubleToLongBits(v);
		return h;
	}

	private static int color(Color c) { return c == null ? 0 : c.getRGB(); }

	/** Rough heap cost of what this state owns itself (not shared with the state before it), for reports. */
	long ownBytes(Extras prev) {
		long b = 0;
		if (roi != null && (prev == null || prev.roi != roi)) b += roiBytes(roi);
		if (rm != null && (prev == null || prev.rm != rm)) {
			// the list of references, plus the ROIs this step copied (the others are the previous step's objects)
			java.util.Set<Roi> old = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Roi, Boolean>());
			if (prev != null && prev.rm != null) old.addAll(java.util.Arrays.asList(prev.rm));
			b += 16 + 16L * rm.length;
			for (Roi r : rm) if (!old.contains(r)) b += roiBytes(r);
		}
		if (rt != null && (prev == null || prev.rt != rt)) b += rt.ownBytes(prev == null ? null : prev.rt);
		return b;
	}

	static long roiBytes(Roi r) {
		FloatPolygon p = r.getFloatPolygon();
		return 200 + (p == null ? 0 : p.npoints * 8L);
	}

	static void log(String s) { if (IJ.debugMode) IJ.log("Undo History: " + s); }
}
