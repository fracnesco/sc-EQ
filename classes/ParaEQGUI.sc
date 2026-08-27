// =====================================================================
// ParaEQPanel - the eq's controls as one view; ParaEQGUI - as a window
//
//   [x] on  [x] spectrum  preset [warm v] [flat]  [name   ][save][delete]   [copy][paste]
//   +------------------------------------------------------------------------+
//   |  the curve, five handles, the analysis behind them                     |
//   +------------------------------------------------------------------------+
//   [low shelf][peak 1][peak 2][peak 3][high shelf]  freq [ 80] dB [-6.0] q [0.80]
//
// The panel is what every window that shows an eq is built from: the
// eq's own window, the compressor's sidechain page, the master chain in
// ServerView, the module family's eq tab. It follows one ParaEQ, and can
// be pointed at another - `panel.eq = otherEq` - which is how a module
// window shows the eq of whichever pad is selected.
//
//   panel = ParaEQPanel(eq);
//   window.layout = VLayout(panel.view, hints.view);
//   panel.hints = hints;          // labels every control
//   panel.running = true;         // the analysis, while the panel is showing
//   ...
//   panel.remove;                 // in window.onClose
//
// ParaEQGUI is the same in a window of its own, with a hint bar under it:
// what `eq.gui` opens.
// =====================================================================

