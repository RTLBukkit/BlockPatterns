package au.id.rleach.blockpatterns;

import com.mojang.brigadier.StringReader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

/**
 * Two parsers for Minecraft-style block predicate strings (as used by vanilla commands):
 * - API-Only: parses syntax and produces a matcher using Bukkit/Paper APIs only
 * - NMS-Based: delegates to Mojang's parser to validate and reflect the parsed block/tag and state properties
 *
 * Grammar supported (subset of vanilla for now):
 *  - Block: "minecraft:oak_log[axis=x]", "stone", "minecraft:furnace[facing=north,lit=true]"
 *  - Tag:   "#minecraft:logs", "#minecraft:planks[axis=x]" (states are allowed and apply to each block in tag)
 *
 * Semantics:
 *  - If a blockstate property is specified, it is REQUIRED to match exactly.
 *  - If a blockstate property is omitted, it is treated as a wildcard.
 *  - Orientation-related properties are tracked separately for transform (rotate/mirror) reasoning.
 *
 * NOTE: NBT predicates and data components are intentionally out-of-scope for this milestone.
 */
public final class BlockStatePredicateParsers {

    private BlockStatePredicateParsers() {}

    /** Known blockstate property names that are affected by rotation/mirroring. */
    private static final Set<String> ORIENTATION_PROPERTIES = Set.of(
            // Common across many blocks
            "facing", "axis", "rotation",
            // Stairs/doors/trapdoors
            "half", "shape", "hinge", "open",
            // Slabs
            "type",
            // Rails
            "rail_shape", "shape",
            // Adapter/legacy variants
            "north", "south", "east", "west", // e.g., multi-face blocks
            // Misc
            "powered", // lever/button orientation effects on mirrors
            "wall_post_bit", "up", "down"
    );

    /**
     * Parse result common shape used by both implementations.
     * <p>
     * Notes on semantics:
     * - requiredProperties contains only properties explicitly specified by the user input. Omitted props are wildcards.
     * - orientationProperties is a subset of keys from requiredProperties that affect rotation/mirroring.
     *   This allows downstream transform/verification logic to remap only the relevant properties without
     *   changing the matching semantics captured by {@link #requiredProperties}.
     */
    public record ParseResult(
            String raw,
            boolean isTag,
            NamespacedKey key,
            Map<String, String> requiredProperties,
            Set<String> orientationProperties
    ) {
        public boolean hasOrientationConstraints() {
            return !orientationProperties.isEmpty();
        }

        /**
         * Create a functional matcher for a BlockData using API-only comparison semantics.
         *
         * Matching rules:
         * - Tag predicates first ensure the material belongs to the tag, then evaluate requested properties.
         * - Block predicates ensure the material identity matches, then evaluate requested properties.
         * - Only explicitly requested properties are checked; all others are treated as wildcards.
         *
         * Note on orientationProperties: they are informational flags indicating which of the required properties
         * are affected by rotation/mirroring. They are not additional constraints beyond requiredProperties.
         */
        public Predicate<BlockData> toApiMatcher() {
            return data -> {
                if (isTag) {
                    Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                    if (tag == null || !tag.isTagged(data.getMaterial())) return false;
                } else {
                    Material m = data.getMaterial();
                    // NamespacedKey to Material is non-trivial; rely on Bukkit's matchMaterial
                    Material want = matchMaterialFromKey(key);
                    if (want == null || m != want) return false;
                }
                if (requiredProperties.isEmpty()) return true; // wildcard for others

                Map<String, String> actual = extractProperties(data);
                for (Map.Entry<String, String> req : requiredProperties.entrySet()) {
                    String actualVal = actual.get(req.getKey());
                    if (actualVal == null || !actualVal.equals(req.getValue())) return false;
                }
                return true;
            };
        }

        /** Convenience block predicate that fetches BlockData from a Block. */
        public Predicate<Block> toApiBlockMatcher() {
            Predicate<BlockData> dataMatcher = toApiMatcher();
            return b -> dataMatcher.test(b.getBlockData());
        }
    }

    /** API-only parser implementation. */
    public static final class ApiParser {
        private ApiParser() {}

