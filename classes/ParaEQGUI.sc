// =====================================================================
// ParaEQPanel - the eq's controls as one view; ParaEQGUI - as a window
//
//   (o) EQ   < warm *          v >  save  ...                ±24 dB v  spectrum
//   +---------------------------------------------------------------+ +-----+
//   | 2 · bell · 1.20 kHz · -3.0 dB · 1.39 oct     440 Hz · a5 · mids| |in out|
//   |   the response: each band's fill, the cuts' veils, the sum    | | |  | |
//   |   in ivory, seven dots, the spectrum of what leaves behind it | | |  | |
//   |  20    50   100   200   500   1k   2k   5k   10k   20k        | |     |
//   +---------------------------------------------------------------+ +-----+
//   | low cut      [in] | | band 2 · bell     1 2 3 4 5 | | high cut     [in] |
//   | freq  slope  res  | |  (( freq ))  (( gain ))  width| | freq  slope  res  |
//
// The frame every effect window shares (sc-fx-ui) - the header, the display
// with its meters at the right, titled cards, the knobs and their gestures,
// the hint bar - and an equaliser's face: one undivided plane you play, the
// cards in frequency order under the axis they work on (the low cut, one
// band at a time, the high cut), each band's colour on its dot, its fill,
// its number in the selector and its knobs. There is no mix: an eq mixed
// with its own dry signal combs. There is no output either, yet - the eq
// adds no gain stage of its own - so the band card takes that width.
//
// The cuts are switches with slopes. Out - as every builtin preset but the
// last three leaves them - their knobs dim and their dots are rings, so it
// is plain they do nothing and cost nothing. A band at 0 dB is the identity
// filter, so its frequency and width dim too until its gain moves.
//
// The panel is what every window that shows an eq is built from: the
// eq's own window, the compressor's sidechain page, the master chain in
// ServerPanel, the module family's eq tab. It follows one ParaEQ, and can
// be pointed at another - `panel.eq = otherEq` - which is how a module
// window shows the eq of whichever pad is selected.
//
//   panel = ParaEQPanel(eq);
//   window.layout = VLayout(panel.view, hints.view);
//   panel.hints = hints;          // labels every control
//   panel.running = true;         // the analysis and the meters, while showing
//   ...
//   panel.remove;                 // in window.onClose
//
// ParaEQGUI is the same in a window of its own, with the one-line footer
// every effect window has: what `eq.gui` opens.
// =====================================================================

