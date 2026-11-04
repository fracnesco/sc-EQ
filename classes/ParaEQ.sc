// =====================================================================
// ParaEQ - a five band parametric EQ and two cuts, as a UGen and as
// an object
//
// Five biquads in series - low shelf, three peaks, high shelf - each
// band a [freq, dB, q] triple, and either end of the spectrum can be cut
// away as well. The filter is one pseudo-UGen, so it runs wherever a
// UGen does:
//
//   Ndef(\pad, { var sig = Saw.ar([110, 111]); ParaEQ.ar(sig, \eq) });
//
// and an object drives it, wherever it runs:
//
//   e = ParaEQ(Ndef(\pad), \eq);      // the \eq control of that Ndef
//   e.gui;                            // the curve, the handles, the presets
//   e.setBand(0, 80, -6, 0.8);        // low shelf, 80 Hz, -6 dB
//   e.preset = \telephone;
//   e.dbAt_(4, 3);                    // air
//
// The two cuts - a high pass and a low pass - are switches rather than
// gains, and both are out until you switch them in, so an eq that has
// never been told about them is the eq it always was:
//
//   e.highPassOn = true;              // the rumble gone
//   e.highPassFreq = 60;
//   e.lowPassOn = true;
//   e.lowPassFreq = 12000;
//   e.highPassSlope = 48;             // how hard it cuts: 6 to 96 dB per octave, in 6s
//
// The same object can own a synth on a bus instead, in place:
//
//   e = ParaEQ.onBus(0, 2);           // the main out
//   e.enabled = true;
//
// or sit in a chain of effects - see FxChain. A curve you like can be
// kept under a name of your own, and moved from one eq to another:
//
//   e.saveAsPreset("my room");        // joins the preset menu
//   e.copyBands;                      // and this pair is copy
//   other.pasteBands;                 // and paste
//
// The curve the window draws is worked out here too, from the same
// coefficients the server uses, so what you see is the filter you get.
// The module family's ModuleEQ is one of these, on a pad's own chain.
// =====================================================================

