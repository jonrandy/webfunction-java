package org.webfunction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes a single attribute of an object returned by an endpoint.
 * See <a href="https://webfunction.org/package#attribute-definition">webfunction.org/package#attribute-definition</a>.
 */
public record Attribute(String name, Type type, List<Object> values, List<String> flags, String docs) {

    @JsonCreator
    public Attribute(
            @JsonProperty("name") String name,
            @JsonProperty("type") Type type,
            @JsonProperty("values") List<Object> values,
            @JsonProperty("flags") List<String> flags,
            @JsonProperty("docs") String docs
    ) {
        this.name = name;
        this.type = type;
        // values may legitimately contain an explicit JSON null entry
        // (a "no value" choice) - List.copyOf rejects null elements
        // outright, so a null-tolerant copy is used here instead. flags
        // has no such requirement (it's a fixed, non-null vocabulary),
        // so it's left as-is.
        this.values = values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
        this.flags = flags == null ? List.of() : List.copyOf(flags);
        this.docs = docs;
    }

    /**
     * Reports whether the attribute declares the "nullable" flag.
     *
     * <p>Per spec this means more than just "the value may be null": the
     * key MAY be absent from the object entirely, and when present its
     * value MAY be null. Consumers SHOULD treat a missing key and a null
     * value equivalently.
     */
    public boolean nullable() {
        return flags.contains("nullable");
    }

    /** Reports whether the attribute is internal-only. */
    public boolean isPrivate() {
        return flags.contains("private");
    }
}