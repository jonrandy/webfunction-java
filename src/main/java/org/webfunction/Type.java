package org.webfunction;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Represents a Web Function type specification - what appears in an
 * endpoint's {@code returns}, or an argument's or attribute's {@code type}.
 * See <a href="https://webfunction.org/package#types">webfunction.org/package#types</a>.
 *
 * <p>The wire grammar is recursive: a type is a single base/refined type,
 * or a union of types written as a JSON array, where an array <i>entry</i>
 * that is itself an array denotes an array type (whose own entries are, in
 * turn, a union of the types its items may take). {@link Type} models this
 * directly rather than flattening it to a list of strings, so array
 * element types and object.&lt;n&gt; references survive parsing.
 *
 * <p>Ported from webfunction-go's {@code type.go}, cross-checked against
 * the Ruby reference client's {@code Type::parse}/{@code Type::detect}.
 */
@JsonDeserialize(using = Type.Deserializer.class)
public final class Type {

    private final List<TypeAlt> union;

    public Type(List<TypeAlt> union) {
        this.union = List.copyOf(union);
    }

    /** Every alternative this type may be. A plain (non-union) type has exactly one entry. */
    public List<TypeAlt> union() {
        return union;
    }

    /** Reports whether any alternative in the union has the given base type. */
    public boolean hasBase(String base) {
        return union.stream().anyMatch(alt -> alt.base().equals(base));
    }

    /**
     * Reports whether the union includes an "array" alternative with no
     * nested element type (i.e. array of any, at the wire level) -
     * distinct from {@link #hasBase(String)} with "array", which also
     * matches a typed array.
     */
    public boolean hasBareArray() {
        return union.stream().anyMatch(alt -> alt.base().equals("array") && alt.of() == null);
    }

    /**
     * Returns the names of every object definition referenced anywhere
     * within this type, including inside array element types - mirrors
     * the Ruby reference's {@code Type#objects}.
     */
    public List<String> objectNames() {
        List<String> names = new ArrayList<>();
        for (TypeAlt alt : union) {
            if (alt.isObjectRef()) {
                names.add(alt.refinement());
            }
            if (alt.of() != null) {
                names.addAll(alt.of().objectNames());
            }
            if (alt.arg() != null) {
                names.addAll(alt.arg().objectNames());
            }
        }
        return names;
    }

    @Override
    public String toString() {
        return format("default");
    }

    /** Renders every alternative using the given style - see {@link TypeAlt#format(String)}. */
    public String format(String style) {
        return union.stream().map(alt -> alt.format(style)).collect(Collectors.joining("|"));
    }

    /**
     * Reports whether {@code value} conforms to this type: at least one
     * alternative in the union must accept it. Mirrors the Ruby
     * reference's {@code Type#valid?}.
     *
     * <p>Values are expected in their decoded-JSON form (as produced by
     * Jackson's {@code ObjectMapper.readValue(json, Object.class)}):
     * {@code Map<String, Object>} for object, {@code List<Object>} for
     * array, a {@link Number} subtype for number, {@link String},
     * {@link Boolean}, or {@code null}.
     */
    public boolean valid(Object value) {
        return union.stream().anyMatch(alt -> altValid(alt, value));
    }

    private static boolean altValid(TypeAlt alt, Object value) {
        return switch (alt.base()) {
            case "any" -> true;
            case "string" -> value instanceof String s && refinementValid(alt, s);
            case "number" -> value instanceof Number n && refinementValid(alt, n);
            case "object" -> value instanceof Map;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "array" -> value instanceof List<?> list
                    && (alt.of() == null || list.stream().allMatch(alt.of()::valid));
            default -> false;
        };
    }

    private static boolean refinementValid(TypeAlt alt, Object value) {
        if (alt.refinement() == null || alt.refinement().isEmpty()) {
            return true;
        }
        Predicate<Object> validator = REFINEMENT_VALIDATORS.get(alt.refinement());
        return validator == null || validator.test(value);
    }

    /**
     * Lists the refinements recognized for each base type that supports
     * refinement ("string" and "number"). Mirrors the Ruby reference's
     * {@code Type::ALLOWED_REFINEMENTS}.
     */
    public static final Map<String, List<String>> ALLOWED_REFINEMENTS = Map.of(
            "number", List.of("u32", "u64", "i32", "i64", "f32", "f64", "timestamp"),
            "string", List.of("date", "time", "datetime", "uuid", "base64", "email", "phone", "url", "uri", "ipv4", "ipv6", "hostname")
    );

    private static double num(Object v) {
        return ((Number) v).doubleValue();
    }

