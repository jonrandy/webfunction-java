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
}