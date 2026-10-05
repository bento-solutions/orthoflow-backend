package com.orthoflow.export.application.service;

import com.orthoflow.export.application.dto.ExportFormat;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.CsvWriter;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.export.infrastructure.XlsxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The one place a report becomes a file. A controller builds a
 * {@link TableExport}, reads {@code format} from the request and returns
 * {@link #respond}; nothing else in the application writes a PDF, sheet or CSV.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private final PdfService pdfService;
    private final LetterheadProvider letterheadProvider;

    public byte[] render(TableExport table, ExportFormat format, UUID practiceId, String lang) {
        return switch (format) {
            case CSV -> CsvWriter.write(table);
            case XLSX -> XlsxWriter.write(table);
            case PDF -> pdfService.renderTable(table, letterheadProvider.forPractice(practiceId), lang);
        };
    }

    public ResponseEntity<byte[]> respond(TableExport table, String format, String baseName, UUID practiceId, String lang) {
        ExportFormat fmt = ExportFormat.parse(format);
        return download(render(table, fmt, practiceId, lang), fmt, baseName);
    }

    /** A document already rendered (an invoice, a fee note) sent as a download. */
    public ResponseEntity<byte[]> download(byte[] bytes, ExportFormat format, String baseName) {
        String filename = baseName.replaceAll("[^A-Za-z0-9._-]", "_") + "." + format.extension();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(format.mediaType()))
                .body(bytes);
    }

    public Letterhead letterhead(UUID practiceId) {
        return letterheadProvider.forPractice(practiceId);
    }
}
