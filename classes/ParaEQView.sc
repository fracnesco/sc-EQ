// =====================================================================
// ParaEQView - the response curve of an eq, and its handles
//
// The five bands as five dots on a frequency / gain plane, over the curve
// they add up to, and after them the two cuts - the high pass and the low
// pass - as two more dots on the flat line, hollow while they are out.
// Drag a dot and the filter follows:
//
//   drag                  frequency and gain
//   cmd + drag up / down  the width of that band (rs / rq)
//   alt + drag            the same, for a hand that is already there
//   the wheel             the same again, without pressing anything
//   shift while dragging  snap to round frequencies, whole dB, round widths
//   double click a dot    that band back to 0 dB - a cut in or out
//
// A cut has no gain, so dragging one moves it along the frequency axis
// alone and the height of the mouse means nothing to it.
//
// A plain click picks up the nearest band, so a dot never has to be hit
// exactly. A cmd drag does not: it leaves the selection alone and works
// the band that is already selected, from wherever you press, so the
// width can be found without hunting for the dot first.
//
// The width drag is relative - it starts from the width the band already
// has rather than from where the mouse landed - so it never jumps.
//
// The curve is worked out from the same coefficients the server runs -
// see ParaEQ - so it is the filter, not a sketch.
//
// Behind it, while the eq is on, is what the signal actually sounds
// like: give the view a ParaEQSpectrum and it draws the analysis under
// the curve, on its own dB scale, so a cut can be put where something
// is rather than where it might be. Without one it draws the curve
// alone.
// =====================================================================