    private static boolean isIntegral(double n) {
        return n == Math.rint(n) && !Double.isInfinite(n);
    }

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$");
    private static final Pattern DATETIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]*={0,2}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::$|^::1$|^([0-9a-fA-F]{1,4}:)*::([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4}$");

    private static Predicate<Object> regexValidator(Pattern pattern) {
        return v -> v instanceof String s && pattern.matcher(s).matches();
    }

    /**
     * A value-level validator per refinement name, ported from the Ruby
     * reference's {@code Type::REFINEMENT_VALIDATORS}. Refinement names
     * are unique across base types, so a flat map (keyed by refinement,
     * not base+refinement) is sufficient, matching the Ruby original.
     */
    private static final Map<String, Predicate<Object>> REFINEMENT_VALIDATORS = Map.ofEntries(
            Map.entry("u32", (Predicate<Object>) v -> { double n = num(v); return isIntegral(n) && n >= 0 && n <= 0xFFFFFFFFL; }),
            Map.entry("u64", (Predicate<Object>) v -> { double n = num(v); return isIntegral(n) && n >= 0; }),
            Map.entry("i32", (Predicate<Object>) v -> { double n = num(v); return isIntegral(n) && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE; }),
            Map.entry("i64", (Predicate<Object>) v -> { double n = num(v); return isIntegral(n); }),
            Map.entry("f32", (Predicate<Object>) v -> !Double.isNaN(num(v)) && !Double.isInfinite(num(v))),
            Map.entry("f64", (Predicate<Object>) v -> !Double.isNaN(num(v)) && !Double.isInfinite(num(v))),
            Map.entry("timestamp", (Predicate<Object>) v -> { double n = num(v); return isIntegral(n) && n >= 0; }),
            Map.entry("date", regexValidator(DATE)),
            Map.entry("time", regexValidator(TIME)),
            Map.entry("datetime", regexValidator(DATETIME)),
            Map.entry("uuid", regexValidator(UUID_PATTERN)),
            Map.entry("base64", regexValidator(BASE64)),
            Map.entry("email", regexValidator(EMAIL)),
            Map.entry("phone", regexValidator(PHONE)),
            Map.entry("hostname", regexValidator(HOSTNAME)),
            Map.entry("ipv4", (Predicate<Object>) v -> v instanceof String s && IPV4.matcher(s).matches()),
            Map.entry("ipv6", (Predicate<Object>) v -> v instanceof String s && IPV6.matcher(s).matches()),
            Map.entry("url", (Predicate<Object>) v -> {
                if (!(v instanceof String s)) return false;
                try {
                    URI u = new URI(s);
                    return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null;
                } catch (URISyntaxException e) {
                    return false;
                }
            }),
            Map.entry("uri", (Predicate<Object>) v -> {
                if (!(v instanceof String s)) return false;
                try {
                    return new URI(s).getScheme() != null;
                } catch (URISyntaxException e) {
                    return false;
                }
            })
    );

    /**
     * Parses the recursive type grammar: a JSON null (treated as "any" -
     * only actually expected for choices/values elements, not type
     * fields themselves, but handled defensively), a bare string, a
     * generic application object ({@code { "object.<name>": <type> }}),
     * or a JSON array whose entries are strings, nested arrays or
     * application objects.
     */
    static final class Deserializer extends StdDeserializer<Type> {
        Deserializer() {
            super(Type.class);
        }

        @Override
        public Type deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            return parse(node);
        }

        private static Type parse(JsonNode node) {
            if (node == null || node.isNull()) {
                return new Type(List.of(new TypeAlt("any", null, null)));
            }
            if (node.isTextual()) {
                return new Type(List.of(parseString(node.asText())));
            }
            if (node.isObject()) {
                return new Type(List.of(parseApplication(node)));
            }
            if (node.isArray()) {
                if (node.isEmpty()) {
                    // Not permitted per spec ("Arrays MUST NOT be empty at
                    // any depth"), but fail soft to "any" rather than
                    // erroring outright on a non-conformant package.
                    return new Type(List.of(new TypeAlt("any", null, null)));
                }
                List<TypeAlt> alts = new ArrayList<>();
                for (JsonNode entry : node) {
                    if (entry.isTextual()) {
                        alts.add(parseString(entry.asText()));
                    } else if (entry.isArray()) {
                        alts.add(new TypeAlt("array", null, parse(entry)));
                    } else if (entry.isObject()) {
                        alts.add(parseApplication(entry));
                    } else {
                        throw new IllegalArgumentException("unexpected type entry: " + entry.getNodeType());
                    }
                }
                return new Type(alts);
            }
            throw new IllegalArgumentException("unexpected type value: " + node.getNodeType());
        }

        /**
         * Parses a generic application, {@code { "object.<name>": <type> }}.
         * Anything that is not exactly that shape resolves to a bare "any"
         * carrying the reason, rather than an error, so a package using
         * generics (even incorrectly) still loads.
         */
        private static TypeAlt parseApplication(JsonNode node) {
            if (node.size() != 1) {
                return malformed(TypeAlt.MALFORMED_KEY_COUNT);
            }
            String key = node.fieldNames().next();
            if (!key.startsWith("object.") || key.equals("object.")) {
                return malformed(TypeAlt.MALFORMED_KEY_PREFIX);
            }
            try {
                return new TypeAlt("object", key.substring("object.".length()), null, parse(node.get(key)), null, false);
            } catch (IllegalArgumentException e) {
                return malformed(TypeAlt.MALFORMED_ARGUMENT);
            }
        }

        private static TypeAlt malformed(String reason) {
            return new TypeAlt("any", null, null, null, reason, false);
        }

        private static TypeAlt parseString(String s) {
            if (s.equals("array")) {
                return new TypeAlt("array", null, null);
            }
            if (s.equals("T")) {
                return new TypeAlt("any", null, null, null, null, true);
            }
            int dot = s.indexOf('.');
            if (dot >= 0) {
                String base = s.substring(0, dot);
                String refinement = s.substring(dot + 1);
                if (base.equals("string") || base.equals("number")) {
                    List<String> allowed = ALLOWED_REFINEMENTS.get(base);
                    if (allowed == null || !allowed.contains(refinement)) {
                        return new TypeAlt(base, null, null);
                    }
                }
                return new TypeAlt(base, refinement, null);
            }
            return new TypeAlt(s, null, null);
        }
    }
}