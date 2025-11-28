BlockPatterns TODO — Implementation Plan Outline

1. Goals and Non‑Negotiable Requirements
- Detect player‑built multi‑block structures ("patterns").
- Patterns can be rotated on any axis and optionally mirrored.
- Block requirements are composable (materials, blockstates, predicates, groups).
- Respect blockstate orientation during rotation transforms; allow config to disable or ignore specific rotations/mirrors per pattern.
- Precompute as much as possible; use chunk palettes, section palettes, and fast filters to short‑circuit scans.
- Dynamically compare multiple detection strategies and select the best at runtime based on measurements.
- Leverage Minecraft server statistics (global and per‑world) to identify anchors/key blocks shared across patterns.

2. High‑Level Architecture
- API module (inside plugin):
  - Pattern model: `Pattern`, `BlockRequirement`, `BlockGroup`, `Transform`, `PatternVariant`.
  - Detection API: `Detector`, `DetectionContext`, `DetectionResult`.
  - Registry: `PatternRegistry` (loaded from config/resources; hot‑reloadable).
- Engine module:
  - Precompute service: expands canonical pattern into rotation/mirror variants; computes anchors, bounding boxes, key‑block frequency tables.
  - Indexing: maps key blocks → candidate patterns/variants; builds bit‑set/ID maps for fast palette checks.
  - Detection strategies (pluggable via SPI): e.g., Anchor‑First BFS, Voxel Hash + Vote, Template Matching w/ Transform, Constraint Propagation.
  - Fast‑fail filters: chunk/section palette filter, height range, biome (optional), dimension whitelist.
  - Metrics + Strategy Coordinator: measures hit/miss cost, selects strategy per pattern/world dynamically.
- Integration layer:
  - Paper event listeners: block place/break, piston, explosion, multi‑block updates, chunk load, tick hooks (budgeted), world init.
  - Commands (Cloud): debug, profile, reload, inspect pattern, trace detection; permissions.
  - Adventure messaging for feedback; SLF4J for logs.

3. Pattern Definition & Configuration
- Format: YAML in `src/main/resources/patterns/*.yml` bundled into jar, merged with `plugins/BlockPatterns/patterns/` at runtime. Versioned via BoostedYAML (top‑level `version`).
- Components per pattern:
  - `id`, `name`, `description`.
  - `origin` (anchor) semantics and relative coordinates.
  - `shape`: 3D matrix or sparse list of positions with `BlockRequirement` expressions.
  - `requirements` language: material sets, blockstate predicates, tags (block groups), we will not implement groups, opting for tag definitions.
  - `transforms`: allowed rotations (X/Y/Z) and mirrors (X/Y/Z). Lists of allowed/ignored transforms; default = all.
  - `dimensions`: allowed worlds (overworld/nether/end), height ranges, optional biomes.
  - `tolerances`: allowed air gaps, replaceables, or fuzzy matches (optional future work).
  - `actions`: events to fire when matched; custom payload to listener.
- Tooling:
  - Validate patterns on load; compile to internal IR.
  - Generate and store `PatternVariant` hashes/anchors.

4. Transform System
- Define 24 rotation group (cube symmetries) + 3 mirrors per axis; represent transforms as 3x3 matrices with orientation remapping tables.
- For each blockstate type with facing/orientation (stairs, logs, directional, rotatable, axis, waterlogged, etc.), define remap functions under transform.
- Allow per‑pattern blacklist of transforms that are invalid for semantic reasons.
- Cache transformed variants (shape + state remaps + anchors) with stable IDs and hashes.

5. Precomputation & Indexing
- For each pattern:
  - Compute bounding box, volume, block frequency histogram, key block candidates (rarest/high‑information blocks).
  - Produce a minimal anchor set (positions within pattern) that are likely to appear in world (e.g., target a distinctive blockstate).
  - Precompute palette checksums: material ID bitsets required by the pattern.
- Build global indices:
  - Block → list of (patternVariant, relative positions where it appears).
  - Material and state signatures → candidate variants.
  - Optional biome/dimension tags to skip early.

6. World Data & Fast‑Fail Filters
- Chunk/Section palette filter:
  - For a chunk section (16x16x16), read its palette; if required materials of a candidate pattern variant are not subset of the section+neighbors palettes, skip.
  - Maintain rolling window across neighbor sections if pattern spans multiple sections.
- Height/dimension filters: reject via AABB if pattern would be out‑of‑bounds.
- Optional player proximity filter for on‑place checks to reduce scan radius.

7. Detection Strategies to Implement & Compare
- Strategy A: Anchor‑First Local Search
  - Triggered by block place/break of a key block; enumerate candidate variants from key‑block index; verify neighborhood via deterministic scan.
