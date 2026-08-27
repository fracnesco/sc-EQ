// =====================================================================
// FxPresetStore - named settings, the ones the library ships and yours
//
// Every effect class keeps one of these: a list of the presets it was
// written with, and after them the ones you have saved, which live in a
// file of your own between sessions. The store does not care what a
// preset holds - an array of eq bands, an Event of compressor settings -
// only that it can be written with asCompileString and read back.
//
//   store = FxPresetStore(ParaEQ, [\flat, bands, \warm, bands], path);
//   store.names;                 // the menu
//   store.at(\warm);             // a copy of one
//   store.add("my room", bands); // joins the menu, and the file
//   store.remove("my room");
//   store.detect(bands);         // the name these settings are, or nil
//
// A name the library ships is refused rather than shadowed: those are
// what every saved kit and chain is written against. Adding, removing
// and clearing announce `\presets` on the owning class, so every open
// window hears about it.
// =====================================================================

FxPresetStore {
	var <owner;				// the class that announces \presets
	var <builtins;			// name, data, name, data - in menu order
	var <userPresets;		// the same, for the ones you save yourself
	var <>file;				// where those are kept between sessions
	var <>legacyFile;		// read once, if `file` does not exist yet
	var loaded = false;

	*new { |owner, builtins, file, legacyFile|
		^super.newCopyArgs(owner, builtins ? [], [], file, legacyFile)
	}

	// The ones the library ships, then the ones you have saved. Reading
	// them is what brings the saved ones in from disk, so nothing is read
	// at startup that may never be looked at.
	all {
		this.prEnsure;
		^builtins ++ userPresets
	}

	names { ^this.all.clump(2).collect(_.first) }

	// a copy of one preset's data, or nil
	at { |name|
		var all = this.all;
		var i = all.indexOf(name.asSymbol);
		^i !? { all[i + 1].deepCopy }
	}

	includes { |name| ^this.names.includes(name.asSymbol) }

	isBuiltin { |name| ^builtins.clump(2).any { |pair| pair[0] == name.asSymbol } }

	// whether a name is one of yours, and so can be written over or removed
	isUser { |name|
		this.prEnsure;
		^userPresets.clump(2).any { |pair| pair[0] == name.asSymbol }
	}

	// the preset some settings are, or nil once you have moved something
	detect { |data|
		var all = this.all;
		var i = all.clump(2).detectIndex { |pair| pair[1] == data };
		^i !? { all[i * 2] }
	}

	// Keep some settings under a name. Saving under a name you have used
	// before writes over it. Answers the name, or nil if it was refused.
	add { |name, data|
		var i;
		name = name.asString.stripWhiteSpace;
		if (name.isEmpty) {
			("%: a preset needs a name".format(owner.name)).warn;
			^nil
		};
		name = name.asSymbol;
		if (this.isBuiltin(name)) {
			("%: % is one of the built in presets - pick another name"
				.format(owner.name, name.asCompileString)).warn;
			^nil
		};
		if (data.isNil) {
			("%: nothing to keep under %".format(owner.name, name.asCompileString)).warn;
			^nil
		};
		this.prEnsure;
		data = data.deepCopy;
		i = userPresets.indexOf(name);
		if (i.notNil) { userPresets[i + 1] = data } {
			userPresets = userPresets ++ [name, data];
		};
		this.prWrite;
		owner.changed(\presets);
		^name
	}

	// Drop one of yours. The built in ones stay where they are.
	remove { |name|
		var i;
		this.prEnsure;
		name = name.asSymbol;
		i = userPresets.indexOf(name);
		if (i.isNil) {
			("%: % is not one of yours to remove"
				.format(owner.name, name.asCompileString)).warn;
			^nil
		};
		userPresets = userPresets.copy;
		userPresets.removeAt(i);		// the name
		userPresets.removeAt(i);		// and its data
		this.prWrite;
		owner.changed(\presets);
		^name
	}

	// Forget the lot, on disk as well.
	clear {
		userPresets = [];
		loaded = true;
		this.prWrite;
		owner.changed(\presets);
	}

	// ------------------------------------------------------------ the file

	prEnsure {
		var data, path;
		if (loaded) { ^this };
		loaded = true;					// a file that will not read is not
		userPresets = userPresets ? [];	// read again every time it is asked
		path = file;
		if (path.isNil or: { File.exists(path).not }) {
			// the first session after a rename reads the old file once
			path = legacyFile;
			if (path.isNil or: { File.exists(path).not }) { ^this };
		};
		data = try { path.load };
		if (data.isArray and: { data.size.even }) {
			userPresets = data;
			if (path != file) { this.prWrite };	// and keeps it under the new name
		} {
			("%: could not read the presets at %".format(owner.name, path)).warn;
		};
		^this
	}

	prWrite {
		if (file.isNil) { ^this };
		try {
			File.use(file, "w", { |f| f.write(userPresets.asCompileString) });
		} { |err|
			("%: could not write the presets to %".format(owner.name, file)).warn;
		};
	}
}
