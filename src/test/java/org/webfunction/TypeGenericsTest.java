package org.webfunction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypeGenericsTest {

    private static Type parse(String raw) throws Exception {
        return Json.MAPPER.readValue(raw, Type.class);
    }

    private static void assertParses(String raw, String expected) throws Exception {
        assertEquals(expected, parse(raw).toString(), raw);
    }

    /** Every pre-existing form must parse exactly as before. */
    @Test
    void existingFormsUnchanged() throws Exception {
        assertParses("\"string\"", "string");
        assertParses("\"string.email\"", "string.email");
        assertParses("[\"string\",\"null\"]", "string|null");
        assertParses("[[\"string\"]]", "array<string>");
        assertParses("[[\"object.person\",\"null\"]]", "array<object.person|null>");
        assertParses("[\"array\",[\"object.person\"]]", "array|array<object.person>");
        assertParses("\"object.user\"", "object.user");
    }

    @Test
    void singleApplication() throws Exception {
        Type t = parse("{ \"object.list\": \"object.user\" }");
        assertEquals("object.list<object.user>", t.toString());
        assertEquals(1, t.union().size());
        TypeAlt alt = t.union().get(0);
        assertEquals("object", alt.base());
        assertEquals("list", alt.refinement());
        assertTrue(alt.isObjectRef());
        assertEquals("object.user", alt.arg().toString());
        assertNull(alt.malformed());
        assertFalse(alt.param());
    }

    @Test
    void unionNestedAndArrayArguments() throws Exception {
        assertParses("{ \"object.list\": [\"object.user\", \"null\"] }", "object.list<object.user|null>");
        assertParses("{ \"object.page\": { \"object.page\": \"object.user\" } }", "object.page<object.page<object.user>>");
        assertParses("{ \"object.list\": [[\"string\"]] }", "object.list<array<string>>");
        assertEquals(List.of("page", "page", "user"), parse("{ \"object.page\": { \"object.page\": \"object.user\" } }").objectNames());
    }

    @Test
    void applicationsInsideUnionsAndArrays() throws Exception {
        assertParses("[{ \"object.list\": \"object.user\" }, \"null\"]", "object.list<object.user>|null");
        assertParses("[[{ \"object.list\": \"object.user\" }]]", "array<object.list<object.user>>");
        assertParses("[[{ \"object.list\": \"object.user\" }, \"null\"]]", "array<object.list<object.user>|null>");
        assertParses("{ \"object.list\": [{ \"object.pair\": \"number\" }, \"null\"] }", "object.list<object.pair<number>|null>");
    }

    /** Malformed applications must never fail parsing: they resolve to "any", with the reason recorded. */
    @Test
    void malformedApplicationsResolveToAny() throws Exception {
        String[][] cases = {
                {"{}", TypeAlt.MALFORMED_KEY_COUNT},
                {"{ \"object.a\": \"string\", \"object.b\": \"string\" }", TypeAlt.MALFORMED_KEY_COUNT},
                {"{ \"string\": \"object.user\" }", TypeAlt.MALFORMED_KEY_PREFIX},
                {"{ \"list\": \"object.user\" }", TypeAlt.MALFORMED_KEY_PREFIX},
                {"{ \"object.\": \"string\" }", TypeAlt.MALFORMED_KEY_PREFIX},
                {"{ \"object.list\": 5 }", TypeAlt.MALFORMED_ARGUMENT},
        };
        for (String[] c : cases) {
            Type t = parse(c[0]);
            assertEquals(1, t.union().size(), c[0]);
            TypeAlt alt = t.union().get(0);
            assertEquals("any", alt.base(), c[0]);
            assertNull(alt.refinement(), c[0]);
            assertNull(alt.arg(), c[0]);
            assertEquals(c[1], alt.malformed(), c[0]);
            assertTrue(t.valid("anything"), c[0]);
            assertTrue(t.valid(Map.of("x", 1)), c[0]);
        }

        // A malformed entry inside a union is kept as "any", not dropped.
        Type t = parse("[{ \"string\": \"x\" }, \"null\"]");
        assertEquals("any|null", t.toString());
        assertEquals(TypeAlt.MALFORMED_KEY_PREFIX, t.union().get(0).malformed());
    }

    @Test
    void formatAndValidate() throws Exception {
        Type t = parse("{ \"object.list\": \"object.user\" }");
        assertEquals("list", t.format("compact"));
        assertEquals("object", t.format("base"));
        assertTrue(t.hasBase("object"));
        assertTrue(t.valid(Map.of()));
        assertFalse(t.valid("nope"));
    }

    /** An unresolved type parameter reads as "any" to every consumer, but is marked for tooling. */
    @Test
    void typeParameterResolvesToAny() throws Exception {
        assertParses("\"T\"", "any");
        assertParses("[[\"T\"]]", "array<any>");
        assertParses("[\"T\",\"null\"]", "any|null");

        TypeAlt alt = parse("\"T\"").union().get(0);
        assertEquals("any", alt.base());
        assertTrue(alt.param());
        assertTrue(parse("[[\"T\"]]").union().get(0).of().union().get(0).param());
        assertTrue(parse("\"T\"").valid("anything"));
        assertTrue(parse("\"T\"").valid(Map.of("x", 1)));

        // A literal "any", and an object that happens to be named T, are not parameters.
        assertFalse(parse("\"any\"").union().get(0).param());
        assertFalse(parse("\"object.T\"").union().get(0).param());
        assertParses("\"object.T\"", "object.T");
    }

    /** The original failure mode: a package using generics made the whole package fail to load. */
    @Test
    void packageWithGenericsLoads() throws Exception {
        String json = """
                {
                  "base_url": "https://api.example.com/",
                  "endpoints": [
                    {
                      "name": "list-users",
                      "returns": { "object.list": "object.user" },
                      "arguments": [{ "name": "filter", "type": [{ "object.pair": "string" }, "null"] }]
                    },
                    { "name": "broken", "returns": {}, "arguments": [] }
                  ],
                  "objects": [
                    { "name": "list", "attributes": [
                      { "name": "total", "type": "number" },
                      { "name": "items", "type": [["T"]] }
                    ] }
                  ]
                }
                """;
        Package pkg = Json.MAPPER.readValue(json, Package.class);
        Endpoint ep = pkg.endpoint("list-users").orElseThrow();
        assertEquals("object.list<object.user>", ep.returns().toString());
        assertEquals("object.pair<string>|null", ep.argument("filter").orElseThrow().type().toString());
        assertEquals("any", pkg.endpoint("broken").orElseThrow().returns().toString());
        assertEquals("array<any>", pkg.object("list").orElseThrow().attributes().get(1).type().toString());
    }

    /**
     * {"array": X} is the canonical way to write an array of X (spec: Generics, Application).
     * It must behave exactly like the legacy [[X]] form.
     */
    @Test
    void arrayApplicationBehavesLikeLegacyForm() throws Exception {
        String[][] pairs = {
                {"{ \"array\": \"string\" }", "[[\"string\"]]"},
                {"{ \"array\": \"object.user\" }", "[[\"object.user\"]]"},
                {"{ \"array\": [\"string\", \"null\"] }", "[[\"string\", \"null\"]]"},
                {"{ \"array\": \"T\" }", "[[\"T\"]]"},
                {"{ \"array\": { \"array\": \"string\" } }", "[[[\"string\"]]]"},
                {"{ \"array\": { \"object.page\": \"object.user\" } }", "[[{ \"object.page\": \"object.user\" }]]"},
                {"{ \"object.page\": { \"array\": \"object.user\" } }", "{ \"object.page\": [[\"object.user\"]] }"},
                {"[{ \"array\": \"string\" }, \"null\"]", "[[\"string\"], \"null\"]"},
        };
        for (String[] c : pairs) {
            Type a = parse(c[0]);
            Type l = parse(c[1]);
            assertEquals(l.toString(), a.toString(), c[0]);
            assertEquals(l.format("base"), a.format("base"), c[0]);
            assertEquals(l.format("compact"), a.format("compact"), c[0]);
            assertEquals(l.objectNames(), a.objectNames(), c[0]);
            assertEquals(l.union().size(), a.union().size(), c[0]);
        }
    }

    @Test
    void arrayApplicationFormats() throws Exception {
        assertParses("{ \"array\": \"string\" }", "array<string>");
        assertParses("{ \"array\": \"object.user\" }", "array<object.user>");
        assertParses("{ \"array\": [\"string\", \"number\"] }", "array<string|number>");
        assertParses("{ \"array\": { \"array\": \"string\" } }", "array<array<string>>");
        assertParses("{ \"array\": { \"object.page\": \"object.user\" } }", "array<object.page<object.user>>");
        assertParses("{ \"object.page\": { \"array\": \"object.user\" } }", "object.page<array<object.user>>");
        assertParses("{ \"array\": \"T\" }", "array<any>");
        assertParses("[{ \"array\": \"string\" }, \"null\"]", "array<string>|null");
        assertParses("[\"array\", { \"array\": \"string\" }]", "array|array<string>");
    }

    @Test
    void arrayApplicationShape() throws Exception {
        Type t = parse("{ \"array\": \"object.user\" }");
        assertEquals(1, t.union().size());
        TypeAlt alt = t.union().get(0);
        assertEquals("array", alt.base());
        assertNull(alt.refinement());
        assertNotNull(alt.of());
        assertEquals("object.user", alt.of().toString());
        assertNull(alt.arg());
        assertNull(alt.malformed());
        assertFalse(alt.param());
        assertTrue(t.hasBase("array"));
        assertFalse(t.hasBareArray());
        assertEquals("array", t.format("base"));

        // T survives as the element of an array application.
        assertTrue(parse("{ \"array\": \"T\" }").union().get(0).of().union().get(0).param());
    }

    @Test
    void arrayApplicationObjectNames() throws Exception {
        assertEquals(List.of("user"), parse("{ \"array\": \"object.user\" }").objectNames());
        assertEquals(List.of("page", "user"), parse("{ \"array\": { \"object.page\": \"object.user\" } }").objectNames());
        assertEquals(List.of("page", "user"), parse("{ \"object.page\": { \"array\": \"object.user\" } }").objectNames());
        assertEquals(List.of("user"), parse("{ \"array\": { \"array\": \"object.user\" } }").objectNames());
    }

    @Test
    void arrayApplicationValidates() throws Exception {
        Type strings = parse("{ \"array\": \"string\" }");
        assertTrue(strings.valid(List.of("a", "b")));
        assertTrue(strings.valid(List.of()));
        assertFalse(strings.valid(List.of(1)));
        assertFalse(strings.valid("a"));
        assertFalse(strings.valid(null));

        Type mixed = parse("{ \"array\": [\"string\", \"number\"] }");
        assertTrue(mixed.valid(List.of("a", 1)));
        assertFalse(mixed.valid(List.of(true)));

        Type nested = parse("{ \"array\": { \"array\": \"number\" } }");
        assertTrue(nested.valid(List.of(List.of(1), List.of())));
        assertFalse(nested.valid(List.of(List.of("a"))));
    }

    /** Everything malformed before stays malformed, for the same reasons; only the key "array" changed meaning. */
    @Test
    void malformedArrayApplicationsResolveToAny() throws Exception {
        String[][] cases = {
                {"{ \"array\": \"string\", \"object.a\": \"string\" }", TypeAlt.MALFORMED_KEY_COUNT},
                {"{ \"array\": 5 }", TypeAlt.MALFORMED_ARGUMENT},
                {"{ \"arrays\": \"string\" }", TypeAlt.MALFORMED_KEY_PREFIX},
                {"{ \"array.x\": \"string\" }", TypeAlt.MALFORMED_KEY_PREFIX},
                {"{ \"Array\": \"string\" }", TypeAlt.MALFORMED_KEY_PREFIX},
        };
        for (String[] c : cases) {
            Type t = parse(c[0]);
            assertEquals(1, t.union().size(), c[0]);
            TypeAlt alt = t.union().get(0);
            assertEquals("any", alt.base(), c[0]);
            assertEquals(c[1], alt.malformed(), c[0]);
            assertTrue(t.valid("anything"), c[0]);
        }
    }

    /** Spec: a union of one entry is that entry. The model already holds one alternative either way; this pins it. */
    @Test
    void unionOfOneEntryIsThatEntry() throws Exception {
        assertEquals(1, parse("[\"string\"]").union().size());
        assertEquals("string", parse("[\"string\"]").format("base"));
        assertEquals("email", parse("[\"string.email\"]").format("compact"));
        assertEquals("string.email", parse("[\"string.email\"]").toString());
        assertTrue(parse("[\"T\"]").union().get(0).param());
        assertEquals("array", parse("[{ \"array\": \"string\" }]").format("base"));
        assertEquals(1, parse("[{ \"array\": \"string\" }]").union().size());
        // The legacy array shorthand is not a one-entry union: [["x"]] is still array<x>.
        assertParses("[[\"string\"]]", "array<string>");
        // Unions of more than one entry are unchanged.
        assertEquals(2, parse("[\"string\", \"null\"]").union().size());
    }

    @Test
    void packageWithArrayApplicationLoads() throws Exception {
        String json = """
                {
                  "base_url": "https://api.example.com/",
                  "endpoints": [
                    { "name": "get-comments", "returns": { "array": "object.comment" }, "arguments": [] }
                  ],
                  "objects": [
                    { "name": "comment", "attributes": [
                      { "name": "id", "type": "string.uuid" },
                      { "name": "message", "type": "string" },
                      { "name": "replies", "type": { "array": "object.comment" } }
                    ] }
                  ]
                }
                """;
        Package pkg = Json.MAPPER.readValue(json, Package.class);
        assertEquals("array<object.comment>", pkg.endpoint("get-comments").orElseThrow().returns().toString());
        assertEquals("array<object.comment>", pkg.object("comment").orElseThrow().attributes().get(2).type().toString());
    }
}