ParaEQPanel {
	var <eq, <view, <eqView, <spectrum;
	var <>subject;				// what the hints call the thing being shaped
	var onBox, specBox, presetMenu, bandBtns, freqBox, dbBox, qBox;
	var flatBtn, nameField, saveBtn, delBtn, copyBtn, pasteBtn, helpText;
	var ctrl, classCtrl, hints, showOnBox;

	// `subject` is what the hints call the thing being shaped - "this pad",
	// "the sidechain" - and `showOnBox: false` leaves the on switch out,
	// for a window that has one of its own.
	*new { |eq, parent, subject, showOnBox = true|
		^super.new.init(eq, parent, subject, showOnBox)
	}

	init { |argEq, parent, argSubject, argShowOnBox|
		subject = argSubject ? "this eq";
		showOnBox = argShowOnBox ? true;

		eqView = ParaEQView(nil);
		eqView.view.minHeight_(150);
		eqView.action = { |v, what| this.prSelectBand(v.selected) };

		// The analysis behind the curve. It runs only while `running` is
		// set - a window sets it when the panel is showing - so a panel
		// sitting on a hidden page costs nothing for it.
		spectrum = ParaEQSpectrum(nil);
		spectrum.action = { { eqView.refresh }.defer };
		eqView.spectrum = spectrum;

		view = View(parent).layout_(
			VLayout(this.prTopRow, [eqView.view, \stretch, 1], this.prBandRow)
				.margins_(0).spacing_(6));

		// The presets and the clipboard belong to the class, so a panel
		// that did not make the change still has to hear about it.
		classCtrl = SimpleController(ParaEQ)
			.put(\presets, { { this.refresh }.defer })
			.put(\clipboard, { { this.refresh }.defer });

		this.eq_(argEq);
		^this
	}

	// Which eq the panel is showing. The view, the analyser and the boxes
	// all follow it; a nil leaves an empty panel.
	eq_ { |anEq|
		if (anEq === eq) { ^this.refresh };
		ctrl !? (_.remove);
		ctrl = nil;
		eq = anEq;
		eqView.eq_(eq);
		spectrum.eq_(eq);
		eq !? {
			ctrl = SimpleController(eq)
				.put(\bands, { { this.refresh }.defer })
				.put(\settings, { { this.refresh }.defer })
				.put(\enabled, { { this.refresh }.defer })
				.put(\node, { { spectrum.prSync }.defer })
				.put(\freed, { { this.eq_(nil) }.defer });
		};
		this.refresh;
	}

	running_ { |bool| spectrum.running = bool }
	running { ^spectrum.running }

	// the window is closing: this stops listening, and the analyser takes
	// its synth and its buffer with it
	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		classCtrl !? (_.remove);
		classCtrl = nil;
		spectrum !? (_.free);
	}

	selected { ^eqView.selected }
	selected_ { |i| eqView.selected_(i) }

	// ---------------------------------------------------------------- hints

	// A hint bar - see FxHintBar - to label every control. A panel without
	// one simply never gets one.
	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		bar.attach(onBox, if (subject == "this eq") {
			"switch this eq on - off, it costs nothing"
		} {
			"switch the eq into %'s chain - off, it costs nothing".format(subject)
		});
		bar.attach(specBox,
			"draw what % sounds like behind the curve, while the eq is on"
				.format(subject));
		bar.attach(presetMenu,
			"ready made curves, then any you have saved - reads (custom) once"
			" you move a band");
		bar.attach(flatBtn, "every band back to 0 dB");
		bar.attach(nameField, "a name to keep the current curve under");
		bar.attach(saveBtn,
			"keep this curve under that name - it joins the menu, in this session"
			" and the next");
		bar.attach(delBtn, "drop the curve now selected, if it is one of yours");
		bar.attach(copyBtn,
			"put this curve aside - it can be pasted onto any eq anywhere");
		bar.attach(pasteBtn,
			"take the copied curve onto this eq - only the curve moves, not"
			" whether it is on");
		bar.attach(eqView.view,
			"drag a dot for frequency and gain, cmd-drag or the wheel for width,"
			" shift to snap, double click for 0 dB - the shape behind is the"
			" sound itself");
		bandBtns.do { |b, i|
			bar.attach(b, "work on the " ++ ParaEQ.bandName(i));
		};
		bar.attach(freqBox, { "where the % sits, in Hz"
			.format(ParaEQ.bandName(eqView.selected)) });
		bar.attach(dbBox, { "how far the % is lifted or cut, in dB"
			.format(ParaEQ.bandName(eqView.selected)) });
		bar.attach(qBox, { "how wide the % is - smaller is narrower"
			.format(ParaEQ.bandName(eqView.selected)) });
		bar.attach(helpText, "the mouse on the curve, in short");
		^this
	}

	hints { ^hints }

	// ----------------------------------------------------------------- rows

	prTopRow {
		onBox = CheckBox().string_("eq on")
			.action_({ |v| eq !? (_.enabled_(v.value)) });
		onBox.visible = showOnBox;
		// One switch for every window and every session: the analysis is
		// cheap but it is not free, and a machine that is working hard can
		// have it back.
		specBox = CheckBox().string_("spectrum")
			.action_({ |v|
				ParaEQSpectrum.on = v.value;
				spectrum.prSync;
				eqView.refresh;
			});
		presetMenu = PopUpMenu().fixedWidth_(104)
			.items_(ParaEQ.presetNames.collect(_.asString) ++ ["(custom)"])
			.action_({ |v|
				var names = ParaEQ.presetNames;
				if (v.value < names.size) { eq !? (_.preset_(names[v.value])) };
			});

		// A curve worth keeping gets a name and joins the menu, in this
		// session and the next - see ParaEQ:addPreset.
		nameField = TextField().fixedWidth_(92).string_("");
		nameField.action = { this.prSavePreset };
		saveBtn = FxPalette.button("save", 42, { this.prSavePreset });
		delBtn = FxPalette.button("delete", 50, {
			var name = eq !? (_.presetName);
			name !? { ParaEQ.removePreset(name) };
		});

		// and one curve can be carried to another eq, anywhere
		copyBtn = FxPalette.button("copy", 44, { eq !? (_.copyBands) });
		pasteBtn = FxPalette.button("paste", 46, { eq !? (_.pasteBands) });

		flatBtn = FxPalette.button("flat", 40, { eq !? (_.flat) });

		^HLayout(
			onBox, specBox,
			FxPalette.label("preset", 40), presetMenu,
			flatBtn,
			nameField, saveBtn, delBtn,
			nil,
			copyBtn, pasteBtn
		).spacing_(6)
	}

	// The name box first, then the preset the eq is already on, so `save`
	// with an empty box means "keep this one as it now is".
	prSavePreset {
		var typed = nameField.string.stripWhiteSpace;
		var name = if (typed.isEmpty) { eq !? (_.presetName) } { typed };
		if (eq.isNil) { ^this };
		if (name.isNil) {
			"ParaEQ: type a name to keep this curve under".warn;
			^this
		};
		if (eq.saveAsPreset(name).notNil) { nameField.string = "" };
	}

	prBandRow {
		bandBtns = ParaEQ.numBands.collect { |i|
			Button().fixedWidth_(66).fixedHeight_(19).canFocus_(false)
				.states_([[ParaEQ.bandName(i), FxPalette.dim, FxPalette.panel]])
				.action_({ this.prSelectBand(i) })
		};
		freqBox = NumberBox().fixedWidth_(58).decimals_(0)
			.clipLo_(ParaEQ.minFreq).clipHi_(ParaEQ.maxFreq)
			.action_({ |v| eq !? (_.freqAt_(eqView.selected, v.value)) });
		dbBox = NumberBox().fixedWidth_(48).decimals_(1)
			.clipLo_(ParaEQ.dbRange.neg).clipHi_(ParaEQ.dbRange)
			.action_({ |v| eq !? (_.dbAt_(eqView.selected, v.value)) });
		qBox = NumberBox().fixedWidth_(48).decimals_(2)
			.clipLo_(ParaEQ.qLo(0)).clipHi_(ParaEQ.qHi)
			.action_({ |v| eq !? (_.qAt_(eqView.selected, v.value)) });
		helpText = StaticText()
			.string_("drag a dot: freq and gain   cmd-drag or wheel: width"
				"   shift: snap   double click: 0 dB")
			.stringColor_(FxPalette.dim).font_(FxPalette.smallFont)
			.align_(\right).minWidth_(330);

		^HLayout(*(bandBtns ++ [
			FxPalette.label("freq", 30), freqBox,
			FxPalette.label("dB", 20), dbBox,
			FxPalette.label("q", 14), qBox,
			nil,
			helpText
		])).spacing_(4)
	}

	// The view owns which band is being worked on - it is the thing drawing
	// the highlight - so this hands it over and then reads it back like
	// everything else does.
	prSelectBand { |i|
		eqView.selected_(i);
		this.refresh;
	}

	// -------------------------------------------------------------- refresh

	refresh {
		var e = eq, i = eqView.selected;
		var names = ParaEQ.presetNames;
		var items = names.collect(_.asString) ++ ["(custom)"];
		var pre;
		if (view.isNil or: { view.isClosed }) { ^this };
		// the list grows and shrinks as presets are kept and dropped
		if (presetMenu.items != items) { presetMenu.items = items };
		specBox.value = ParaEQSpectrum.on;
		pasteBtn.enabled = ParaEQ.hasClipboard;
		if (e.isNil) {
			onBox.value = false;
			delBtn.enabled = false;
			eqView.refresh;
			^this
		};
		pre = e.presetName;
		onBox.value = e.enabled;
		// the bands are a preset until you move one, and then they are yours
		presetMenu.value = (pre !? { names.indexOf(pre) }) ? names.size;
		// only a preset of your own can be dropped
		delBtn.enabled = pre.notNil and: { ParaEQ.isUserPreset(pre) };
		freqBox.value = e.freqAt(i).round(1);
		dbBox.value = e.dbAt(i).round(0.1);
		qBox.clipLo = ParaEQ.qLo(i);		// a shelf will not go as narrow
		qBox.value = e.qAt(i).round(0.01);
		bandBtns.do { |b, j|
			var on = (j == i);
			b.states_([[
				ParaEQ.bandName(j),
				if (on) { FxPalette.bg } { FxPalette.eqBand(j) },
				if (on) { FxPalette.eqBand(j) } { FxPalette.panel }
			]]);
		};
		eqView.refresh;
	}
}


