// =====================================================================
// FxStrip - a chain of effects as a strip of small cards, left to right
//
//   strip = FxStrip(chain);            // any FxChain
//   layout: ..., strip.view            // 176 high, as wide as it is given
//   strip.chain = anotherChain;        // the same strip on another chain
//   strip.running = true;              // the cards follow their effects
//   strip.remove;                      // in window.onClose
//
// The sound goes through the cards in the order they stand. A card is its
// effect at a glance - on or off, its name in its colour, the preset, a small
// picture of what it is doing, and the few knobs that say most about it (the
// `hero` ones of its *mapParams). Everything else is in the effect's own
// window, one click away on the arrow. The dotted card at the end adds an
// effect; a card's ... menu moves it, takes it out, or picks a preset.
//
// FxDeviceCard is one card; FxMapControls makes a widget for any parameter
// listed the *mapParams way - an effect's, a sampler pad's - so the knobs of
// a card and the cards of a module's window are made by the same code, and
// know which parameter they are (FxMapHooks).
// =====================================================================

FxMapControls {
	// A widget for one parameter of something that answers mapGet and mapSet.
	// `e` is one entry of its *mapParams; `thingFunc` answers the thing, so the
	// widget follows whatever it stands for now (the selected pad). Answers an
	// Event: (widget:, refresh: { }), refresh putting the thing's value on it.
	*build { |e, thingFunc, size = \medium, colour|
		var key = e[\key], spec = e[\spec], w, refresh, label = e[\label] ? key.asString;
		var set = { |v| thingFunc.value !? { |t| t.mapSet(key, v) } };
		var get = { thingFunc.value !? { |t| t.mapGet(key) } };
		switch (e[\kind] ? \knob,
			\toggle, {
				w = FxSwitch([label], true).action_({ |s| set.(s.value.binaryValue) });
				refresh = { get.value !? { |v| w.value = v > 0.5 } };
			},
			\trigger, {
				w = FxButton(label, { set.(1) });
				refresh = { };
			},
			\menu, {
				if (e[\items].size <= 4) {
					w = FxSwitch(e[\items].collect { |x| this.itemLabel(x) }, false, nil, label)
						.action_({ |s| set.(s.value) });
				} {
					w = FxChoice(label, e[\items].collect { |x| this.itemLabel(x) })
						.action_({ |c| set.(c.value) });
				};
				refresh = { get.value !? { |v| w.value = v.round.asInteger } };
			},
			{
				w = FxKnob(label, spec, size, spec.minval == spec.maxval.neg, colour);
				w.box = e[\box] ?? { if (spec.minval == 0 and: { spec.maxval == 1 } and: { (spec.units ? "").isEmpty }) { \pct } };
				w.dec = e[\dec];
				w.default = spec.default;
				w.hint = e[\hint] ? "";
				w.action = { |k| set.(k.mapped) };
				refresh = { get.value !? { |v| if (v.isNumber) { w.mapped = v } } };
			}
		);
		w.mapUnitFunc = thingFunc;
		w.mapKey = key;
		^(widget: w, refresh: refresh, key: key, kind: e[\kind] ? \knob)
	}

	// oneShot -> "one shot"
	*itemLabel { |x|
		var str = x.asString, out = "";
		str.do { |c| if (c.isUpper) { out = out ++ " " ++ c.toLower } { out = out ++ c } };
		^out
	}
}


