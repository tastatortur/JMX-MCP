package org.jmxmcp.model;

import org.junit.jupiter.api.Test;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class JmxValueFormatterTest {

    private static final String[] KV = {"key", "value"};

    @Test
    void format_withNull_shouldReturnNull() {
        assertThat(JmxValueFormatter.format(null)).isEqualTo("null");
    }

    @Test
    void format_withSimpleValue_shouldUseToString() {
        assertThat(JmxValueFormatter.format(42L)).isEqualTo("42");
        assertThat(JmxValueFormatter.format("text")).isEqualTo("text");
    }

    @Test
    void format_withObjectArray_shouldListElements() {
        assertThat(JmxValueFormatter.format(new String[]{"a", "b"})).isEqualTo("[a, b]");
    }

    @Test
    void format_withPrimitiveArray_shouldListElements() {
        assertThat(JmxValueFormatter.format(new long[]{1, 2, 3})).isEqualTo("[1, 2, 3]");
        assertThat(JmxValueFormatter.format(new int[0])).isEqualTo("[]");
    }

    @Test
    void format_withCollectionAndMap_shouldExpandThem() {
        assertThat(JmxValueFormatter.format(List.of("a", "b"))).isEqualTo("[a, b]");
        assertThat(JmxValueFormatter.format(Map.of("k", 1))).isEqualTo("{k=1}");
    }

    @Test
    void format_withComposite_shouldRenderItemsOnly() throws Exception {
        assertThat(JmxValueFormatter.format(usage(100, 42))).isEqualTo("{max=100, used=42}");
    }

    @Test
    void format_withNestedComposite_shouldRenderNestedBraces() throws Exception {
        CompositeType outer = new CompositeType("Outer", "d", new String[]{"name", "usage"},
            new String[]{"name", "usage"}, new OpenType<?>[]{SimpleType.STRING, usage(0, 0).getCompositeType()});
        CompositeData data = new CompositeDataSupport(outer, new String[]{"name", "usage"},
            new Object[]{"heap", usage(100, 42)});

        assertThat(JmxValueFormatter.format(data)).isEqualTo("{name=heap, usage={max=100, used=42}}");
    }

    @Test
    void format_withKeyValueTable_shouldRenderAsMap() throws Exception {
        TabularData table = keyValueTable(2);

        assertThat(JmxValueFormatter.format(table)).isEqualTo("{k0=v0, k1=v1}");
    }

    @Test
    void format_withGeneralTable_shouldRenderRowList() throws Exception {
        CompositeType row = new CompositeType("Row", "d", new String[]{"id", "name"},
            new String[]{"id", "name"}, new OpenType<?>[]{SimpleType.INTEGER, SimpleType.STRING});
        TabularDataSupport table = new TabularDataSupport(new TabularType("T", "d", row, new String[]{"id"}));
        table.put(new CompositeDataSupport(row, new String[]{"id", "name"}, new Object[]{1, "one"}));

        assertThat(JmxValueFormatter.format(table)).isEqualTo("[{id=1, name=one}]");
    }

    @Test
    void format_withCompositeArray_shouldRenderEachElement() throws Exception {
        CompositeData[] array = {usage(1, 1), usage(2, 2)};

        assertThat(JmxValueFormatter.format(array)).isEqualTo("[{max=1, used=1}, {max=2, used=2}]");
    }

    @Test
    void format_withOversizedArray_shouldTruncateAndSummarise() {
        int[] big = IntStream.range(0, JmxValueFormatter.MAX_ELEMENTS + 5).toArray();

        String result = JmxValueFormatter.format(big);

        assertThat(result).startsWith("[0, 1, 2").endsWith("99, ... (5 more)]");
    }

    @Test
    void format_withOversizedTable_shouldTruncateAndSummarise() throws Exception {
        String result = JmxValueFormatter.format(keyValueTable(JmxValueFormatter.MAX_ELEMENTS + 3));

        assertThat(result).endsWith("k99=v99, ... (3 more)}");
    }

    @Test
    void format_withEmptyContainers_shouldRenderEmptyBrackets() throws Exception {
        assertThat(JmxValueFormatter.format(new String[0])).isEqualTo("[]");
        assertThat(JmxValueFormatter.format(List.of())).isEqualTo("[]");
        assertThat(JmxValueFormatter.format(Map.of())).isEqualTo("{}");
        assertThat(JmxValueFormatter.format(keyValueTable(0))).isEqualTo("{}");
    }

    @Test
    void format_withNullElements_shouldRenderNull() throws Exception {
        CompositeType type = new CompositeType("N", "d", new String[]{"a", "b"},
            new String[]{"a", "b"}, new OpenType<?>[]{SimpleType.STRING, SimpleType.STRING});
        CompositeData data = new CompositeDataSupport(type, new String[]{"a", "b"}, new Object[]{null, "x"});

        assertThat(JmxValueFormatter.format(data)).isEqualTo("{a=null, b=x}");
        assertThat(JmxValueFormatter.format(new String[]{"a", null})).isEqualTo("[a, null]");
        assertThat(JmxValueFormatter.format(Arrays.asList("a", null))).isEqualTo("[a, null]");
    }

    @Test
    void format_withCharAndByteArrays_shouldListElements() {
        assertThat(JmxValueFormatter.format(new char[]{'a', 'b'})).isEqualTo("[a, b]");
        assertThat(JmxValueFormatter.format(new byte[]{1, 2})).isEqualTo("[1, 2]");
    }

    @Test
    void format_withNestingBeyondDepthLimit_shouldShowPlaceholder() {
        Object nested = "leaf";
        for (int i = 0; i < 12; i++) {
            List<Object> wrapper = new ArrayList<>();
            wrapper.add(nested);
            nested = wrapper;
        }

        String result = JmxValueFormatter.format(nested);

        assertThat(result).isEqualTo("[".repeat(8) + "<ArrayList>" + "]".repeat(8));
    }

    @Test
    void format_withMutuallyReferencingLists_shouldNotOverflowTheStack() {
        List<Object> a = new ArrayList<>();
        List<Object> b = new ArrayList<>();
        a.add(b);
        b.add(a);

        assertThat(JmxValueFormatter.format(a)).contains("<ArrayList>");
    }

    private static CompositeData usage(long max, long used) throws Exception {
        CompositeType type = new CompositeType("Usage", "d", new String[]{"max", "used"},
            new String[]{"max", "used"}, new OpenType<?>[]{SimpleType.LONG, SimpleType.LONG});
        return new CompositeDataSupport(type, new String[]{"max", "used"}, new Object[]{max, used});
    }

    private static TabularData keyValueTable(int rows) throws Exception {
        CompositeType row = new CompositeType("Entry", "d", KV, KV,
            new OpenType<?>[]{SimpleType.STRING, SimpleType.STRING});
        TabularDataSupport table = new TabularDataSupport(new TabularType("Map", "d", row, new String[]{"key"}));
        for (int i = 0; i < rows; i++) {
            table.put(new CompositeDataSupport(row, KV, new Object[]{"k" + i, "v" + i}));
        }
        return table;
    }
}
