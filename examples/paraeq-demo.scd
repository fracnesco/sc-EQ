// ParaEQ - a walkthrough, one block at a time
//
// Evaluate each ( ... ) block in turn with the cursor inside it. This is
// an editor document, not a file to .load.

// ---- 1. the eq as a UGen, in an Ndef, and the object that drives it
(
s.boot;
)

(
Ndef(\pad, {
	var sig = Saw.ar([110, 110.7]) * 0.15;
	sig = RLPF.ar(sig, LFNoise2.kr(0.2).exprange(400, 4000), 0.4);
	ParaEQ.ar(sig, \eq)			// the controls \eq and \eqOn, read by the object below
}).play;
)

(
~eq = ParaEQ(Ndef(\pad), \eq);		// drives that Ndef's \eq control
~eq.gui;							// drag the dots; the spectrum behind is the Ndef's bus
)

~eq.preset = \telephone;
~eq.preset = \loudness;
~eq.setBand(0, 80, -9, 0.8);		// low shelf, 80 Hz, -9 dB
~eq.dbAt_(4, 4);					// air
~eq.enabled = false;				// crossfades to dry - the Ndef does not know
~eq.enabled = true;
~eq.flat;

// ---- 2. a curve worth keeping, and one carried somewhere else
~eq.setBand(2, 900, -6, 1.4);
~eq.saveAsPreset("demo scoop");		// joins every eq's menu, in this session and the next
~eq.copyBands;

// ---- 3. the same eq with a node of its own, in place on the main out
(
~master = ParaEQ.onBus(0, 2);
~master.enabled = true;
~master.pasteBands;					// the curve from step 2
~master.gui;
)

~master.enabled = false;			// the node fades out; off costs nothing
~master.free;

// ---- 4. the master chain: an eq and a compressor on the main out
(
~chain = FxChain.master;			// bus 0, one channel per output
~chain.units;						// -> [ParaEQ, Compressor] when the compressor is installed
~chain.gui;
)

~chain.eq.enabled = true;
~chain.eq.preset = \warm;
~chain.savePreset("demo master");	// -> <sc EQ>/presets/demo master.scd
~chain.loadPreset("demo master");

// ---- 5. tidy up
(
ParaEQ.removePreset("demo scoop");
~chain.free;
~eq.free;
Ndef(\pad).clear;
)
