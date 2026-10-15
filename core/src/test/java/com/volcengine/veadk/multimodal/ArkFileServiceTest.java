/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.volcengine.ark.runtime.model.files.FileMeta;
import com.volcengine.ark.runtime.model.files.UploadFileRequest;
import com.volcengine.ark.runtime.service.ArkService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ArkFileServiceTest {
    @TempDir Path directory;
    private final ArkService ark = mock(ArkService.class);

    @Test
    void defaultConstructorDefersCredentialResolution() {
        new ArkFileService().close();
    }

    @Test
    void uploadsUserDataAndReturnsFileIdPart() throws Exception {
        Path file = directory.resolve("notes.txt");
        Files.writeString(file, "hello");
        FileMeta metadata = new FileMeta();
        metadata.setId("file-123");
        metadata.setStatus("processed");
        when(ark.uploadFile(any())).thenReturn(metadata);

        var part = new ArkFileService(ark, 100).upload(file, "text/plain");

        assertThat(part.fileData().orElseThrow().fileUri()).contains("file_id://file-123");
        assertThat(part.fileData().orElseThrow().mimeType()).contains("text/plain");
        var request = ArgumentCaptor.forClass(UploadFileRequest.class);
        verify(ark).uploadFile(request.capture());
        assertThat(request.getValue().getPurpose()).isEqualTo("user_data");
    }

    @Test
    void rejectsUnsupportedOrOversizedFilesBeforeNetwork() throws Exception {
        Path file = directory.resolve("payload.bin");
        Files.write(file, new byte[] {1, 2, 3});
        var service = new ArkFileService(ark, 2);
        assertThatThrownBy(() -> service.upload(file, "application/octet-stream"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> service.upload(file, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void rejectsUploadWithoutFileId() throws Exception {
        Path file = directory.resolve("notes.txt");
        Files.writeString(file, "hello");
        when(ark.uploadFile(any())).thenReturn(new FileMeta());
        assertThatThrownBy(() -> new ArkFileService(ark, 100).upload(file, "text/plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("file ID");
    }
}
