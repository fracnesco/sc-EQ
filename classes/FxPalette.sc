// =====================================================================
// FxPalette - the colours and fonts every effect window is drawn in
//
// The same values the module family, Insight and ServerPanel use, so an
// eq opened from a pad, from the master chain or from an Ndef looks like
// one instrument. Kept here so this repository depends on nothing.
//
//   FxPalette.bg
//   FxPalette.eqBand(2)
//   FxPalette.label("freq", 30)
//   FxPalette.button("flat", 40, { eq.flat })
// =====================================================================

FxPalette {
	classvar <bg, <panel, <text, <dim, <accent;
	classvar <wave, <waveDim, <waveBg;
	classvar <flash, <handle, <sel, <good, <clip, <peak;
	classvar <eqBands;
	classvar font, smallFont, boldFont, monoFont;

	*initClass {
		bg       = Color(0.13, 0.13, 0.15);
		panel    = Color(0.20, 0.20, 0.24);
		text     = Color(0.86, 0.87, 0.89);
		dim      = Color(0.55, 0.56, 0.62);
		accent   = Color(0.98, 0.65, 0.20);
		wave     = Color(0.42, 0.76, 0.95);
		waveDim  = Color(0.30, 0.33, 0.40);
		waveBg   = Color(0.09, 0.10, 0.12);
		flash    = Color(0.98, 0.65, 0.20);
		handle   = Color(0.95, 0.95, 0.97);
		sel      = Color(0.42, 0.76, 0.95);
		good     = Color(0.40, 0.86, 0.60);
		clip     = Color(0.95, 0.35, 0.30);
		peak     = Color(0.95, 0.96, 0.98);

		// The eq bands read left to right, so the colour says which end of
		// the spectrum a handle belongs to before you have read its name.
		// After them the two cuts, in colours off that sweep, since they
		// take away rather than lift.
		eqBands  = [
			Color(0.42, 0.76, 0.95),
			Color(0.40, 0.86, 0.80),
			Color(0.60, 0.88, 0.48),
			Color(0.95, 0.82, 0.36),
			Color(0.98, 0.65, 0.20),
			Color(0.72, 0.60, 0.95),
			Color(0.95, 0.52, 0.74)
		];
	}

	*eqBand { |i| ^eqBands.wrapAt(i.asInteger) }

	// ---------------------------------------------------------------- fonts

	*font { ^font ?? { font = Font(Font.defaultSansFace, 11) } }
	*smallFont { ^smallFont ?? { smallFont = Font(Font.defaultSansFace, 9) } }
	*boldFont { ^boldFont ?? { boldFont = Font(Font.defaultSansFace, 12, true) } }
	*monoFont { ^monoFont ?? { monoFont = Font(Font.defaultMonoFace, 11) } }

	// -------------------------------------------------------------- widgets

	// the small dim label every row starts with
	*label { |str, width, align = \right|
		var t = StaticText().string_(str).stringColor_(dim)
			.font_(this.smallFont).align_(align);
		width !? { t.fixedWidth_(width) };
		^t
	}

	// a one state button in the house colours
	*button { |str, width, func|
		var b = Button().states_([[str, text, panel]]).canFocus_(false)
			.action_({ func.value });
		width !? { b.fixedWidth_(width) };
		^b
	}

	// the dark palette, so the Qt widgets that are not coloured by hand -
	// sliders, menus, number boxes - come out like the rest
	*darken { |window|
		var v = window.tryPerform(\view) ? window;
		if (GUI.id == \qt) { v.palette = QPalette.dark };
		v.background = bg;
		^window
	}
}
