// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-eq.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// ParaEQ depends on nothing. Two other repositories are built on it -
// Compressor (../sc compressor) and ModuleCommon (../module common) -
// and their installers link this one first when it sits next to them.
// BusManager, if it happens to be installed, lets a bus be named; and
// ServerView, if it is the fracnesco fork, grows an fx section that
// opens the master chain. Neither is needed to compile.

(
var src = PathName(thisProcess.nowExecutingPath).pathOnly;
var dst = Platform.userExtensionDir +/+ "ParaEQ";

// drop the trailing slash, ln -s does not like it
if (src.endsWith("/")) { src = src.drop(-1) };

case
{ File.exists(dst) } {
	("ParaEQ: % already exists - remove it first if you want to relink."
		.format(dst)).warn;
}
{ true } {
	File.mkdir(Platform.userExtensionDir);
	("ln -s" + src.shellQuote + dst.shellQuote).unixCmd({ |exit|
		if (exit == 0) {
			"ParaEQ: linked".postln;
			("   " ++ src).postln;
			("-> " ++ dst).postln;
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		} {
			("ParaEQ: linking failed with exit code" + exit).warn;
		};
	});
};
)


// ---------------------------------------------------------------- verify
(
var missing = [\ParaEQ, \ParaEQView, \ParaEQSpectrum, \ParaEQPanel, \ParaEQGUI,
	\FxUnit, \FxChain, \FxChainPanel, \FxChainGUI, \FxPresetStore, \FxPalette, \FxHintBar]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("ParaEQ is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors.".format(missing.join(", "))).warn;
} {
	"ParaEQ is installed.".postln;
	"  presets: % - %".format(ParaEQ.presetNames.size, ParaEQ.presetNames.join(", ")).postln;
	"  in a graph:  ParaEQ.ar(sig, \\eq)      then  ParaEQ(Ndef(\\x), \\eq).gui".postln;
	"  on a bus:    ParaEQ.onBus(0, 2).enabled_(true).gui".postln;
	"  the master:  FxChain.master.gui".postln;
	"  help:        ParaEQ, then cmd+d".postln;
	if (\Compressor.asClass.notNil) {
		"  Compressor is installed too - FxChain.master has one after the eq".postln;
	};
	if (\ModuleCommon.asClass.notNil) {
		"  ModuleCommon is installed too - every pad's eq is a ParaEQ".postln;
	};
	if (\ServerView.asClass.notNil) {
		"  ServerView is installed too - its fx button opens the master chain".postln;
	};
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "ParaEQ";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %. Compressor and ModuleCommon will not compile until it is back."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
