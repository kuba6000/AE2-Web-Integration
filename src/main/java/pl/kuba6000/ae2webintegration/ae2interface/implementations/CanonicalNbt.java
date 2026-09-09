package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.*;

import org.jetbrains.annotations.NotNull;

import com.google.common.hash.PrimitiveSink;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Streams typed native data without allocating a complete canonical byte representation. */
@SuppressWarnings("UnstableApiUsage")
final class CanonicalNbt {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 65536;
    private int nodes;

    private CanonicalNbt() {}

    static void write(@NotNull Tag tag, @NotNull PrimitiveSink sink) {
        new CanonicalNbt().writeTag(tag, sink, 0);
    }

    private void writeTag(@NotNull Tag tag, @NotNull PrimitiveSink sink, int depth) {
        sink.putByte(tag.getId());
        writePayload(tag, sink, depth);
    }

    private void writePayload(@NotNull Tag tag, @NotNull PrimitiveSink sink, int depth) {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
            throw new IllegalArgumentException("Native identity exceeds traversal bounds");
        }
        switch (tag.getId()) {
            case Tag.TAG_BYTE:
                sink.putByte(((NumericTag) tag).getAsByte());
                break;
            case Tag.TAG_SHORT:
                sink.putShort(Short.reverseBytes(((NumericTag) tag).getAsShort()));
                break;
            case Tag.TAG_INT:
                writeInt(sink, ((NumericTag) tag).getAsInt());
                break;
            case Tag.TAG_LONG:
                writeLong(sink, ((NumericTag) tag).getAsLong());
                break;
            case Tag.TAG_FLOAT:
                writeInt(sink, Float.floatToIntBits(((NumericTag) tag).getAsFloat()));
                break;
            case Tag.TAG_DOUBLE:
                writeLong(sink, Double.doubleToLongBits(((NumericTag) tag).getAsDouble()));
                break;
            case Tag.TAG_STRING:
                StableKey.writeText(sink, tag.getAsString());
                break;
            case Tag.TAG_BYTE_ARRAY:
                byte[] bytes = ((ByteArrayTag) tag).getAsByteArray();
                writeInt(sink, bytes.length);
                sink.putBytes(bytes);
                break;
            case Tag.TAG_INT_ARRAY:
                int[] ints = ((IntArrayTag) tag).getAsIntArray();
                writeInt(sink, ints.length);
                for (int value : ints) writeInt(sink, value);
                break;
            case Tag.TAG_LONG_ARRAY:
                long[] longs = ((LongArrayTag) tag).getAsLongArray();
                writeInt(sink, longs.length);
                for (long value : longs) writeLong(sink, value);
                break;
            case Tag.TAG_LIST:
                ListTag list = (ListTag) tag;
                checkChildren(list.size());
                sink.putByte(list.isEmpty() ? Tag.TAG_END : list.getElementType());
                writeInt(sink, list.size());
                for (Tag element : list) writePayload(element, sink, depth + 1);
                break;
            case Tag.TAG_COMPOUND:
                CompoundTag compound = (CompoundTag) tag;
                checkChildren(compound.size());
                List<String> names = new ArrayList<>(compound.getAllKeys());
                Collections.sort(names);
                writeInt(sink, names.size());
                for (String name : names) {
                    StableKey.writeText(sink, name);
                    writeTag(compound.get(name), sink, depth + 1);
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported native tag type: " + tag.getId());
        }
    }

    private void checkChildren(int count) {
        if (count > MAX_NODES - nodes) {
            throw new IllegalArgumentException("Native identity exceeds traversal bounds");
        }
    }

    // PrimitiveSink encodes numeric primitives little-endian; canonical identity uses big-endian.
    private static void writeInt(@NotNull PrimitiveSink sink, int value) {
        sink.putInt(Integer.reverseBytes(value));
    }

    private static void writeLong(@NotNull PrimitiveSink sink, long value) {
        sink.putLong(Long.reverseBytes(value));
    }
}