ParaEQPanel {
	var <eq, <view, <eqView, <spectrum, <meter, <header;
	var <>subject;				// what the hints call the thing being shaped
	var showOnBox, compact;
	var specSwitch, rangeChoice;
	var cutCards, cutIns, cutFreqs, cutSlopes, cutRes;
	var bandCard, bandStack, bandSelector, bandFreqs, bandGains, bandWidths;
	var <shownBand = 0;
	var ctrl, classCtrl, hints, running = false, watcher;

	// `subject` is what the hints call the thing being shaped - "this pad",
	// "the sidechain" - and `showOnBox: false` leaves the power button out,
	// for a window that has one of its own. `compact: true` is for a host
	// short of height: the band's knobs medium rather than big.
	*new { |eq, parent, subject, showOnBox = true, compact = false|
		^super.new.init(eq, parent, subject, showOnBox, compact)
	}

	init { |argEq, parent, argSubject, argShowOnBox, argCompact|
		var controls;
		subject = argSubject ? "this eq";
		showOnBox = argShowOnBox != false;
		compact = argCompact == true;

		eqView = ParaEQView(nil);
		eqView.view.minHeight_(if (compact) { 120 } { 170 });
		eqView.action = { |v, what|
			switch (what,
				\select, { this.prSelected(v.selected) },
				\range, { this.prRefreshRange }
			)
		};
		eqView.hoverAction = { |v, i| this.prHovered(i) };
		eqView.editAction = { |v, i| this.prTypeFreq(i) };

		// The analysis behind the curve. It runs only while `running` is
		// set - a window sets it when the panel is showing - so a panel
		// sitting on a hidden page costs nothing for it.
		spectrum = ParaEQSpectrum(nil);
		spectrum.action = { { eqView.refresh }.defer };
		eqView.spectrum = spectrum;

		meter = FxMeter(nil);

		// One switch for every window and every session: the analysis is
		// cheap but it is not free, and a machine that is working hard can
		// have it back.
		specSwitch = FxSwitch(["spectrum"], true).action_({ |s|
			ParaEQSpectrum.on = s.value;
			spectrum.prSync;
			eqView.refresh;
		});
		rangeChoice = FxChoice(nil, ParaEQView.ranges.collect { |r| "±% dB".format(r) })
			.action_({ |c| eqView.range = ParaEQView.ranges[c.value] });
		header = FxHeader(nil, "eq", FxPalette.ivory, [rangeChoice.view, specSwitch.view], showOnBox);

		controls = this.prControls;
		view = View(parent).layout_(VLayout(
			header.view,
			[HLayout([eqView.view, stretch: 1], meter.view).spacing_(8).margins_(0), stretch: 1],
			controls
		).margins_(0).spacing_(8));

		// The presets and the clipboard belong to the class, so a panel
		// that did not make the change still has to hear about it.
		classCtrl = SimpleController(ParaEQ)
			.put(\presets, { { this.refresh }.defer })
			.put(\clipboard, { { this.refresh }.defer });

		this.eq_(argEq);
		^this
	}

	// Which eq the panel is showing. The view, the analyser, the meter, the
	// header and the cards all follow it; a nil leaves an empty panel.
	eq_ { |anEq|
		if (anEq === eq) { ^this.refresh };
		ctrl !? (_.remove);
		ctrl = nil;
		if (running) { eq !? { |old| old.meter = false } };
		eq = anEq;
		eqView.eq_(eq);
		spectrum.eq_(eq);
		header.unit = eq;
		meter.unit = eq;
		eq !? {
			ctrl = SimpleController(eq)
				.put(\bands, { { this.refresh }.defer })
				.put(\settings, { { this.refresh }.defer })
				.put(\enabled, { { this.refresh }.defer })
				.put(\levels, { { meter.refresh }.defer })
				.put(\node, { { spectrum.prSync; this.refresh }.defer })
				.put(\freed, { { this.eq_(nil) }.defer });
			if (running) { this.prMeterOn };
		};
		this.refresh;
	}

	// the analysis and the meters, while the panel is showing
	running_ { |bool|
		running = bool == true;
		spectrum.running = running;
		eq !? { if (running) { this.prMeterOn } { eq.meter = false } };
		this.prWatch;
	}

	running { ^running }

	prMeterOn { if (eq.canMeter) { eq.meter = true } }

	// A node that stops saying its levels - switched off, freed, paused - would
	// leave the meter standing at its last reply: this lets it fall.
	prWatch {
		watcher !? (_.stop);
		watcher = nil;
		if (running.not) { ^this };
		watcher = SkipJack({
			eq !? { |e| if (e.meter and: { e.isAlive.not }) { meter.refresh } };
		}, 0.25, { view.isNil or: { view.isClosed } or: { running.not } }, "ParaEQPanel meter", AppClock);
	}

	// the window is closing: this stops listening, and the analyser takes
	// its synth and its buffer with it
	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		classCtrl !? (_.remove);
		classCtrl = nil;
		watcher !? (_.stop);
		watcher = nil;
		spectrum !? (_.free);
		eq !? { eq.meter = false };
		running = false;
	}

	selected { ^eqView.selected }
	selected_ { |i| eqView.selected_(i) }

	// every control the panel draws, for a check or a picture
	widgets {
		^[specSwitch, rangeChoice, bandSelector] ++ cutIns ++ cutFreqs ++ cutSlopes ++ cutRes
			++ bandFreqs ++ bandGains ++ bandWidths
	}

	isClosed { ^view.isNil or: { view.isClosed } }

	// ---------------------------------------------------------------- cards

	// In frequency order, like the axis above them: the low cut, the band
	// shown, the high cut. Each grows to share the width, at most to 1.6
	// times what it needs, so its knobs do not drift apart.
	prControls {
		cutIns = Array.newClear(ParaEQ.numCuts);
		cutFreqs = Array.newClear(ParaEQ.numCuts);
		cutSlopes = Array.newClear(ParaEQ.numCuts);
		cutRes = Array.newClear(ParaEQ.numCuts);
		cutCards = ParaEQ.numCuts.collect { |j| this.prCutCard(j) };
		bandCard = this.prBandCard;
		^HLayout(
			[cutCards[0].view, stretch: 2],
			[bandCard.view, stretch: 3],
			[cutCards[1].view, stretch: 2]
		).margins_(0).spacing_(10)
	}

	// knobs side by side, top aligned, spread when the card is given room
	prRow { |knobs|
		var items = [];
		knobs.do { |k, n|
			items = items.add([k.view, align: \top]);
			if (n < (knobs.size - 1)) { items = items.add(nil) };
		};
		^HLayout(*items).margins_(0).spacing_(6)
	}

	// A cut: its switch on the title line, then where it cuts, how hard, and
	// how its corner rings - in silver, the colour of what an eq takes away.
	prCutCard { |j|
		var i = ParaEQ.numBands + j, col = FxPalette.cut, d = ParaEQ.defaultCuts[j], card;
		cutIns[j] = FxSwitch(["in"], true).action_({ |s| eq !? { eq.cutOn_(i, s.value) } });
		cutFreqs[j] = FxKnob("freq", ControlSpec(ParaEQ.minFreq, ParaEQ.maxFreq, \exp, 0, d[0], "Hz"),
			\medium, false, col).action_({ |k| eq !? { eq.freqAt_(i, k.mapped) } });
		cutSlopes[j] = FxKnob("slope", ControlSpec(0, 1), \medium, false, col)
			.stepNames_(ParaEQ.slopes.collect { |s| "% dB/oct".format(s) })
			.default_(ParaEQ.slopes.indexOf(ParaEQ.defaultSlope))
			.action_({ |k| eq !? { eq.slopeAt_(i, ParaEQ.slopes.clipAt(k.mapped.round.asInteger)) } });
		cutRes[j] = FxKnob("res", ControlSpec(-1, 1, \lin, 0, 0), \small, true, col)
			.box_(\pct).action_({ |k| eq !? { eq.resAt_(i, k.mapped) } });
		cutFreqs[j].hint = "where it starts taking the % away - type Hz or a note: e2 is 41 Hz"
			.format(#["lows", "highs"][j]);
		cutSlopes[j].hint = "how hard it cuts, in dB per octave - a step on a playing sound can click";
		cutRes[j].hint = "how its corner rings: 0 is flat, up rings, down rounds it off";
		[cutFreqs[j], cutSlopes[j], cutRes[j]].do { |k| this.prLinkHover(k, i) };
		card = FxCard(ParaEQ.cutWord(i), this.prRow([cutFreqs[j], cutSlopes[j], cutRes[j]]), col,
			[cutIns[j].view]);
		card.view.maxWidth = (card.view.sizeHint.width * 1.6).ceil;
		^card
	}

	// One band at a time, the way a channel strip shows its selected band:
	// a stack of five, one per band - each in its colour, a bell's width in
	// octaves and a shelf's in percent - and the selector on the title line.
	prBandCard {
		var card;
		bandFreqs = Array.newClear(ParaEQ.numBands);
		bandGains = Array.newClear(ParaEQ.numBands);
		bandWidths = Array.newClear(ParaEQ.numBands);
		bandStack = StackLayout(*ParaEQ.numBands.collect { |i| this.prBandContent(i) });
		bandSelector = FxSwitch((1 .. ParaEQ.numBands).collect(_.asString))
			.itemColours_(ParaEQ.numBands.collect { |i| ParaEQ.handleColour(i) })
			.action_({ |s| eqView.selected = s.value });
		card = FxCard(this.prBandTitle(0), View().layout_(bandStack), ParaEQ.handleColour(0),
			[bandSelector.view]);
		card.view.maxWidth = (card.view.sizeHint.width * 1.6).ceil;
		^card
	}

	prBandContent { |i|
		var col = ParaEQ.handleColour(i), d = ParaEQ.defaultBands[i];
		var size = if (compact) { \medium } { \big };
		bandFreqs[i] = FxKnob("freq", ControlSpec(ParaEQ.minFreq, ParaEQ.maxFreq, \exp, 0, d[0], "Hz"),
			size, false, col).action_({ |k| eq !? { eq.freqAt_(i, k.mapped) } });
		bandGains[i] = FxKnob("gain", ControlSpec(ParaEQ.dbRange.neg, ParaEQ.dbRange, \lin, 0, 0, "dB"),
			size, true, col).dec_(1).action_({ |k| eq !? { eq.dbAt_(i, k.mapped) } });
		bandWidths[i] = if (ParaEQ.isShelf(i)) {
			FxKnob("width", ControlSpec(ParaEQ.qLo(i), ParaEQ.qHi, \exp, 0, 1), \medium, false, col)
				.box_(\pct)
		} {
			FxKnob("width", ControlSpec(0.05, 8, \exp, 0, ParaEQ.bellOctaves(1, d[0]), "oct"),
				\medium, false, col).dec_(2)
		};
		bandWidths[i].action = { |k| eq !? { eq.widthAt_(i, k.mapped) } };
		bandFreqs[i].hint = "where band % sits - type Hz or a note: a5 is 440 Hz".format(i + 1);
		bandGains[i].hint = "how far band % lifts or cuts - double click: 0 dB".format(i + 1);
		bandWidths[i].hint = if (ParaEQ.isShelf(i)) {
			"how gently the shelf rises: 100 % is the steepest with no bump"
		} {
			"how wide the bell is, in octaves - up is wider, as the wheel on its dot"
		};
		[bandFreqs[i], bandGains[i], bandWidths[i]].do { |k| this.prLinkHover(k, i) };
		^View().layout_(this.prRow([bandFreqs[i], bandGains[i], bandWidths[i]]))
	}

	prBandTitle { |i| ^"band % · %".format(i + 1, ParaEQ.shapeWord(i)) }

	// a knob under the pointer rings its dot on the curve
	prLinkHover { |k, i|
		k.view.mouseEnterAction = { eqView.hot = i; nil };
		k.view.mouseLeaveAction = { if (eqView.hot == i) { eqView.hot = nil }; nil };
	}

	// ----------------------------------------------------------- selection

	// The view owns which handle is being worked on - it is the thing drawing
	// the ring - so the cards follow it: a band shows in the band card, a cut
	// lights its own card and leaves the band card where it was.
	prSelected { |i|
		if (ParaEQ.isCut(i).not) { this.prShowBand(i) };
		this.prRefreshSelection;
	}

	prShowBand { |i|
		shownBand = i;
		bandStack.index = i;
		bandSelector.value = i;
		bandCard.title = this.prBandTitle(i);
		bandCard.colour = ParaEQ.handleColour(i);
	}

	// A cut that is selected lights its own card; the band card keeps showing
	// the last band, with no number lit - so clicking that number takes the
	// band back.
	prRefreshSelection {
		var sel = eqView.selected;
		cutCards.do { |c, j|
			var lit = sel == (ParaEQ.numBands + j);
			if (c.colour != (if (lit) { FxPalette.handle } { FxPalette.cut })) {
				c.colour = if (lit) { FxPalette.handle } { FxPalette.cut };
			};
		};
		bandSelector.value = if (ParaEQ.isCut(sel)) { nil } { shownBand };
	}

	prRefreshRange {
		rangeChoice.value = ParaEQView.ranges.indexOf(eqView.range) ? (ParaEQView.ranges.size - 1);
	}

	// a dot under the pointer lights its number in the selector and the
	// names of its knobs
	prHovered { |i|
		bandSelector.hot = if (i.notNil and: { ParaEQ.isCut(i).not }) { i };
		ParaEQ.numBands.do { |b|
			[bandFreqs[b], bandGains[b], bandWidths[b]].do { |k| k.highlight = (b == i) };
		};
		ParaEQ.numCuts.do { |j|
			[cutFreqs[j], cutSlopes[j], cutRes[j]].do { |k| k.highlight = ((ParaEQ.numBands + j) == i) };
		};
	}

	// a right click on a dot asked for a frequency: the knob's own field
	prTypeFreq { |i|
		if (ParaEQ.isCut(i)) {
			cutFreqs[i - ParaEQ.numBands].prEdit
		} {
			this.prShowBand(i);
			bandFreqs[i].prEdit
		};
	}

	// ---------------------------------------------------------------- hints

	// A hint bar - see FxHintBar - to label every control. A panel without
	// one simply never gets one. The display writes to it itself: a bar's
	// attach would take over the pointer it follows.
	hints_ { |bar|
		hints = bar;
		eqView.hints = bar;
		if (bar.isNil) { ^this };
		header.hints = bar;
		// one line each: the footer also says where the eq runs
		bar.attach(header.power.view, if (subject == "this eq") {
			"switch this eq on - off, it costs nothing"
		} {
			"switch the eq into %'s chain - off, it costs nothing".format(subject)
		});
		bar.attach(specSwitch.view, {
			if (eq.notNil and: { eq.analysisAvailable }) {
				"the spectrum of what leaves the eq, behind the curve - one switch for every eq window"
			} {
				"nothing to analyse: % has no node, chain or proxy bus - a plain synth needs analysisBus"
					.format(subject)
			}
		});
		bar.attach(rangeChoice.view,
			"how many dB the display shows either side of 0 - only the view, the bands keep their gains");
		bar.attach(meter.view, {
			if (eq.notNil and: { eq.meter } and: { eq.isDriving } and: { eq.hasReplied.not }) {
				"no levels come back: a graph of your own needs ParaEQ.ar(sig, \\%, meter: true)"
					.format(eq.controlName)
			} {
				"in and out of the eq, the peaks held as numbers - click to clear them and the clip lights"
			}
		});
		bar.attach(bandSelector.view, "which band the card shows, each in its colour - or click its dot");
		cutIns.do { |s, j|
			bar.attach(s.view, if (j == 0) {
				"low cut - a high pass: it takes the lows away - out, it costs nothing"
			} {
				"high cut - a low pass: it takes the highs away - out, it costs nothing"
			});
		};
		(bandFreqs ++ bandGains ++ bandWidths ++ cutFreqs ++ cutSlopes ++ cutRes).do { |k|
			k.hints = bar;
			bar.attach(k.view, { "%  %   -   %".format(k.label, k.text, this.prWhyDim(k) ? k.hint) });
		};
		^this
	}

	hints { ^hints }

	// what a dimmed knob is waiting for, in place of its hint; nil if it is lit
	prWhyDim { |k|
		if (k.inactive.not or: { eq.isNil }) { ^nil };
		^case
		{ bandFreqs.includes(k) or: { bandWidths.includes(k) } } {
			"at 0 dB a band does nothing: lift or cut it first"
		}
		{ cutRes.includes(k) and: { eq.cutOn(ParaEQ.numBands + cutRes.indexOf(k)) } } {
			"at 6 dB/oct there is no corner to ring"
		}
		{ true } { "the cut is out: switch it in" }
	}

	// -------------------------------------------------------------- refresh

	refresh {
		var e = eq, off;
		if (this.isClosed) { ^this };
		header.refresh;
		specSwitch.value = ParaEQSpectrum.on;
		specSwitch.inactive = e.isNil or: { e.analysisAvailable.not };
		this.prRefreshRange;
		meter.view.visible = e.notNil and: { e.canMeter };
		if (e.isNil) {
			this.widgets.do { |w| w.tryPerform(\inactive_, true) };
			eqView.refresh;
			^this
		};
		off = e.enabled.not;
		this.widgets.do { |w| w.muted = off; w.inactive = false };
		specSwitch.inactive = e.analysisAvailable.not;
		ParaEQ.numBands.do { |i|
			var flat = e.dbAt(i).abs < 0.05;
			this.prSet(bandFreqs[i], e.freqAt(i));
			this.prSet(bandGains[i], e.dbAt(i));
			this.prSet(bandWidths[i], e.widthAt(i));
			// a double click puts the width back to where the band starts - rq
			// or rs 1 - which for a bell is a little narrower as it rises
			if (ParaEQ.isShelf(i).not) {
				bandWidths[i].default = ParaEQ.bellOctaves(1, e.freqAt(i), e.sampleRate);
			};
			bandFreqs[i].inactive = flat;
			bandWidths[i].inactive = flat;
		};
		ParaEQ.numCuts.do { |j|
			var i = ParaEQ.numBands + j, in = e.cutOn(i), slope = e.slopeAt(i);
			cutIns[j].value = in;
			this.prSet(cutFreqs[j], e.freqAt(i));
			this.prSet(cutSlopes[j], ParaEQ.slopes.indexOf(slope) ? 1);
			this.prSet(cutRes[j], e.resAt(i));
			cutFreqs[j].inactive = in.not;
			cutSlopes[j].inactive = in.not;
			cutRes[j].inactive = in.not or: { slope <= ParaEQ.slopeLo };
		};
		this.prRefreshSelection;
		eqView.refresh;
		meter.refresh;
	}

	// a knob told only when it would move, so a drag on the curve repaints
	// the one that changed rather than all of them
	prSet { |k, v|
		var u;
		if (v.isNil) { ^this };
		u = k.spec.unmap(k.spec.constrain(v)).clip(0, 1);
		if ((k.value - u).abs > 1e-6) { k.value = u };
	}
}


// =====================================================================
// ParaEQGUI - one eq in a window of its own
//
//   e = ParaEQ.onBus(0, 2);
//   e.gui;                      // or ParaEQGUI(e)
//
// The panel above in FxDeviceGUI, the window every effect has: the hint at
// the left of one line along the bottom, where the eq runs at its right.
// Closing the window stops the analyser and the meter; the eq itself is
// untouched.
// =====================================================================

ParaEQGUI : FxDeviceGUI {
	eq { ^unit }
}
