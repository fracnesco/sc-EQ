// =====================================================================
// ParaEQ - a five band parametric EQ, as a UGen and as an object
//
// Five biquads in series - low shelf, three peaks, high shelf - each
// band a [freq, dB, q] triple. The filter is one pseudo-UGen, so it runs
// wherever a UGen does:
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

	var <bands;

	*initClass {
		bandNames = ["low shelf", "peak 1", "peak 2", "peak 3", "high shelf"];
		bandTypes = [\lowShelf, \peak, \peak, \peak, \hiShelf];
	}

	*controlName { ^\eq }

	// --------------------------------------------------------------- the DSP

	// The filter, as a UGen. `name` is the control the bands arrive on -
	// fifteen numbers, [freq, q, dB] per band, lagged so a dragged handle
	// slides - and `name`On is a second control that crossfades the whole
	// thing to dry, so an eq in a graph can be switched off without the
	// graph knowing. `bypass: false` leaves that control out, for a node
	// that is freed instead of switched.
	//
	//   ParaEQ.ar(sig, \eq);                 // controls \eq and \eqOn
	//   ParaEQ.ar(sig, \bands, bypass: false)
	//
	*ar { |in, name = \eq, bypass = true, lag|
		var b, sig, on;
		name = name.asSymbol;
		b = Lag.kr(NamedControl.kr(name, this.flatControls), lag ? this.lag);

		sig = BLowShelf.ar(in, b[0], b[1].max(0.6), b[2]);
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

	// --------------------------------------------------------------- bands

	*numBands { ^5 }

	*defaultBands { ^this.presetStore.builtins[1].deepCopy }

	// the shelves take an rs, the peaks an rq, and they tolerate different
	// amounts of it: a shelf below 0.6 is where the coefficients go bad
	*isShelf { |i| ^(i == 0) or: { i == (this.numBands - 1) } }
	*qLo { |i| ^if (this.isShelf(i)) { 0.6 } { 0.05 } }
	*qHi { ^10 }

	*bandName { |i| ^bandNames[i] ? "band" }

	prInitSettings { bands = ParaEQ.defaultBands }

	freqAt { |i| ^bands[i] !? (_[0]) }
	dbAt { |i| ^bands[i] !? (_[1]) }
	qAt { |i| ^bands[i] !? (_[2]) }

	freqAt_ { |i, v| this.setBand(i, v, nil, nil) }
	dbAt_ { |i, v| this.setBand(i, nil, v, nil) }
	qAt_ { |i, v| this.setBand(i, nil, nil, v) }

	// nil leaves that part of the band where it was, so the window can move
	// one number box without disturbing the other two
	setBand { |i, freq, db, q|
		if (this.prSetBand(i, freq, db, q)) {
			this.prPush;
			this.prChanged(\bands);
		};
	}

	// every band at once, as [[freq, dB, q], ...] - one push, one redraw
	bands_ { |list|
		if (list.isNil) { ^this };
		list.do { |b, i| b !? { this.prSetBand(i, b[0], b[1], b[2]) } };
		this.prPush;
		this.prChanged(\bands);
	}

	flat { this.bands_(ParaEQ.defaultBands) }

	prSetBand { |i, freq, db, q|
		var b = bands[i];
		if (b.isNil) { ^false };
		freq !? { b[0] = freq.clip(minFreq, maxFreq) };
		db !? { b[1] = db.clip(dbRange.neg, dbRange) };
		q !? { b[2] = q.clip(ParaEQ.qLo(i), ParaEQ.qHi) };
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

	// a node already carrying fifteen numbers: read them back as bands
	prAcceptControls { |array|
		var a = array.asArray;
		if (a.size != (ParaEQ.numBands * 3)) { ^false };
		bands = a.clump(3).collect { |c| [c[0], c[2], c[1]] };
		^true
	}

	// ------------------------------------------------------------- presets

	settings { ^bands.deepCopy }
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

	*prBuiltinPresets {
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
		]
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
	// rather than shadowed, and so is a curve that is not five bands long.
	*addPreset { |name, bandList|
		if (bandList.isNil or: { bandList.size != this.numBands }) {
			("ParaEQ: a preset is % bands, not %"
				.format(this.numBands, bandList !? (_.size))).warn;
			^nil
		};
		^this.presetStore.add(name, bandList.collect { |b| [b[0], b[1], b[2]] })
	}

	*removePreset { |name| ^this.presetStore.remove(name) }

	*clearUserPresets { this.presetStore.clear }

	// the preset these bands are, or nil once you have moved one
	presetName { ^ParaEQ.presetStore.detect(bands) }

	preset_ { |name|
		var b = ParaEQ.presetBands(name);
		if (b.isNil) {
			("ParaEQ: no preset called %".format(name.asCompileString)).warn;
			^this
		};
		this.bands_(b);
	}

	saveAsPreset { |name| ^ParaEQ.addPreset(name, bands) }

	// --------------------------------------------------- copy and paste

	// Put this curve aside, and take it up again on another eq. The
	// clipboard is one per class, so it crosses pads, chains and Ndefs
	// alike.
	copyBands {
		clipboard = bands.collect { |b| [b[0], b[1], b[2]] };
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
		this.bands_(other.bands);
		^this
	}

	// ------------------------------------------------------------- state

	// what a set, a kit or a chain writes - the shape ModuleEQ always had
	asDict { ^(enabled: enabled, bands: bands.deepCopy) }

	fromDict { |d|
		if (d.isNil) { ^this };
		d[\bands] !? { |v| this.bands_(v) };
		d[\settings] !? { |v| this.bands_(v) };
		d[\enabled] !? { |v| this.enabled_(v) };
	}

	// -------------------------------------------------------- the response

	// The dB curve of every band over `freqs`, worked out from the same
	// coefficients the server runs. Sum them for the curve you hear.
	responseAt { |freqs, sr = 44100|
		^bands.collect { |b, i|
			ParaEQ.magnitudes(
				ParaEQ.coeffs(bandTypes[i], sr, b[0], b[2], b[1]),
				freqs, sr
			).ampdb.clip(-200, 200)
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