- Strategy B: Voxel Hash Vote
  - Compute locality‑sensitive hashes of neighborhoods; compare to precomputed pattern hashes; vote across anchors.
- Strategy C: Template Sweep (Chunk Scan)
  - We will never implement this strategy, instead letting players resolve it.
- Strategy D: Constraint Propagation
  - Use SAT‑like reduction with composable constraints; prune rapidly on contradictions.
- Metrics: record avg time/op, false positive rate, cache hit rate per world/pattern; auto‑select best per context.

8. Verification Engine (Exact Match)
- Given candidate origin + transform, verify all required positions:
  - Read blocks via NMS fast path to reduce allocations; batch access when possible.
  - Apply requirement predicates (material/state/NBT/predicate groups).
  - Early terminate on first failure; count checks for metrics.
- Optional: tolerance/fuzzy modes (later milestone).

9. Events and Actions
- Fire `PatternMatchEvent(patternId, origin, transform, matchedVolume, metadata)`.
- Provide action hooks: run commands, call webhooks, integrate with other plugins via API, Adventure messages.
- Add rate‑limits and cool‑downs per pattern/world and per region to avoid spam.

10. Runtime Profiling & Telemetry
- Embed lightweight metrics: timers, counters, histograms.
- Per‑strategy stats with dynamic selection and cooldowns to prevent oscillation.
- Debug modes: trace why a candidate was rejected (palette miss, anchor fail, verify fail).

11. Concurrency, Caching, and Budgets
- Caches:
  - Pattern variant cache (in‑memory; soft references).
  - Section palette cache with TTL and event invalidation on block changes.
  - Anchor candidate cache per chunk.
  - Cache recently matched partial patterns and resume.

12. Data Structures & NMS Boundaries
- Use primitive collections to minimize allocations in hot paths.

14. Configuration Surface
- `config.yml`:
  - global: performance budgets, strategy enable/disable, telemetry toggles, debug verbosity.
  - per‑pattern overrides (allowed transforms, anchors, tolerances, cooldowns).
- `patterns/*.yml`: see Section 3.

15. Persistence (Optional Later)
- Persist strategy performance snapshots per world to warm start after restart.

16. Testing Strategy (Dev‑time Guidance)
- Unit tests for transform remapping tables for key block types (stair/facing/axis).
- Property tests: applying inverse transform returns original state.
- Golden tests for palette fast‑fail correctness on synthetic chunk sections.
- Integration tests with an embedded world or scripted dev server scenarios.

17. Milestones & Deliverables
- M1: Project scaffolding, config bootstrap, command stub, metrics skeleton.
- M2: Pattern model + loader + transform system with orientation remaps and variant generation.
- M3: Precompute indices (anchors, histograms, palette bitsets); chunk/section palette cache.
- M4: Strategy A (Placed First) Check all patterns efficiently by brute force starting with the placed block.
- M5: Strategy B (Rare's First) Create a decision tree that hoists blocks that eliminate the most pattern candidates (across all patterns) to the top.
- M6: Strategy C (Cached) Optimize the cache of the decision tree to have pre-sliced arrays of references to NMS blocks in the format the palette lookup returns.
- M7: API events/actions; cooldowns; admin UX.
- M8: Optimization pass (allocs, data structures), docs, and release pipeline verification.

18. Risks & Mitigations
- Version drift with NMS/dev‑bundle: encapsulate NMS; CI to build against specified dev bundle.
- False positives/negatives: rigorous verification step, conservative fast‑fail filters, trace tools.
- Performance regressions: guard with budgets and dynamic strategy selection; continuous profiling.
- Complex transform rules for exotic blocks: start with common directional/axis blocks, expand iteratively.

19. Open Questions / Things to Decide
- Minimum granularity for palette prefilter: section vs. chunk vs. neighborhood radius? Start with section + neighbors.
- Should mirrors be enabled globally or per pattern? Default per pattern with global default = enabled.
- How to represent composable requirements DSL: YAML + mini‑DSL vs. purely structured keys? Favor structured keys first.
- Do we support fuzzy/tolerant matches (e.g., decorations ignored)? Defer to later milestone.
- When to recompute indices after reloads and world events; background thread policies.

20. Nice‑to‑Haves (Later)
- Editor tooling to author patterns from a selection in‑game (`/bp capture area` → YAML export).
- Visualization overlays (particles/adventure maps) for matched shapes.
- Service Provider Interface so other plugins can register patterns programmatically.

References & Implementation Notes
- Build with Java 25 toolchain; run via `./gradlew runDevBundleServer`.
- Use Shadow relocations already configured; keep new shaded libs relocated and test on a clean server.
- Keep `defaultConfig.yml` version key bumped when changing defaults; BoostedYAML will merge to disk.
