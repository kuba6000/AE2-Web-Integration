package pl.kuba6000.ae2webintegration.core.crafting;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Stores one row ID per relationship, with contiguous immutable ranges for each source in row order. */
final class Adjacency {

    private final int[] offsets;
    private final int[] rows;

    Adjacency(int sources, int count, IntUnaryOperator sourceOf) {
        this(sources, count, sourceOf, row -> true);
    }

    Adjacency(int sources, int count, IntUnaryOperator sourceOf, IntPredicate include) {
        offsets = new int[sources + 1];
        for (int row = 0; row < count; row++) if (include.test(row)) offsets[sourceOf.applyAsInt(row) + 1]++;
        for (int source = 1; source < offsets.length; source++) offsets[source] += offsets[source - 1];
        rows = new int[offsets[sources]];
        int[] next = offsets.clone();
        for (int row = 0; row < count; row++) if (include.test(row)) rows[next[sourceOf.applyAsInt(row)]++] = row;
    }

    List<Integer> rowsFor(int source) {
        return new RowIds(rows, offsets[source], offsets[source + 1]);
    }

    /** A view avoids copying a high-degree neighborhood on every expansion. */
    static final class RowIds extends AbstractList<Integer> implements RandomAccess {

        private final int[] rows;
        private final int start;
        private final int end;

        RowIds(int[] rows, int start, int end) {
            this.rows = rows;
            this.start = start;
            this.end = end;
        }

        @Override
        public Integer get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("Adjacency index out of range");
            return rows[start + index];
        }

        @Override
        public int size() {
            return end - start;
        }
    }
}
