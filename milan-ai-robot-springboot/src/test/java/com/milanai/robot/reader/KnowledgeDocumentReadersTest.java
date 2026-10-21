package com.milanai.robot.reader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentReadersTest {

    @TempDir
    Path directory;

    @Test
    void markdownRetainsImageAddressAndFileOwnership() throws Exception {
        String image = "https://example.com/qr.png?ref=$demo";
        Path file = directory.resolve("support.md");
        Files.writeString(file, "## Support\n\nScan this image: ![contact](" + image + ")\n");

        List<Document> documents = new MarkdownReader().loadMarkdown(new FileSystemResource(file), metadata("support.md"));

        assertThat(text(documents)).contains(image);
        assertOwnership(documents, "support.md");
    }

    @Test
    void pdfExtractsPageTextAndRetainsFileOwnership() throws Exception {
        Path file = directory.resolve("warranty.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Product warranty lasts twelve months.");
                content.endText();
            }
            pdf.save(file.toFile());
        }

        List<Document> documents = new PdfReader().read(new FileSystemResource(file), metadata("warranty.pdf"));

        assertThat(text(documents).replaceAll("\\s+", " ").trim())
                .contains("Product warranty lasts twelve months.");
        assertOwnership(documents, "warranty.pdf");
    }

    @Test
    void wordExtractsDocxTextAndRetainsFileOwnership() throws Exception {
        Path file = directory.resolve("warranty.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            writeEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            writeEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            writeEntry(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Product warranty lasts twelve months.</w:t></w:r></w:p></w:body>
                    </w:document>
                    """);
        }

        List<Document> documents = new WordReader().read(new FileSystemResource(file), metadata("warranty.docx"));

        assertThat(text(documents)).contains("Product warranty lasts twelve months.");
        assertOwnership(documents, "warranty.docx");
    }

    private Map<String, Object> metadata(String filename) {
        return Map.of("mdStorageId", 42L, "originalFileName", filename);
    }

    private void assertOwnership(List<Document> documents, String filename) {
        assertThat(documents).isNotEmpty().allSatisfy(document ->
                assertThat(document.getMetadata())
                        .containsEntry("mdStorageId", 42L)
                        .containsEntry("originalFileName", filename));
    }

    private String text(List<Document> documents) {
        return String.join("\n", documents.stream().map(Document::getText).toList());
    }

    private void writeEntry(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
