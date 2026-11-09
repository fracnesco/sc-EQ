// =====================================================================
// ParaEQView - the response of an eq, and its handles: the eq's display
//
// One plane, frequency along (log, 20 Hz to 20 kHz) and gain up, with what
// leaves the eq drawn behind it. On it, each band's lift or cut is a fill in
// the band's colour, each cut that is in a silver veil over what it takes
// away, and the sum of them all - what you hear - one ivory line. Seven dots
// to take hold of: the five bands, numbered 1 to 5 low to high, and the two
// cuts, a silver dot at each end, a ring while it is out.
//
//   drag a dot             a band: frequency and gain - a cut: frequency,
//                          and up for a corner that rings (its res)
//   shift while dragging   fine: a tenth of the movement
//   the wheel on a dot     a band's width - a cut's slope, a step a notch
//   cmd + drag up / down   the selected band's width, or the selected cut's
//                          res, from wherever you press (alt or ctrl too)
//   double click a dot     a band back to 0 dB - a cut in or out
//   right click a dot      a menu: back to 0 dB, back to where it starts,
//                          type a frequency - for a cut: in, slope, flat corner
//
// A press picks up the nearest dot, so one never has to be hit exactly, and
// every drag is relative - from where the dot was, not where the mouse
// landed - so nothing jumps. A strip along the top names the selected dot;
// its right end reads the pointer: the frequency, the nearest Tidal note
// (note 0 is c5, 261.6 Hz, so "a5" is 440 and "e2" 41.2), the region and,
// with the spectrum on, its level there. Under the Hz axis a row marks the
// Cs in Tidal's octaves.
//
// The curve is worked out from the same coefficients the server runs - see
// ParaEQ - so it is the filter, not a sketch. The spectrum (a ParaEQSpectrum,
// given as `spectrum`) has a scale of its own: the curve is gain, a few dB
// either side of flat, the analysis is level, most of the way down to nothing.
//
// A panel links it to its cards: `action` hears \select, `hoverAction` the dot
// under the pointer, `hot` rings a dot whose knob is hovered, `editAction` is
// asked to type a frequency. `hints` is a hint bar the view writes to itself,
// since a bar's attach would take over the pointer this view follows.
// =====================================================================

