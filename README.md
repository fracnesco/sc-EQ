# ParaEQ

**A five band parametric eq with a high pass and a low pass, that runs anywhere
a UGen does, driven by an object that runs anywhere sclang does.** A
SuperCollider extension.

Write `ParaEQ.ar` into a graph of your own — an Ndef, a SynthDef — and drive its
bands from an object with a window: the curve, seven handles, the spectrum of
the signal behind them, presets, a clipboard. The same object can own a synth on
a bus instead, in place, or sit in a chain of effects on the main out.

```supercollider
s.boot;
Ndef(\pad, { var sig = Saw.ar([110, 110.7]) * 0.1; ParaEQ.ar(sig, \eq) }).play;

e = ParaEQ(Ndef(\pad), \eq);     // drives that Ndef's \eq control
e.gui;                           // drag the dots; the analysis behind is the Ndef's bus
e.preset = \telephone;
e.setBand(0, 80, -9, 0.8);       // low shelf, 80 Hz, -9 dB, rs 0.8
e.highPassOn = true;             // and nothing under 60 Hz
e.highPassFreq = 60;
```

It depends on nothing. Two other extensions of mine are built on it —
[Compressor](../sc%20compressor) and [ModuleCommon](../module%20common), whose
pads and slots each own one of these — and [ServerView](https://github.com/fracnesco/ServerView.quark)
grows an `fx` section that opens the master chain when this is installed.
[BusManager](../sc%20bus%20manager) is optional and lets a bus be named.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific
apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-eq.git");
thisProcess.recompile;
```

`Quarks.install` clones the repository into your `downloaded-quarks` folder and
puts it on the class path; the recompile is what makes the classes exist, and is
the same thing as `Language -> Recompile Class Library` (ctrl/cmd + shift + L).

The quark goes by the name of the repository, `sc-eq`; the class inside it is
`ParaEQ`.

### From a clone

If you would rather have a working copy you can edit, clone it wherever you keep
things and link it into the extensions folder:

```sh
git clone https://github.com/fracnesco/sc-eq.git
```

There is an `install.scd` in the repository that does the linking for you: open
it and evaluate it, then recompile the class library. It symlinks the folder
into `Platform.userExtensionDir` as `ParaEQ` and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy the folder into the
extensions folder by hand — `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a
duplicate class error at compile time.

### Check that it took

```supercollider
ParaEQ.presetNames;              // -> [flat, rumble cut, warm, bright, ...]
ParaEQ.onBus(0, 2).enabled_(true).gui;
```

Select `ParaEQ` and press cmd/ctrl + d for the help file, and see
[examples/paraeq-demo.scd](examples/paraeq-demo.scd) for a walkthrough you can
evaluate a block at a time.

## In a graph of your own

`ParaEQ.ar(in, name)` is the filter — five biquads in series, low shelf, three
peaks, high shelf, each a `[freq, dB, q]` triple, with a high pass and a low
pass in front of them — reading its bands off a control called `name`, its two
cuts off `name` + `Cut`, their slopes off `name` + `Slope`, and an on/off switch off `name` + `On`. It goes wherever
a UGen goes:

```supercollider
Ndef(\pad, { var sig = ...; ParaEQ.ar(sig, \eq) });

SynthDef(\voice, { |out| var sig = ...; Out.ar(out, ParaEQ.ar(sig, \tone)) }).add;
x = Synth(\voice);
```

and the object drives it, whatever the node is — an Ndef, a Synth, a Group,
anything that answers to `set`:

```supercollider
e = ParaEQ(Ndef(\pad), \eq);
t = ParaEQ(x, \tone);
e.bands;                         // [[freq, dB, q], ...] x 5
e.setBand(2, 900, -6, 1.4);      // one band; nil leaves a part where it was
e.dbAt_(4, 3);                   // one number of one band
e.enabled = false;               // crossfades to dry; the graph does not know
e.gui;
```

## Either end taken off

After the five bands come two cuts — a high pass and a low pass. They are
switches rather than gains, and both are **out until you switch one in**, so an
eq that has never been told about them is the eq it always was:

```supercollider
e.highPassOn = true;             // the rumble gone
e.highPassFreq = 60;
e.highPassQ = 1.414;             // an rq: 1.414 is flat, smaller rings at the corner
e.highPassSlope = 48;            // how hard it cuts: 6 to 96 dB per octave, in 6s, 12 by default
e.lowPassOn = true;  e.lowPassFreq = 12000;
e.toggleCut(ParaEQ.highPass);    // the same two by handle index: 5 and 6
e.flat;                          // every band flat and both cuts out again
```

They travel on a control of their own, `name` + `Cut`, so a node built before
they existed still takes the bands; a preset, the clipboard and a saved kit
carry them along with the curve, and anything saved before they existed reads
back with both of them out.

The slope goes from 6 to 96 in steps of 6, because a filter only comes in whole
orders, 6 dB per octave each. Every step is 3 dB down at the corner, so changing
it only changes how hard the filter falls away, and a value between steps goes to
the nearest one. Nothing fades as it changes, so moving it on a playing sound can
click. 12 is the one biquad these cuts always were, and anything saved before there
were slopes reads back at 12. Your rq works on the narrowest stage, and at 6 there
is none for it to work on. Having all of it in the graph costs a stereo eq 51 KB of real time memory against
16 before, whatever the slope is; the CPU hardly moves.

An Ndef keeps what it was set in its nodeMap, so a second object made for the
same Ndef adopts the bands it already carries instead of resetting them, and a
rebuilt Ndef comes back with them.

## On a bus, in place

The same object can own a synth instead:

```supercollider
e = ParaEQ.onBus(16, 2);         // a Bus, an index, or a BusManager name
e.enabled = true;                // the node is built, at the tail of the default group
e.enabled = false;               // and faded out again - off costs nothing
e = ParaEQ.onBus(0, 2, target: someGroup, addAction: \addToTail);
```

`enabled` means the same thing in every mode: with a node of its own the node
exists exactly while the eq is on; driving a graph, the `On` control crossfades
the filter out. That is the one thing this extension has to get right, and
everything else — the chain below, the module family's pads — is built on it.

## A chain of effects on a bus

`FxChain` is a group on the server with a list of effects inside it, all working
on one bus one after another. The one everybody wants is the master chain:

```supercollider
c = FxChain.master;              // bus 0, one channel per output
c.units;                         // -> [ParaEQ, Compressor] when Compressor is installed
c.eq.enabled = true;
c.eq.preset = \warm;
c.compressor.threshold = -18;    // nil without the Compressor extension
c.gui;
```

and any bus will do, with any effects, in any order:

```supercollider
c = FxChain(\verb, bus: 16, numChannels: 2);
c.add(ParaEQ.new);
c.add(Compressor.new);
c.moveUnit(1, 0);
c.remove(c.units[0]);
c.enabled = false;               // the whole chain out, whatever each effect says
```

The group is built after the default group when the server comes up and again
after cmd + period, so the chain hears everything that plays, and whatever reads
the bus at the tail — ServerView's meters, a recorder, Insight — hears the chain.
The server's own volume synth sits before it, so the volume knob is a trim into
the chain rather than a fader after it. An effect that is off has no node.

A chain writes to one preset file and reads it back effect by effect, so a
master setting travels as one thing:

```supercollider
c.savePreset("tonight");         // -> <this repo>/presets/tonight.scd
c.loadPreset("tonight");
FxChain.presetNames;
```

The folder is ignored by git, so updating the extension never touches your
presets.

## Presets, and a clipboard

Every eq shares one preset list: the ones the library ships and, after them, the
ones you save.

```supercollider
ParaEQ.presetNames;
e.preset = \loudness;
e.presetName;                    // -> \loudness, or nil once you move a band
e.saveAsPreset("my room");       // joins every eq's menu, in this session and the next
ParaEQ.removePreset("my room");
ParaEQ.presetFile;               // paraeq-presets.scd in the support folder - not the repository
```

A name the library ships is refused rather than shadowed: those are what every
kit, set and chain preset is written against.

And one curve can be carried anywhere — from a pad in OneShot to the master eq,
from an Ndef to a chain:

```supercollider
e.copyBands;
other.pasteBands;                // only the curve moves, not whether it is on
other.copyFrom(e);               // the same without the clipboard
```

## The window

`e.gui`, or the same panel inside another window — the compressor's sidechain
page, the master chain, a module's eq tab.

```
 [x] eq on  [x] spectrum  preset [warm v] [flat]  [name   ][save][delete]   [copy][paste]
 +--------------------------------------------------------------------------------+
 |  the curve, seven handles, and behind them the spectrum of what the eq is on   |
 +--------------------------------------------------------------------------------+
 [low shelf][peak 1][peak 2][peak 3][high shelf][high pass][low pass]
                              freq [ 80] dB [-9.0] q [0.80] [x] in
 hover a control and this bar says what it does
```

- **drag a dot** for frequency and gain; a click picks up the nearest one, so a
  dot never has to be hit exactly.
- **cmd + drag up and down**, or the **wheel**, for the width of the selected band.
  It is relative, so it never jumps.
- **shift** while dragging snaps to round frequencies, whole dB, round widths.
- **double click** a dot for 0 dB — or, on one of the two cuts, in and out.
- The two cuts are the last handles, drawn as **rings** while they are out.
  `in` is their switch; the dB box greys out on one, since a cut has no gain.
- The line along the bottom says what the pointer is on. Untick **hints** to fold
  it away.

The curve is worked out in the language from the same coefficients the server
runs, so what you see is the filter you get — not a sketch of it.

### The spectrum behind the curve

While the eq is on, the shape behind the curve is what the eq is working on:
the signal just after the filters, analysed and drawn on its own dB scale under
the frequencies it belongs to, with a slower mark hanging over the peaks. An eq
with a node of its own is read right after that node; an eq driving an Ndef is
read on the Ndef's bus; an eq driving a plain Synth has no bus to read until you
say which — `e.analysisBus = 16`.

Nothing is analysed unless a window is showing it. The **spectrum** box is a
class wide switch, `ParaEQSpectrum.on`, so it holds across windows and sessions.

## The module family

With [ModuleCommon](../module%20common) installed, every OneShot pad, LongSample
slot and Warper slot's eq *is* one of these — `ModuleEQ` is a `ParaEQ` on the
slot's own chain — with the same presets, the same clipboard and the same panel
on the module window's eq tab. Kits and sets store the eq as they always have.

ModuleCommon needs this repository to compile; its installer links this one first
when it sits next to it.

## Server restarts

An eq with a node of its own, and every chain, registers with `ServerBoot`,
`ServerTree` and `ServerQuit`: the defs go up on boot, the nodes are rebuilt with
the tree after cmd + period, and nothing is talked to after a quit. An eq driving
an Ndef leaves all of that to the Ndef.

## Files

| file | what it holds |
|------|---------------|
| [classes/ParaEQ.sc](classes/ParaEQ.sc) | the filter as a UGen, the object that drives it, the bands, presets, clipboard, the curve's maths |
| [classes/ParaEQView.sc](classes/ParaEQView.sc) | the response curve with a handle per band, over the spectrum |
| [classes/ParaEQSpectrum.sc](classes/ParaEQSpectrum.sc) | what the eq is working on: one FFT, read back a frame at a time |
| [classes/ParaEQGUI.sc](classes/ParaEQGUI.sc) | `ParaEQPanel`, the controls as one view, and `ParaEQGUI`, the window |
| [classes/FxUnit.sc](classes/FxUnit.sc) | what an effect is: driving a node, owning a node, joining a chain, presets, the window hook |
| [classes/FxChain.sc](classes/FxChain.sc) | a series of effects on a bus, the master chain, chain presets, server lifecycle |
| [classes/FxChainGUI.sc](classes/FxChainGUI.sc) | `FxChainPanel` and `FxChainGUI`: the effects stacked, with their own panels |
| [classes/FxPresetStore.sc](classes/FxPresetStore.sc) | named settings: the ones the library ships and yours, on disk |
| [classes/FxHintBar.sc](classes/FxHintBar.sc) | the strip that says what the mouse is over |
| [classes/FxPalette.sc](classes/FxPalette.sc) | the colours and fonts every effect window is drawn in |
| [HelpSource/Classes/](HelpSource/Classes/) | the help files, cmd/ctrl + d on a class name |
| [examples/paraeq-demo.scd](examples/paraeq-demo.scd) | a walkthrough you can evaluate a block at a time |
| [install.scd](install.scd) | symlinks the repository into the extensions folder |
| [sc-eq.quark](sc-eq.quark) | the quark manifest, named after the repository so `Quarks` can find it |

## Issues

Bug reports and questions are welcome in
[Issues](https://github.com/fracnesco/sc-eq/issues). If something misbehaves,
the post window usually has the half of the story I need.

## License

MIT — see [LICENSE](LICENSE).
