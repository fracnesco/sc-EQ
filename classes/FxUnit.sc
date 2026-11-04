// =====================================================================
// FxUnit - what an effect is, whichever graph it ends up in
//
// An effect here is two things that agree with each other: a piece of
// DSP written as a pseudo-UGen that reads named controls, and an object
// on the language side that holds the settings and pushes them at
// whatever node is running that DSP. The same DSP runs in three places,
// and the same object drives all three:
//
//   // in any graph of your own - an Ndef, a SynthDef - as a UGen
//   Ndef(\pad, { var sig = Saw.ar(110); ParaEQ.ar(sig, \eq) ! 2 });
//   e = ParaEQ(Ndef(\pad), \eq);       // drives that graph's \eq control
//   e.gui;
//
//   // on a bus, in place, with a synth of its own
//   e = ParaEQ.onBus(16, 2);
//   e.enabled = true;
//
//   // in a chain of effects on a bus - see FxChain
//   FxChain.master.units[0];
//
// Whatever way it runs, the object has the same settings, the same
// presets, the same clipboard, the same window - and a subclass writes
// its DSP once. What it provides:
//
//   *ar           the DSP, reading NamedControls off `name`
//   *defName      the name of the in place synth, per channel count
//   *synthDef     that synth: In - *ar - XOut
//   controls      the flat array the node is given
//   settings      what a preset and the clipboard hold
//   settings_     the same, put back
//   asDict / fromDict   what a set or a kit writes, enabled and all
//   *guiClass     its window
//   *panelClass   the same as a view, for a window with several
//
// `enabled` means what it says in every mode: with a node of its own the
// node is built or faded out; driving a graph, an `On` control is set
// and the DSP crossfades to dry. Off costs nothing when it can.
// =====================================================================

