# Scan V3 — Design Notes

This is a design sketch, not production code. It's written to be as close to
compileable as possible under Scala 3.8.3 + Kyo 1.0-RC1 + capture checking,
but there are specific things the sketch does not carry through to production.
This document tells you what they are, in order of severity.

## What works in this design

**The match-type treatment of `Nothing` as state identity.** This is the core
idea and it works. `ComposeState[Nothing, S] = S` reduces at the type level,
and composition of two pure scans produces a scan whose state type is
`Nothing`, which means no state threading at runtime. This is cleaner than the
`NoState` sentinel pattern used in FreeScanV2.

**Effect row composition via Kyo's `<` type.** Composing a scan with `E = Any`
and a scan with `E = Sync` produces a scan with `E = Any & Sync`, which Kyo
reduces to `Sync`. This is structurally correct. The same pattern works for
any combination of Kyo effects.

**Fusion of pure chains.** `Arr >>> Arr` becomes a `FusedArr` with the
functions chained. `FusedArr >>> Arr` extends the chain. Beyond the fusion
threshold (128 functions), further composition creates an `AndThen` rather
than extending the chain, preventing both stack overflows and pathological
O(n) per-element iteration through a huge array. This is the Cats `AndThen`
pattern and it's production-tested there.

**Introspectable GADT structure.** The composed scan is a concrete ADT you can
pattern-match on at any time: an interpreter can walk the tree, inline pure
nodes, discover opportunities for further fusion, generate code, whatever.
This is the "free" in the design.

**Arrow combinators with proper laws.** `>>>`, `&&&`, `***`, `|||`, `map`,
`contramap`, `dimap` all make sense and the composition algebra is consistent.

## What's sketched but not production-ready

### The Kyo-to-ZIO bridge

The `kyoToTask` helper in `KyoInterp.scala` is currently naive. It assumes
`effect.asInstanceOf[A]` works to extract a pure value, which is only correct
when the effect row is actually empty (`A < Any`). For `A < Sync` it should
call `Sync.run(effect)`. For `A < Abort[E]`, `Abort.run`. For combinations,
you sequence the handlers in the right order.

Production version needs to pattern-match on the effect row at the type level
(via a typeclass like `ToTask[E]`) and dispatch to the right handler. This
is a day or two of work once you've decided which Kyo effects you actually
want to support.

### The `release` LIFO ordering

`ComposeOps.releaseAndThen` says "release right (acquired later) before left."
That's the right order for resources acquired in init order. But the init for
AndThen allocates *left first*, then right. So right is acquired later, and
should be released first. The code has it right, but the comment in an earlier
draft I didn't keep had it backwards — this is exactly the kind of thing you
need a test suite to verify, because the ordering matters for correctness.

A real test would:

1. Allocate two resources that log on release.
2. Compose scans that use them in a pipeline.
3. Run and close the scope.
4. Verify the release log shows the right order.

The sketch doesn't include this test.

### Capture checking on the state type

The HashingScan example marks the state type as `HasherState^` (capture set
containing some capability), but the sketch doesn't wire this through the
composition operators. In production, `Scan.Aux[I, O, S, E]` where `S` has a
capture set needs the compiler to track that capture through `>>>` and `&&&`
so that composed scans correctly declare their resource requirements.

In current Scala 3.8 this works for simple cases but the error messages when
it doesn't work are rough. I'd expect to spend real time on capture-annotation
ergonomics here before this is usable in daily work. Specifically:

1. The `^` annotation on type aliases (like `HasherState_^ = HasherState^`)
   needs to propagate correctly through match-type reduction.
2. Kyo's `< S` type has its own variance and capture interactions that aren't
   fully documented.
3. Some scalac edge cases around separation checking in SAM conversions are
   known; the Scala Users forum thread I found during research is one example.

Plan on this being 2-3 weeks of experimentation before you can use the `^`
annotations throughout without cryptic compile errors.

### The Kyo Record issue

