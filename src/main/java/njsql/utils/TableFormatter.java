package njsql.utils;

import java.util.ArrayList;
import java.util.List;

public class TableFormatter {

    public static String formatTable(List<String> headers, List<List<String>> rows) {
        List<Integer> columnWidths = new ArrayList<>();

        // Tính độ rộng lớn nhất cho mỗi cột
        for (int i = 0; i < headers.size(); i++) {
            int maxWidth = headers.get(i).length();
            for (List<String> row : rows) {
                if (i < row.size()) {
                    maxWidth = Math.max(maxWidth, row.get(i).length());
                }
            }
            columnWidths.add(maxWidth);
        }

        StringBuilder builder = new StringBuilder();
        String horizontalLine = buildHorizontalLine(columnWidths);

        // Vẽ dòng tiêu đề
        builder.append(horizontalLine);
        builder.append(buildRow(headers, columnWidths));
        builder.append(horizontalLine);

        // Vẽ các dòng dữ liệu
        for (List<String> row : rows) {
            builder.append(buildRow(row, columnWidths));
        }
        builder.append(horizontalLine);

        return builder.toString();
    }

    private static String buildHorizontalLine(List<Integer> columnWidths) {
        StringBuilder line = new StringBuilder("+");
        for (int width : columnWidths) {
            line.append("-".repeat(width + 2)).append("+");
        }
        line.append("\n");
        return line.toString();
    }
    public static String format(List<String[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return ">> No data to display.";
        }

        List<String> headers = List.of(rows.get(0));
        List<List<String>> dataRows = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) {
            dataRows.add(List.of(rows.get(i)));
        }

        return formatTable(headers, dataRows);
    }
    private static String buildRow(List<String> row, List<Integer> widths) {
        StringBuilder line = new StringBuilder("|");
        for (int i = 0; i < widths.size(); i++) {
            String cell = i < row.size() ? row.get(i) : "";
            line.append(" ").append(padRight(cell, widths.get(i))).append(" |");
        }
        line.append("\n");
        return line.toString();
    }

    private static String padRight(String text, int width) {
        return String.format("%-" + width + "s", text);
    }
}
