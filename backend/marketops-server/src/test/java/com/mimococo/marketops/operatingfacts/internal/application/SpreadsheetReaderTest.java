package com.mimococo.marketops.operatingfacts.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings={"\n", "\r\n", "\r"})
    void lineEndingsAndMissingColumnsPreserveTheRowBoundaries(String ending) {
        var sheet = reader.read(csv(" SKU\tNote\tCost"+ending+"a\t\"line1\nline2\"\t1.20"+ending+"b\tlast"),CSV);
        assertThat(sheet.header()).containsExactly("sku","note","cost");
        assertThat(sheet.rows()).containsExactly(Map.of("sku","a","note","line1\nline2","cost","1.20"),
                Map.of("sku","b","note","last","cost",""));
        assertThatThrownBy(() -> sheet.rows().getFirst().put("cost","999")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"sku,SKU\na,b", "sku,\na,b", "sku,cost\na,1,2", "sku,cost\na,\"unclosed",
            "sku,cost\na,\"1\"junk", "sku,cost\na,1\"2", "   \n\n", "\"\""})
    void ambiguousSeparatedValuesAreRejected(String content) {
        rejected(csv(content),CSV,ErrorCode.IMPORT_VALIDATION_FAILED);
    }

    @Test
    void malformedUtf8NullInputsAndResourceLimitsFailClosed() {
        rejected(new byte[]{(byte)0xc3,(byte)0x28},CSV,ErrorCode.IMPORT_VALIDATION_FAILED);
        rejected(null,CSV,ErrorCode.IMPORT_TOO_LARGE);
        rejected(csv("sku"),null,ErrorCode.VALIDATION_FAILED);
        rejected(csv(String.join(",",java.util.Collections.nCopies(129,"field"))),CSV,ErrorCode.IMPORT_TOO_LARGE);
        rejected(csv("sku\n"+"a\n".repeat(SpreadsheetReader.MAXIMUM_ROWS+1)),CSV,ErrorCode.IMPORT_TOO_LARGE);
        rejected(csv("sku\n"+"a\n".repeat(SpreadsheetReader.MAXIMUM_ROWS)+"b"),CSV,ErrorCode.IMPORT_TOO_LARGE);
        assertThat(reader.read(csv("sku\n"+"a\n".repeat(SpreadsheetReader.MAXIMUM_ROWS)),CSV).rows()).hasSize(SpreadsheetReader.MAXIMUM_ROWS);
    }

    @Test
    void workbookOrderSharedRichTextSparseCellsAndRawNumbersAreReadExactly() {
        var parts=workbook("""
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>note</t></is></c><c r="C1" t="str"><v>cost</v></c></row>
                <row r="2"><c r="A2" t="s"><v>1</v></c><c r="C2"><v>99999999999999.9999</v></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><r><t>part</t></r><r><t>two</t></r></is></c><c r="B3" t="b"><v>1</v></c><c r="C3" t="n"><v>1.2300</v></c></row>
                <row r="4"><c r="A4" t="b"><v>0</v></c><c r="B4" t="d"><v>2026-08-28</v></c><c r="C4"/></row>
                """);
        parts.put("xl/sharedStrings.xml","""
                <sst><si><t>SKU</t></si><si>
                  <r><t>widget</t></r>
                  <r><t>-a</t></r><rPh><t>phonetic-annotation</t></rPh>
                </si></sst>
                """);
        // The first declared tab is sheet9; the lexicographically first part must never win.
        parts.put("xl/worksheets/sheet1.xml",worksheet("<row><c r='A1'><v>wrong-sheet</v></c></row>"));
        var sheet=reader.read(zip(parts),WORKBOOK);
        assertThat(sheet.header()).containsExactly("sku","note","cost");
        assertThat(sheet.rows()).containsExactly(Map.of("sku","widget-a","note","","cost","99999999999999.9999"),
                Map.of("sku","parttwo","note","true","cost","1.2300"),Map.of("sku","false","note","2026-08-28","cost",""));
    }

    @ParameterizedTest
    @ValueSource(strings={"-1","1","2147483648","word",""})
    void sharedStringIndicesMustBeExactAndInRange(String index) {
        var parts=workbook("<row><c r='A1' t='s'><v>"+index+"</v></c></row>");
        parts.put("xl/sharedStrings.xml","<sst><si><t>sku</t></si></sst>");
        rejected(zip(parts),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings={
        "<row><c r='A1'><f>2+2</f><v>4</v></c></row>",
        "<row><c r='A1' t='e'><v>#N/A</v></c></row>",
        "<row><c r='A1' t='unsupported'><v>sku</v></c></row>",
        "<row><c r='A1' t='b'><v>truthy</v></c></row>",
        "<row><c r='A1'><v>sku</v></c><c r='a1'><v>changed</v></c></row>",
        "<row><c><v>sku</v></c></row>", "<row><c r='A0'><v>sku</v></c></row>",
        "<row><c r='ZZZ1'><v>sku</v></c></row>", "<row><c r='ZZZZ1'><v>sku</v></c></row>",
        "<row r='2'><c r='A1'><v>sku</v></c></row>", "<row r='0'/>",
        "<row r='1'><c r='A1'><v>sku</v></c></row><row r='1'/>",
        "<row><row/></row>", "<c r='A1'><v>sku</v></c>",
        "<row><c r='A1'><c r='B1'/></c></row>", "<row><c r='A1'><v>sku</row>",
        "<row><c r='B1'><v>sku</v></c></row>"
    })
    void malformedOrAmbiguousWorksheetCellsAreRejected(String rows) {
        rejected(zip(workbook(rows)),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
    }

    @Test
    void twoLetterColumnsRemainInTheirDeclaredPosition() {
        StringBuilder header=new StringBuilder("<row r='1'>");
        for(int i=0;i<28;i++) {
            String column=i<26?String.valueOf((char)('A'+i)):"A"+(char)('A'+i-26);
            header.append("<c r='").append(column).append("1'><v>col").append(i).append("</v></c>");
        }
        var sheet=reader.read(zip(workbook(header+"</row><row r='2'><c r='AB2'><v>last</v></c></row>")),WORKBOOK);
        assertThat(sheet.rows().getFirst()).hasSize(28).containsEntry("col27","last").containsEntry("col0","");
    }

    @ParameterizedTest
    @ValueSource(strings={"https://example.invalid/sheet.xml","../sheet.xml","worksheets/../../sheet.xml","worksheets/missing.xml"})
    void worksheetTargetsMustResolveToAnExistingInternalPart(String target) {
        var parts=workbook("<row><c r='A1'><v>sku</v></c></row>");
        parts.put("xl/_rels/workbook.xml.rels",relationship(target,"worksheet",""));
        rejected(zip(parts),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
    }

    @Test
    void externalNonWorksheetDuplicateAndMissingRelationshipsAreRefused() {
        for(String rel:List.of(relationship("worksheets/sheet9.xml","worksheet"," TargetMode='External'"),
                relationship("worksheets/sheet9.xml","chartsheet",""),"<Relationships/>","<Relationships>",
                "<Relationships><Relationship/><Relationship/></Relationships>",
                relationship("worksheets/sheet9.xml","worksheet","").replace("</Relationships>","<Relationship Id='chosen'/></Relationships>"))) {
            var parts=workbook("<row><c r='A1'><v>sku</v></c></row>");
            parts.put("xl/_rels/workbook.xml.rels",rel);
            rejected(zip(parts),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        for(String xml:List.of("<workbook><sheets><sheet/></sheets></workbook>","<workbook/>","<workbook>")) {
            var parts=workbook(""); parts.put("xl/workbook.xml",xml);
            rejected(zip(parts),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        rejected(zip("xl/worksheets/sheet1.xml",worksheet("<row><c r='A1'><v>sku</v></c></row>")),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
    }

    @Test
    void absoluteInternalPartAndStrictRelationshipsAreSupported() {
        var parts=workbook("<row><c r='A1'><v>sku</v></c></row>");
        parts.put("xl/_rels/workbook.xml.rels",relationship("/xl/worksheets/sheet9.xml","worksheet"," TargetMode='Internal'"));
        parts.replaceAll((name,xml) -> xml.replace("http://schemas.openxmlformats.org/officeDocument/2006/relationships","http://purl.oclc.org/ooxml/officeDocument/relationships"));
        assertThat(reader.read(zip(parts),WORKBOOK).header()).containsExactly("sku");
    }

    @Test
    void dtdEntitiesInEveryParsedXmlPartAreRejectedBeforeResolution() {
        for(String name:List.of("xl/workbook.xml","xl/_rels/workbook.xml.rels","xl/sharedStrings.xml","xl/worksheets/sheet9.xml")) {
            var parts=workbook("<row><c r='A1'><v>sku</v></c></row>");
            parts.put(name,"<!DOCTYPE x [<!ENTITY external SYSTEM 'https://example.invalid/never-fetch'>]><x>&external;</x>");
            rejected(zip(parts),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
        }
    }

    @Test
    void malformedSharedStringsAndExcessiveRowsEntriesOrExpansionAreRejected() {
        for(String xml:List.of("<sst>","<sst><si><si><t>bad</t></si></si></sst>")) {
            var parts=workbook("");parts.put("xl/sharedStrings.xml",xml);
            rejected(zip(parts),WORKBOOK,ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        rejected(zip(workbook("<row/>".repeat(SpreadsheetReader.MAXIMUM_ROWS+2))),WORKBOOK,ErrorCode.IMPORT_TOO_LARGE);
        var entries=new LinkedHashMap<String,String>();
        for(int i=0;i<513;i++) entries.put("ignored/"+i,"small");
        rejected(zip(entries),WORKBOOK,ErrorCode.IMPORT_TOO_LARGE);
        // Even unused ZIP members count toward the total expanded bound.
        rejected(zip("ignored.bin","x".repeat(64*1024*1024+1)),WORKBOOK,ErrorCode.IMPORT_TOO_LARGE);
    }

    private void rejected(byte[] bytes,String type,ErrorCode code) {
        assertThatThrownBy(() -> reader.read(bytes,type)).isInstanceOf(OperationRejectedException.class)
                .extracting(e -> ((OperationRejectedException)e).errorCode()).isEqualTo(code);
    }
    private static String worksheet(String rows) { return "<worksheet><sheetData>"+rows+"</sheetData></worksheet>"; }
    private static Map<String,String> workbook(String rows) {
        var parts=new LinkedHashMap<String,String>();
        parts.put("xl/workbook.xml","<workbook xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='first' sheetId='9' r:id='chosen'/><sheet name='second' sheetId='1' r:id='other'/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels",relationship("worksheets/sheet9.xml","worksheet",""));
        parts.put("xl/worksheets/sheet9.xml",worksheet(rows));
        return parts;
    }
    private static String relationship(String target,String type,String attributes) {
        return "<Relationships><Relationship Id='chosen' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/"+type+"' Target='"+target+"'"+attributes+"/></Relationships>";
    }
    private static byte[] zip(Map<String,String> parts) {
        var buffer=new ByteArrayOutputStream();
        try(var archive=new ZipOutputStream(buffer)) {
            for(var part:parts.entrySet()) {
                archive.putNextEntry(new ZipEntry(part.getKey())); archive.write(csv(part.getValue())); archive.closeEntry();
            }
        } catch(IOException impossible) { throw new AssertionError(impossible); }
        return buffer.toByteArray();
    }

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

}