// =====================================================================
// ParaEQGUI - one eq in a window of its own
//
//   e = ParaEQ.onBus(0, 2);
//   e.gui;                      // or ParaEQGUI(e)
//
// The panel above, a status line, and a hint bar. Closing the window
// stops the analyser; the eq itself is untouched.
// =====================================================================

ParaEQGUI {
	var <eq, <window, <panel, <hints;
	var statusText, ctrl;

	*new { |eq, x, y| ^super.new.init(eq, x, y) }

	init { |argEq, x, y|
		eq = argEq;
		window = Window("eq · " ++ eq.label, Rect(x ? 100, y ? 100, 760, 300));
		FxPalette.darken(window);

		panel = ParaEQPanel(eq);
		hints = FxHintBar();
		statusText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim);

		window.layout = VLayout(
			[panel.view, stretch: 1],
			HLayout(statusText, nil, hints.toggle).spacing_(4),
			hints.view
		).margins_(8).spacing_(6);

		hints.watch(window);
		panel.hints = hints;
		hints.attach(statusText, "where this eq is running");

		ctrl = SimpleController(eq)
			.put(\enabled, { { this.prRefreshStatus }.defer })
			.put(\node, { { this.prRefreshStatus }.defer })
			.put(\label, { { this.prRefreshStatus }.defer })
			.put(\freed, { { this.close }.defer });

		window.onClose = {
			ctrl.remove;
			panel.remove;
			eq.prGuiClosed;
		};
		panel.running = true;
		this.prRefreshStatus;
		window.front;
		^this
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }

	prRefreshStatus {
		var parts = [];
		if (window.isClosed) { ^this };
		window.name = "eq · " ++ eq.label;
		case
		{ eq.chain.notNil } { parts = parts.add("in " ++ eq.chain.name) }
		{ eq.isDriving } { parts = parts.add("driving " ++ eq.target.asString) }
		{ eq.isOnBus } { parts = parts.add("bus " ++ eq.bus) }
		{ true } { parts = parts.add("not running anywhere yet") };
		eq.numChannels !? { |n| parts = parts.add(n.asString ++ " ch") };
		parts = parts.add(if (eq.enabled) { "on" } { "off" });
		if (eq.isOnBus and: { eq.enabled } and: { eq.synth.isNil }) {
			parts = parts.add("waiting for the server");
		};
		statusText.string = parts.join(" · ");
	}
}
