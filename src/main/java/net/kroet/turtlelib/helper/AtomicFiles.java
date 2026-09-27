package net.kroet.turtlelib.helper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/**
 * Replaces a file's content in one step, so a crash while writing can't leave
 * an empty or half-written admin file behind.
 */
final class AtomicFiles {
    private static final Logger FALLBACK_LOGGER = Logger.getLogger(AtomicFiles.class.getName());

    private AtomicFiles() {
    }

    static void write(Path target, byte[] bytes) throws IOException {
        write(target, bytes, FALLBACK_LOGGER);
    }

    // Writes a hidden temp file next to the target (not *.yml, so no helper reads
    // it as a config or lang file), flushes it to disk and moves it into place.
    static void write(Path target, byte[] bytes, Logger logger) throws IOException {
        Path temp = tempFileFor(target);
        try {
            writeDurably(temp, bytes);
            keepPermissions(target, temp);
            try {
                moveIntoPlace(temp, target);
            } catch (FileSystemException e) {
                // e.g. Windows refuses the replace while an editor holds the file
                Files.write(target, bytes);
                if (logger != null) {
                    logger.warning("Could not replace " + target.getFileName() + " in one step (" + e
                            + "); wrote it in place instead");
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeDurably(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Path tempFileFor(Path target) {
        Path absolute = target.toAbsolutePath();
        return absolute.resolveSibling("." + absolute.getFileName() + ".turtlelib.tmp");
    }

    private static void keepPermissions(Path target, Path temp) {
        try {
            if (Files.exists(target)) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
            }
        } catch (UnsupportedOperationException | IOException e) {
            // no POSIX permissions here (e.g. Windows); the defaults apply
        }
    }
}