FxDeviceCard {
	classvar <>knobCount = 4;		// how many hero knobs a card shows at most

	var <unit, <chain, <strip, <view;
	var power, nameText, presetText, openBtn, moreBtn, dropBtn, thumb, controls;

	asView { ^view }

	*new { |unit, chain, strip| ^super.newCopyArgs(unit, chain, strip).init }

	*colourOf { |unit| ^unit.class.tryPerform(\identityColour) ? FxPalette.wave }

	*titleOf { |unit| ^(unit.class.tryPerform(\title) ? unit.class.name).asString }

	init {
		var colour = this.class.colourOf(unit), heroes, width;
		heroes = (unit.class.tryPerform(\mapParams) ? []).select { |e| e[\hero] == true and: { (e[\kind] ? \knob) == \knob } }
			.keep(knobCount);
		// two knobs of one name (a drive per stage) are told apart by their keys
		heroes = heroes.collect { |e|
			if (heroes.count { |x| x[\label] == e[\label] } > 1) {
				e.copy.put(\label, FxMapControls.itemLabel(e[\key]).replace("1", " 1").replace("2", " 2").replace("3", " 3").replace("4", " 4"))
			} { e }
		};
		controls = heroes.collect { |e| FxMapControls.build(e, { unit }, \small, colour) };
		width = (heroes.size.max(3) * FxKnob.cellWidth(\small)) + 16;
		power = FxPower().action_({ |p| unit.enabled = p.value });
		nameText = StaticText().string_(this.class.titleOf(unit)).font_(FxPalette.font).stringColor_(colour);
		presetText = StaticText().font_(FxPalette.smallFont).stringColor_(FxPalette.dim);
		openBtn = FxButton("↗", { unit.gui }, 20);
		moreBtn = FxButton("...", { this.prMenu }, 24);
		// out of the chain in one click: the effect and its settings go
		dropBtn = FxButton("x", { chain.remove(unit) }, 20);
		thumb = UserView().fixedHeight_(46);
		thumb.drawFunc = { |v| this.prDrawThumb(v) };
		thumb.mouseDownAction = { |v, x, y, mod, button, count| if (count == 2) { unit.gui }; true };
		view = UserView().fixedWidth_(width);
		view.drawFunc = { |v|
			Pen.fillColor = FxPalette.card;
			Pen.addRoundedRect(v.bounds.moveTo(0, 0), 4, 4);
			Pen.fill;
			Pen.fillColor = FxPalette.fade(colour, if (unit.enabled) { 1 } { 0.35 });
			Pen.addRoundedRect(Rect(0, 0, 3, v.bounds.height), 1.5, 1.5);
			Pen.fill;
		};
		view.layout = VLayout(
			HLayout(power.view, nameText, [presetText, stretch: 1], openBtn.view, moreBtn.view, dropBtn.view)
				.margins_(0).spacing_(4),
			thumb,
			HLayout(*(controls.collect { |c| c[\widget].view } ++ [nil])).margins_(0).spacing_(0),
			nil
		).margins_([8, 5, 6, 4]).spacing_(4);
		strip.hints !? { |h| this.hints_(h) };
		this.refresh;
	}

	hints_ { |bar|
		var name = this.class.titleOf(unit);
		bar.attach(power.view, "% in or out of the chain - out, it costs nothing".format(name));
		bar.attach(openBtn.view, "open %'s own window: everything it has".format(name));
		bar.attach(moreBtn.view, "presets, move it earlier or later in the chain, take it out");
		bar.attach(dropBtn.view, "take % out of the chain".format(name));
		bar.attach(thumb, "what % is doing now - double click for its window".format(name));
		controls.do { |c|
			var k = c[\widget];
			k.hints = bar;
			bar.attach(k.view, { "% %  %".format(name, k.label, k.text) });
		};
	}

	// the effect's state on the card
	refresh {
		var on = unit.enabled, pre;
		if (view.isClosed) { ^this };
		power.value = on;
		pre = unit.tryPerform(\presetName);
		presetText.string = (pre ? "").asString;
		controls.do { |c| c[\refresh].value; c[\widget].muted = on.not };
		thumb.refresh;
		view.refresh;
	}

	prMenu {
		var i = chain.units.indexOf(unit), names = unit.class.tryPerform(\presetNames) ? [];
		var presets = names.collect { |n| MenuAction(n.asString, { unit.preset = n; this.refresh }) };
		Menu(*([
			MenuAction("open its window", { unit.gui }),
			MenuAction.separator,
			MenuAction("earlier in the chain", { chain.moveUnit(i, i - 1) }).enabled_(i.notNil and: { i > 0 }),
			MenuAction("later in the chain", { chain.moveUnit(i, i + 1) }).enabled_(i.notNil and: { i < (chain.size - 1) }),
			MenuAction("take it out", { chain.remove(unit) }),
			MenuAction.separator
		] ++ if (presets.isEmpty) { [MenuAction("no presets").enabled_(false)] } {
			[Menu(*presets).title_("preset")]
		})).front;
	}

	// ------------------------------------------------------------- the picture

	// An effect's class may draw its own: *drawThumb { |unit, rect| }, with Pen,
	// inside rect. Without one, the card draws what it can tell from the
	// parameters the effect has.
	prDrawThumb { |v|
		var r = v.bounds.moveTo(0, 0), colour = FxPalette.fade(this.class.colourOf(unit), if (unit.enabled) { 1 } { 0.4 });
		Pen.fillColor = FxPalette.waveBg;
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.5);
		Pen.fillRect(Rect(r.left, r.center.y, r.width, 1));
		if (unit.class.respondsTo(\drawThumb)) {
			unit.class.drawThumb(unit, r.insetBy(4, 3), colour);
		} {
			this.class.drawDefaultThumb(unit, r.insetBy(4, 3), colour);
		};
	}

	*drawDefaultThumb { |unit, r, colour|
		var keys = unit.class.tryPerform(\mapKeys) ? [], get = { |k| unit.mapGet(k) }, norm;
		norm = { |k| var e = unit.class.mapParam(k); e[\spec].unmap(get.(k) ? e[\spec].default) };
		Pen.strokeColor = colour; Pen.fillColor = colour; Pen.width = 1.5;
		case
		// an eq: its curve, 20 Hz to 20 kHz, +-24 dB
		{ unit.respondsTo(\responseAt) } {
			// responseAt answers one curve per handle: their sum is what is heard
			var n = 48, freqs = n.collect { |i| 20 * (1000 ** (i / (n - 1))) }, dbs = unit.responseAt(freqs).sum;
			dbs.do { |db, i|
				var p = (r.left + (i / (n - 1) * r.width)) @ (r.center.y - (db.clip(-24, 24) / 24 * r.height * 0.5));
				if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
		}
		// a compressor: its static curve, -60 to 0 dB in, the same out
		{ unit.respondsTo(\staticGain) } {
			var n = 24;
			n.do { |i|
				var in = (i / (n - 1)).linlin(0, 1, -60, 0), out = (in + unit.staticGain(in)).clip(-60, 6);
				var p = (r.left + (i / (n - 1) * r.width)) @ (r.bottom - (out.linlin(-60, 6, 0, 1) * r.height));
				if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
		}
		// something that decays: a tail as long as its decay
		{ keys.includes(\decay) } {
			var d = norm.(\decay).linlin(0, 1, 0.25, 1);
			Pen.fillColor = FxPalette.fade(colour, 0.4);
			Pen.moveTo(r.left @ r.bottom);
			Pen.lineTo(r.left @ r.top);
			24.do { |i| var t = i / 23; Pen.lineTo((r.left + (t * r.width * d)) @ (r.top + ((1 - exp(t.neg * 3.5)) * r.height))) };
			Pen.lineTo((r.left + (r.width * d)) @ r.bottom);
			Pen.fill;
		}
		// something that repeats: bars falling by its feedback
		{ keys.includes(\feedback) and: { keys.includes(\timeL) or: { keys.includes(\time) } or: { keys.includes(\delay) } } } {
			var fb = norm.(\feedback).linlin(0, 1, 0.3, 0.92), n = 7;
			n.do { |i|
				var h = r.height * (fb ** i);
				Pen.fillRect(Rect(r.left + 2 + (i * (r.width - 4) / n), r.bottom - h, 4, h));
			};
		}
		// something that drives: a curve that bends harder with its drive
		{ keys.includes(\drive1) or: { keys.includes(\drive) } } {
			var k = if (keys.includes(\drive1)) { \drive1 } { \drive }, d = norm.(k).linlin(0, 1, 0.6, 6), n = 32;
			n.do { |i|
				var x = (i / (n - 1) * 2) - 1, y = (x * d).tanh;
				var p = (r.left + (i / (n - 1) * r.width)) @ (r.center.y - (y * r.height * 0.45));
				if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
		}
		// anything else: its hero parameters as bars
		{
			var heroes = (unit.class.tryPerform(\mapParams) ? []).select { |e| e[\hero] == true and: { (e[\kind] ? \knob) == \knob } };
			heroes.do { |e, i|
				var h = (norm.(e[\key]) * (r.height - 2)).max(1), w = (r.width / heroes.size.max(1)) - 4;
				Pen.fillColor = FxPalette.fade(colour, 0.7);
				Pen.fillRect(Rect(r.left + 2 + (i * (r.width / heroes.size.max(1))), r.bottom - h, w, h));
			};
		};
	}

	remove { }
}


FxStrip {
	classvar <>height = 176;

	var <chain, <view, canvas, cards, ctrl, unitCtrls, poller, <running = false, <hints, addView;
	var <>label;		// said at the right of the strip: whose chain it is

	asView { ^view }

	*new { |chain| ^super.new.init(chain) }

	init { |argChain|
		cards = [];
		unitCtrls = [];
		view = ScrollView().hasBorder_(false).fixedHeight_(height)
			.hasVerticalScroller_(false).autohidesScrollers_(true).background_(FxPalette.waveBg);
		this.chain_(argChain);
	}

	// the same strip on another chain: the selected pad's, the master's
	chain_ { |aChain|
		if (aChain === chain) { ^this };
		ctrl !? (_.remove);
		chain = aChain;
		ctrl = chain !? { SimpleController(chain).put(\units, { { this.prRebuild }.defer }) };
		this.prRebuild;
	}

	hints_ { |bar|
		hints = bar;
		cards.do { |c| c.hints_(bar) };
		this.prHintAdd;
	}

	// the cards follow their effects: a knob moved in an effect's own window, a
	// preset, a controller. Ten times a second while the strip is on show.
	running_ { |bool|
		running = bool == true;
		poller !? (_.stop);
		poller = nil;
		if (running) {
			poller = SkipJack({ this.refresh }, 0.1, { view.isNil or: { view.isClosed } }, "FxStrip");
		};
	}

	refresh { if (view.notNil and: { view.isClosed.not }) { cards.do(_.refresh) } }

	// The headings of the add menu, in its order - the ones SuperDirtGUI's
	// insert menu has, so an effect is found in the same place everywhere.
	*categories {
		^[
			\dynamics -> "dynamics", \filter -> "eq & filter", \distortion -> "distortion",
			\delay -> "delay", \reverb -> "reverb", \modulation -> "modulation",
			\pitch -> "pitch & frequency", \spectral -> "spectral", \granular -> "granular",
			\resonator -> "resonator", \repeat -> "repeat & glitch", \utility -> "utility",
			\other -> "other"
		]
	}

	// An effect class's heading: what it says itself (*insertCategory), else
	// the ones known here, a reverb's display, or \other.
	*categoryOf { |cls|
		var known = IdentityDictionary[
			\ParaEQ -> \filter, \Compressor -> \dynamics, \Growl -> \distortion, \Tunnel -> \delay,
			\Smear -> \spectral, \Cloud -> \granular, \Stammer -> \repeat, \Sympathy -> \resonator,
			\Spiral -> \pitch
		];
		var display = cls.tryPerform(\displayClass), reverbView = \ReverbView.asClass;
		// a reverb is known by its display: ReverbView, or one built on it
		var isReverb = display.notNil and: { reverbView.notNil }
			and: { (display === reverbView) or: { display.superclasses.includes(reverbView) } };
		var cat = (if (cls.class.findRespondingMethodFor(\insertCategory).notNil) { cls.insertCategory })
			?? { known[cls.name] }
			?? { if (isReverb) { \reverb } }
			? \other;
		^if (this.categories.any { |a| a.key == cat }) { cat } { \other }
	}

	// the classes an effect can be made of, by what they are called
	*effectClasses {
		var list = [\ParaEQ, \Compressor].collect(_.asClass).reject(_.isNil);
		\FxParamUnit.asClass !? { |base|
			list = list ++ base.allSubclasses.reject { |c| c.name.asString.beginsWith("Meta_") }
				.sort { |a, b| a.name < b.name };
		};
		^list
	}

	prRebuild {
		var items;
		if (view.isNil or: { view.isClosed }) { ^this };
		cards = (chain !? (_.units) ? []).collect { |u| FxDeviceCard(u, chain, this) };
		addView = UserView().fixedWidth_(76);
		addView.drawFunc = { |v|
			var r = v.bounds.moveTo(0, 0).insetBy(0.5, 0.5);
			Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.6);
			Pen.width = 1;
			Pen.lineDash = FloatArray[4, 3];
			Pen.addRoundedRect(r, 4, 4);
			Pen.stroke;
			Pen.stringCenteredIn("+ effect", r, FxPalette.font, FxPalette.dim);
		};
		addView.mouseDownAction = { this.prAddMenu; true };
		this.prHintAdd;
		canvas = View().background_(FxPalette.waveBg);
		canvas.layout = HLayout(*(cards.collect(_.view) ++ [addView, nil])).margins_(8).spacing_(8);
		canvas.fixedHeight = height - 14;
		view.canvas = canvas;
	}

	prHintAdd {
		if (hints.notNil and: { addView.notNil }) {
			hints.attach(addView, "add an effect at the end of the chain - any that is installed");
		};
	}

	// the installed effects under their headings, a submenu each
	prAddMenu {
		var classes = this.class.effectClasses, groups;
		if (chain.isNil) { ^this };
		groups = this.class.categories.collect { |cat|
			var mine = classes.select { |cls| this.class.categoryOf(cls) == cat.key };
			if (mine.notEmpty) {
				Menu(*mine.collect { |cls|
					MenuAction((cls.tryPerform(\title) ? cls.name).asString, {
						var u = chain.add(cls.new);
						u.enabled = true;
					})
				}).title_(cat.value)
			}
		}.reject(_.isNil);
		Menu(*groups).front;
	}

	remove {
		this.running = false;
		ctrl !? (_.remove);
		ctrl = nil;
	}
}
