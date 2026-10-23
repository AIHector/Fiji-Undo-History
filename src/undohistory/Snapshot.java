package undohistory;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.LUT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One state of an image: its planes (shared with neighbouring states when unchanged),
 * dimensions, calibration, overlay, display range and LUTs.
 */
final class Snapshot {

	final int width, height, bitDepth;
	final int nChannels, nSlices, nFrames;
	final Plane[] planes;
	final String[] labels;
	final Calibration cal;
	final Overlay overlay;
	final double min, max;
	final LUT lut;          // single-channel images
	final LUT[] luts;       // composite images
	final int compositeMode;
	final boolean[] activeChannels; // composite images: the channels ticked in the Channels Tool
	/** Selection, ROI Manager list, Results table, and the signatures used to compare states. */
	final Extras extras;
	/** True when only non-pixel parts changed: the planes are the previous step's. */
	final boolean metaOnly;

	private Snapshot(ImagePlus imp, Plane[] planes, String[] labels, Snapshot prev, Extras extras, boolean metaOnly) {
		this.extras = extras;
		this.metaOnly = metaOnly;
		width = imp.getWidth();
		height = imp.getHeight();
		bitDepth = imp.getBitDepth();
		nChannels = imp.getNChannels();
		nSlices = imp.getNSlices();
		nFrames = imp.getNFrames();
		this.planes = planes;
		this.labels = labels;
		cal = imp.getCalibration().copy();
		Overlay ov = imp.getOverlay();
		// an unchanged overlay is shared with the previous step instead of copied again
		overlay = ov == null ? null : prev != null && prev.extras.overlaySig == extras.overlaySig && prev.overlay != null
				? prev.overlay : ov.duplicate();
		min = imp.getDisplayRangeMin();
		max = imp.getDisplayRangeMax();
		if (imp.isComposite()) {
			LUT[] l = ((CompositeImage) imp).getLuts();
			luts = l == null ? null : l.clone();
			compositeMode = ((CompositeImage) imp).getMode();
			boolean[] a = ((CompositeImage) imp).getActiveChannels();
			activeChannels = a == null ? null : a.clone();
			lut = null;
		} else {
			luts = null;
			compositeMode = -1;
			activeChannels = null;
			ImageProcessor ip = imp.getProcessor();
			lut = (bitDepth == 24 || ip == null) ? null : ip.getLut();
		}
	}

	static long sizeInBytes(ImagePlus imp) {
		return (long) imp.getWidth() * imp.getHeight() * imp.getStackSize()
				* (imp.getBitDepth() == 24 ? 4 : Math.max(1, imp.getBitDepth() / 8));
	}

	/**
	 * Captures the image, reusing planes of {@code prev} that are unchanged (or, when
	 * {@code only} is given, every plane not in it, without reading those). Returns
	 * {@code null} when nothing differs from {@code prev}. The returned planes are not yet
	 * retained: the caller retains them when it keeps the snapshot.
	 */
	static Snapshot capture(ImagePlus imp, Snapshot prev, Store store, long time, java.util.Set<Integer> only)
			throws Store.NoRoomException {
		int n = imp.getStackSize();
		ImageStack stack = n > 1 ? imp.getImageStack() : null;
		Plane[] planes = new Plane[n];
		String[] labels = new String[n];
		boolean sameShape = prev != null && prev.planes.length == n
				&& prev.width == imp.getWidth() && prev.height == imp.getHeight()
				&& prev.bitDepth == imp.getBitDepth()
				&& prev.nChannels == imp.getNChannels() && prev.nSlices == imp.getNSlices()
				&& prev.nFrames == imp.getNFrames();
		boolean changed = !sameShape;
		List<Plane> created = new ArrayList<>();
		try {
			for (int i = 0; i < n; i++) {
				labels[i] = stack != null ? stack.getSliceLabel(i + 1) : null;
				if (sameShape && only != null && !only.contains(i + 1)) {
					planes[i] = prev.planes[i]; // not touched by the tool: no need to read it
					continue;
				}
				Object live = stack != null ? stack.getPixels(i + 1) : imp.getProcessor().getPixels();
				if (live == null) throw new Store.NoRoomException("plane " + (i + 1) + " has no pixel data");
				Plane p = store.capture(live, sameShape ? prev.planes[i] : null, time);
				if (p != (sameShape ? prev.planes[i] : null)) {
					created.add(p);
					changed = true;
				}
				planes[i] = p;
			}
		} catch (Store.NoRoomException | RuntimeException e) {
			for (Plane p : created) store.discard(p);
			throw e;
		}
		Extras ex = Extras.capture(imp, prev == null ? null : prev.extras);
		if (!changed) {
			// same pixels: a step only if the selection, overlay, display, ROI Manager or Results changed
			if (prev == null || ex.changeFrom(prev.extras) == null) return null;
			return new Snapshot(imp, prev.planes, prev.labels, prev, ex, true);
		}
		return new Snapshot(imp, planes, labels, prev, ex, false);
	}

	/**
	 * A step for a change that touched no pixels (a selection, an overlay edit, the ROI Manager...): compares only the
	 * signatures, never reads the planes, and shares them with prev. Returns null when nothing tracked changed.
	 */
	static Snapshot captureMeta(ImagePlus imp, Snapshot prev) {
		if (prev == null) return null;
		boolean sameShape = prev.planes.length == imp.getStackSize() && prev.width == imp.getWidth()
				&& prev.height == imp.getHeight() && prev.bitDepth == imp.getBitDepth();
		if (!sameShape) return null;
		Extras ex = Extras.capture(imp, prev.extras);
		if (ex.changeFrom(prev.extras) == null) return null;
		return new Snapshot(imp, prev.planes, prev.labels, prev, ex, true);
	}

