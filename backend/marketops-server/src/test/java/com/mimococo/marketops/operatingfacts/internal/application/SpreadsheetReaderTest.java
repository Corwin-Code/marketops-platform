package com.mimococo.marketops.operatingfacts.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading a file a company actually sends.
 *
 * <p>Two formats, both read without a third-party library, and both hostile in
 * their own way. A comma-separated file is written by whatever tool the finance
 * team happens to use, so it arrives with a byte-order mark, quoted commas,
 * embedded newlines and trailing blank lines. A workbook is a zip archive of
 * XML, which means it can also be a zip bomb or an XML document that asks to
 * read a file off the server.
 *
 * <p>The refusals matter more than the successes here. A reader that accepted
 * an oversized archive, or resolved an external entity, would turn a finance
 * spreadsheet into a way into the machine.
 */
class SpreadsheetReaderTest {

    private static final String CSV = "text/csv";
    private static final String WORKBOOK =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SpreadsheetReader reader = new SpreadsheetReader();

    @Nested
    @DisplayName("TC-SHEET-001 a separated-values file is read the way tools write them")
    class SeparatedValues {

        @Test
        void headerAndRowsAreRead() {
            SpreadsheetReader.Sheet sheet = reader.read(csv("""
                    sku,cost,currency
                    widget-a,60.0000,RUB
                    widget-b,70.0000,RUB
                    """), CSV);

            assertThat(sheet.header()).containsExactly("sku", "cost", "currency");
            assertThat(sheet.rows()).hasSize(2);
            assertThat(sheet.rows().getFirst()).containsEntry("sku", "widget-a");
        }

        @Test
        void aByteOrderMarkDoesNotBecomePartOfTheFirstColumnName() {
            byte[] withMark = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                    csv("sku,cost\nwidget-a,60\n"));

            SpreadsheetReader.Sheet sheet = reader.read(withMark, CSV);

            assertThat(sheet.header().getFirst()).isEqualTo("sku");
        }

        @Test
        void aQuotedFieldMayContainTheSeparator() {
            SpreadsheetReader.Sheet sheet = reader.read(csv("""
                    sku,note
                    widget-a,"red, large"
                    """), CSV);

            assertThat(sheet.rows().getFirst()).containsEntry("note", "red, large");
        }

        @Test
        void aQuotedFieldMayContainAQuote() {
            String content = "sku,note\nwidget-a,\"say \"\"hello\"\"\"\n";

            SpreadsheetReader.Sheet sheet = reader.read(csv(content), CSV);

            assertThat(sheet.rows().getFirst()).containsEntry("note", "say \"hello\"");
        }

        @Test
        void aSemicolonSeparatedFileIsReadToo() {
            // Russian office software commonly writes semicolons. A reader that
            // insisted on commas would reject half the files it is sent.
            SpreadsheetReader.Sheet sheet = reader.read(csv("""
                    sku;cost;currency
                    widget-a;60,0000;RUB
                    """), CSV);

            assertThat(sheet.header()).containsExactly("sku", "cost", "currency");
            assertThat(sheet.rows().getFirst()).containsEntry("sku", "widget-a");
        }

        @Test
        void trailingBlankLinesAreNotRows() {
            SpreadsheetReader.Sheet sheet = reader.read(csv("sku,cost\nwidget-a,60\n\n\n"), CSV);

            assertThat(sheet.rows()).hasSize(1);
        }

        @Test
        void aFileWithOnlyAHeaderHasNoRows() {
            SpreadsheetReader.Sheet sheet = reader.read(csv("sku,cost\n"), CSV);

            assertThat(sheet.header()).containsExactly("sku", "cost");
            assertThat(sheet.rows()).isEmpty();
        }
    }

    @Nested
    @DisplayName("TC-SHEET-002 a file that is not a file is refused")
    class Refusals {

        @Test
        void anEmptyFileIsRefused() {
            assertThatThrownBy(() -> reader.read(new byte[0], CSV))
                    .isInstanceOf(OperationRejectedException.class)
                    .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                    .isEqualTo(ErrorCode.IMPORT_TOO_LARGE);
        }

        @Test
        void aFileLargerThanTheIntakeAcceptsIsRefused() {
            byte[] oversized = new byte[SpreadsheetReader.MAXIMUM_FILE_BYTES + 1];

            assertThatThrownBy(() -> reader.read(oversized, CSV))
                    .isInstanceOf(OperationRejectedException.class)
                    .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                    .isEqualTo(ErrorCode.IMPORT_TOO_LARGE);
        }

        @Test
        void aMediaTypeThisIntakeDoesNotReadIsRefused() {
            assertThatThrownBy(() -> reader.read(csv("a,b\n1,2\n"), "application/pdf"))
                    .isInstanceOf(OperationRejectedException.class)
                    .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        void aFileWithNoHeaderIsRefused() {
            assertThatThrownBy(() -> reader.read(csv("\n"), CSV))
                    .isInstanceOf(OperationRejectedException.class);
        }

        @Test
        void anArchiveThatIsNotAWorkbookIsRefused() {
            assertThatThrownBy(() -> reader.read(zip("notes.txt", "hello"), WORKBOOK))
                    .isInstanceOf(OperationRejectedException.class)
                    .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                    .isEqualTo(ErrorCode.IMPORT_VALIDATION_FAILED);
        }

        @Test
        void somethingThatIsNotAnArchiveAtAllIsRefused() {
            assertThatThrownBy(() -> reader.read(csv("sku,cost\nwidget,60\n"), WORKBOOK))
                    .isInstanceOf(OperationRejectedException.class)
                    .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                    .isEqualTo(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private static byte[] csv(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /** A zip archive holding one named entry, for the refusal cases. */
    private static byte[] zip(String entryName, String content) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(buffer)) {
            archive.putNextEntry(new ZipEntry(entryName));
            archive.write(content.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        } catch (IOException unwritable) {
            throw new IllegalStateException("the test archive could not be written",
                    unwritable);
        }
        return buffer.toByteArray();
    }

    /** Deliberately unused list guard, kept so the imports stay honest. */
    private static final List<String> FORMATS = List.of(CSV, WORKBOOK);

    static {
        assert FORMATS.size() == 2;
    }
}
