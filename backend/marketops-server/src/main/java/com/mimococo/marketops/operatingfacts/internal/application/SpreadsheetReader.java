package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * Reads a submitted comma-separated or worksheet file into rows of text.
 *
 * <p>Everything here reads; nothing interprets. A cell becomes the text the file
 * contains and validation decides what that text means, because a reader that
 * guessed at types would make "the file said 1.5" and "the file said the first
 * of May" indistinguishable by the time anybody could check.
 *
 * <p>The worksheet reader is deliberately narrow: the first sheet, shared and
 * inline strings, and the raw stored value for everything else. A cell holding a
 * date as a spreadsheet serial number is returned as that number and rejected by
 * validation with a reason the submitter can act on, rather than converted using
 * an epoch this system would have to assume.
 *
 * <p>Both readers are bounded and refuse an oversized or malformed file rather
 * than allocating whatever it asks for. The worksheet reader disables external
 * entity resolution, so a crafted document cannot make this process fetch
 * anything or read a local file.
 */
@Component
public class SpreadsheetReader {

    /** Largest file this intake accepts. */
    public static final int MAXIMUM_FILE_BYTES = 8 * 1024 * 1024;

    /** Largest number of data rows one submission may carry. */
    public static final int MAXIMUM_ROWS = 50_000;

    /** Largest number of columns a header may declare. */
    private static final int MAXIMUM_COLUMNS = 128;

    /** Largest expansion the worksheet reader will accept from one entry. */
    private static final long MAXIMUM_ENTRY_BYTES = 64L * 1024 * 1024;

    private static final String WORKSHEET_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String CSV_MEDIA_TYPE = "text/csv";

