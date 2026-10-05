package com.dmc.backend.reporting;

import static org.springframework.http.HttpStatus.*;

import com.dmc.backend.models.hazard.HazardReport.PhotoEvidence;
import com.dmc.backend.models.hazard.HazardReport.UserReference;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Private local storage for the prototype: no public static-file mapping. Back up it with MongoDB. */
@Component
public class LocalEvidenceStorage implements EvidenceStorage {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000;
    private final Path root;

    public LocalEvidenceStorage(@Value("${dmc.reports.evidence-directory:.data/hazard-evidence}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public PhotoEvidence store(MultipartFile file, UserReference owner, Instant capturedAt, Instant uploadedAt) {
        if (file == null || file.isEmpty()) throw invalid("Attach a nonempty JPEG or PNG photo.");
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        byte[] bytes;
        try (InputStream input = file.getInputStream()) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException exception) { throw unavailable(); }
        if (bytes.length > MAX_BYTES) throw tooLarge();
        String contentType = validateImage(bytes, file.getContentType());
        String filename = filename(file.getOriginalFilename(), contentType);
        String id = UUID.randomUUID().toString();
        PhotoEvidence photo = new PhotoEvidence(id, id, filename, contentType, bytes.length,
                digest(bytes), owner, uploadedAt, capturedAt);
        Path temp = null;
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("Invalid storage directory");
            temp = Files.createTempFile(root, ".upload-", ".tmp");
            try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp, path(photo), StandardCopyOption.ATOMIC_MOVE);
            return photo;
        } catch (IOException exception) {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw unavailable();
        }
    }

    @Override
    public byte[] read(PhotoEvidence photo) {
        try {
            if (Files.isSymbolicLink(root)) throw new IOException("Invalid directory");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path(photo), LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length != photo.sizeBytes() || !digest(bytes).equals(photo.sha256())) {
                throw new IOException("Evidence integrity failure");
            }
            return bytes;
        } catch (IOException exception) { throw unavailable(); }
    }

    @Override
    public void discardUnattached(PhotoEvidence photo) {
        // Best effort: do not replace a useful conflict response with a cleanup failure.
        try { Files.deleteIfExists(path(photo)); } catch (IOException ignored) { }
    }

    private Path path(PhotoEvidence photo) {
        String key = photo.storageKey();
        if (!key.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw unavailable();
        }
        return root.resolve(key);
    }

    private String validateImage(byte[] bytes, String declaredType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Upload an actual JPEG or PNG image.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String actualType = switch (format) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    default -> throw invalid("Only JPEG and PNG photos are supported.");
                };
                if (!actualType.equals(declaredType)) throw invalid("Photo content does not match its media type.");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                    throw invalid("Photo dimensions must not exceed 20 megapixels.");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw invalid("The photo could not be decoded.");
                return actualType;
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("The photo could not be decoded.");
        }
    }

    private String filename(String supplied, String contentType) {
        String name = supplied == null ? "photo" : supplied.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..")) name = "photo";
        if (name.length() > 120) name = name.substring(0, 120);
        // Normalize extension to validated content; client names never select a disk path.
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + (contentType.equals("image/png") ? ".png" : ".jpg");
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private static ReportException invalid(String message) {
        return new ReportException(UNPROCESSABLE_ENTITY, "INVALID_PHOTO", message,
                java.util.List.of(new ReportException.FieldError("photo", "invalid", message)));
    }
    private static ReportException tooLarge() {
        return new ReportException(PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE", "Photo must not exceed 5 MiB.");
    }
    private static ReportException unavailable() {
        return new ReportException(SERVICE_UNAVAILABLE, "EVIDENCE_UNAVAILABLE", "Photo storage is temporarily unavailable.");
    }
}
