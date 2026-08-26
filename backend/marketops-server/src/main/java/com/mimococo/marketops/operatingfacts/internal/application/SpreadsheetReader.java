package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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
     * Read comma-separated values, honouring quoted fields.
     *
     * <p>A quoted field may contain a separator, a newline and a doubled quote,
     * which is what the format actually allows; a reader that split on commas
     * would silently corrupt exactly the rows that carry a product name with a
     * comma in it.
     */
    private static Sheet readSeparatedValues(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

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
                    }
                } else {
                    field.append(character);
                }
                continue;
            }
            switch (character) {
                case '"' -> quoted = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // A carriage return is part of a line ending, never data.
                }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(List.copyOf(row));
                    row.clear();
                    if (rows.size() > MAXIMUM_ROWS + 1) {
                        throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
                    }
                }
                default -> field.append(character);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(List.copyOf(row));
        }
        return toSheet(rows);
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
        long expanded = 0;
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                String name = entry.getName();
                boolean needed = name.equals("xl/workbook.xml")
                        || name.equals("xl/sharedStrings.xml")
                        || name.startsWith("xl/worksheets/");
                if (!needed) {
                    continue;
                }
                byte[] part = archive.readAllBytes();
                expanded += part.length;
                if (expanded > MAXIMUM_ENTRY_BYTES) {
                    throw OperationRejectedException.of(ErrorCode.IMPORT_TOO_LARGE);
                }
                parts.put(name, part);
            }
        } catch (IOException unreadable) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return parts;
    }

    /**
     * The first worksheet in the archive.
     *
     * <p>Sheets are numbered in the package, so the lowest-numbered part is the
     * first sheet. Reading only the first is a stated limit of this intake
     * rather than an accident: a file whose data is on a second sheet is
     * rejected with a reason instead of imported as empty.
     */
    private static byte[] firstWorksheet(Map<String, byte[]> parts) {
        return parts.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("xl/worksheets/sheet"))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static List<String> readSharedStrings(byte[] part) {
        List<String> strings = new ArrayList<>();
        if (part == null) {
            return strings;
        }
        try (AutoCloseableReader reader = openXml(part)) {
            StringBuilder current = null;
            while (reader.stream().hasNext()) {
                int event = reader.stream().next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("si".equals(reader.stream().getLocalName())) {
                        current = new StringBuilder();
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && current != null) {
                    current.append(reader.stream().getText());
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "si".equals(reader.stream().getLocalName()) && current != null) {
                    strings.add(current.toString());
                    current = null;
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
            String cellType = null;
            String cellReference = null;
            StringBuilder value = null;
            boolean collecting = false;

            while (reader.stream().hasNext()) {
                int event = reader.stream().next();
                XMLStreamReader stream = reader.stream();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (stream.getLocalName()) {
                        case "row" -> row = new ArrayList<>();
                        case "c" -> {
                            cellType = stream.getAttributeValue(null, "t");
                            cellReference = stream.getAttributeValue(null, "r");
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
                } else if (event == XMLStreamConstants.CHARACTERS && collecting && value != null) {
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
        if (!"s".equals(cellType)) {
            return raw;
        }
        try {
            int index = Integer.parseInt(raw.trim());
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
        } catch (NumberFormatException notAnIndex) {
            return "";
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
            return;
        }
        while (row.size() <= column) {
            row.add("");
        }
        row.set(column, value);
    }

    /** The zero-based column a cell reference such as {@code AB7} names. */
    private static int columnIndex(String reference) {
        if (reference == null || reference.isEmpty()) {
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
        List<Map<String, String>> dataRows = new ArrayList<>(nonEmpty.size() - 1);
        for (List<String> row : nonEmpty.subList(1, nonEmpty.size())) {
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