ParaEQView {
	classvar font, boldFont;
	classvar <>grabRadius = 10;			// how near a dot counts as hitting it

	// How much of the width one pixel of a cmd drag is worth. 1.02 puts the
	// whole 0.05 .. 10 range inside a drag the height of the view.
	classvar <>widthPerPixel = 1.02;

	// what shift snaps to
	classvar <>freqSnap;
	classvar <>shelfQSnap, <>peakQSnap;

	var <view, <eq, <>action;
	var <selected;
	var <>spectrum;						// a ParaEQSpectrum, or nil for none
	var drag, dragY, dragQ;				// \band or \width, and where it started
	var bw = 1, bh = 1;					// the view's size, read once per pass
	var curveCache, curveKey;			// the response, kept between redraws

	*initClass {
		freqSnap = [20, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 315, 400,
			500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300,
			8000, 10000, 12500, 16000, 20000];
		shelfQSnap = [0.6, 0.7, 1, 1.5, 2.5, 5, 10];
		peakQSnap = [0.1, 0.25, 0.5, 0.7, 1, 1.5, 2.5, 5, 10];
	}

	// Which keys mean "width, not position". Mouse events arrive carrying
	// Cocoa modifier bits whatever the platform - see QKeyModifiers.toCocoa -
	// so isCmd is command on macOS, isCtrl is control everywhere else, and
	// alt is alt. Command is not swallowed on the way here: a view only
	// starts a drag on cmd + click if it has something to drag, and this one
	// has not.
	*isWidthMod { |mod|
		^mod.isCmd or: { mod.isCtrl } or: { mod.isAlt }
	}

	// what a handle is marked with: the bands numbered, the cuts lettered
	*handleLabel { |i|
		^if (ParaEQ.isCut(i)) {
			["H", "L"][i - ParaEQ.numBands] ? "C"
		} {
			(i + 1).asString
		}
	}

	*font { ^font ?? { font = Font(Font.defaultSansFace, 9) } }
	*boldFont { ^boldFont ?? { boldFont = Font(Font.defaultSansFace, 10, true) } }

	*new { |parent, bounds| ^super.new.init(parent, bounds) }

	init { |parent, bounds|
		selected = 0;
		view = UserView(parent, bounds ? Rect(0, 0, 600, 150));
		view.minHeight_(90);
		view.background_(FxPalette.waveBg);
		view.drawFunc = { |v| this.prDraw(v) };
		view.mouseDownAction = { |v, x, y, mod, but, count|
			this.prMeasure; this.prMouseDown(x, y, mod, count) };
		view.mouseMoveAction = { |v, x, y, mod| this.prApply(x, y, mod) };
		view.mouseUpAction = { |v, x, y| drag = nil };
		view.mouseWheelAction = { |v, x, y, mod, xDelta, yDelta|
			this.prWheel(yDelta) };
		^this
	}

	eq_ { |anEq| eq = anEq; this.refresh }

	// The band the window is working on lives here and nowhere else, so
	// setting it from outside - eqView.selected = 3 - reaches the number
	// boxes too. Saying what it already says does nothing, which is what
	// stops that round trip from coming back round again.
	selected_ { |i|
		i = i.clip(0, ParaEQ.numHandles - 1);
		if (i == selected) { ^this };
		selected = i;
		this.refresh;
		action.value(this, \select);
	}

	refresh { view !? (_.refresh) }

	// ------------------------------------------------------------ geometry

	// Both the drawing and the mouse work in pixels, and every point of the
	// curve asks for them, so the size is read once and kept.
	prMeasure {
		var b = view.bounds;
		bw = b.width.max(1);
		bh = b.height.max(1);
	}

	prW { ^bw }
	prH { ^bh }

	prFreqToX { |f| ^f.clip(ParaEQ.minFreq, ParaEQ.maxFreq)
		.explin(ParaEQ.minFreq, ParaEQ.maxFreq, 0, this.prW) }

	prXToFreq { |x| ^x.clip(0, this.prW)
		.linexp(0, this.prW, ParaEQ.minFreq, ParaEQ.maxFreq) }

	prDbToY { |db| ^db.linlin(ParaEQ.dbRange.neg, ParaEQ.dbRange,
		this.prH, 0, \none) }

	prYToDb { |y| ^y.linlin(0, this.prH, ParaEQ.dbRange,
		ParaEQ.dbRange.neg, \none).clip2(ParaEQ.dbRange) }

	// the server's rate when it is up, so the curve is the one being run
	prSampleRate { ^eq !? (_.sampleRate) ? 44100 }

	// The spectrum has a scale of its own: the curve is drawn in dB of
	// gain, a few either side of flat, and the analysis in dB of level,
	// most of the way down to nothing. They share the frequency axis and
	// nothing else.
	prLevelToY { |db|
		^db.clip(ParaEQSpectrum.floorDb, ParaEQSpectrum.topDb).linlin(
			ParaEQSpectrum.floorDb, ParaEQSpectrum.topDb, this.prH, 0, \none)
	}

	// The response of every band over the width of the view, worked out
	// once and kept until something moves. With an analyser running the
	// view redraws twenty times a second, and five biquads over a couple
	// of hundred frequencies is not work to do twenty times a second.
	prCurves {
		var n, xs, key;
		n = (bw / 3).round.asInteger.clip(48, 260);
		key = [eq.settings.flat, bw, this.prSampleRate, n];
		if (curveKey == key) { ^curveCache };
		xs = Array.fill(n, { |i| i * bw / (n - 1) });
		curveCache = [xs, eq.responseAt(xs.collect { |x| this.prXToFreq(x) },
			this.prSampleRate)];
		curveCache = curveCache.add(curveCache[1].sum);
		curveKey = key;
		^curveCache
	}

	// --------------------------------------------------------------- mouse

	// Never miss: a click that lands on no dot picks up the nearest one.
	prNearest { |x, y|
		var best = 0, bestDist = inf;
		ParaEQ.numHandles.do { |i|
			var d = Point(this.prFreqToX(eq.freqAt(i)), this.prDbToY(eq.dbAt(i)))
				.dist(Point(x, y));
			if (d < bestDist) { bestDist = d; best = i };
		};
		^[best, bestDist]
	}

	prMouseDown { |x, y, mod, count|
		var hit, i, dist;
		if (eq.isNil) { ^this };

		// A width drag works the band that is already selected, from
		// wherever you press, and moves nothing until the mouse does.
		if (ParaEQView.isWidthMod(mod)) {
			drag = \width;
			dragY = y;
			dragQ = eq.qAt(selected);
			^this
		};

		hit = this.prNearest(x, y);
		i = hit[0];
		dist = hit[1];
		this.selected_(i);
		// double click: a band back to flat, a cut in or out
		if (count > 1 and: { dist <= grabRadius }) {
			drag = nil;
			if (ParaEQ.isCut(i)) { eq.toggleCut(i) } { eq.dbAt_(i, 0) };
			this.refresh;
			^this
		};
		drag = \band;
		this.prApply(x, y, mod);
	}

	prApply { |x, y, mod|
		var i = selected, freq, db, q;
		if (drag.isNil or: { eq.isNil }) { ^this };

		// Relative, and always measured from where the drag began, so the
		// width neither jumps on the first pixel nor ratchets against its
		// own floor on the way back up. Up is wider, as the wheel is.
		if (drag == \width) {
			q = dragQ * widthPerPixel.pow(dragY - y);
			if (mod.isShift) { q = q.nearestInList(this.prQSnap(i)) };
			eq.qAt_(i, q);
			this.refresh;
			^this
		};

		freq = this.prXToFreq(x);
		db = this.prYToDb(y);
		if (mod.isShift) {
			freq = freq.nearestInList(freqSnap);
			db = db.round(1);
		} {
			freq = freq.round(1);
			db = db.round(0.1);
		};
		eq.setBand(i, freq, db, nil);
		this.refresh;
	}

	prWheel { |yDelta|
		var i = selected;
		if (eq.isNil) { ^this };
		eq.qAt_(i, eq.qAt(i) * 1.08.pow(yDelta.clip(-3, 3)));
		this.refresh;
	}

	prQSnap { |i| ^if (ParaEQ.isShelf(i)) { shelfQSnap } { peakQSnap } }

	// ------------------------------------------------------------- drawing

	prDraw { |v|
		var w, h, cache, xs, curves, sum;
		this.prMeasure;
		w = bw;
		h = bh;

		Pen.fillColor = FxPalette.waveBg;
		Pen.fillRect(Rect(0, 0, w, h));
		if (eq.isNil) { ^this };

		// what the signal sounds like, under everything else
		this.prDrawSpectrum(w, h);
		this.prDrawGrid(w, h);

		// One point every few pixels: the curve is smooth enough at that
		// spacing, and it is kept, since a drag redraws this on every step.
		cache = this.prCurves;
		xs = cache[0];
		curves = cache[1];
		sum = cache[2];

		// each band as a filled area away from flat, the selected one brighter
		curves.do { |curve, i|
			var col = FxPalette.eqBand(i);
			Pen.fillColor = col.copy.alpha_(if (i == selected) { 0.30 } { 0.13 });
			this.prBandShape(xs, curve, h);
			Pen.fill;
		};

		// and the one you actually hear, over the top
		Pen.strokeColor = FxPalette.text;
		Pen.width = 1.5;
		Pen.moveTo(Point(xs[0], this.prDbToY(sum[0]).clip(-2, h + 2)));
		sum.do { |db, i|
			if (i > 0) { Pen.lineTo(Point(xs[i], this.prDbToY(db).clip(-2, h + 2))) };
		};
		Pen.stroke;

		this.prDrawHandles(w, h);
		this.prDrawReadout(w, h);

		Pen.strokeColor = FxPalette.panel;
		Pen.width = 1;
		Pen.strokeRect(Rect(0.5, 0.5, w - 1, h - 1));
	}

	// out along the curve, back along the zero line
	prBandShape { |xs, curve, h|
		var zero = this.prDbToY(0);
		Pen.moveTo(Point(xs[0], zero));
		curve.do { |db, i|
			Pen.lineTo(Point(xs[i], this.prDbToY(db).clip(-2, h + 2)));
		};
		Pen.lineTo(Point(xs.last, zero));
		Pen.lineTo(Point(xs[0], zero));
	}

	// The analysis: a filled shape under a line, with the mark that hangs
	// over the peaks above it. Log spaced the way the axis is, so it sits
	// exactly under the part of the curve it belongs to.
	prDrawSpectrum { |w, h|
		var sp = spectrum, levels, peaks, freqs, n, x0, x1;
		if (sp.isNil or: { sp.visible.not }) { ^this };
		levels = sp.levels;
		peaks = sp.peaks;
		freqs = sp.freqs;
		n = freqs.size.min(levels.size);
		if (n < 2) { ^this };
		x0 = this.prFreqToX(freqs[0]);
		x1 = this.prFreqToX(freqs[n - 1]);

		Pen.fillColor = FxPalette.wave.copy.alpha_(0.17);
		Pen.moveTo(Point(x0, h));
		n.do { |i|
			Pen.lineTo(Point(this.prFreqToX(freqs[i]), this.prLevelToY(levels[i])));
		};
		Pen.lineTo(Point(x1, h));
		Pen.lineTo(Point(x0, h));
		Pen.fill;

		Pen.strokeColor = FxPalette.wave.copy.alpha_(0.55);
		Pen.width = 1;
		Pen.moveTo(Point(x0, this.prLevelToY(levels[0])));
		n.do { |i|
			if (i > 0) {
				Pen.lineTo(Point(this.prFreqToX(freqs[i]), this.prLevelToY(levels[i])));
			};
		};
		Pen.stroke;

		Pen.strokeColor = FxPalette.dim.copy.alpha_(0.45);
		Pen.moveTo(Point(x0, this.prLevelToY(peaks[0])));
		n.do { |i|
			if (i > 0) {
				Pen.lineTo(Point(this.prFreqToX(freqs[i]), this.prLevelToY(peaks[i])));
			};
		};
		Pen.stroke;
	}

	prDrawGrid { |w, h|
		var zero = this.prDbToY(0);

		Pen.width = 1;
		Pen.strokeColor = FxPalette.waveDim.copy.alpha_(0.35);
		#[30, 50, 80, 200, 300, 500, 800, 2000, 3000, 5000, 8000, 15000].do { |f|
			var x = this.prFreqToX(f);
			Pen.line(Point(x, 0), Point(x, h));
		};
		Pen.stroke;

		Pen.strokeColor = FxPalette.waveDim;
		#[-18, -12, -6, 6, 12, 18].do { |db|
			var y = this.prDbToY(db);
			Pen.line(Point(0, y), Point(w, y));
		};
		#[100, 1000, 10000].do { |f|
			var x = this.prFreqToX(f);
			Pen.line(Point(x, 0), Point(x, h));
		};
		Pen.stroke;

		// flat, which is where every band starts and where the fills close
		Pen.strokeColor = FxPalette.dim.copy.alpha_(0.7);
		Pen.line(Point(0, zero), Point(w, zero));
		Pen.stroke;

		#[-18, -12, -6, 6, 12, 18].do { |db|
			Pen.stringLeftJustIn(db.asString ++ " dB",
				Rect(3, this.prDbToY(db) + 1, 44, 11),
				ParaEQView.font, FxPalette.waveDim);
		};
		[[100, "100"], [1000, "1k"], [10000, "10k"]].do { |item|
			Pen.stringLeftJustIn(item[1],
				Rect(this.prFreqToX(item[0]) + 3, h - 13, 34, 11),
				ParaEQView.font, FxPalette.waveDim);
		};
	}

	// The bands numbered 1 to 5, and the two cuts as H and L on the flat
	// line. A cut that is out is a ring rather than a dot: it is still
	// there to take hold of, and says at a glance that it is doing nothing.
	prDrawHandles { |w, h|
		ParaEQ.numHandles.do { |i|
			var out = eq.cutOn(i).not;
			var col = FxPalette.eqBand(i);
			var p = Point(this.prFreqToX(eq.freqAt(i)), this.prDbToY(eq.dbAt(i)));
			var r = if (i == selected) { 6 } { 4.5 };
			if (out) {
				Pen.strokeColor = col;
				Pen.width = 1.5;
				Pen.strokeOval(Rect(p.x - r, p.y - r, r * 2, r * 2));
			} {
				Pen.fillColor = col;
				Pen.fillOval(Rect(p.x - r, p.y - r, r * 2, r * 2));
			};
			if (i == selected) {
				Pen.strokeColor = FxPalette.handle;
				Pen.width = 1.5;
				Pen.strokeOval(Rect(p.x - r - 3, p.y - r - 3, (r + 3) * 2, (r + 3) * 2));
			};
			Pen.stringCenteredIn(ParaEQView.handleLabel(i),
				Rect(p.x - 6, p.y - 6, 12, 12),
				ParaEQView.font,
				if (out) { col } { FxPalette.waveBg });
		};
	}

	prDrawReadout { |w, h|
		var i = selected;
		var str;
		if (ParaEQ.isCut(i)) {
			str = "% - % Hz  % dB/oct  rq %".format(
				ParaEQ.bandName(i),
				eq.freqAt(i).round(1).asInteger,
				eq.slopeAt(i).asInteger,
				eq.qAt(i).round(0.01));
			if (eq.cutOn(i).not) { str = str ++ "   (out)" };
		} {
			str = "% - % Hz  % dB  %".format(
				ParaEQ.bandName(i),
				eq.freqAt(i).round(1).asInteger,
				if (eq.dbAt(i) > 0) { "+" ++ eq.dbAt(i).round(0.1) } { eq.dbAt(i).round(0.1) },
				(if (ParaEQ.isShelf(i)) { "rs " } { "rq " }) ++ eq.qAt(i).round(0.01));
		};
		if (eq.enabled.not) { str = str ++ "   (eq off)" };
		Pen.fillColor = FxPalette.waveBg.copy.alpha_(0.72);
		Pen.fillRect(Rect(0, 0, w, 14));
		Pen.stringLeftJustIn(" " ++ str, Rect(3, 1, w - 6, 12),
			ParaEQView.boldFont,
			if (eq.enabled and: { eq.cutOn(i) }) {
				FxPalette.eqBand(i)
			} {
				FxPalette.dim
			});
	}
}