        public static ParseResult parse(String input) throws IllegalArgumentException {
            String raw = Objects.requireNonNull(input, "input").trim();
            if (raw.isEmpty()) throw new IllegalArgumentException("Empty block predicate");

            boolean isTag = raw.startsWith("#");
            String work = isTag ? raw.substring(1) : raw;

            int bracket = work.indexOf('[');
            String idPart = bracket >= 0 ? work.substring(0, bracket) : work;
            String propsPart = bracket >= 0 ? sliceBrackets(work, bracket) : null;

            NamespacedKey key = parseKey(idPart);
            Map<String, String> props = propsPart == null ? Map.of() : parseProps(propsPart);
            Set<String> orient = new HashSet<>();
            for (String p : props.keySet()) if (ORIENTATION_PROPERTIES.contains(p)) orient.add(p);

            // Validate existence for API-only where possible:
            if (isTag) {
                Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) throw new IllegalArgumentException("Unknown block tag: #" + key);
            } else {
                Material mat = matchMaterialFromKey(key);
                if (mat == null) throw new IllegalArgumentException("Unknown block id: " + key);
                // Best-effort validation: attempt to construct BlockData with specified props only
                try {
                    // Build a minimal spec string like "namespace:id[prop=val,...]"
                    String spec = key.toString() + (props.isEmpty() ? "" : propsToBracketString(props));
                    Bukkit.createBlockData(spec);
                } catch (Throwable t) {
                    throw new IllegalArgumentException("Invalid properties for block " + key + ": " + props, t);
                }
            }

            return new ParseResult(raw, isTag, key, Collections.unmodifiableMap(props), Collections.unmodifiableSet(orient));
        }

        /**
         * Returns the inside of the trailing bracket list given the index of the opening bracket.
         * Example: "oak_log[axis=x]" with openingIndex at '[' returns "axis=x".
         *
         * @throws IllegalArgumentException if the brackets are malformed
         */
        private static String sliceBrackets(String work, int openingIndex) {
            if (openingIndex < 0 || openingIndex >= work.length() || work.charAt(openingIndex) != '[')
                throw new IllegalArgumentException("Internal parser error (bracket)");
            if (!work.endsWith("]"))
                throw new IllegalArgumentException("Unclosed properties list: " + work);
            return work.substring(openingIndex + 1, work.length() - 1);
        }

        /**
         * Parses a namespaced identifier, defaulting to the minecraft namespace if absent.
         * Accepts lower-case letters, digits, '-', '_' per vanilla conventions.
         */
        private static NamespacedKey parseKey(String id) {
            String s = id.trim();
            if (s.isEmpty()) throw new IllegalArgumentException("Missing identifier before properties");
            if (!s.contains(":")) s = "minecraft:" + s;
            try {
                return NamespacedKey.fromString(s);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid namespaced id: " + s, ex);
            }
        }

