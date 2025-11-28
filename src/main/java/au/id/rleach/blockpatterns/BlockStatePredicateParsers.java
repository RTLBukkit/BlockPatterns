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

    /** Parse result common shape used by both implementations. */
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
                // TODO: We seem be ignoring rotatable properties, are they duplicated in required properties?
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

        private static String sliceBrackets(String work, int openingIndex) {
            if (openingIndex < 0 || openingIndex >= work.length() || work.charAt(openingIndex) != '[')
                throw new IllegalArgumentException("Internal parser error (bracket)");
            if (!work.endsWith("]"))
                throw new IllegalArgumentException("Unclosed properties list: " + work);
            return work.substring(openingIndex + 1, work.length() - 1);
        }

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

        private static boolean isIdentChar(int ch) {
            return ch == '_' || ch == '-' || (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z');
        }
    }

    /** NMS-backed parser. Uses Mojang's parser to validate and then reflects the chosen properties. */
    public static final class NmsParser {
        private NmsParser() {}

        //TODO: I highly value compile time errors over reflection. We should never use reflection, rewrite this and add those directions to TODO.md
        public static ParseResult parse(String input) throws IllegalArgumentException {
            String raw = Objects.requireNonNull(input, "input").trim();
            if (raw.isEmpty()) throw new IllegalArgumentException("Empty block predicate");

            boolean isTag = raw.startsWith("#");
            String work = isTag ? raw.substring(1) : raw;

            // We leverage the vanilla parser for block state strings. The class names below are Mojang-mapped.
            try {
                // Prepare reader of just the id + optional [props]
                StringReader reader = new StringReader(work);

                // We'll parse block state definition. For tags, we still parse the same shape; tag presence is handled separately below.
                // Using reflection to avoid hard failures if names drift, but still aiming for the common 1.20-1.21 APIs.
                // Attempt 1: net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock
                Class<?> parserCls = Class.forName("net.minecraft.commands.arguments.blocks.BlockStateParser");
                // Static method signature: parseForBlock(HolderLookup<Block>, StringReader, boolean allowNBT)
                // We'll get the built-in block registry lookup.
                Class<?> builtInRegs = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                Object blocksRegistry = builtInRegs.getField("BLOCK").get(null);
                // BLOCK is a Registry<Block>; call asLookup()
                Object lookup = blocksRegistry.getClass().getMethod("asLookup").invoke(blocksRegistry);
                Object result = parserCls
                        .getMethod("parseForBlock", Class.forName("net.minecraft.core.HolderLookup"), StringReader.class, boolean.class)
                        .invoke(null, lookup, reader, false);

                // result is BlockStateParser.BlockResult
                // Extract state: block state and properties map via toString parsing as a fallback.
                Object state = result.getClass().getMethod("blockState").invoke(result);

                // Obtain the resource location (namespace:id)
                Object block = state.getClass().getMethod("getBlock").invoke(state);
                Object key = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                        .getField("BLOCK").get(null)
                        .getClass().getMethod("getKey", Class.forName("net.minecraft.world.level.block.Block"))
                        .invoke(Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null), block);
                String idStr = key.toString(); // ResourceLocation#toString is namespace:id
                NamespacedKey nk = Objects.requireNonNull(NamespacedKey.fromString(idStr));

                // Gather properties into a simple string map using the state's values() map
                Map<String, String> props = new LinkedHashMap<>();
                try {
                    // state.getValues() : Map<Property<?>, Comparable<?>>
                    Map<?, ?> values = (Map<?, ?>) state.getClass().getMethod("getValues").invoke(state);
                    for (Map.Entry<?, ?> e : values.entrySet()) {
                        Object property = e.getKey();
                        String name = (String) property.getClass().getMethod("getName").invoke(property);
                        String val = String.valueOf(e.getValue());
                        props.put(name, val);
                    }
                } catch (ReflectiveOperationException ignore) {
                    // Fallback: derive from state's toString if needed (format: namespace:id[prop=val,...])
                    String s = state.toString();
                    int i = s.indexOf('[');
                    if (i >= 0 && s.endsWith("]")) {
                        Map<String, String> parsed = ApiParser.parseProps(s.substring(i + 1, s.length() - 1));
                        props.putAll(parsed);
                    }
                }

                Set<String> orient = new HashSet<>();
                for (String p : props.keySet()) if (ORIENTATION_PROPERTIES.contains(p)) orient.add(p);

                return new ParseResult(raw, isTag, nk, Collections.unmodifiableMap(props), Collections.unmodifiableSet(orient));
            } catch (Throwable t) {
                throw new IllegalArgumentException("Failed to parse via NMS: " + input + ". You can use ApiParser as a fallback.", t);
            }
        }
    }

    // ---- Helpers ----

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

    private static @Nullable Material matchMaterialFromKey(@NotNull NamespacedKey key) {
        // The Bukkit matcher understands namespaced keys (case-insensitive)
        Material m = Material.matchMaterial(key.toString(), true); //TODO: Why is legacyName true (valid comment for all instances)?
        if (m != null) return m;
        // Fallback: try path only for minecraft namespace
        if ("minecraft".equals(key.getNamespace()))
            return Material.matchMaterial(key.getKey(), true);
        return null;
    }

    /** Extracts the property map from a BlockData using its canonical string form. */
    private static Map<String, String> extractProperties(BlockData data) {
        String s = data.getAsString(true); // includes namespace and all set properties
        int i = s.indexOf('[');
        if (i < 0 || !s.endsWith("]")) return Collections.emptyMap();
        String body = s.substring(i + 1, s.length() - 1);
        return ApiParser.parseProps(body);
    }
}