ParaEQView {
	classvar font, boldFont;
	classvar <>grabRadius = 20;			// how near a dot counts as hitting it

	// How much of the width one pixel of a cmd drag is worth. 1.02 puts the
	// whole 0.05 .. 10 range inside a drag the height of the view.
	classvar <>widthPerPixel = 1.02;

	// what shift used to snap to, before it meant fine; kept for anyone
	// who reads them
	classvar <>freqSnap;
	classvar <>shelfQSnap, <>peakQSnap;

	// the height the next view opens at, in dB either side of flat
	classvar <>lastRange = 24;

	var <view, <eq, <>action, <>hoverAction, <>editAction;
	var <selected = 0, <hot, <hover, <range;
	var <>spectrum, <hints;
	var drag, dragHandle, dragX = 0, dragY = 0, dragFrom, dragFine = false, wheelAcc = 0;
	var pointer, menu, lastPaint = 0, pending = false;
	var bw = 1, bh = 1, plot;
	var curveCache, curveKey;

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
	// alt is alt.
	*isWidthMod { |mod|
		^mod.isCmd or: { mod.isCtrl } or: { mod.isAlt }
	}

	// what a handle is marked with: the bands numbered, the cuts by a stroke
	// the way they slope - / for the low cut, \ for the high
	*handleLabel { |i|
		^if (ParaEQ.isCut(i)) {
			["/", "\\"][i - ParaEQ.numBands] ? "c"
		} {
			(i + 1).asString
		}
	}

	// the heights a view can show, in dB either side of flat
	*ranges { ^#[6, 12, 24] }

	*font { ^font ?? { font = Font(Font.defaultSansFace, 9) } }
	*boldFont { ^boldFont ?? { boldFont = Font(Font.defaultSansFace, 9, true) } }

	*new { |parent, bounds| ^super.new.init(parent, bounds) }

	init { |parent, bounds|
		range = lastRange;
		plot = Rect(30, 18, 100, 100);
		view = UserView(parent, bounds ? Rect(0, 0, 600, 200));
		view.minHeight_(120).minWidth_(240);
		view.background_(FxPalette.waveBg);
		view.acceptsMouseOver = true;
		view.drawFunc = { |v| this.prDraw };
		view.mouseDownAction = { |v, x, y, mod, button, count|
			this.prMouseDown(x, y, mod, count, button); true };
		view.mouseMoveAction = { |v, x, y, mod| this.prMouseMove(x, y, mod); true };
		view.mouseUpAction = { this.prMouseUp; true };
		view.mouseWheelAction = { |v, x, y, mod, xDelta, yDelta|
			this.prWheel(x, y, yDelta, mod); true };
		// the pointer readout and the hover rings follow the mouse with no
		// button down; answering true keeps the window from clearing the hint
		view.mouseOverAction = { |v, x, y| this.prHover(x, y); true };
		view.mouseLeaveAction = { this.prLeave; nil };
		^this
	}

	eq_ { |anEq|
		eq = anEq;
		curveKey = nil;
		drag = nil;
		this.refresh;
	}

	// The handle the window is working on lives here and nowhere else, so
	// setting it from outside - eqView.selected = 3 - reaches the cards too.
	// Saying what it already says does nothing, which is what stops that
	// round trip from coming back round again.
	selected_ { |i|
		i = i.clip(0, ParaEQ.numHandles - 1).asInteger;
		if (i == selected) { ^this };
		selected = i;
		this.refresh;
		action.value(this, \select);
	}

	// a dot to ring, because its knob is under the pointer, or nil
	hot_ { |i| if (hot != i) { hot = i; this.refresh } }

	// how many dB either side of flat the plane shows - 6, 12 or 24. A band
	// past it is pinned to the edge with an arrow; nothing about it changes
	range_ { |db|
		db = (db ? 24).clip(1, ParaEQ.dbRange);
		if (db == range) { ^this };
		range = db;
		lastRange = range;
		this.refresh;
		// so a menu showing the range follows it, whoever changed it
		action.value(this, \range);
	}

	// a hint bar (FxHintBar, ModuleHintBar ...) that the view writes to as
	// the pointer moves - see the header
	hints_ { |bar| hints = bar }

	isDragging { ^drag.notNil }

	refresh { if (view.notNil and: { view.isClosed.not }) { view.refresh } }

	// ------------------------------------------------------------ geometry

	// The plane's parts, worked out from the size once per pass: a strip
	// along the top for the caption, a gutter at the left for the dB, the
	// Hz under the plot and, when there is room, the notes under that.
	prMeasure {
		var b = view.bounds, rows;
		bw = b.width.max(1);
		bh = b.height.max(1);
		rows = if (this.prShowNotes) { 26 } { 14 };
		plot = Rect(30, 18, (bw - 36).max(10), (bh - 18 - rows).max(10));
	}

	prShowNotes { ^bh >= 170 }

	prFreqToX { |f|
		^plot.left + f.clip(ParaEQ.minFreq, ParaEQ.maxFreq)
			.explin(ParaEQ.minFreq, ParaEQ.maxFreq, 0, plot.width)
	}

	prXToFreq { |x|
		^(x - plot.left).clip(0, plot.width)
			.linexp(0, plot.width, ParaEQ.minFreq, ParaEQ.maxFreq)
	}

	prDbToY { |db| ^plot.top + db.linlin(range.neg, range, plot.height, 0, \none) }

	// the spectrum's own scale, over the same height
	prLevelToY { |db|
		^plot.top + db.clip(ParaEQSpectrum.floorDb, ParaEQSpectrum.topDb).linlin(
			ParaEQSpectrum.floorDb, ParaEQSpectrum.topDb, plot.height, 0, \none)
	}

	// the server's rate when it is up, so the curve is the one being run
	prSampleRate { ^eq !? (_.sampleRate) ? 44100 }

	// Where a handle's dot is: a band at its frequency and gain, a cut at its
	// frequency and as high as its corner rings - on the flat line when it is
	// flat - so dragging a cut up makes it ring. Pinned inside the plane.
	prHandlePoint { |i|
		var y = if (ParaEQ.isCut(i)) { eq.resAt(i) * range } { eq.dbAt(i) };
		^Point(this.prFreqToX(eq.freqAt(i)), this.prDbToY(y.clip(range.neg, range)))
	}

	// a band past the edge of the plane: 1 above, -1 below, 0 inside
	prPinned { |i|
		var db = if (ParaEQ.isCut(i)) { 0 } { eq.dbAt(i) };
		^case { db > range } { 1 } { db < range.neg } { -1 } { true } { 0 }
	}

	// Never miss: whichever dot is nearest, and how near
	prNearest { |x, y|
		var best = 0, bestDist = inf;
		ParaEQ.numHandles.do { |i|
			var d = this.prHandlePoint(i).dist(Point(x, y));
			if (d < bestDist) { bestDist = d; best = i };
		};
		^[best, bestDist]
	}

	// the dot under the pointer, or nil
	prHandleAt { |x, y|
		var n;
		if (eq.isNil or: { x.isNil }) { ^nil };
		n = this.prNearest(x, y);
		^if (n[1] <= grabRadius) { n[0] }
	}

	// The response of every handle over the width of the plot, worked out
	// once and kept until something moves. With the spectrum running the
	// view redraws fifteen times a second, and seven filters over a few
	// hundred frequencies is not work to do fifteen times a second.
	prCurves {
		var n, xs, key, sr = this.prSampleRate;
		n = (plot.width / 3).round.asInteger.clip(48, 320);
		key = [eq.settings.flat, plot.left, plot.width, sr, n];
		if (curveKey == key) { ^curveCache };
		xs = Array.fill(n, { |k| plot.left + (k * plot.width / (n - 1)) });
		curveCache = [xs, eq.responseAt(xs.collect { |x| this.prXToFreq(x) }, sr)];
		curveCache = curveCache.add(curveCache[1].sum);
		curveKey = key;
		^curveCache
	}

	// --------------------------------------------------------------- mouse

	prMouseDown { |x, y, mod, count, button|
		var near, i;
		this.prMeasure;
		if (eq.isNil) { ^this };
		// right click: the dot under the pointer's menu, or the selected one's
		if ((button ? 0) == 1) { ^this.prMenu(this.prHandleAt(x, y) ? selected) };
		// A width drag works the handle that is already selected, from
		// wherever you press, and moves nothing until the mouse does.
		if (ParaEQView.isWidthMod(mod)) { ^this.prStartDrag(\width, selected, x, y, mod) };
		near = this.prNearest(x, y);
		i = near[0];
		this.selected_(i);
		// double click on a dot: a band back to flat, a cut in or out
		if ((count ? 1) > 1 and: { near[1] <= grabRadius }) {
			drag = nil;
			if (ParaEQ.isCut(i)) { eq.toggleCut(i) } { eq.dbAt_(i, 0) };
			^this.refresh
		};
		this.prStartDrag(\move, i, x, y, mod);
	}

	prStartDrag { |kind, i, x, y, mod|
		drag = kind;
		dragHandle = i;
		dragX = x;
		dragY = y;
		dragFine = mod.isShift;
		dragFrom = this.prDragFrom(i);
		this.refresh;
	}

	// what a drag is measured from: frequency, gain or res, width
	prDragFrom { |i|
		^[eq.freqAt(i), if (ParaEQ.isCut(i)) { eq.resAt(i) } { eq.dbAt(i) }, eq.qAt(i)]
	}

	// Relative, and always measured from where the drag began, so nothing
	// jumps on the first pixel. Shift is fine, a tenth of the movement; a
	// change of speed mid drag carries on from where it is.
	prMouseMove { |x, y, mod|
		var i = dragHandle, fine, k, dx, dy, f, v;
		if (drag.isNil or: { eq.isNil }) { ^this };
		fine = mod.isShift;
		if (fine != dragFine) {
			dragFine = fine;
			dragX = x;
			dragY = y;
			dragFrom = this.prDragFrom(i);
		};
		k = if (fine) { 0.1 } { 1 };
		dx = (x - dragX) * k;
		dy = (dragY - y) * k;				// up is more
		if (drag == \width) {
			// up is wider, as the wheel is - and up rings a cut more
			if (ParaEQ.isCut(i)) {
				eq.resAt_(i, (dragFrom[1] + (dy / 200)).clip(-1, 1));
			} {
				eq.qAt_(i, dragFrom[2] * widthPerPixel.pow(dy));
			};
			this.prSayDrag;
			^this.refresh
		};
		f = this.prRoundFreq(this.prXToFreq(this.prFreqToX(dragFrom[0]) + dx), fine);
		if (ParaEQ.isCut(i)) {
			v = (dragFrom[1] + (dy / (plot.height * 0.5))).clip(-1, 1);
			eq.setBand(i, f, nil, ParaEQ.cutRq(v));
		} {
			v = dragFrom[1] + (dy * 2 * range / plot.height);
			eq.setBand(i, f, v.round(if (fine) { 0.01 } { 0.1 }), nil);
		};
		this.prSayDrag;
		this.refresh;
	}

	// while dragging, the bar says what the dot is now (R-CTRL-4)
	prSayDrag {
		hints !? { |bar| dragHandle !? { |i| bar.string = this.prHandleText(i) } };
	}

	// three figures, four when fine: 20.3, 203, 2030 Hz
	prRoundFreq { |f, fine|
		var step = 10 ** (f.max(1).log10.floor - if (fine) { 3 } { 2 });
		^f.round(step)
	}

	prMouseUp { drag = nil; this.refresh }

	// The wheel works the dot under the pointer, or the selected one: a band's
	// width, up wider; a cut's slope, a step per notch, up steeper.
	prWheel { |x, y, yDelta, mod|
		var i;
		this.prMeasure;
		if (eq.isNil or: { yDelta == 0 }) { ^this };
		i = this.prHandleAt(x, y) ? selected;
		this.selected_(i);
		if (ParaEQ.isCut(i)) {
			wheelAcc = wheelAcc + yDelta.clip(-3, 3);
			if (wheelAcc.abs >= 3) {
				eq.slopeAt_(i, eq.slopeAt(i) + (ParaEQ.slopeStep * wheelAcc.sign));
				wheelAcc = 0;
			};
		} {
			eq.qAt_(i, eq.qAt(i) * 1.08.pow(yDelta.clip(-3, 3) * if (mod.isShift) { 0.1 } { 1 }));
		};
		hints !? { |bar| bar.string = this.prHandleText(i) };
		this.refresh;
	}

	prHover { |x, y|
		var h;
		this.prMeasure;
		pointer = Point(x, y);
		h = this.prHandleAt(x, y);
		if (h != hover) {
			hover = h;
			hoverAction.value(this, h);
		};
		hints !? { |bar| bar.string = this.hint(x, y) };
		this.prRefreshSoon;
	}

	prLeave {
		pointer = nil;
		if (hover.notNil) {
			hover = nil;
			hoverAction.value(this, nil);
		};
		this.refresh;
	}

	// at most every 40 ms while the pointer moves: the readout wants it, the
	// meters' rate is plenty
	prRefreshSoon {
		if ((Main.elapsedTime - lastPaint) >= 0.04) { ^this.refresh };
		if (pending.not) {
			pending = true;
			AppClock.sched(0.04, { this.refresh; nil });
		};
	}

	// What a right click offers: on a band, back to 0 dB, back to where it
	// starts, and a frequency typed; on a cut, in or out, its slope, a flat
	// corner and a frequency typed.
	prMenuActions { |i|
		var type = MenuAction("type a frequency...", { editAction.value(this, i) });
		^if (ParaEQ.isCut(i)) {
			[
				MenuAction(ParaEQ.cutWord(i)).enabled_(false),
				MenuAction("in", { eq !? (_.toggleCut(i)) }).checkable_(true).checked_(eq.cutOn(i)),
				Menu(*ParaEQ.slopes.collect { |s|
					MenuAction("% dB/oct".format(s), { eq !? (_.slopeAt_(i, s)) })
						.checkable_(true).checked_(s == eq.slopeAt(i))
				}).title_("slope").asMenuAction,
				MenuAction("flat corner", { eq !? (_.resAt_(i, 0)) }),
				type
			]
		} {
			[
				MenuAction("band % · %".format(i + 1, ParaEQ.shapeWord(i))).enabled_(false),
				MenuAction("back to 0 dB", { eq !? (_.dbAt_(i, 0)) }),
				MenuAction("back to where it starts", {
					var d = ParaEQ.defaultBands[i];
					eq !? (_.setBand(i, d[0], d[1], d[2]));
				}),
				type
			]
		}
	}

	prMenu { |i|
		this.selected_(i);
		menu !? { menu.destroy };
		menu = Menu(*this.prMenuActions(i));
		menu.front;
	}

	// What the hint bar says with the pointer here: the dot under it, or the
	// whole of the mouse - one line each, short enough for a footer that also
	// says where the eq runs.
	hint { |x, y|
		var h;
		if (eq.isNil) { ^"the response of an eq - there is none here yet" };
		h = this.prHandleAt(x, y);
		^case
		{ h.isNil } {
			"drag a dot: frequency and gain · wheel: width or slope · shift: fine · cmd-drag: width"
			" · double click: 0 dB"
		}
		{ ParaEQ.isCut(h) } {
			"% (%) - drag: where it cuts, up: it rings · wheel: slope · double click: in/out · right click: more"
				.format(ParaEQ.cutWord(h), if (eq.cutOn(h)) { "in" } { "out" })
		}
		{ true } {
			"band % · % - drag: frequency and gain · wheel: width · double click: 0 dB · right click: more"
				.format(h + 1, ParaEQ.shapeWord(h))
		}
	}

	// ------------------------------------------------------------- drawing

	prDraw {
		var on, cache;
		this.prMeasure;
		lastPaint = Main.elapsedTime;
		pending = false;
		Pen.fillColor = FxPalette.waveBg;
		Pen.fillRect(Rect(0, 0, bw, bh));
		this.prDrawGrid;
		if (eq.notNil) {
			on = eq.enabled;
			cache = this.prCurves;
			Pen.use {
				Pen.addRect(plot);
				Pen.clip;
				this.prDrawSpectrum;
				this.prDrawFills(cache[0], cache[1]);
				this.prDrawSum(cache[0], cache[2], on);
			};
		};
		this.prDrawAxes;
		if (eq.notNil) {
			this.prDrawHandles(on);
			this.prDrawBubble;
		};
		this.prDrawCaption(on ? false);
		Pen.strokeColor = FxPalette.card;
		Pen.width = 1;
		Pen.strokeRect(Rect(0.5, 0.5, bw - 1, bh - 1));
	}

	// the dB between the gain lines, for each height
	prTickStep { ^case { range <= 6 } { 2 } { range <= 12 } { 3 } { true } { 6 } }

	prDrawGrid {
		var step = this.prTickStep, zero;
		Pen.width = 1;
		// every 1-2-3 ... 9 of each decade, the decades brighter
		[10, 100, 1000, 10000].do { |dec|
			(1..9).do { |m|
				var f = dec * m, x;
				if (f >= ParaEQ.minFreq and: { f <= ParaEQ.maxFreq }) {
					x = this.prFreqToX(f).round + 0.5;
					Pen.strokeColor = if (m == 1) { FxPalette.waveDim } { FxPalette.fade(FxPalette.waveDim, 0.35) };
					Pen.line(Point(x, plot.top), Point(x, plot.bottom));
					Pen.stroke;
				};
			};
		};
		Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.6);
		(1 .. (range / step).floor.asInteger).do { |k|
			[k * step, (k * step).neg].do { |db|
				var y;
				if (db.abs < range) {
					y = this.prDbToY(db).round + 0.5;
					Pen.line(Point(plot.left, y), Point(plot.right, y));
				};
			};
		};
		Pen.stroke;
		// flat, which is where every band starts and where the fills close
		zero = this.prDbToY(0).round + 0.5;
		Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.7);
		Pen.line(Point(plot.left, zero), Point(plot.right, zero));
		Pen.stroke;
		// the strip the caption sits in, apart from the plane
		Pen.fillColor = FxPalette.card;
		Pen.fillRect(Rect(1, 16, bw - 2, 1));
	}

	// The dB at the left, the Hz under the plot, and under them the Cs in
	// Tidal's octaves (c5 is note 0), with a tick for each on the plot's edge.
	prDrawAxes {
		var step = this.prTickStep, f9 = FxPalette.smallFont, y0 = plot.bottom + 1;
		(0 .. (range / step).floor.asInteger).do { |k|
			[k * step, (k * step).neg].asSet.do { |db|
				var y = this.prDbToY(db), str;
				if (db.abs < range or: { db == 0 }) {
					str = case { db == 0 } { "0 dB" } { db > 0 } { "+" ++ db } { db.asString };
					Pen.stringRightJustIn(str, Rect(0, y - 6, plot.left - 4, 12), f9, FxPalette.dim);
				};
			};
		};
		[[20, "20"], [50, "50"], [100, "100"], [200, "200"], [500, "500"], [1000, "1k"],
			[2000, "2k"], [5000, "5k"], [10000, "10k"], [20000, "20k"]].do { |item, k|
			var x = this.prFreqToX(item[0]);
			case
			{ k == 0 } { Pen.stringLeftJustIn(item[1], Rect(x + 2, y0, 40, 12), f9, FxPalette.dim) }
			{ k == 9 } { Pen.stringRightJustIn(item[1], Rect(x - 42, y0, 40, 12), f9, FxPalette.dim) }
			{ true } { Pen.stringCenteredIn(item[1], Rect(x - 20, y0, 40, 12), f9, FxPalette.dim) };
		};
		Pen.stringRightJustIn("Hz", Rect(0, y0, plot.left - 4, 12), f9, FxPalette.fade(FxPalette.dim, 0.7));
		if (this.prShowNotes) {
			Pen.stringRightJustIn("note", Rect(0, y0 + 12, plot.left - 4, 12), f9,
				FxPalette.fade(FxPalette.dim, 0.7));
			Pen.strokeColor = FxPalette.dim;
			Pen.width = 1;
			(2..9).do { |oct|
				var x = this.prFreqToX((oct * 12).midicps);
				Pen.line(Point(x, plot.bottom - 3), Point(x, plot.bottom));
				Pen.stroke;
				Pen.stringCenteredIn("c" ++ oct, Rect(x - 16, y0 + 12, 32, 12), f9,
					if (oct == 5) { FxPalette.text } { FxPalette.dim });
			};
		};
	}

	// The analysis: a filled shape under a line, with the slower mark that
	// hangs over the peaks above it. Log spaced the way the axis is, so it
	// sits exactly under the part of the curve it belongs to.
	prDrawSpectrum {
		var sp = spectrum, levels, peaks, freqs, n, x0, x1;
		if (sp.isNil or: { sp.visible.not }) { ^this };
		levels = sp.levels;
		peaks = sp.peaks;
		freqs = sp.freqs;
		n = freqs.size.min(levels.size);
		if (n < 2) { ^this };
		x0 = this.prFreqToX(freqs[0]);
		x1 = this.prFreqToX(freqs[n - 1]);

		Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.17);
		Pen.moveTo(Point(x0, plot.bottom));
		n.do { |k| Pen.lineTo(Point(this.prFreqToX(freqs[k]), this.prLevelToY(levels[k]))) };
		Pen.lineTo(Point(x1, plot.bottom));
		Pen.lineTo(Point(x0, plot.bottom));
		Pen.fill;

		Pen.width = 1;
		Pen.strokeColor = FxPalette.fade(FxPalette.wave, 0.5);
		Pen.moveTo(Point(x0, this.prLevelToY(levels[0])));
		(1 .. n - 1).do { |k| Pen.lineTo(Point(this.prFreqToX(freqs[k]), this.prLevelToY(levels[k]))) };
		Pen.stroke;

		Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.4);
		Pen.moveTo(Point(x0, this.prLevelToY(peaks[0])));
		(1 .. n - 1).do { |k| Pen.lineTo(Point(this.prFreqToX(freqs[k]), this.prLevelToY(peaks[k]))) };
		Pen.stroke;
	}

	// whether a handle is lit: selected, under the pointer, or its knob is
	prLit { |i| ^(i == selected) or: { i == hover } or: { i == hot } }

	// Each band's own lift or cut as a fill away from flat, in its colour -
	// a band at 0 dB is the identity filter and draws nothing - and each
	// cut that is in as a silver veil over what it takes away.
	prDrawFills { |xs, curves|
		var zero = this.prDbToY(0);
		curves.do { |curve, i|
			var col, a;
			if (ParaEQ.isCut(i)) {
				if (eq.cutOn(i)) { col = FxPalette.cut; a = if (this.prLit(i)) { 0.24 } { 0.16 } };
			} {
				if (eq.dbAt(i).abs >= 0.05) {
					col = ParaEQ.handleColour(i);
					a = if (this.prLit(i)) { 0.30 } { 0.13 };
				};
			};
			col !? {
				Pen.fillColor = FxPalette.fade(col, a);
				Pen.moveTo(Point(xs[0], zero));
				curve.do { |db, k|
					Pen.lineTo(Point(xs[k], this.prDbToY(db).clip(plot.top - 2, plot.bottom + 2)))
				};
				Pen.lineTo(Point(xs.last, zero));
				Pen.lineTo(Point(xs[0], zero));
				Pen.fill;
			};
		};
	}

	// what you hear, in the eq's own colour, over the top
	prDrawSum { |xs, sum, on|
		Pen.strokeColor = FxPalette.fade(FxPalette.ivory, if (on) { 1 } { 0.35 });
		Pen.width = 2;
		Pen.joinStyle = 1;
		Pen.moveTo(Point(xs[0], this.prDbToY(sum[0]).clip(plot.top - 2, plot.bottom + 2)));
		sum.do { |db, k|
			if (k > 0) { Pen.lineTo(Point(xs[k], this.prDbToY(db).clip(plot.top - 2, plot.bottom + 2))) };
		};
		Pen.stroke;
	}

	// The bands as numbered dots in their colours, the cuts as silver dots
	// with the stroke of their slope - rings while they are out, still there
	// to take hold of. The selected one is ringed white, accent while it is
	// dragged; a hovered one, or one whose knob is hovered, ringed faintly.
	prDrawHandles { |on|
		var order = (0 .. ParaEQ.numHandles - 1).reject { |i| i == selected } ++ [selected];
		order.do { |i|
			var p = this.prHandlePoint(i), cut = ParaEQ.isCut(i), r = if (i == selected) { 7 } { 6 };
			var col = ParaEQ.handleColour(i), out = cut and: { eq.cutOn(i).not };
			var a = if (on) { 1 } { 0.6 }, pin = this.prPinned(i), ink;
			if (out) {
				Pen.fillColor = FxPalette.waveBg;
				Pen.fillOval(Rect.aboutPoint(p, r, r));
				Pen.strokeColor = FxPalette.fade(col, a);
				Pen.width = 1.5;
				Pen.strokeOval(Rect.aboutPoint(p, r - 0.75, r - 0.75));
			} {
				Pen.fillColor = FxPalette.fade(col, a);
				Pen.fillOval(Rect.aboutPoint(p, r, r));
			};
			ink = if (out) { FxPalette.fade(col, a) } { FxPalette.waveBg };
			if (cut) {
				// the cut's own shape, small: the low cut climbs out of the lows
				// and stays up, the high cut stays up and falls into the highs
				// (a bare stroke in a ring read as a "no" sign)
				Pen.strokeColor = ink;
				Pen.width = 1.3;
				Pen.joinStyle = 1;
				if (i == ParaEQ.highPass) {
					Pen.moveTo(p + Point(-3.5, 3));
					Pen.lineTo(p + Point(-0.5, -1.5));
					Pen.lineTo(p + Point(3.5, -1.5));
				} {
					Pen.moveTo(p + Point(-3.5, -1.5));
					Pen.lineTo(p + Point(0.5, -1.5));
					Pen.lineTo(p + Point(3.5, 3));
				};
				Pen.stroke;
			} {
				Pen.stringCenteredIn((i + 1).asString, Rect.aboutPoint(p, r, r), ParaEQView.boldFont, ink);
			};
			// past the edge: an arrow pointing the way it went
			if (pin != 0) {
				Pen.fillColor = FxPalette.fade(col, a);
				Pen.moveTo(p + Point(-4, pin.neg * (r + 2)));
				Pen.lineTo(p + Point(4, pin.neg * (r + 2)));
				Pen.lineTo(p + Point(0, pin.neg * (r + 7)));
				Pen.lineTo(p + Point(-4, pin.neg * (r + 2)));
				Pen.fill;
			};
			case
			{ i == selected } {
				Pen.strokeColor = if (drag.notNil and: { dragHandle == i }) { FxPalette.accent } { FxPalette.handle };
				Pen.width = 1.5;
				Pen.strokeOval(Rect.aboutPoint(p, r + 3, r + 3));
			}
			{ (i == hover) or: { i == hot } } {
				Pen.strokeColor = FxPalette.fade(FxPalette.handle, 0.5);
				Pen.width = 1.5;
				Pen.strokeOval(Rect.aboutPoint(p, r + 3, r + 3));
			};
		};
	}

	// while a dot is dragged, its values beside it, so the eyes stay on the curve
	prDrawBubble {
		var i = dragHandle, p, str, w, r;
		if (drag.isNil or: { i.isNil }) { ^this };
		p = this.prHandlePoint(i);
		str = if (ParaEQ.isCut(i)) {
			FxPalette.format(eq.freqAt(i), "Hz") ++ "  res " ++ FxPalette.format(eq.resAt(i), nil, \pct, 0, true)
		} {
			if (drag == \width) {
				FxPalette.format(eq.freqAt(i), "Hz") ++ "  " ++ this.prWidthText(i)
			} {
				FxPalette.format(eq.freqAt(i), "Hz") ++ "  " ++ FxPalette.format(eq.dbAt(i), "dB", nil, 1, true)
			}
		};
		w = (str.bounds(FxPalette.font).width + 14).ceil;
		r = Rect(p.x + 14, p.y - 30, w, 18);
		if (r.right > (plot.right - 2)) { r = r.moveTo(p.x - 14 - w, r.top) };
		if (r.top < (plot.top + 2)) { r = r.moveTo(r.left, p.y + 14) };
		Pen.fillColor = FxPalette.fade(FxPalette.control, 0.9);
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		Pen.stringCenteredIn(str, r, FxPalette.font, FxPalette.accent);
	}

	// a band's width as words: octaves for a bell, a percent for a shelf
	prWidthText { |i|
		^if (ParaEQ.isShelf(i)) {
			"width " ++ FxPalette.fixed(eq.qAt(i) * 100, 0) ++ " %"
		} {
			FxPalette.fixed(eq.widthAt(i), 2) ++ " oct"
		}
	}

	// one handle in a line: "2 · bell · 1.20 kHz · -3.0 dB · 1.39 oct",
	// "low cut · 80 Hz · 24 dB/oct · res 0 %", "low cut · out"
	prHandleText { |i|
		^if (ParaEQ.isCut(i)) {
			if (eq.cutOn(i)) {
				[ParaEQ.cutWord(i), FxPalette.format(eq.freqAt(i), "Hz"),
					eq.slopeAt(i).asString ++ " dB/oct",
					"res " ++ FxPalette.format(eq.resAt(i), nil, \pct, 0, true)].join(" · ")
			} {
				ParaEQ.cutWord(i) ++ " · out"
			}
		} {
			[(i + 1).asString, ParaEQ.shapeWord(i), FxPalette.format(eq.freqAt(i), "Hz"),
				FxPalette.format(eq.dbAt(i), "dB", nil, 1, true), this.prWidthText(i)].join(" · ")
		}
	}

	// What is under the pointer: the frequency, the nearest note as Tidal
	// says it, the region and, with the spectrum showing, its level there.
	prPointerText {
		var f, parts, lv;
		if (pointer.isNil or: { plot.containsPoint(pointer).not }) { ^nil };
		f = this.prXToFreq(pointer.x);
		parts = [FxPalette.format(f, "Hz"), FxPalette.noteName(f), FxPalette.regionName(f)];
		if (spectrum.notNil and: { spectrum.visible }) {
			lv = spectrum.tryPerform(\levelAt, f);
			if (lv.notNil and: { lv > ParaEQSpectrum.floorDb }) {
				parts = parts.add(FxPalette.fixed(lv, 0) ++ " dB");
			};
		};
		^parts.join(" · ")
	}

	// The strip along the top: the selected handle at the left, in its colour,
	// and at the right the pointer - or the dot under it, when that is another.
	prDrawCaption { |on|
		var i = selected, left, colour, right, lw, rect = Rect(8, 1, bw - 16, 14);
		if (eq.isNil) {
			Pen.stringLeftJustIn("no eq", rect, FxPalette.font, FxPalette.dim);
			^this
		};
		left = this.prHandleText(i);
		colour = case
		{ on.not } { FxPalette.dim }
		{ ParaEQ.isCut(i) and: { eq.cutOn(i).not } } { FxPalette.dim }
		{ true } { ParaEQ.handleColour(i) };
		if (on.not) { left = left ++ " · eq off" };
		Pen.stringLeftJustIn(left, rect, FxPalette.font, colour);
		lw = left.bounds(FxPalette.font).width;
		right = if (hover.notNil and: { hover != i }) { this.prHandleText(hover) } { this.prPointerText };
		right !? {
			if ((right.bounds(FxPalette.smallFont).width + lw + 24) < rect.width) {
				Pen.stringRightJustIn(right, rect, FxPalette.smallFont, FxPalette.dim);
			};
		};
	}
}
