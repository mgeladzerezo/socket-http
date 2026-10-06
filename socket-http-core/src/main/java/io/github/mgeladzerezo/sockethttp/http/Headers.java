package io.github.mgeladzerezo.sockethttp.http;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * An ordered, case-insensitive multimap of header fields.
 *
 * <p>Field names compare case-insensitively (RFC 9110 §5.1) and the same name may appear more
 * than once; both the order of fields and the spelling the sender used are preserved. Lookups
 * are a linear scan, which beats hashing for the couple of dozen fields a real message has.
 *
 * <p>Not thread-safe: a header set belongs to one request or one response.
 */
public final class Headers {

    private final List<String> names = new ArrayList<>(16);
    private final List<String> values = new ArrayList<>(16);

    /** Appends a field, keeping any existing fields with the same name. */
    public Headers add(String name, String value) {
        names.add(name);
        values.add(value);
        return this;
    }

    /** Replaces every field called {@code name} with a single field. */
    public Headers set(String name, String value) {
        remove(name);
        return add(name, value);
    }

    /** Removes every field called {@code name}; returns whether any existed. */
    public boolean remove(String name) {
        boolean removed = false;
        for (int i = names.size() - 1; i >= 0; i--) {
            if (names.get(i).equalsIgnoreCase(name)) {
                names.remove(i);
                values.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    /** The first value of {@code name}, or {@code null} when the field is absent. */
    public String get(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return values.get(i);
            }
        }
        return null;
    }

    /** The first value of {@code name} as an {@link Optional}. */
    public Optional<String> first(String name) {
        return Optional.ofNullable(get(name));
    }

    /** Every value of {@code name}, in the order received. */
    public List<String> all(String name) {
        List<String> result = new ArrayList<>(2);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                result.add(values.get(i));
            }
        }
        return result;
    }

    public boolean contains(String name) {
        return get(name) != null;
    }

    /**
     * The members of a comma-separated list field, lower-cased and trimmed, across every
     * occurrence of the field (RFC 9110 §5.3: repeated fields are equivalent to one field
     * with the values joined by commas). Empty members are dropped (§5.6.1.2).
     */
    public List<String> tokens(String name) {
        List<String> result = new ArrayList<>(2);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                for (String part : values.get(i).split(",")) {
                    String token = part.trim();
                    if (!token.isEmpty()) {
                        result.add(token.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return result;
    }

    /** Whether the list field {@code name} has {@code token} among its members. */
    public boolean hasToken(String name, String token) {
        return tokens(name).contains(token.toLowerCase(Locale.ROOT));
    }

    /** Number of fields (not of distinct names). */
    public int size() {
        return names.size();
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    /** Distinct field names, lower-cased, in order of first appearance. */
    public Set<String> names() {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** Visits every field in order, with the spelling it was added with. */
    public void forEach(BiConsumer<String, String> action) {
        for (int i = 0; i < names.size(); i++) {
            action.accept(names.get(i), values.get(i));
        }
    }

    /** Name of the {@code i}-th field. */
    public String nameAt(int i) {
        return names.get(i);
    }

    /** Value of the {@code i}-th field. */
    public String valueAt(int i) {
        return values.get(i);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Headers other && names.equals(other.names) && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return 31 * names.hashCode() + values.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        forEach((n, v) -> sb.append(n).append(": ").append(v).append('\n'));
        return sb.toString();
    }
}