AGENTS.md in the Graviton repo pins Scala to 3.7.4 because `kyo.Record` has
a `selectDynamic` regression on 3.8+. This sketch avoids that by not using
`Record` at all — named state is modelled via the `StateTypes` match types
and plain tuples.

If you want Records back, you have three options, ordered by how much work:

1. **Don't use Records.** The current design works fine with tuples.
2. **Use Records only for *input/output* types, not state.** The regression
   is in composed-state record projection. If Records appear only at scan
   boundaries (inputs and outputs), the issue doesn't bite.
3. **Fork Kyo's Record.scala and fix the `selectDynamic` dispatch.** This is
   a genuinely tractable fix (maybe a day of work) if you care enough.

The current sketch goes with option 1 because it's the lowest-risk path.

### The "fast path" for pure scans in `toZPipeline`

The interpreter has a fast path that compiles `FusedArr` to a single
`ZPipeline.map`. This is correct and valuable. But the threshold for whether
a composed scan counts as "pure enough" to take the fast path is currently
binary (is the top-level node an Arr/FusedArr?). In a real implementation,
you'd do a deeper structural analysis:

- A `Product` of two pure scans can also be compiled to a pure map.
- A `Choice` of two pure scans can also be compiled to a pure map.
- A `Fanout` of two pure scans can be compiled to a pure map producing tuples.

The sketch only takes the fast path for the simplest case. A production
interpreter would walk the tree looking for the largest pure subtree and
compile it with a single map. This is an optimization; it doesn't change
correctness.

## What's unresolved

### Whether `Sync` is the right effect for JVM resource allocation

`HashingScan` uses `Sync` as the effect row for `MessageDigest.getInstance`.
Kyo's `Sync` is roughly "synchronous side effects, including JVM calls." This
is probably right, but Kyo 1.0-RC1 is still stabilizing its effect taxonomy
and `Mem` or a more specific effect might turn out to be preferable. Worth
a conversation with Flavio or a look at the kyo-core examples.

### Whether to expose `Aux` or hide it

I've used `Scan.Aux[I, O, S, E]` freely in method signatures. This is fine
for library implementors but terrible for library users, who don't want to
see state types in their type signatures. The usual solution is to have two
layers: public API methods take `Scan[I, O]` with existential state, and
internal implementations use `Aux` to access the specific state. This
sketch doesn't draw that line cleanly yet.

### Whether to use direct-style Kyo or flatMap chains

I used `kyo.Kyo.flatMap` and `kyo.Kyo.map` throughout because they're
universally available. Kyo also supports direct-style via its compiler plugin,
which would make the `ComposeOps` code much more readable. But the plugin is
still stabilizing and I didn't want to make the sketch depend on it.
Production code should probably use direct-style for readability.

## What to do with this

If you want to ship this:

1. **Write the full test suite first.** Property tests for all the laws,
   explicit tests for LIFO release ordering, tests for the Kyo→ZIO bridge
   under each effect row.
2. **Pick a concrete first application.** The natural one is to replace
   the block-keyed ingest pipeline in `CasIngest.pipeline`. That pipeline is
   already a `>>>` chain of pure and effectful scans, and the new design
   would let it carry capture annotations that prevent accidental hasher
   leaks.
3. **Leave FreeScanV2 in place.** The sketch is a parallel implementation,
   not a replacement. FreeScanV2 is working in production; this is a
   candidate for the next generation, not the current one.

If you don't want to ship this:

That's also fine. The design is still useful as a reference — it clarifies
what the ideal scan algebra looks like, which informs what FreeScanV2 could
grow into over time. And the exercise of writing it out identifies the
actual sharp edges (Kyo→ZIO bridge, capture-annotation ergonomics, Record
issue) that you'd have to solve no matter what path you take.

My honest advice: write the tests. If they pass, you've learned something
about your constraints. If they don't, you've learned what's actually hard.
Either way, don't ship this ahead of Nevada.
