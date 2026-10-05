package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.HazardReport.UserReference;
import com.dmc.backend.models.hazard.HazardReport.PhotoEvidence;
import static com.dmc.backend.reporting.ReportFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.dmc.backend.models.hazard.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

class LocalEvidenceStorageTest {
    @TempDir Path directory;
    LocalEvidenceStorage storage;
    UserReference owner = new UserReference("citizen", "Citizen");
    @BeforeEach void setup() { storage = new LocalEvidenceStorage(directory.toString()); }
    @ParameterizedTest @ValueSource(strings = {"png", "jpeg"})
    void validatesStoresAndReadsActualSupportedImages(String format) throws Exception {
        byte[] bytes = image(format, 3, 3);
        var photo = storage.store(new MockMultipartFile("file", "../../unsafe\\name." + format,
                "image/" + format, bytes), owner, NOW.minusSeconds(30), NOW);
        assertThat(storage.read(photo)).isEqualTo(bytes);
        assertThat(photo.originalFilename()).doesNotContain("/", "\\");
        assertThat(photo.storageKey()).isEqualTo(photo.id());
        assertThat(photo.sizeBytes()).isEqualTo(bytes.length);
        assertThat(photo.sha256()).hasSize(64);
        assertThat(photo.capturedAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(Files.isRegularFile(directory.resolve(photo.storageKey()))).isTrue();
    }
    @Test void rejectsFakeImagesWrongContentTypesAndUnsupportedFormats() throws Exception {
        assertInvalid(new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes()));
        assertInvalid(new MockMultipartFile("file", "real.png", "image/jpeg", image("png", 2, 2)));
        assertInvalid(new MockMultipartFile("file", "real.gif", "image/gif", image("gif", 2, 2)));
        assertThat(Files.list(directory).count()).isZero();
    }
    @Test void rejectsEmptyOversizedAndTruncatedImages() throws Exception {
        assertInvalid(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]));
        byte[] bytes = image("png", 2, 2);
        assertInvalid(new MockMultipartFile("file", "truncated.png", "image/png", java.util.Arrays.copyOf(bytes, bytes.length / 2)));
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]), owner, null, NOW))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.status().value()).isEqualTo(413));
    }
    @Test void rejectsExcessiveDimensionsBeforeDecodingFullImage() throws Exception {
        byte[] bytes = image("png", 5000, 4001);
        assertInvalid(new MockMultipartFile("file", "huge.png", "image/png", bytes));
    }
    @Test void missingTamperedAndTraversalFilesAreUnavailable() throws Exception {
        var photo = storage.store(new MockMultipartFile("file", "road.png", "image/png", image("png", 2, 2)), owner, null, NOW);
        Files.write(directory.resolve(photo.storageKey()), new byte[]{1, 2, 3});
        assertThatThrownBy(() -> storage.read(photo)).isInstanceOf(ReportException.class);
        storage.discardUnattached(photo);
        assertThatThrownBy(() -> storage.read(photo)).isInstanceOf(ReportException.class);
        var traversal = new PhotoEvidence("id", "../private", "road.png", "image/png", 3, "a".repeat(64), owner, NOW, null);
        assertThatThrownBy(() -> storage.read(traversal)).isInstanceOf(ReportException.class);
    }
    @Test void refusesSymlinkEvidenceFiles() throws Exception {
        var photo = storage.store(new MockMultipartFile("file", "road.png", "image/png", image("png", 2, 2)), owner, null, NOW);
        Path outside = directory.resolve("another-file"); Files.write(outside, image("png", 2, 2));
        Files.delete(directory.resolve(photo.storageKey())); Files.createSymbolicLink(directory.resolve(photo.storageKey()), outside);
        assertThatThrownBy(() -> storage.read(photo)).isInstanceOf(ReportException.class);
    }
    @Test void unwritableStorageReturnsSafeError() throws Exception {
        Path file = directory.resolve("not-a-directory"); Files.writeString(file, "file");
        var blocked = new LocalEvidenceStorage(file.toString());
        assertThatThrownBy(() -> blocked.store(new MockMultipartFile("file", "road.png", "image/png", image("png", 2, 2)), owner, null, NOW))
                .isInstanceOfSatisfying(ReportException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.getMessage()).doesNotContain(file.toString());
                });
    }
    private void assertInvalid(MockMultipartFile file) {
        assertThatThrownBy(() -> storage.store(file, owner, null, NOW))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.status().value()).isEqualTo(422));
    }
    static byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
}