ParaEQ : FxUnit {
	classvar <bandNames;			// what each band is called in the window
	classvar <bandTypes;			// which filter each one is
	classvar <cutNames;				// the two cuts, after the bands
	classvar <cutTypes;
	classvar store;					// the presets - see FxPresetStore
	classvar <clipboard;			// one curve, held aside, shared by every eq

	// The curve is drawn to this many dB either side of flat, and no band
	// is allowed past it - the filters stay well behaved inside it.
	classvar <>dbRange = 24;
	classvar <>minFreq = 20, <>maxFreq = 20000;

	// How quickly the filters follow a drag, and how long the eq takes to
	// fade in and out of a chain when it is switched.
	classvar <>lag = 0.1;
	classvar <>fade = 0.02;

	// How narrow a cut may be made. Below this the resonance at the corner
	// is a whistle rather than a filter.
	classvar <>cutQLo = 0.1;

	var <bands;
	var <cuts;						// [freq, rq, in] each - see numHandles - and a
									// slope after them, when it is not the default

	*initClass {
		bandNames = ["low shelf", "peak 1", "peak 2", "peak 3", "high shelf"];
		bandTypes = [\lowShelf, \peak, \peak, \peak, \hiShelf];
		cutNames = ["high pass", "low pass"];
		cutTypes = [\hiPass, \loPass];
	}

	*controlName { ^\eq }

	// --------------------------------------------------------------- the DSP

	// The filter, as a UGen. `name` is the control the bands arrive on -
	// fifteen numbers, [freq, q, dB] per band, lagged so a dragged handle
	// slides. `name`Cut is a second control carrying the two cuts, [freq,
	// rq, in] each, and `name`On a third that crossfades the whole thing
	// to dry, so an eq in a graph can be switched off without the graph
	// knowing. `bypass: false` leaves that last one out, for a node that
	// is freed instead of switched. `name`Slope carries how hard each cut
	// cuts, on a control of its own and not lagged: see prCut.
	//
	//   ParaEQ.ar(sig, \eq);                 // \eq, \eqCut, \eqSlope and \eqOn
	//   ParaEQ.ar(sig, \bands, bypass: false)
	//
	// The cuts come first, being the ones that take something away, and
	// the five bands after them. All of it is linear, so that order is
	// housekeeping rather than sound.
	*ar { |in, name = \eq, bypass = true, lag|
		var b, c, slopes, sig, on;
		name = name.asSymbol;
		lag = lag ? this.lag;
		b = Lag.kr(NamedControl.kr(name, this.flatControls), lag);
		c = Lag.kr(NamedControl.kr(this.cutName(name), this.flatCutControls), lag);
		slopes = NamedControl.kr(this.slopeName(name), this.flatSlopeControls);

		sig = this.prCut(\hiPass, in, c[0], c[1], c[2], slopes[0]);
		sig = this.prCut(\loPass, sig, c[3], c[4], c[5], slopes[1]);
		sig = BLowShelf.ar(sig, b[0], b[1].max(0.6), b[2]);
		sig = BPeakEQ.ar(sig, b[3], b[4].max(0.05), b[5]);
		sig = BPeakEQ.ar(sig, b[6], b[7].max(0.05), b[8]);
		sig = BPeakEQ.ar(sig, b[9], b[10].max(0.05), b[11]);
		sig = BHiShelf.ar(sig, b[12], b[13].max(0.6), b[14]);

		// A bad value would spread down the chain and poison everything it
		// reached, so it stops here. What keeps the filters themselves in
		// range is the limits the object puts on the bands.
		sig = Sanitize.ar(sig);

		if (bypass) {
			on = Lag.kr(NamedControl.kr((name.asString ++ "On").asSymbol, 1), this.fade);
			sig = in + ((sig - in) * on);
		};
		^sig
	}

	// One cut and the switch that fades it in: at 0 the signal goes past
	// untouched, so a cut that is out is out, however its frequency has
	// been left. The switch is lagged with everything else on that
	// control, which is what makes switching one in a fade rather than a
	// click.
	//
	// A filter only comes in whole orders, 6 dB per octave each, so that is
	// what a slope is: a Butterworth filter of that order, 3 dB down at its
	// corner whatever the order, so a step only changes how hard it falls
	// away. Between two orders there is no filter that holds its corner, which
	// is why the slope has steps. A graph cannot change once it is built, so
	// all eight biquads are always there, and the slope says how many are in.
	// Nothing fades as it changes. 12 is one biquad, the filter these cuts
	// always were.
	*prCut { |type, sig, freq, rq, amount, slope|
		var cut = sig, order, half, sr = SampleRate.ir;
		freq = freq.clip(minFreq, maxFreq);
		// the object only ever sends a step: this is for a node set by hand
		order = (slope / this.slopeStep).round(1).clip(1, this.slopeHi div: this.slopeStep);
		half = order div: 2;
		this.numCutBiquads.do { |j|
			var width = Select.kr(order, (0 .. this.slopeHi div: this.slopeStep).collect { |o|
				this.prStageRq(j, o, 2.sqrt)
			});
			if (j == 0) { width = (width * rq / 2.sqrt).max(this.cutQLo) };
			cut = this.prPick(half > j, cut, if (type == \hiPass) {
				BHiPass.ar(cut, freq, width)
			} {
				BLowPass.ar(cut, freq, width)
			});
		};
		cut = this.prPick(order % 2, cut, FOS.ar(cut, *this.prOnePole(type, sr * 0.49, pi / sr, freq)));
		^sig + ((cut - sig) * amount.clip(0, 1))
	}

	// one signal or the other, channel by channel. An exact wire where a stage
	// is not in, and cheaper than a crossfade, of which there would be nine
	*prPick { |which, off, on|
		^[off.asArray, on.asArray].flop.collect { |pair| Select.ar(which, pair) }.unbubble
	}

	// The next two are the same arithmetic for the curve and for the
	// server: on numbers here, and on UGens in prCut.

	// The width of one biquad of a Butterworth filter, the narrowest first.
	// The cut's rq works on that narrowest one, the one that sets the corner:
	// 1.414 leaves the filter flat, and at order 2 it is the rq itself.
	*prStageRq { |j, order, rq|
		var r = 2 * sin((2 * j + 1) * pi / (2 * order.max(1)));
		if (j == 0) { r = r * rq / 2.sqrt };
		^r.max(this.cutQLo)
	}

	// the odd order's first-order stage, as FOS takes it: [a0, a1, b1].
	// `top` is as high as a frequency goes and `w` is pi over the sample rate
	*prOnePole { |type, top, w, freq|
		var k = (freq.min(top) * w).tan;
		var g = (1 + k).reciprocal;
		^if (type == \hiPass) {
			[g, g.neg, (1 - k) * g]
		} {
			[k * g, k * g, (1 - k) * g]
		}
	}

	*defName { |numChannels| ^("paraEQ" ++ numChannels.asInteger).asSymbol }

	// In place on a bus: XOut rather than Out, so the envelope crossfades
	// between what is on the bus and what came out of the filters, and
	// switching the eq in and out leaves the dry signal behind rather than
	// silence.
	*synthDef { |numChannels|
		var n = numChannels.asInteger;
		^SynthDef(this.defName(n), { |bus = 0, gate = 1, fade = 0.02|
			var sig = ParaEQ.ar(In.ar(bus, n), \eq, bypass: false);
			var env = EnvGen.kr(Env.asr(fade, 1, fade), gate, doneAction: Done.freeSelf);
			XOut.ar(bus, env, sig);
		})
	}

	*guiClass { ^ParaEQGUI }
	*panelClass { ^ParaEQPanel }

	// ------------------------------------------------- bands, and the cuts

	// Five parametric bands, and after them the two cuts. A window numbers
	// them 0 .. 6 together and calls each one a handle: they are all
	// something to take hold of on the curve, and everything below that
	// takes an `i` takes one of those numbers.
	//
	//   0 .. 4   low shelf, peak 1, peak 2, peak 3, high shelf
	//   5        high pass   - ParaEQ.highPass
	//   6        low pass    - ParaEQ.lowPass
	//
	// A band is [freq, dB, q] and is always in; a cut is [freq, rq, in],
	// has no gain, and is out until it is switched in.
	*numBands { ^5 }
	*numCuts { ^2 }
	*numHandles { ^this.numBands + this.numCuts }
	*isCut { |i| ^i >= this.numBands }
	*highPass { ^this.numBands }
	*lowPass { ^this.numBands + 1 }

	// the \flat preset: five flat bands, and both cuts out where they start
	*flatSettings { ^this.presetStore.builtins[1].deepCopy }
	*defaultBands { ^this.flatSettings.keep(this.numBands) }
	*defaultCuts { ^this.flatSettings.drop(this.numBands) }

	// How hard a cut cuts, in dB per octave, in whole filter orders. Only a
	// cut has one.
	*defaultSlope { ^12 }
	*slopeStep { ^6 }
	*slopeLo { ^6 }
	*slopeHi { ^96 }
	*slopes { ^(this.slopeLo, this.slopeLo + this.slopeStep .. this.slopeHi) }

	// the step a value is nearest to
	*nearestSlope { |v| ^v.round(this.slopeStep).clip(this.slopeLo, this.slopeHi) }

	// what the graph holds for the steepest slope: 96 dB per octave is eight
	// biquads
	*numCutBiquads { ^8 }

	// the shelves take an rs, the peaks an rq, and they tolerate different
	// amounts of it: a shelf below 0.6 is where the coefficients go bad,
	// and a cut below cutQLo is a whistle at the corner
	*isShelf { |i| ^(i == 0) or: { i == (this.numBands - 1) } }
	*qLo { |i|
		^if (this.isCut(i)) { cutQLo } { if (this.isShelf(i)) { 0.6 } { 0.05 } }
	}
	*qHi { ^10 }

	*bandName { |i|
		^(if (this.isCut(i)) { cutNames[i - this.numBands] } { bandNames[i] })
			? "band"
	}

	prInitSettings {
		bands = ParaEQ.defaultBands;
		cuts = ParaEQ.defaultCuts;
	}

	// One part of one handle. A cut has no gain, so its dB is 0 and stays
	// 0; what it has instead is the switch below.
	freqAt { |i| ^this.prHandle(i) !? (_[0]) }
	dbAt { |i| ^if (ParaEQ.isCut(i)) { 0 } { bands[i] !? (_[1]) } }
	qAt { |i|
		^if (ParaEQ.isCut(i)) {
			cuts[i - ParaEQ.numBands] !? (_[1])
		} {
			bands[i] !? (_[2])
		}
	}

	// nil on a band, which has no slope
	slopeAt { |i|
		^if (ParaEQ.isCut(i)) { cuts[i - ParaEQ.numBands] !? { |c| c[3] ? ParaEQ.defaultSlope } }
	}

	slopeAt_ { |i, v|
		if (ParaEQ.isCut(i).not) { ^this };
		if (this.prSetCut(i - ParaEQ.numBands, nil, nil, nil, v)) {
			this.prPush;
			this.prChanged(\bands);
		};
	}

	prHandle { |i| ^if (ParaEQ.isCut(i)) { cuts[i - ParaEQ.numBands] } { bands[i] } }

	freqAt_ { |i, v| this.setBand(i, v, nil, nil) }
	dbAt_ { |i, v| this.setBand(i, nil, v, nil) }
	qAt_ { |i, v| this.setBand(i, nil, nil, v) }

	// nil leaves that part of the handle where it was, so the window can
	// move one number box without disturbing the other two. `db` is
	// ignored on a cut - cutOn_ is what a cut has in its place.
	setBand { |i, freq, db, q|
		var moved = if (ParaEQ.isCut(i)) {
			this.prSetCut(i - ParaEQ.numBands, freq, q, nil)
		} {
			this.prSetBand(i, freq, db, q)
		};
		if (moved) {
			this.prPush;
			this.prChanged(\bands);
		};
	}

	// Whether a handle is in. A band always is - its gain is what says
	// what it does - so this answers true for one, and setting it on one
	// does nothing.
	cutOn { |i|
		^if (ParaEQ.isCut(i)) {
			(cuts[i - ParaEQ.numBands] !? (_[2])) ? false
		} {
			true
		}
	}

	cutOn_ { |i, bool|
		if (ParaEQ.isCut(i).not) { ^this };
		if (this.prSetCut(i - ParaEQ.numBands, nil, nil, bool)) {
			this.prPush;
			this.prChanged(\bands);
		};
	}

	toggleCut { |i| this.cutOn_(i, this.cutOn(i).not) }

	// the same two, under their own names
	highPassOn { ^this.cutOn(ParaEQ.highPass) }
	highPassOn_ { |bool| this.cutOn_(ParaEQ.highPass, bool) }
	highPassFreq { ^this.freqAt(ParaEQ.highPass) }
	highPassFreq_ { |v| this.freqAt_(ParaEQ.highPass, v) }
	highPassQ { ^this.qAt(ParaEQ.highPass) }
	highPassQ_ { |v| this.qAt_(ParaEQ.highPass, v) }
	highPassSlope { ^this.slopeAt(ParaEQ.highPass) }
	highPassSlope_ { |v| this.slopeAt_(ParaEQ.highPass, v) }

	lowPassOn { ^this.cutOn(ParaEQ.lowPass) }
	lowPassOn_ { |bool| this.cutOn_(ParaEQ.lowPass, bool) }
	lowPassFreq { ^this.freqAt(ParaEQ.lowPass) }
	lowPassFreq_ { |v| this.freqAt_(ParaEQ.lowPass, v) }
	lowPassQ { ^this.qAt(ParaEQ.lowPass) }
	lowPassQ_ { |v| this.qAt_(ParaEQ.lowPass, v) }
	lowPassSlope { ^this.slopeAt(ParaEQ.lowPass) }
	lowPassSlope_ { |v| this.slopeAt_(ParaEQ.lowPass, v) }

	// Every handle at once, as [[freq, dB, q], ...] - one push, one
	// redraw. Seven of them carry the two cuts after the bands, which is
	// what a preset and the clipboard hold; five is a curve saved before
	// the cuts existed, and leaves them where they are. A cut with no slope
	// in it was saved at the default one, and goes back to it.
	bands_ { |list|
		if (list.isNil) { ^this };
		list.do { |b, i|
			b !? {
				if (ParaEQ.isCut(i)) {
					this.prSetCut(i - ParaEQ.numBands, b[0], b[1], b[2],
						b[3] ? ParaEQ.defaultSlope)
				} {
					this.prSetBand(i, b[0], b[1], b[2])
				}
			}
		};
		this.prPush;
		this.prChanged(\bands);
	}

	// both cuts at once, as [[freq, rq, in], [freq, rq, in]], a slope after
	// the three when it is not the default
	cuts_ { |list|
		if (list.isNil) { ^this };
		list.do { |c, j| c !? { this.prSetCut(j, c[0], c[1], c[2], c[3] ? ParaEQ.defaultSlope) } };
		this.prPush;
		this.prChanged(\bands);
	}

	// every band flat and both cuts out - the whole eq doing nothing
	flat { this.bands_(ParaEQ.flatSettings) }

	prSetBand { |i, freq, db, q|
		var b = bands[i];
		if (b.isNil) { ^false };
		freq !? { b[0] = freq.clip(minFreq, maxFreq) };
		db !? { b[1] = db.clip(dbRange.neg, dbRange) };
		q !? { b[2] = q.clip(ParaEQ.qLo(i), ParaEQ.qHi) };
		^true
	}

	// `j` is 0 for the high pass and 1 for the low pass - the cuts among
	// themselves, not among the handles. A cut at the default slope stays
	// three long, so everything saved before there were slopes still compares
	// equal to it, and the presets go on being recognised.
	prSetCut { |j, freq, q, on, slope|
		var c = cuts[j];
		if (c.isNil) { ^false };
		freq !? { c[0] = freq.clip(minFreq, maxFreq) };
		q !? { c[1] = q.clip(cutQLo, ParaEQ.qHi) };
		on !? { c[2] = on.booleanValue };
		slope !? {
			slope = ParaEQ.nearestSlope(slope);
			c = c.keep(3);
			if (slope != ParaEQ.defaultSlope) { c = c.add(slope) };
			cuts[j] = c;
		};
		^true
	}

	// ------------------------------------------------------------ the node

	// What the synth is given: [freq, q, dB] per band, in band order. The
	// SynthDef's own default is this array with every band flat, so an eq
	// that has not been told anything yet is silent about it.
	controls { ^bands.collect { |b| [b[0], b[2], b[1]] }.flat }

	*flatControls {
		^this.defaultBands.collect { |b| [b[0], b[2], b[1]] }.flat
	}

	// and what the second control is given: [freq, rq, in] per cut, with
	// `in` at 0 for a cut that is out
	cutControls { ^cuts.collect { |c| [c[0], c[1], c[2].binaryValue] }.flat }

	*flatCutControls {
		^this.defaultCuts.collect { |c| [c[0], c[1], c[2].binaryValue] }.flat
	}

	// and the third: each cut's slope, in dB per octave
	slopeControls { ^(0 .. ParaEQ.numCuts - 1).collect { |j| cuts[j][3] ? ParaEQ.defaultSlope } }

	*flatSlopeControls { ^this.defaultSlope ! this.numCuts }

	// the control the cuts arrive on, next to the one the bands do
	*cutName { |name| ^(name.asString ++ "Cut").asSymbol }

	cutName { ^ParaEQ.cutName(controlName) }

	// and the slopes, next to them: a node built before there were slopes
	// is told and takes no notice, and goes on cutting at 12
	*slopeName { |name| ^(name.asString ++ "Slope").asSymbol }

	slopeName { ^ParaEQ.slopeName(controlName) }

	// a node already carrying fifteen numbers: read them back as bands
	prAcceptControls { |array|
		var a = array.asArray;
		if (a.size != (ParaEQ.numBands * 3)) { ^false };
		bands = a.clump(3).collect { |c| [c[0], c[2], c[1]] };
		^true
	}

	// The bands travel on one control and the cuts on another, so a node
	// built before the cuts existed still takes the bands, and a node that
	// knows about neither is simply told twice and hears neither.
	prPush {
		super.prPush;
		this.prPushCuts;
	}

	prPushCuts {
		var c = this.cutControls, sl = this.slopeControls;
		synth !? { |sy| sy.set(this.cutName, c, this.slopeName, sl) };
		target !? { |t| t.set(this.cutName, c, this.slopeName, sl) };
	}

	// Taking over a node that already carries settings is FxUnit's
	// business - what is here is the second control: the cuts are read
	// back off it when the node has them, and pushed at it when it has
	// nothing to say about them.
	target_ { |node|
		var held;
		super.target_(node);
		if (node.isNil) { ^this };
		held = node.tryPerform(\nodeMap) !? { |m| m.tryPerform(\at, this.cutName) };
		held = held !? { |h| h.value.asArray };
		if (held.notNil and: { held.size == (ParaEQ.numCuts * 3) }) {
			cuts = held.clump(3).collect { |c| [c[0], c[1], c[2].booleanValue] };
			held = node.tryPerform(\nodeMap) !? { |m| m.tryPerform(\at, this.slopeName) };
			held = held !? { |h| h.value.asArray };
			if (held.notNil and: { held.size == ParaEQ.numCuts }) {
				held.do { |v, j| this.prSetCut(j, nil, nil, nil, v) };
			} {
				this.prPushCuts;
			};
			this.changed(\settings);
		} {
			this.prPushCuts;
		};
		^this
	}

	// ------------------------------------------------------------- presets

	// the five bands and the two cuts after them
	settings { ^bands.deepCopy ++ cuts.deepCopy }
	settings_ { |data| this.bands_(data) }

	// The presets: the ones the library ships and, after them, the ones
	// you have saved. Not in the repository: a curve you liked is yours
	// rather than the library's, and it should still be there after a git
	// pull. ModuleEQ kept them under the older file name, which is read
	// once if the new one does not exist yet.
	*presetStore {
		^store ?? {
			store = FxPresetStore(ParaEQ, this.prBuiltinPresets,
				Platform.userAppSupportDir +/+ "paraeq-presets.scd",
				Platform.userAppSupportDir +/+ "module-eq-presets.scd");
			store
		}
	}

	// The two cuts are put on the end of each of these as it comes out,
	// both out: a preset is the whole eq, so choosing one puts the cuts
	// back where they started as well.
	*prBuiltinPresets {
		// 1.414 is an rq of root two - Q of 0.707, the flattest a cut goes,
		// 3 dB down at its own frequency and no bump before it
		var bothOut = [[30, 1.414, false], [16000, 1.414, false]];
		// flat first, so the menu opens on the way back
		^[
			\flat,
				[[100, 0, 1], [250, 0, 1], [1000, 0, 1], [3500, 0, 1], [6000, 0, 1]],
			'rumble cut',
				[[60, -18, 0.8], [160, -3, 1.2], [1000, 0, 1], [3500, 0, 1], [6000, 0, 1]],
			\warm,
				[[140, 3, 0.8], [300, 1.5, 1], [900, -3, 1.4], [3500, -1.5, 1.2],
					[7000, -2, 0.9]],
			\bright,
				[[90, -2, 0.8], [250, 0, 1], [1000, 0, 1], [3200, 2.5, 1.2],
					[7500, 5, 0.8]],
			\distant,
				[[120, -5, 0.8], [250, 0, 1], [700, -4, 1.6], [3500, -2, 1],
					[5000, -9, 0.8]],
			\loudness,
				[[78, 7.5, 0.65], [250, 0, 1], [890, -9.5, 3.55], [2800, 3.5, 1.54],
					[7400, 7, 1]],
			\telephone,
				[[600, -22, 0.7], [250, 0, 1], [1200, -2, 0.5], [1800, 1, 0.5],
					[4000, -22, 0.7]]
		].clump(2).collect { |pair| [pair[0], pair[1] ++ bothOut.deepCopy] }.flatten(1)
	}

	*presets { ^this.presetStore.builtins }
	*userPresets { ^this.presetStore.userPresets }
	*allPresets { ^this.presetStore.all }
	*presetNames { ^this.presetStore.names }
	*presetBands { |name| ^this.presetStore.at(name) }
	*isUserPreset { |name| ^this.presetStore.isUser(name) }
	*presetFile { ^this.presetStore.file }
	*presetFile_ { |path| this.presetStore.file = path }

	// Keep a curve under a name. It joins the preset menu of every window,
	// in this session and the next. A name the library ships is refused
	// rather than shadowed, and so is a curve that is neither seven
	// handles long - the bands and the two cuts - nor the five bands one
	// saved before the cuts existed.
	*addPreset { |name, bandList|
		var n = bandList !? (_.size);
		if (n != this.numHandles and: { n != this.numBands }) {
			("ParaEQ: a preset is % handles, or the % bands alone, not %"
				.format(this.numHandles, this.numBands, n)).warn;
			^nil
		};
		^this.presetStore.add(name, bandList.collect(_.copy))
	}

	*removePreset { |name| ^this.presetStore.remove(name) }

	*clearUserPresets { this.presetStore.clear }

	// The preset this eq is, or nil once you have moved something. One
	// saved before the cuts existed is five bands long, and still answers
	// while both cuts are where they start.
	presetName {
		^ParaEQ.presetStore.detect(this.settings) ?? {
			if (cuts == ParaEQ.defaultCuts) { ParaEQ.presetStore.detect(bands) }
		}
	}

	preset_ { |name|
		var b = ParaEQ.presetBands(name);
		if (b.isNil) {
			("ParaEQ: no preset called %".format(name.asCompileString)).warn;
			^this
		};
		this.bands_(b);
	}

	saveAsPreset { |name| ^ParaEQ.addPreset(name, this.settings) }

	// --------------------------------------------------- copy and paste

	// Put this curve aside, and take it up again on another eq. The
	// clipboard is one per class, so it crosses pads, chains and Ndefs
	// alike.
	copyBands {
		clipboard = this.settings;
		ParaEQ.changed(\clipboard);
		^clipboard
	}

	*hasClipboard { ^clipboard.notNil }

	pasteBands {
		if (clipboard.isNil) { ^this };
		this.bands_(clipboard);
		^this
	}

	// Straight from another eq, without going through the clipboard. Only
	// the curve moves: whether the other one was switched on is its own
	// business.
	copyFrom { |other|
		if (other.isNil) { ^this };
		this.bands_(other.settings);
		^this
	}

	// ------------------------------------------------------------- state

	// What a set, a kit or a chain writes - the shape ModuleEQ always had,
	// with the cuts alongside it. One written before the cuts existed has
	// no `cuts`, and reads back as an eq with both of them out.
	asDict { ^(enabled: enabled, bands: bands.deepCopy, cuts: cuts.deepCopy) }

	fromDict { |d|
		if (d.isNil) { ^this };
		d[\bands] !? { |v| this.bands_(v) };
		d[\settings] !? { |v| this.bands_(v) };
		d[\cuts] !? { |v| this.cuts_(v) };
		d[\enabled] !? { |v| this.enabled_(v) };
	}

	// -------------------------------------------------------- the response

	// The dB curve of every handle over `freqs`, worked out from the same
	// coefficients the server runs: the five bands, then the two cuts,
	// each of those flat while it is out. Sum them for the curve you hear.
	responseAt { |freqs, sr = 44100|
		^bands.collect { |b, i|
			ParaEQ.magnitudes(
				ParaEQ.coeffs(bandTypes[i], sr, b[0], b[2], b[1]),
				freqs, sr
			).ampdb.clip(-200, 200)
		} ++ cuts.collect { |c, i|
			if (c[2]) {
				ParaEQ.cutCoeffs(cutTypes[i], sr, c[0], c[1], c[3] ? ParaEQ.defaultSlope)
					.collect { |coef| ParaEQ.magnitudes(coef, freqs, sr) }
					.reduce('*').ampdb.clip(-200, 200)
			} {
				Array.fill(freqs.size, 0)
			}
		}
	}

	// One biquad, as [a0, a1, a2, b1, b2] - the shape BEQSuite hands the
	// server, and the same arithmetic, done here in numbers rather than
	// UGens so the window can draw it.
	*coeffs { |type, sr, freq, q, db|
		var a, w0, c, s, alpha, i, j, k, b0rz;
		freq = freq.clip(1, sr * 0.49);
		a = 10 ** (db / 40);
		w0 = 2pi * freq / sr;
		c = w0.cos;
		s = w0.sin;

		// the cuts have no gain: the two of them differ only in which way
		// round the numerator goes
		if (type == \loPass or: { type == \hiPass }) {
			alpha = s * 0.5 * q;
			b0rz = (1 + alpha).reciprocal;
			i = if (type == \loPass) { 1 - c } { 1 + c };
			j = i * 0.5 * b0rz;
			^[
				j,
				if (type == \loPass) { i * b0rz } { i.neg * b0rz },
				j,
				c * b0rz * 2,
				(1 - alpha) * b0rz.neg
			]
		};

		if (type == \peak) {
			alpha = s * 0.5 * q;
			b0rz = (1 + (alpha / a)).reciprocal;
			^[
				(1 + (alpha * a)) * b0rz,
				(c * b0rz * 2).neg,
				(1 - (alpha * a)) * b0rz,
				c * b0rz * 2,
				(1 - (alpha / a)) * b0rz.neg
			]
		};

		// the shelves share everything but three signs
		alpha = s * 0.5 * (((a + a.reciprocal) * (q - 1)) + 2.0).max(0).sqrt;
		i = (a + 1) * c;
		j = (a - 1) * c;
		k = 2 * a.sqrt * alpha;

		if (type == \lowShelf) {
			b0rz = ((a + 1) + j + k).reciprocal;
			^[
				a * ((a + 1) - j + k) * b0rz,
				2 * a * ((a - 1) - i) * b0rz,
				a * ((a + 1) - j - k) * b0rz,
				2 * ((a - 1) + i) * b0rz,
				((a + 1) + j - k) * b0rz.neg
			]
		};

		b0rz = ((a + 1) - j + k).reciprocal;
		^[
			a * ((a + 1) + j + k) * b0rz,
			(2 * a * ((a - 1) + i) * b0rz).neg,
			a * ((a + 1) + j - k) * b0rz,
			(2 * ((a - 1) - i) * b0rz).neg,
			((a + 1) - j - k) * b0rz.neg
		]
	}

	// A whole cut at a slope, as the stages prCut has switched in: a list of
	// the same [a0, a1, a2, b1, b2], the first-order ones with no third term.
	*cutCoeffs { |type, sr, freq, rq, slope|
		var order = (this.nearestSlope(slope) / this.slopeStep).asInteger;
		var list = (order div: 2).collect { |j|
			this.coeffs(type, sr, freq, this.prStageRq(j, order, rq), 0)
		};
		if (order.odd) {
			var c = this.prOnePole(type, sr * 0.49, pi / sr, freq);
			list = list.add([c[0], c[1], 0, c[2], 0]);
		};
		^list
	}

	// |H(e^jw)| of one biquad at every frequency in `freqs`, at once
	*magnitudes { |coef, freqs, sr|
		var w = freqs * (2pi / sr);
		var w2 = w * 2;
		var c1 = w.cos, s1 = w.sin, c2 = w2.cos, s2 = w2.sin;
		var nRe = (c1 * coef[1]) + (c2 * coef[2]) + coef[0];
		var nIm = ((s1 * coef[1]) + (s2 * coef[2])).neg;
		var dRe = 1 - (c1 * coef[3]) - (c2 * coef[4]);
		var dIm = (s1 * coef[3]) + (s2 * coef[4]);
		^((nRe.squared + nIm.squared)
			/ (dRe.squared + dIm.squared).max(1e-20)).sqrt
	}

	// the server's rate when it is up, so a curve is the one being run
	sampleRate {
		var sr = server !? { |s| s.sampleRate };
		^(sr ? 44100).clip(8000, 192000)
	}
}
