package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Append-only length/CRC framed JSON batches. A damaged tail is reported, never silently discarded. */
public final class FileStorageHistoryStore implements IStorageHistoryStore {

    private static final int HEADER_BYTES = 8;
    private final Path directory;
    private final long maxBytesPerGrid;
    private final Gson gson = GSONUtils.createBuilder()
        .create();
    private final Map<StableKey, Long> verifiedLengths = new HashMap<>();

    public FileStorageHistoryStore(@NotNull Path directory, long maxBytesPerGrid) {
        if (maxBytesPerGrid <= HEADER_BYTES || maxBytesPerGrid > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("History file limit must fit a positive bounded record");
        }
        this.directory = directory;
        this.maxBytesPerGrid = maxBytesPerGrid;
    }

    @Override
    public synchronized void append(@NotNull StableKey gridKey, @NotNull List<StorageHistoryPoint> points)
        throws IOException {
        if (points.isEmpty()) return;
        Files.createDirectories(directory);
        Path path = path(gridKey);
        long length = Files.exists(path) ? Files.size(path) : 0;
        Long verified = verifiedLengths.get(gridKey);
        if (verified == null || verified.longValue() != length) {
            Scan scan = scan(path, length, null, Long.MIN_VALUE, 0);
            if (!scan.intact) throw new IOException("Storage history has an incomplete or corrupt tail");
        }
        byte[] bytes = gson.toJson(points)
            .getBytes(StandardCharsets.UTF_8);
        if (length + HEADER_BYTES + bytes.length > maxBytesPerGrid) {
            Files.write(
                directory.resolve(gridKey.toString() + ".gap"),
                new byte[0],
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
            throw new IOException("Storage history file limit reached");
        }
        CRC32 checksum = new CRC32();
        checksum.update(bytes);
        try (FileChannel channel = FileChannel
            .open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
            output.writeInt(bytes.length);
            output.writeInt((int) checksum.getValue());
            output.write(bytes);
            output.flush();
            channel.force(false);
        }
        verifiedLengths.put(gridKey, length + HEADER_BYTES + bytes.length);
    }

    @Override
    public @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis,
        int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("History query limit must be positive");
        Path path = path(gridKey);
        long committedLength;
        synchronized (this) {
            // Append holds this lock until the whole frame is forced. Readers then own an immutable prefix.
            committedLength = Files.exists(path) ? Files.size(path) : 0;
        }
        Scan scan = scan(path, committedLength, itemKey, fromMillis, limit);
        return new StorageHistory(
            new ArrayList<>(scan.points),
            scan.intact && scan.coverage && !Files.exists(directory.resolve(gridKey.toString() + ".gap")),
            scan.truncated);
    }

    private Path path(StableKey key) {
        return directory.resolve(key.toString() + ".history");
    }

    private Scan scan(Path path, long committedLength, @Nullable StableKey itemKey, long fromMillis, int limit)
        throws IOException {
        Scan result = new Scan();
        if (committedLength == 0) return result;
        if (committedLength > maxBytesPerGrid) throw new IOException("Storage history exceeds configured limit");
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            long remaining = committedLength;
            while (remaining > 0) {
                if (remaining < HEADER_BYTES) {
                    result.intact = false;
                    break;
                }
                int length = input.readInt();
                int expected = input.readInt();
                remaining -= HEADER_BYTES;
                if (length < 1 || length > remaining) {
                    result.intact = false;
                    break;
                }
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                remaining -= length;
                CRC32 checksum = new CRC32();
                checksum.update(bytes);
                if ((int) checksum.getValue() != expected) {
                    result.intact = false;
                    break;
                }
                StorageHistoryPoint[] points;
                try {
                    points = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), StorageHistoryPoint[].class);
                } catch (JsonParseException invalid) {
                    result.intact = false;
                    break;
                }
                if (points == null) {
                    result.intact = false;
                    break;
                }
                for (StorageHistoryPoint point : points) {
                    if (point == null || point.kind() == null) {
                        result.intact = false;
                        return result;
                    }
                    if (point.observedAt() < fromMillis) continue;
                    if (itemKey != null && point.resource() != null
                        && !itemKey.equals(
                            point.resource()
                                .itemKey()))
                        continue;
                    result.coverage &= point.complete();
                    if (limit > 0) {
                        if (result.points.size() == limit) {
                            result.points.removeFirst();
                            result.truncated = true;
                        }
                        result.points.addLast(point);
                    }
                }
            }
        } catch (EOFException truncated) {
            result.intact = false;
        }
        return result;
    }

    private static final class Scan {

        private final Deque<StorageHistoryPoint> points = new ArrayDeque<>();
        private boolean intact = true;
        private boolean coverage = true;
        private boolean truncated;
    }
}
