package org.jmxmcp.model;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Renders JMX attribute and operation values as compact, readable text.
 * <p>
 * Plain {@code toString()} is unusable for most non-trivial JMX values: arrays print as
 * {@code [Ljava.lang.String;@6bb0bb40} and open-type data repeats its full type metadata for every item.
 * This formatter expands arrays and collections, renders {@link CompositeData} as {@code {key=value, ...}}
 * and {@link TabularData} either as a map (when its rows are key/value pairs) or as a list of rows.
 */
public final class JmxValueFormatter {

    /** Maximum number of elements rendered per array, collection or table; the rest is summarised. */
    static final int MAX_ELEMENTS = 100;

    /** Maximum nesting depth; deeper containers are shown as a {@code <TypeName>} placeholder. */
    private static final int MAX_DEPTH = 8;

    private static final String KEY_COLUMN = "key";
    private static final String VALUE_COLUMN = "value";

    private JmxValueFormatter() {
    }

    public static String format(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (depth >= MAX_DEPTH && !isSimple(value)) {
            // Placeholder instead of toString(): avoids raw metadata dumps and unbounded recursion on cycles
            sb.append('<').append(value.getClass().getSimpleName()).append('>');
        } else if (value instanceof CompositeData composite) {
            appendComposite(sb, composite, depth);
        } else if (value instanceof TabularData table) {
            appendTabular(sb, table, depth);
        } else if (value.getClass().isArray()) {
            appendArray(sb, value, depth);
        } else if (value instanceof Collection<?> collection) {
            appendElements(sb, collection.iterator(), collection.size(), depth);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(sb, map, depth);
        } else {
            sb.append(value);
        }
    }

    private static boolean isSimple(Object value) {
        return !(value instanceof CompositeData || value instanceof TabularData || value instanceof Collection<?>
            || value instanceof Map<?, ?> || value.getClass().isArray());
    }

    private static void appendComposite(StringBuilder sb, CompositeData composite, int depth) {
        sb.append('{');
        boolean first = true;
        // Item names are sorted by CompositeType, so output is stable
        for (String name : composite.getCompositeType().keySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(name).append('=');
            append(sb, composite.get(name), depth + 1);
        }
        sb.append('}');
    }

    private static void appendTabular(StringBuilder sb, TabularData table, int depth) {
        var columns = table.getTabularType().getRowType().keySet();
        boolean keyValueRows = columns.size() == 2 && columns.contains(KEY_COLUMN) && columns.contains(VALUE_COLUMN);
        if (!keyValueRows) {
            appendElements(sb, table.values().iterator(), table.size(), depth);
            return;
        }
        sb.append('{');
        int count = 0;
        for (Object row : table.values()) {
            if (count == MAX_ELEMENTS) {
                sb.append(", ... (").append(table.size() - MAX_ELEMENTS).append(" more)");
                break;
            }
            if (count++ > 0) {
                sb.append(", ");
            }
            CompositeData entry = (CompositeData) row;
            append(sb, entry.get(KEY_COLUMN), depth + 1);
            sb.append('=');
            append(sb, entry.get(VALUE_COLUMN), depth + 1);
        }
        sb.append('}');
    }

    private static void appendMap(StringBuilder sb, Map<?, ?> map, int depth) {
        sb.append('{');
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count == MAX_ELEMENTS) {
                sb.append(", ... (").append(map.size() - MAX_ELEMENTS).append(" more)");
                break;
            }
            if (count++ > 0) {
                sb.append(", ");
            }
            append(sb, entry.getKey(), depth + 1);
            sb.append('=');
            append(sb, entry.getValue(), depth + 1);
        }
        sb.append('}');
    }

    private static void appendArray(StringBuilder sb, Object array, int depth) {
        int length = Array.getLength(array);
        sb.append('[');
        for (int i = 0; i < Math.min(length, MAX_ELEMENTS); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            append(sb, Array.get(array, i), depth + 1);
        }
        appendOverflow(sb, length);
        sb.append(']');
    }

    private static void appendElements(StringBuilder sb, Iterator<?> elements, int size, int depth) {
        sb.append('[');
        for (int i = 0; i < Math.min(size, MAX_ELEMENTS) && elements.hasNext(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            append(sb, elements.next(), depth + 1);
        }
        appendOverflow(sb, size);
        sb.append(']');
    }

    private static void appendOverflow(StringBuilder sb, int size) {
        if (size > MAX_ELEMENTS) {
            sb.append(", ... (").append(size - MAX_ELEMENTS).append(" more)");
        }
    }
}
