package org.webfunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrivateFlagTest {

    private static Package parse() throws Exception {
        String json = """
                {
                  "base_url": "https://api.example.com/",
                  "endpoints": [
                    {"name": "internal-sync", "returns": "boolean", "flags": ["private"]},
                    {
                      "name": "find-account",
                      "returns": "object",
                      "arguments": [
                        {"name": "id", "type": "string", "flags": ["required"]},
                        {"name": "debug", "type": "boolean", "flags": ["private"]}
                      ],
                      "attributes": [
                        {"name": "email", "type": "string"},
                        {"name": "audit_ref", "type": "string", "flags": ["private"]}
                      ]
                    }
                  ]
                }
                """;
        return Json.MAPPER.readValue(json, Package.class);
    }

    @Test
    void endpointPrivate() throws Exception {
        Package pkg = parse();
        assertTrue(pkg.endpoint("internal-sync").orElseThrow().isPrivate());
        assertFalse(pkg.endpoint("find-account").orElseThrow().isPrivate());
    }

    @Test
    void argumentPrivate() throws Exception {
        Endpoint ep = parse().endpoint("find-account").orElseThrow();
        assertTrue(ep.argument("debug").orElseThrow().isPrivate());
        assertFalse(ep.argument("id").orElseThrow().isPrivate());
    }

    @Test
    void attributePrivate() throws Exception {
        Endpoint ep = parse().endpoint("find-account").orElseThrow();
        assertTrue(ep.attribute("audit_ref").orElseThrow().isPrivate());
        assertFalse(ep.attribute("email").orElseThrow().isPrivate());
    }
}