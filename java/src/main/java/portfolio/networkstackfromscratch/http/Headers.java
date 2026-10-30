package portfolio.networkstackfromscratch.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Ordered header fields with case-insensitive lookup; repeated names are kept as separate entries. */
public final class Headers {
    /** One field as received. */
    public record Field(String name, String value) {}

    private final List<Field> fields = new ArrayList<>();

    /** Append a field. */
    public Headers add(String name, String value) {
        fields.add(new Field(name, value));
        return this;
    }

    /** Replace every field with this name by one field. */
    public Headers set(String name, String value) {
        remove(name);
        return add(name, value);
    }

    /** Remove every field with this name. */
    public void remove(String name) {
        fields.removeIf(f -> f.name().equalsIgnoreCase(name));
    }

    /** First value for a name. */
    public Optional<String> first(String name) {
        return fields.stream().filter(f -> f.name().equalsIgnoreCase(name)).map(Field::value).findFirst();
    }

    /** Every value for a name, in order. */
    public List<String> all(String name) {
        return fields.stream().filter(f -> f.name().equalsIgnoreCase(name)).map(Field::value).toList();
    }

    /** True when any comma-separated element of any field with this name equals {@code token}. */
    public boolean hasToken(String name, String token) {
        for (String v : all(name)) {
            for (String part : v.split(",")) {
                if (part.strip().equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lower-cased comma-separated elements of every field with this name. */
    public List<String> tokens(String name) {
        List<String> out = new ArrayList<>();
        for (String v : all(name)) {
            for (String part : v.split(",")) {
                String t = part.strip().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** True when a field with this name exists. */
    public boolean contains(String name) {
        return first(name).isPresent();
    }

    /** All fields in order. */
    public List<Field> fields() {
        return List.copyOf(fields);
    }

    /** Number of fields. */
    public int size() {
        return fields.size();
    }
}