	/** What changed from prev, as a step name, for steps that changed no pixels. */
	String metaChange(Snapshot prev) {
		return prev == null ? null : extras.changeFrom(prev.extras);
	}

	/** Writes this state into {@code imp}, coming from state {@code from}. Caller must hold the engine lock. */
	void restoreInto(ImagePlus imp, Store store, Snapshot from) throws IOException {
		boolean sameShape = imp.getStackSize() == planes.length
				&& imp.getWidth() == width && imp.getHeight() == height
				&& imp.getBitDepth() == bitDepth
				&& imp.getNChannels() == nChannels && imp.getNSlices() == nSlices
				&& imp.getNFrames() == nFrames;
		boolean samePlanes = sameShape && from != null && from.planes.length == planes.length;
		for (int i = 0; samePlanes && i < planes.length; i++) samePlanes = from.planes[i] == planes[i];
		if (samePlanes) {
			// a step that changed no pixels (selection, overlay, ROI Manager...): the pixels are already right
		} else if (sameShape) {
			// Copy into the live arrays so CompositeImage channel processors,
			// which hold references to them, see the new data. Needs no extra memory.
			if (planes.length == 1) {
				store.readInto(planes[0], imp.getProcessor().getPixels());
			} else {
				ImageStack stack = imp.getImageStack();
				for (int i = 0; i < planes.length; i++) {
					store.readInto(planes[i], stack.getPixels(i + 1));
					stack.setSliceLabel(labels[i], i + 1);
				}
				imp.getProcessor().setPixels(stack.getPixels(imp.getCurrentSlice()));
			}
		} else {
			long need = (long) width * height * planes.length * (bitDepth == 24 ? 4 : Math.max(1, bitDepth / 8));
			if (Store.heapHeadroom() - need < Store.heapReserve() / 2) {
				System.gc();
				if (Store.heapHeadroom() - need < Store.heapReserve() / 2)
					throw new IOException("not enough free memory to rebuild the image (" + (need >> 20) + " MB needed)");
			}
			ImageStack stack = new ImageStack(width, height);
			for (int i = 0; i < planes.length; i++)
				stack.addSlice(labels[i], store.materialize(planes[i]));
			int c = Math.min(imp.getC(), nChannels), z = Math.min(imp.getZ(), nSlices), t = Math.min(imp.getT(), nFrames);
			imp.setStack(stack, nChannels, nSlices, nFrames);
			if (imp.isHyperStack() || imp.getStackSize() > 1)
				imp.setPosition(Math.max(1, c), Math.max(1, z), Math.max(1, t));
		}
		imp.getProcessor().setSnapshotPixels(null);
		imp.setCalibration(cal.copy());
		imp.setOverlay(overlay == null ? null : overlay.duplicate());
		extras.restore(imp, from == null ? null : from.extras);
		if (imp.isComposite() && luts != null && ((CompositeImage) imp).getNChannels() == luts.length) {
			CompositeImage ci = (CompositeImage) imp;
			ci.setLuts(luts.clone());
			if (compositeMode > 0) ci.setMode(compositeMode);
			setActive(ci, activeChannels);
			ci.updateAllChannelsAndDraw();
			ij.plugin.frame.Channels.updateChannels(); // the Channels Tool shows the restored ticks
		} else {
			if (lut != null && !imp.isComposite())
				imp.getProcessor().setLut(lut);
			if (bitDepth != 24)
				imp.setDisplayRange(min, max);
			imp.updateAndDraw();
		}
	}

	/** A new ImagePlus showing this state (for "Copy"). */
	ImagePlus toImage(String title, Store store) throws IOException {
		ImageStack stack = new ImageStack(width, height);
		for (int i = 0; i < planes.length; i++)
			stack.addSlice(labels[i], store.materialize(planes[i]));
		ImagePlus imp = new ImagePlus(title, stack);
		imp.setDimensions(nChannels, nSlices, nFrames);
		if (nChannels > 1 && bitDepth != 24) {
			CompositeImage ci = new CompositeImage(imp, compositeMode > 0 ? compositeMode : CompositeImage.COMPOSITE);
			if (luts != null && luts.length == nChannels) ci.setLuts(luts.clone());
			setActive(ci, activeChannels);
			imp = ci;
		} else if (nSlices > 1 && nFrames > 1 || nFrames > 1 && nChannels > 1) {
			imp.setOpenAsHyperStack(true);
		}
		imp.setCalibration(cal.copy());
		if (overlay != null) imp.setOverlay(overlay.duplicate());
		if (!imp.isComposite()) {
			if (lut != null) imp.getProcessor().setLut(lut);
			if (bitDepth != 24) imp.setDisplayRange(min, max);
		}
		return imp;
	}

	/** Ticks the saved channels (getActiveChannels() is the image's own array, so it is filled in place). */
	static void setActive(CompositeImage ci, boolean[] saved) {
		boolean[] a = ci.getActiveChannels();
		if (saved != null && a != null && a.length == saved.length) System.arraycopy(saved, 0, a, 0, a.length);
	}
}