FxUnit {
	classvar defs, sent, quitHooks;

	var <enabled = false;
	var <target;				// the node it drives, in driver mode - nil otherwise
	var <controlName;			// the control prefix on it
	var <synth;					// its own node, on a bus
	var <server, <bus, <numChannels;
	var placement;				// [target, addAction] for the node of its own
	var <chain;					// the FxChain it belongs to, or nil
	var <>analysisBus;			// where an analyser reads this effect, if not obvious
	var <>action;				// { |unit, what| } - called on every change
	var <label;					// what a window calls it
	var <guiObj;
	var <>onFree;

	*initClass {
		defs = IdentityDictionary.new;
		sent = IdentityDictionary.new;
		quitHooks = IdentityDictionary.new;
	}

	// --------------------------------------------------------- what to build

	// the control the DSP reads when none is named
	*controlName { ^\fx }

	// the fade in and out of the chain when a node of its own is switched
	*fade { ^0.02 }

	// the DSP, the in place def, its name - a subclass has these
	*ar { |in, name| ^this.subclassResponsibility(thisMethod) }
	*defName { |numChannels| ^this.subclassResponsibility(thisMethod) }
	*synthDef { |numChannels| ^this.subclassResponsibility(thisMethod) }
	*guiClass { ^this.subclassResponsibility(thisMethod) }
	*panelClass { ^this.subclassResponsibility(thisMethod) }

	// the flat array of numbers the node is given, and what a preset holds
	controls { ^this.subclassResponsibility(thisMethod) }
	settings { ^this.subclassResponsibility(thisMethod) }
	settings_ { |data| ^this.subclassResponsibility(thisMethod) }

	// ---------------------------------------------------------------- new

	// Drive a control on a node: a Synth, a Group, an Ndef or any other
	// NodeProxy - anything that answers to `set`. With no target the object
	// is a holder of settings that something else pushes; the compressor
	// keeps its sidechain eq that way.
	*new { |target, controlName|
		^super.new.init(target, controlName)
	}

	// A node of its own, in place on a bus. `target` and `addAction` say
	// where it goes in the tree; the tail of the default group by default.
	// Nothing is built until it is switched on.
	*onBus { |bus = 0, numChannels = 2, target, addAction = \addToTail, server|
		^this.new(nil).prInitOnBus(bus, numChannels, target, addAction, server)
	}

	init { |argTarget, argControlName|
		controlName = (argControlName ? this.class.controlName).asSymbol;
		label = this.class.name.asString;
		this.prInitSettings;
		this.target_(argTarget);
		^this
	}

	// a subclass sets its defaults here
	prInitSettings { }

	prInitOnBus { |argBus, argChannels, argTarget, addAction, argServer|
		bus = FxUnit.prBusIndex(argBus) ? 0;
		numChannels = (argChannels ? 2).asInteger.clip(1, 64);
		server = argServer ?? { argTarget.tryPerform(\server) } ?? { Server.default };
		placement = [argTarget, addAction ? \addToTail];
		enabled = false;
		^this
	}

	free {
		this.prFreeNode;
		guiObj !? (_.close);
		chain !? (_.remove(this));
		onFree.value(this);
		this.changed(\freed);
	}

	label_ { |str| label = str.asString; this.changed(\label) }

	// ------------------------------------------------------------- target

	// The node this drives. Setting one pushes the settings at it - unless
	// the node already carries some, as an Ndef does in its nodeMap once it
	// has been set, in which case those are taken over.
	target_ { |node|
		var map, held;
		target = node;
		if (node.isNil) { ^this };
		server = node.tryPerform(\server) ? Server.default;
		map = node.tryPerform(\nodeMap);
		held = map !? { |m| m.tryPerform(\at, controlName) };
		if (held.notNil and: { this.prAcceptControls(held) }) {
			held = map.tryPerform(\at, this.onName);
			enabled = held.isNil or: { held.asArray.first > 0 };
			this.changed(\settings);
			this.changed(\enabled);
		} {
			enabled = true;
			this.prPushAll;
		};
		^this
	}

	// what a node carried, read back into settings: answers whether it could
	prAcceptControls { |array| ^false }

	isDriving { ^target.notNil }
	isOnBus { ^placement.notNil or: { chain.notNil } }

	onName { ^(controlName.asString ++ "On").asSymbol }

	// ------------------------------------------------------------ enabled

	enabled_ { |bool|
		bool = bool.booleanValue;
		if (bool == enabled) { ^this };
		enabled = bool;
		this.prEnabledChanged;
		this.prChanged(\enabled);
	}

	isActive { ^enabled }

	prEnabledChanged {
		case
		{ chain.notNil } { chain.prSyncUnit(this) }
		{ placement.notNil } { this.prSyncNode }
		{ true } { this.prPushOn };
	}

	// ------------------------------------------------------------- pushing

	// everything the node has to hear: the settings, and whether it is on
	prPushAll {
		this.prPush;
		this.prPushOn;
	}

	prPush {
		var c = this.controls;
		synth !? { |sy| sy.set(controlName, c) };
		target !? { |t| t.set(controlName, c) };
	}

	prPushOn {
		target !? { |t| t.set(this.onName, enabled.binaryValue) };
	}

	prChanged { |what|
		action.value(this, what);
		this.changed(what);
	}

	// ------------------------------------------------------------ the node

	// The node of its own: made when it is enabled and there is a server
	// to make it on, faded out when it is not. The def goes to the server
	// first and this comes back once it has arrived.
	prSyncNode {
		var tgt, act;
		if (chain.notNil) { ^chain.prSyncUnit(this) };
		if (placement.isNil or: { server.isNil }) { ^this };
		if (enabled.not) { ^this.prFreeNode };
		if (synth.notNil) { ^this };
		if (server.serverRunning.not) { ^this };
		if (this.class.prEnsureDef(server, numChannels, { this.prSyncNode }).not) { ^this };
		tgt = placement[0] ?? { server.defaultGroup };
		act = placement[1] ? \addToTail;
		this.prMakeNode(tgt, act);
	}

	// One bundle, sent now rather than at the server's latency: an array
	// control cannot travel in s_new, so the settings follow in the message
	// after it, and everything told to this node from here on is sent
	// without latency too and stays behind them.
	prMakeNode { |tgt, addAction|
		server.makeBundle(nil, {
			synth = Synth(this.class.defName(numChannels),
				[\bus, bus, \fade, this.class.fade] ++ this.prNodeArgs, tgt, addAction);
			this.prPush;
		});
		this.changed(\node);
		^synth
	}

	// a subclass with more to say to its node adds it here
	prNodeArgs { ^[] }

	prFreeNode {
		synth !? { |sy| sy.set(\fade, this.class.fade, \gate, 0) };
		synth = nil;
		this.changed(\node);
	}

	// the server took it with everything else
	prNodeGone { synth = nil }

	// ------------------------------------------------------------- chains

	prJoinChain { |aChain|
		chain = aChain;
		server = aChain.server;
		bus = aChain.bus;
		numChannels = aChain.numChannels;
		placement = nil;
		target = nil;
	}

	prLeaveChain {
		this.prFreeNode;
		chain = nil;
	}

	// ----------------------------------------------------------- analysis

	// Where an analyser reading this effect's output goes in the tree, as
	// [target, addAction] - after the node of its own, at the tail of a
	// driven proxy's group - and which bus it reads there. nil where there
	// is nothing to say: a driven Synth does not know its bus, so
	// `analysisBus` has to be set by hand before a spectrum can be drawn.
	analysisPlacement {
		var g;
		if (synth.notNil) { ^[synth, \addAfter] };
		if (target.notNil) {
			g = target.tryPerform(\group);
			g !? { ^[g, \addToTail] };
		};
		^nil
	}

	analysisBusIndex {
		^analysisBus ?? {
			if (target.notNil) {
				target.tryPerform(\bus) !? { |b| b.tryPerform(\index) }
			} { bus }
		}
	}

	analysisChannels {
		^numChannels ?? { target.tryPerform(\numChannels) } ? 2
	}

	analysisAvailable {
		^this.analysisPlacement.notNil and: { this.analysisBusIndex.notNil }
	}

	// ----------------------------------------------------------------- gui

	gui { |x, y|
		if (guiObj.isNil) { guiObj = this.class.guiClass.new(this, x, y) } { guiObj.front };
		^guiObj
	}

	prGuiClosed { guiObj = nil }

	// the same controls as a view, for a window that shows several effects
	panel { |parent| ^this.class.panelClass.new(this, parent) }

	// --------------------------------------------------------------- state

	asDict { ^(enabled: enabled, settings: this.settings) }

	fromDict { |d|
		if (d.isNil) { ^this };
		d[\settings] !? { |v| this.settings_(v) };
		d[\enabled] !? { |v| this.enabled_(v) };
	}

	// --------------------------------------------------------------- defs

	// The in place def for a channel count, built once and kept.
	*def { |numChannels|
		var key = this.defName(numChannels);
		^defs[key] ?? { defs[key] = this.synthDef(numChannels); defs[key] }
	}

	// Whether the def is on that server. If not, it is sent, and `done` is
	// called once the server has it - so a node is never asked for before
	// its def has arrived.
	*prEnsureDef { |server, numChannels, done|
		var key = this.defName(numChannels);
		var servers = sent[key] ?? { sent[key] = IdentitySet.new; sent[key] };
		if (servers.includes(server)) { ^true };
		this.prWatchQuit(server);
		{
			var d = this.def(numChannels);
			d.add;
			// add has told every booted server already. A big def goes by a file
			// that each send deletes, so telling one twice would lose it
			if (Server.allBootedServers.includes(server).not) { d.send(server) };
			server.sync;
			servers.add(server);
			done.value;
		}.fork(AppClock);
		^false
	}

	// after a quit nothing that was sent is there any more
	*prWatchQuit { |server|
		quitHooks[server] ?? {
			var f = { sent.do { |set| set.remove(server) } };
			quitHooks[server] = f;
			ServerQuit.add(f, server);
		};
	}

	*prDefGone { |server| sent.do { |set| set.remove(server) } }

	// ------------------------------------------------------------ helpers

	// A Bus, a bus index, a BusManager name - all ways of saying which bus.
	// The registry is looked up by name at runtime, so this compiles and
	// runs without it; without it a name is not a bus and says so.
	*prBusIndex { |val|
		var reg, index;
		if (val.isNil) { ^nil };
		if (val.isNumber) { ^val.asInteger };
		if (val.isKindOf(Bus)) { ^val.index };
		if (val.isKindOf(Symbol) or: { val.isKindOf(String) }) {
			reg = \BusManager.asClass;
			index = reg !? { reg.index(val.asSymbol) };
			if (index.isNumber) { ^index.asInteger };
			("% is not a registered bus name%".format(val.asCompileString,
				if (reg.isNil) { " - BusManager is not installed" } { "" })).warn;
			^nil
		};
		if (val.respondsTo(\tapBus)) { ^this.prBusIndex(val.tapBus) };
		index = val.tryPerform(\asControlInput);
		if (index.isNumber) { ^index.asInteger };
		("% cannot be read as a bus".format(val.asCompileString)).warn;
		^nil
	}

	printOn { |stream|
		stream << this.class.name << "(" << label << ", "
			<< if (enabled) { "on" } { "off" } << ")"
	}
}
