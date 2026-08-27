// =====================================================================
// FxChainPanel - a chain's effects, stacked; FxChainGUI - in a window
//
//   [x] chain on   master_localhost  preset [tonight v] [save] [load]        [+ eq] [+ compressor]
//   -- eq -------------------------------------------------- [up][down][x]
//   |  the eq panel                                                        |
//   -- compressor ------------------------------------------ [up][down][x]
//   |  the compressor panel                                                |
//
// One strip per effect - its name, and buttons to move or drop it -
// over the effect's own panel, which is where all of its controls are.
// The panel follows the chain: an effect added from code turns up, one
// removed goes away. ServerView embeds this under its counters; `gui`
// opens it in a window with a hint bar.
//
//   panel = FxChainPanel(chain);
//   window.layout = VLayout(panel.view, hints.view);
//   panel.hints = hints;
//   panel.running = true;          // the analysers, while it is showing
//   panel.remove;                  // in window.onClose
// =====================================================================

FxChainPanel {
	var <chain, <view, <panels;
	var onBox, nameText, presetMenu, saveBtn, loadBtn, addBtns, unitsView;
	var strips, ctrl, hints, running = false, presetPaths;

	*new { |chain, parent| ^super.new.init(chain, parent) }

	init { |argChain, parent|
		chain = argChain;
		panels = [];
		strips = [];
		unitsView = View().layout_(VLayout().margins_(0).spacing_(4));
		view = View(parent).layout_(
			VLayout(this.prHeaderRow, unitsView).margins_(0).spacing_(6));
		ctrl = SimpleController(chain)
			.put(\units, { { this.prRebuild }.defer })
			.put(\enabled, { { this.refresh }.defer })
			.put(\presets, { { this.refresh }.defer })
			.put(\freed, { { this.prRebuild }.defer });
		this.prRebuild;
		^this
	}

	remove {
		ctrl !? (_.remove);
		ctrl = nil;
		panels.do(_.remove);
		panels = [];
	}

	// the analysers of every effect that has one, on while the panel shows
	running_ { |bool|
		running = bool;
		panels.do { |p| p.tryPerform(\running_, bool) };
	}

	running { ^running }

	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		bar.attach(onBox,
			"the whole chain in or out - out, every effect's node is faded away"
			" and the bus is left alone");
		bar.attach(nameText, "this chain: its name, its bus, its channels");
		bar.attach(presetMenu, "the chain presets saved so far, newest first - pick one to load it");
		bar.attach(saveBtn, "write every effect's settings to one preset file");
		bar.attach(loadBtn, "read a chain preset back in, effect by effect");
		addBtns.do { |b|
			bar.attach(b, "another effect at the end of the chain");
		};
		panels.do { |p| p.hints = bar };
		strips.do { |s|
			bar.attach(s[1], "this effect one place earlier in the chain");
			bar.attach(s[2], "this effect one place later in the chain");
			bar.attach(s[3], "take this effect out of the chain");
		};
		^this
	}

	hints { ^hints }

	// --------------------------------------------------------------- rows

	prHeaderRow {
		var items;
		onBox = CheckBox().string_("chain on")
			.action_({ |v| chain.enabled_(v.value) });
		nameText = StaticText().font_(FxPalette.boldFont).stringColor_(FxPalette.accent);
		// index 0 is the menu's own label, so it can sit there between choices
		presetMenu = PopUpMenu().fixedWidth_(110)
			.action_({ |v|
				var i = v.value ? 0;
				var path = if (i > 0) { presetPaths[i - 1] } { nil };
				v.value = 0;
				if (path.notNil) { chain.loadPreset(path) };
			});
		saveBtn = FxPalette.button("save", 44, {
			Dialog.savePanel({ |p| chain.savePreset(p) }, path: FxChain.ensurePresetDir);
		});
		loadBtn = FxPalette.button("load", 44, {
			Dialog.openPanel({ |p| chain.loadPreset(p) }, path: FxChain.ensurePresetDir);
		});
		// one button per effect class that is installed - the compressor
		// is looked up by name, so this compiles without it
		addBtns = [[\ParaEQ, "+ eq"], [\Compressor, "+ compressor"]].collect { |pair|
			pair[0].asClass !? { |cls|
				FxPalette.button(pair[1], nil, { chain.add(cls.new) })
			}
		}.reject(_.isNil);

		items = [onBox, nameText, FxPalette.label("preset", 40), presetMenu,
			saveBtn, loadBtn, nil] ++ addBtns;
		^HLayout(*items).spacing_(6)
	}

	// one strip per effect, and its panel under it
	prRebuild {
		if (view.isNil or: { view.isClosed }) { ^this };
		panels.do(_.remove);
		strips.do { |s| s[0].remove };
		panels = [];
		strips = [];
		chain.units.do { |unit, i|
			var head, up, down, drop, strip, panel;
			head = StaticText().string_(unit.label).font_(FxPalette.boldFont)
				.stringColor_(FxPalette.text);
			up = FxPalette.button("up", 34, { chain.moveUnit(i, i - 1) });
			down = FxPalette.button("down", 44, { chain.moveUnit(i, i + 1) });
			drop = FxPalette.button("x", 22, { chain.remove(unit) });
			up.enabled = i > 0;
			down.enabled = i < (chain.units.size - 1);
			panel = unit.panel;
			panel.tryPerform(\running_, running);
			strip = View().layout_(
				VLayout(
					HLayout(head, nil, up, down, drop).spacing_(4),
					panel.view
				).margins_(0).spacing_(4)
			);
			unitsView.layout.add(strip);
			panels = panels.add(panel);
			strips = strips.add([strip, up, down, drop]);
		};
		hints !? { this.hints_(hints) };
		this.refresh;
	}

	refresh {
		if (view.isNil or: { view.isClosed }) { ^this };
		onBox.value = chain.enabled;
		nameText.string = "%   bus %   % ch".format(chain.name, chain.bus, chain.numChannels);
		presetPaths = FxChain.presets.keep(12);
		presetMenu.items = ["presets"] ++ presetPaths.collect { |p|
			PathName(p).fileNameWithoutExtension };
		presetMenu.value = 0;
		presetMenu.toolTip = if (presetPaths.isEmpty) {
			"no chain presets yet - save one and it appears here"
		} {
			"most recent first:\n" ++ presetPaths.join("\n")
		};
	}
}


// =====================================================================
// FxChainGUI - a chain in a window of its own: what `chain.gui` opens
// =====================================================================

FxChainGUI {
	var <chain, <window, <panel, <hints;
	var ctrl;

	*new { |chain, x, y| ^super.new.init(chain, x, y) }

	init { |argChain, x, y|
		chain = argChain;
		window = Window("fx · " ++ chain.name, Rect(x ? 100, y ? 100, 780, 640));
		FxPalette.darken(window);

		panel = FxChainPanel(chain);
		hints = FxHintBar();

		window.layout = VLayout(
			[panel.view, stretch: 1],
			HLayout(nil, hints.toggle).spacing_(4),
			hints.view
		).margins_(8).spacing_(6);

		hints.watch(window);
		panel.hints = hints;

		ctrl = SimpleController(chain)
			.put(\freed, { { this.close }.defer });

		window.onClose = {
			ctrl.remove;
			panel.remove;
			chain.prGuiClosed;
		};
		panel.running = true;
		window.front;
		^this
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }
}
