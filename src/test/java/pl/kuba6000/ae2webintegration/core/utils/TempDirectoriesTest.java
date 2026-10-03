package pl.kuba6000.ae2webintegration.core.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.WindowsFileLocks;

class TempDirectoriesTest {

    @TempDir
    Path directory;

    @Test
    void deletesNestedTreeAndAcceptsMissingPath() throws IOException {
        Path root = Files.createDirectories(directory.resolve("scratch/child/grandchild"));
        Files.createFile(root.resolve("pixels"));
        Files.createFile(directory.resolve("scratch/another-file"));
        Path sibling = Files.createFile(directory.resolve("keep"));
        TempDirectories.deleteRecursively(directory.resolve("scratch"));
        TempDirectories.deleteRecursively(directory.resolve("scratch"));
        assertFalse(Files.exists(directory.resolve("scratch")));
        assertTrue(Files.exists(sibling));
    }

    @Test
    void doesNotFollowLinksAndDeletesDanglingRootLink() throws IOException {
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Path keep = Files.createFile(outside.resolve("keep"));
        Path root = Files.createDirectory(directory.resolve("scratch"));
        createLink(root.resolve("linked-directory"), outside);
        TempDirectories.deleteRecursively(root);
        assertFalse(Files.exists(root));
        assertTrue(Files.exists(keep));
        Path dangling = directory.resolve("dangling");
        createLink(dangling, directory.resolve("absent"));
        TempDirectories.deleteRecursively(dangling);
        assertFalse(Files.exists(dangling, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void alreadyInterruptedThreadCanCompleteImmediateCleanupWithoutLosingInterrupt() throws IOException {
        Path root = Files.createDirectory(directory.resolve("scratch"));
        Thread.currentThread()
            .interrupt();
        try {
            TempDirectories.deleteRecursively(root);
            assertFalse(Files.exists(root));
            assertTrue(
                Thread.currentThread()
                    .isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void permanentLockReportsFailureWithinBoundAndCanBeCleanedAfterRelease() throws Exception {
        Path root = Files.createDirectory(directory.resolve("scratch"));
        Path file = Files.createFile(root.resolve("locked"));
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try {
            try (SeekableByteChannel lock = WindowsFileLocks.preventDeletion(file)) {
                Future<Void> cleanup = owner.submit(() -> {
                    TempDirectories.deleteRecursively(root);
                    return null;
                });
                ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> cleanup.get(15, TimeUnit.SECONDS));
                assertInstanceOf(FileSystemException.class, failure.getCause());
                assertTrue(Files.exists(file));
            }
            TempDirectories.deleteRecursively(root);
            assertFalse(Files.exists(root));
        } finally {
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void interruptedRetryRetainsInterruptAndFilesystemFailure() throws Exception {
        Path root = Files.createDirectory(directory.resolve("scratch"));
        Path file = Files.createFile(root.resolve("locked"));
        try (SeekableByteChannel lock = WindowsFileLocks.preventDeletion(file)) {
            Thread.currentThread()
                .interrupt();
            try {
                InterruptedIOException failure = assertThrows(
                    InterruptedIOException.class,
                    () -> TempDirectories.deleteRecursively(root));
                assertTrue(
                    Thread.currentThread()
                        .isInterrupted());
                assertInstanceOf(InterruptedException.class, failure.getCause());
                assertEquals(1, failure.getSuppressed().length);
                assertInstanceOf(FileSystemException.class, failure.getSuppressed()[0]);
                assertTrue(Files.exists(file));
            } finally {
                Thread.interrupted();
            }
        }
        TempDirectories.deleteRecursively(root);
        assertFalse(Files.exists(root));
    }

    private static void createLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            assumeTrue(false, "Symbolic links unavailable on this host: " + unsupported);
        }
    }
}