        /**
         * Parses a comma-separated list of key=value pairs into a LinkedHashMap preserving order.
         * Keys/values must be simple identifiers (lowercase letters, digits, '-', '_').
         */
        private static Map<String, String> parseProps(String propsPart) {
            Map<String, String> map = new LinkedHashMap<>();
            if (propsPart.isEmpty()) return map; // allow empty [] though useless
            String[] pairs = propsPart.split(",");
            for (String pair : pairs) {
                String p = pair.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq <= 0 || eq == p.length() - 1) throw new IllegalArgumentException("Bad property entry: " + p);
                String k = p.substring(0, eq).trim();
                String v = p.substring(eq + 1).trim();
                if (!k.chars().allMatch(ApiParser::isIdentChar))
                    throw new IllegalArgumentException("Invalid property key: " + k);
                if (!v.chars().allMatch(ApiParser::isIdentChar))
                    throw new IllegalArgumentException("Invalid property value: " + v);
                map.put(k, v);
            }
            return map;
        }

        /** True if the character is valid within a simple identifier for keys/values. */
        private static boolean isIdentChar(int ch) {
            return ch == '_' || ch == '-' || (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z');
        }
    }

    /**
     * NMS-backed parser.
     * <p>
     * Implementation uses Mojang-mapped classes at compile time (no reflection) for strong typing and
     * early failure when the dev bundle version changes. For tag predicates (starting with '#'), we
     * validate the tag via Bukkit API and parse properties with the same rules as the API parser; Mojang's
     * BlockStateParser only accepts concrete block ids, not tags.
     */
    public static final class NmsParser {
        private NmsParser() {}

        /**
         * Parses the input using Mojang's {@code BlockStateParser} where applicable.
         * For tags, validates against Bukkit's tag registry and returns the same {@link ParseResult} shape.
         */
        public static ParseResult parse(String input) throws IllegalArgumentException {
            String raw = Objects.requireNonNull(input, "input").trim();
            if (raw.isEmpty()) throw new IllegalArgumentException("Empty block predicate");

            boolean isTag = raw.startsWith("#");
            String work = isTag ? raw.substring(1) : raw;

            // Fast-path for tags: BlockStateParser cannot parse tag identifiers; validate via Bukkit and parse props with API rules.
            if (isTag) {
                int bracket = work.indexOf('[');
                String idPart = bracket >= 0 ? work.substring(0, bracket) : work;
                String propsPart = bracket >= 0 ? ApiParser.sliceBrackets(work, bracket) : null;

                NamespacedKey key = ApiParser.parseKey(idPart);
                Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) throw new IllegalArgumentException("Unknown block tag: #" + key);

                Map<String, String> props = propsPart == null ? Map.of() : ApiParser.parseProps(propsPart);
                Set<String> orient = new HashSet<>();
                for (String p : props.keySet()) if (ORIENTATION_PROPERTIES.contains(p)) orient.add(p);
                return new ParseResult(raw, true, key, Collections.unmodifiableMap(props), Collections.unmodifiableSet(orient));
            }

            // Parse a concrete block id using Mojang's parser.
            try {
                // Acquire a HolderLookup<Block> from the built-in registry access.
                net.minecraft.core.RegistryAccess.Frozen registryAccess =
                        net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                                net.minecraft.core.registries.BuiltInRegistries.REGISTRY
                        );
                net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> lookup =
                        registryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);

                net.minecraft.commands.arguments.blocks.BlockStateParser.BlockResult result =
                        net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                                lookup,
                                new StringReader(work),
                                false
                        );

                net.minecraft.world.level.block.state.BlockState state = result.blockState();
                net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
                NamespacedKey nk = Objects.requireNonNull(NamespacedKey.fromString(id.toString()));

                Map<String, String> props = new LinkedHashMap<>();
                for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> e : state.getValues().entrySet()) {
                    String name = e.getKey().getName();
                    String val = String.valueOf(e.getValue());
                    props.put(name, val);
                }

                Set<String> orient = new HashSet<>();
                for (String p : props.keySet()) if (ORIENTATION_PROPERTIES.contains(p)) orient.add(p);

                return new ParseResult(raw, false, nk, Collections.unmodifiableMap(props), Collections.unmodifiableSet(orient));
            } catch (Throwable t) {
                throw new IllegalArgumentException("Failed to parse via NMS: " + input + ".", t);
            }
        }
    }

    // ---- Helpers ----

    /**
     * Serializes a map of properties to vanilla bracket syntax: "[k1=v1,k2=v2]".
     * Order follows the iteration order of the provided map.
     */
    private static String propsToBracketString(Map<String, String> props) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    /**
     * Attempts to resolve a {@link Material} from a {@link NamespacedKey}.
     * Uses strict (non-legacy) matching to avoid surprises with deprecated aliases.
     */
    private static @Nullable Material matchMaterialFromKey(@NotNull NamespacedKey key) {
        // The Bukkit matcher understands namespaced keys (case-insensitive). Use non-legacy matching.
        Material m = Material.matchMaterial(key.toString(), false);
        if (m != null) return m;
        // Fallback: try path only for minecraft namespace
        if ("minecraft".equals(key.getNamespace()))
            return Material.matchMaterial(key.getKey(), false);
        return null;
    }

    /**
     * Extracts the property map from a {@link BlockData} using its canonical string form returned by
     * {@code getAsString(true)} which includes the full namespaced id and all set properties.
     */
    private static Map<String, String> extractProperties(BlockData data) {
        String s = data.getAsString(true); // includes namespace and all set properties
        int i = s.indexOf('[');
        if (i < 0 || !s.endsWith("]")) return Collections.emptyMap();
        String body = s.substring(i + 1, s.length() - 1);
        return ApiParser.parseProps(body);
    }
}
