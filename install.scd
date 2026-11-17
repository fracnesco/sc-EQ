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
// ParaEQ is built on sc fx kit (../sc fx kit), which holds what every
// effect of the family stands on: FxUnit, chains, racks, the widgets. If it
// is sitting next to this folder, this script links it as well. Compressor
// (../sc compressor), ModuleCommon (../module common) and SuperDirtGUI use
// ParaEQ, and their installers link this one when it sits next to them.
// BusManager, if it happens to be installed, lets a bus be named.
//
// Until 2026-11 that framework lived here. With an sc fx kit from before
// then, the class library will not compile: update it (git pull) too.

(
var link, src, parent, kit;

src = PathName(thisProcess.nowExecutingPath).pathOnly;
if (src.endsWith("/")) { src = src.drop(-1) };			// ln -s dislikes it
parent = PathName(src).parentPath;

link = { |from, to, what, done|
	if (from.endsWith("/")) { from = from.drop(-1) };
	case
	{ File.exists(to) } {
		("ParaEQ: % is already installed at %.".format(what, to)).postln;
		done.value;
	}
	{ File.exists(from).not } {
		("ParaEQ: % is not installed, and there is no copy of it at %."
			"\n  The class library will not compile without it."
			"\n  Install it first, then run this again."
			.format(what, from)).warn;
	}
	{ true } {
		File.mkdir(Platform.userExtensionDir);
		("ln -s" + from.shellQuote + to.shellQuote).unixCmd({ |exit|
			if (exit == 0) {
				"ParaEQ: linked % -> %".format(what, to).postln;
				done.value;
			} {
				"ParaEQ: linking % failed with exit code %".format(what, exit).warn;
			};
		});
	};
};

// an sc fx kit from before the move has no FxUnit: ParaEQ would have no base
kit = [Platform.userExtensionDir +/+ "FxKit", parent +/+ "sc fx kit"].detect { |d| File.exists(d) };
if (kit.notNil and: { File.exists(kit +/+ "classes/FxUnit.sc").not }) {
	("ParaEQ: % is an older sc fx kit, from before the framework moved into it."
		"\n  Update it (git pull), or ParaEQ has no FxUnit to stand on.".format(kit)).warn;
};

link.value(parent +/+ "sc fx kit", Platform.userExtensionDir +/+ "FxKit", "FxKit", {
	link.value(src, Platform.userExtensionDir +/+ "ParaEQ", "ParaEQ", {
		"".postln;
		"Now recompile the class library: ctrl/cmd + shift + L".postln;
	});
});
)


// ---------------------------------------------------------------- verify
(
var missing = [\ParaEQ, \ParaEQView, \ParaEQPanel, \ParaEQGUI, \FxUnit]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("ParaEQ is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors - a missing or old sc fx kit is the"
		"\n  usual reason.".format(missing.join(", "))).warn;
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
	if (\ServerPanel.asClass.notNil) {
		"  ServerPanel is installed too - its fx button opens the master chain".postln;
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