    /**
     * Read a submitted file into a header and its data rows.
     *
     * @throws OperationRejectedException when the media type is not accepted, the
     *         file is larger than this intake allows, or the content cannot be
     *         read as the declared type
     */
    public Sheet read(byte[] content, String mediaType) {
        if (content == null || content.length == 0 || content.length > MAXIMUM_FILE_BYTES) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
        }
        if (mediaType == null) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        return switch (mediaType) {
            case CSV_MEDIA_TYPE -> readSeparatedValues(content);
            case WORKSHEET_MEDIA_TYPE -> readWorksheet(content);
            default -> throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        };
    }

    // -----------------------------------------------------------------------
    // Separated values
    // -----------------------------------------------------------------------

    /**
     * Read separated values, honouring quoted fields.
     *
     * <p>A quoted field may contain a separator, a newline and a doubled quote,
     * which is what the format actually allows; a reader that split on commas
     * would silently corrupt exactly the rows that carry a product name with a
     * comma in it.
     *
     * <p>The separator is detected rather than assumed. Office software in a
     * locale that writes decimals with a comma exports semicolons instead, and
     * a Russian finance team's spreadsheet is very often exactly that. A reader
     * that insisted on commas would read such a file as one column, fail every
     * row, and tell the submitter nothing about why.
     */
    private static Sheet readSeparatedValues(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content)).toString();
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        char separator = detectSeparator(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '"') {
                    boolean escapedQuote = index + 1 < text.length()
                            && text.charAt(index + 1) == '"';
                    if (escapedQuote) {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }
            if (closedQuote && character != separator && character != '\r' && character != '\n') {
                throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
            }
            if (character == '"') {
                if (!field.isEmpty()) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                quoted = true;
            } else if (character == separator) {
                row.add(field.toString());
                field.setLength(0);
                closedQuote = false;
                if (row.size() >= MAXIMUM_COLUMNS) throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
            } else if (character == '\r' || character == '\n') {
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
                closedQuote = false;
                if (rows.size() > MAXIMUM_ROWS + 1) {
                    throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
                }
            } else {
                field.append(character);
            }
        }
        if (quoted) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(List.copyOf(row));
        }
        if (rows.size() > MAXIMUM_ROWS + 1) throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
        return toSheet(rows);
    }

    /**
     * Which character separates the fields of this file.
     *
     * <p>Decided from the header line alone, by counting each candidate outside
     * quotes and taking the one that actually divides it. A file whose header
     * contains none of them is a single-column file, and the comma is as good
     * an answer as any: nothing will be split either way.
     */
    private static char detectSeparator(String text) {
        int lineEnd = text.indexOf('\n');
        int carriageReturn = text.indexOf('\r');
        if (carriageReturn >= 0 && (lineEnd < 0 || carriageReturn < lineEnd)) lineEnd = carriageReturn;
        String header = lineEnd < 0 ? text : text.substring(0, lineEnd);
        char best = ',';
        int bestCount = 0;
        for (char candidate : new char[] {',', ';', '\t'}) {
            int count = countOutsideQuotes(header, candidate);
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    /** How many times a character divides a line, ignoring quoted regions. */
    private static int countOutsideQuotes(String line, char candidate) {
        int count = 0;
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == candidate) {
                count++;
            }
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // Worksheet
    // -----------------------------------------------------------------------

    private static Sheet readWorksheet(byte[] content) {
        Map<String, byte[]> parts = unpack(content);
        List<String> sharedStrings = readSharedStrings(parts.get("xl/sharedStrings.xml"));
        byte[] sheet = firstWorksheet(parts);
        if (sheet == null) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return toSheet(readSheetRows(sheet, sharedStrings));
    }

    /**
     * Unpack the entries this reader needs, refusing an archive that expands
     * beyond a sane bound.
     *
     * <p>A compressed archive can describe far more data than it contains, so
     * the limit is on what comes out rather than on what went in.
     */
    private static Map<String, byte[]> unpack(byte[] content) {
        Map<String, byte[]> parts = new HashMap<>();
        var names = new HashSet<String>();
        long expanded = 0;
        int entryCount = 0;
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entryCount > 512) throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
                String name = entry.getName();
                if (!names.add(name)) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                boolean needed = name.equals("xl/workbook.xml")
                        || name.equals("xl/_rels/workbook.xml.rels")
                        || name.equals("xl/sharedStrings.xml")
                        || name.startsWith("xl/worksheets/");
                // Account for every expanded entry, including ignored content;
                // allocating first and checking afterwards cannot bound a ZIP bomb.
                byte[] part = archive.readNBytes(Math.toIntExact(MAXIMUM_ENTRY_BYTES - expanded + 1));
                expanded += part.length;
                if (expanded > MAXIMUM_ENTRY_BYTES) {
                    throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
                }
                if (needed && parts.putIfAbsent(name, part) != null) {
                    throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                }
            }
        } catch (IOException unreadable) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return parts;
    }

    /** Resolve the first declared sheet through its internal package relationship, never filename order or a URL. */
    private static byte[] firstWorksheet(Map<String, byte[]> parts) {
        byte[] workbook = parts.get("xl/workbook.xml");
        byte[] relationships = parts.get("xl/_rels/workbook.xml.rels");
        if (workbook == null || relationships == null) return null;
        String firstId = null;
        try (var reader = openXml(workbook)) {
            while (reader.stream().hasNext()) {
                int event = reader.stream().next();
                if (event == XMLStreamConstants.DTD) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                if (event == XMLStreamConstants.START_ELEMENT && "sheet".equals(reader.stream().getLocalName()) && firstId == null) {
                    firstId = reader.stream().getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                    if (firstId == null) firstId = reader.stream().getAttributeValue("http://purl.oclc.org/ooxml/officeDocument/relationships", "id");
                    if (firstId == null || firstId.isBlank()) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                }
            }
        } catch (XMLStreamException malformed) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        if (firstId == null) return null;
        byte[] sheet = null;
        var ids = new HashSet<String>();
        try (var reader = openXml(relationships)) {
            while (reader.stream().hasNext()) {
                int event = reader.stream().next();
                if (event == XMLStreamConstants.DTD) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                if (event != XMLStreamConstants.START_ELEMENT || !"Relationship".equals(reader.stream().getLocalName())) continue;
                var stream = reader.stream();
                String id = stream.getAttributeValue(null, "Id");
                if (id == null || !ids.add(id)) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                if (!id.equals(firstId)) continue;
                String target = stream.getAttributeValue(null, "Target");
                String type = stream.getAttributeValue(null, "Type");
                String mode = stream.getAttributeValue(null, "TargetMode");
                if (target == null || (mode != null && !"Internal".equals(mode))
                        || !("http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet".equals(type)
                            || "http://purl.oclc.org/ooxml/officeDocument/relationships/worksheet".equals(type))) {
                    throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                }
                String path = target.startsWith("/xl/") ? target.substring(1) : "xl/" + target;
                if (!path.matches("xl/worksheets/[A-Za-z0-9_-][A-Za-z0-9_.-]*[.]xml")) {
                    throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                }
                sheet = parts.get(path);
            }
        } catch (XMLStreamException malformed) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return sheet;
    }

    private static List<String> readSharedStrings(byte[] part) {
        List<String> strings = new ArrayList<>();
        if (part == null) {
            return strings;
        }
        try (AutoCloseableReader reader = openXml(part)) {
            StringBuilder current = null;
            boolean collecting = false;
            boolean phonetic = false;
            while (reader.stream().hasNext()) {
                int event = reader.stream().next();
                if (event == XMLStreamConstants.DTD) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("si".equals(reader.stream().getLocalName())) {
                        if (current != null) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                        current = new StringBuilder();
                    } else if ("rPh".equals(reader.stream().getLocalName())) {
                        phonetic = true;
                    } else if ("t".equals(reader.stream().getLocalName()) && !phonetic) {
                        collecting = true;
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && collecting && current != null) {
                    current.append(reader.stream().getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.stream().getLocalName()) {
                        case "t" -> collecting = false;
                        case "rPh" -> phonetic = false;
                        case "si" -> {
                            if (current != null) strings.add(current.toString());
                            current = null;
                        }
                        default -> { }
                    }
                }
            }
        } catch (XMLStreamException malformed) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return strings;
    }

    private static List<List<String>> readSheetRows(byte[] part, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        try (AutoCloseableReader reader = openXml(part)) {
            List<String> row = null;
            var occupied = new HashSet<Integer>();
            var rowReferences = new HashSet<String>();
            String rowReference = null;
            String cellType = null;
            String cellReference = null;
            StringBuilder value = null;
            boolean collecting = false;

            while (reader.stream().hasNext()) {
                int event = reader.stream().next();
                if (event == XMLStreamConstants.DTD) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                XMLStreamReader stream = reader.stream();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (stream.getLocalName()) {
                        case "f" -> throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                        case "row" -> {
                            if (row != null) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                            rowReference = stream.getAttributeValue(null, "r");
                            if (rowReference != null && (!rowReference.matches("[1-9][0-9]{0,6}") || !rowReferences.add(rowReference))) {
                                throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                            }
                            row = new ArrayList<>();
                            occupied.clear();
                        }
                        case "c" -> {
                            if (row == null || value != null) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                            cellType = stream.getAttributeValue(null, "t");
                            cellReference = stream.getAttributeValue(null, "r");
                            int column = columnIndex(cellReference);
                            if (column < 0 || !occupied.add(column)
                                    || (rowReference != null && !cellReference.replaceFirst("^[A-Za-z]+", "").equals(rowReference))) {
                                throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                            }
                            value = new StringBuilder();
                        }
                        // Characters are collected only inside the two elements
                        // that hold a cell's value. Collecting everywhere would
                        // append the whitespace a formatted document puts
                        // between elements into the value itself.
                        case "v", "t" -> collecting = true;
                        default -> {
                            // No other element carries a value this reader wants.
                        }
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && collecting && value != null) {
                    value.append(stream.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (stream.getLocalName()) {
                        case "v", "t" -> collecting = false;
                        case "c" -> {
                            if (row != null) {
                                placeCell(row, cellReference,
                                        resolveCell(cellType, value, sharedStrings));
                            }
                            value = null;
                            cellType = null;
                            cellReference = null;
                        }
                        case "row" -> {
                            if (row != null) {
                                rows.add(List.copyOf(row));
                                row = null;
                                if (rows.size() > MAXIMUM_ROWS + 1) {
                                    throw OperationRejectedException.of(
                                            ErrorCode.IMPORT_TOO_LARGE);
                                }
                            }
                        }
                        default -> {
                            // Nothing else ends a value this reader collects.
                        }
                    }
                }
            }
        } catch (XMLStreamException malformed) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return rows;
    }

    private static String resolveCell(String cellType,
                                      StringBuilder value,
                                      List<String> sharedStrings) {
        String raw = value == null ? "" : value.toString();
        if ("e".equals(cellType) || (cellType != null && !List.of("s", "inlineStr", "str", "n", "b", "d").contains(cellType))) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        if ("b".equals(cellType)) {
            if (!List.of("0", "1").contains(raw)) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
            return "1".equals(raw) ? "true" : "false";
        }
        if (!"s".equals(cellType)) {
            return raw;
        }
        try {
            int index = Integer.parseInt(raw.trim());
            if (index < 0 || index >= sharedStrings.size()) {
                throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
            }
            return sharedStrings.get(index);
        } catch (NumberFormatException notAnIndex) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
    }

    /**
     * Put a cell at the column its reference names.
     *
     * <p>A worksheet omits empty cells, so appending in document order would
     * shift every value after the first gap into the wrong column. The
     * reference is the only reliable statement of where a value belongs.
     */
    private static void placeCell(List<String> row, String reference, String value) {
        int column = columnIndex(reference);
        if (column < 0 || column >= MAXIMUM_COLUMNS) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        while (row.size() <= column) {
            row.add("");
        }
        row.set(column, value);
    }

    /** The zero-based column a cell reference such as {@code AB7} names. */
    private static int columnIndex(String reference) {
        if (reference == null || !reference.matches("[A-Za-z]{1,3}[1-9][0-9]{0,6}")) {
            return -1;
        }
        int index = 0;
        for (int position = 0; position < reference.length(); position++) {
            char character = Character.toUpperCase(reference.charAt(position));
            if (character < 'A' || character > 'Z') {
                break;
            }
            index = index * 26 + (character - 'A' + 1);
        }
        return index - 1;
    }

    /**
     * Open an XML reader that resolves nothing outside the document.
     *
     * <p>A submitted file is untrusted input. Leaving entity resolution on would
     * let a crafted document make this process read a local file or reach a
     * network address, so both are switched off before anything is parsed.
     */
    private static AutoCloseableReader openXml(byte[] part) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        try {
            InputStream source = new ByteArrayInputStream(part);
            return new AutoCloseableReader(factory.createXMLStreamReader(source));
        } catch (XMLStreamException malformed) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
    }

    private static Sheet toSheet(List<List<String>> rows) {
        List<List<String>> nonEmpty = rows.stream()
                .filter(row -> row.stream().anyMatch(cell -> !cell.isBlank()))
                .toList();
        if (nonEmpty.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        List<String> header = nonEmpty.getFirst().stream()
                .map(cell -> cell.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (header.size() > MAXIMUM_COLUMNS) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
        }
        if (header.stream().anyMatch(String::isBlank)
                || header.stream().distinct().count() != header.size()) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        List<Map<String, String>> dataRows = new ArrayList<>(nonEmpty.size() - 1);
        for (List<String> row : nonEmpty.subList(1, nonEmpty.size())) {
            if (row.size() > header.size()) throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
            Map<String, String> named = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                named.put(header.get(column),
                        column < row.size() ? row.get(column).trim() : "");
            }
            dataRows.add(Map.copyOf(named));
        }
        return new Sheet(header, List.copyOf(dataRows));
    }

    /** A closeable wrapper so a stream reader can be released deterministically. */
    private record AutoCloseableReader(XMLStreamReader stream) implements AutoCloseable {

        @Override
        public void close() {
            try {
                stream.close();
            } catch (XMLStreamException ignored) {
                // A reader that cannot be closed has already produced everything
                // it will produce; there is nothing a caller could do about it.
            }
        }
    }

    /**
     * One submitted file, read into a header and its data rows.
     *
     * @param header the lower-cased column names the first row declared
     * @param rows each data row, keyed by column name
     */
    public record Sheet(List<String> header, List<Map<String, String>> rows) {
    }
}
