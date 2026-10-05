package org.webfunction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes a single argument accepted by an endpoint.
 * See <a href="https://webfunction.org/package#argument-definition">webfunction.org/package#argument-definition</a>.
 *
 * <p><b>Open question:</b> a real production package
 * (api.reservepay.com) sends an additional {@code hint} field on some
 * arguments that isn't modeled here - not in the Ruby reference client,
 * not in webfunction-go/js/php, not in the spec text this was built
 * from. It's silently ignored rather than causing a parse failure (see
 * {@link Json#MAPPER}'s FAIL_ON_UNKNOWN_PROPERTIES setting), but its
 * actual shape/meaning and whether it's worth modeling explicitly is
 * unconfirmed - worth asking whoever owns that API, or checking the
 * webfunction.org spec's changelog for something newer than what this
 * library was built against.
 */
public record Argument(String name, Type type, String group, List<Object> choices, List<String> flags, String docs) {

    /**
     * Explicit, annotated canonical constructor - deliberately not
     * relying on Jackson's automatic record-parameter-name detection
     * combined with the snake_case naming strategy, which was found to
     * throw an illegal-field-access error on Jackson 2.14 (it fell back
     * to setting the record's final fields directly via reflection
     * instead of using the constructor). Explicit @JsonProperty names
     * sidestep that regardless of Jackson version.
     */
    @JsonCreator
    public Argument(
            @JsonProperty("name") String name,
            @JsonProperty("type") Type type,
            @JsonProperty("group") String group,
            @JsonProperty("choices") List<Object> choices,
            @JsonProperty("flags") List<String> flags,
            @JsonProperty("docs") String docs
    ) {
        this.name = name;
        this.type = type;
        this.group = group;
        // choices may legitimately contain an explicit JSON null entry
        // (a "no value" choice) - List.copyOf rejects null elements
        // outright, so a null-tolerant copy is used here instead. flags
        // has no such requirement (it's a fixed, non-null vocabulary),
        // so it's left as-is.
        this.choices = choices == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(choices));
        this.flags = flags == null ? List.of() : List.copyOf(flags);
        this.docs = docs;
    }

    /** Reports whether the argument declares the "required" flag. */
    public boolean required() {
        return flags.contains("required");
    }

    /** Reports whether the argument is not required. */
    public boolean optional() {
        return !required();
    }

    /** Reports whether the argument is internal-only. */
    public boolean isPrivate() {
        return flags.contains("private");
    }
}