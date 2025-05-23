package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;

public class SelectHandler {

    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    public static String handle(String sql, User user) throws Exception {
        String dbName = user.getCurrentDatabase();
        if (dbName == null || dbName.isEmpty()) {
            throw new IllegalArgumentException("No database selected. Please use `USE <dbname>` first.");
        }

        String rootDir = UserManager.getRootDirectory(user.getUsername());

        String sqlLower = sql.toLowerCase().trim();
        if (!sqlLower.contains("from")) {
            if (sqlLower.contains("form")) {
                throw new IllegalArgumentException("Syntax error: Typo 'form' instead of 'FROM'. Please correct the query.");
            }
            throw new IllegalArgumentException("Syntax error: Missing 'FROM' keyword in SELECT query.");
        }

        // Tạo các regex pattern riêng biệt
        Pattern selectPattern = Pattern.compile("SELECT\\s+(.+?)\\s+FROM", Pattern.CASE_INSENSITIVE);
        Pattern fromPattern = Pattern.compile("FROM\\s+(\\w+)(?:\\s+(\\w+))?", Pattern.CASE_INSENSITIVE);
        Pattern joinPattern = Pattern.compile("(LEFT|RIGHT|INNER)?\\s*JOIN\\s+(\\w+)\\s+(\\w+)\\s+ON\\s+(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)", Pattern.CASE_INSENSITIVE);
        Pattern wherePattern = Pattern.compile("WHERE\\s+(.+?)(?:\\s+(?:GROUP BY|ORDER BY)|$|;)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern groupPattern = Pattern.compile("GROUP\\s+BY\\s+((?:\\w+\\.\\w+|\\w+)(?:\\s*,\\s*(?:\\w+\\.\\w+|\\w+))*)", Pattern.CASE_INSENSITIVE);
        Pattern orderPattern = Pattern.compile("ORDER\\s+BY\\s+((?:\\w+\\.\\w+|\\w+))(?:\\s+(ASC|DESC))?", Pattern.CASE_INSENSITIVE);

        // Extract các thành phần của câu query
        Matcher selectMatcher = selectPattern.matcher(sql);
        String columnsPart = selectMatcher.find() ? selectMatcher.group(1).trim() : "*";

        Matcher fromMatcher = fromPattern.matcher(sql);
        if (!fromMatcher.find()) {
            throw new IllegalArgumentException("Invalid SELECT syntax: Missing table name after 'FROM'");
        }
        String mainTable = fromMatcher.group(1).trim();
        String mainAlias = fromMatcher.group(2) != null ? fromMatcher.group(2).trim() : mainTable;

        // Xử lý JOIN nếu có
        Matcher joinMatcher = joinPattern.matcher(sql);
        String joinType = null, joinTable = null, joinAlias = null, leftTable = null, leftColumn = null, rightTable = null, rightColumn = null;
        if (joinMatcher.find()) {
            joinType = joinMatcher.group(1) != null ? joinMatcher.group(1).toUpperCase() : "INNER";
            joinTable = joinMatcher.group(2);
            joinAlias = joinMatcher.group(3);
            leftTable = joinMatcher.group(4);
            leftColumn = joinMatcher.group(5);
            rightTable = joinMatcher.group(6);
            rightColumn = joinMatcher.group(7);
        }

        // Xử lý WHERE clause
        Matcher whereMatcher = wherePattern.matcher(sql);
        String whereClause = null;
        if (whereMatcher.find()) {
            whereClause = whereMatcher.group(1).trim();
        }

        // Xử lý GROUP BY và ORDER BY
        Matcher groupMatcher = groupPattern.matcher(sql);
        String groupByColumns = groupMatcher.find() ? groupMatcher.group(1) : null;

        Matcher orderMatcher = orderPattern.matcher(sql);
        String orderByColumn = null, orderByDirection = "ASC";
        if (orderMatcher.find()) {
            orderByColumn = orderMatcher.group(1);
            orderByDirection = orderMatcher.group(2) != null ? orderMatcher.group(2).toUpperCase() : "ASC";
        }

        // Đọc dữ liệu từ table chính
        String mainTablePath = rootDir + "/" + dbName + "/" + mainTable + ".nson";
        File mainTableFile = new File(mainTablePath);
        if (!mainTableFile.exists()) {
            throw new IllegalArgumentException("Table '" + mainTable + "' does not exist in database '" + dbName + "'.");
        }
        String mainFileContent = new String(Files.readAllBytes(mainTableFile.toPath()), StandardCharsets.UTF_8);
        NsonObject mainTableData;
        try {
            mainTableData = NsonObject.parse(mainFileContent);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON of table '" + mainTable + "': " + e.getMessage());
        }

        NsonObject mainMeta = mainTableData.getObject("_meta");
        if (mainMeta == null) {
            throw new IllegalArgumentException("Invalid table structure for '" + mainTable + "': Missing '_meta'.");
        }
        NsonObject mainTypes = mainTableData.getObject("_types");
        if (mainTypes == null) {
            throw new IllegalArgumentException("Invalid table structure for '" + mainTable + "': Missing '_types'.");
        }
        NsonArray mainData = mainTableData.getArray("data");
        if (mainData == null) {
            throw new IllegalArgumentException("Invalid table structure for '" + mainTable + "': Missing 'data'.");
        }
        NsonArray mainIndexCols = mainMeta.getArray("index");
        if (mainIndexCols == null) {
            mainIndexCols = new NsonArray();
        }

        IndexManager mainIndexManager = new IndexManager();
        mainIndexManager.loadIndexes(mainTablePath, mainData, mainIndexCols);

        NsonArray joinData = null;
        NsonObject joinTypes = null;
        IndexManager joinIndexManager = null;
        if (joinTable != null) {
            String joinTablePath = rootDir + "/" + dbName + "/" + joinTable + ".nson";
            File joinTableFile = new File(joinTablePath);
            if (!joinTableFile.exists()) {
                throw new IllegalArgumentException("Table '" + joinTable + "' does not exist in database '" + dbName + "'.");
            }
            String joinFileContent = new String(Files.readAllBytes(joinTableFile.toPath()), StandardCharsets.UTF_8);
            NsonObject joinTableData;
            try {
                joinTableData = NsonObject.parse(joinFileContent);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to parse JSON of table '" + joinTable + "': " + e.getMessage());
            }

            NsonObject joinMeta = joinTableData.getObject("_meta");
            if (joinMeta == null) {
                throw new IllegalArgumentException("Invalid table structure for '" + joinTable + "': Missing '_meta'.");
            }
            joinTypes = joinTableData.getObject("_types");
            if (joinTypes == null) {
                throw new IllegalArgumentException("Invalid table structure for '" + joinTable + "': Missing '_types'.");
            }
            joinData = joinTableData.getArray("data");
            if (joinData == null) {
                throw new IllegalArgumentException("Invalid table structure for '" + joinTable + "': Missing 'data'.");
            }
            NsonArray joinIndexCols = joinMeta.getArray("index");
            if (joinIndexCols == null) {
                joinIndexCols = new NsonArray();
            }
            joinIndexManager = new IndexManager();
            joinIndexManager.loadIndexes(joinTablePath, joinData, joinIndexCols);
        }

        List<String> columnNames = new ArrayList<>();
        Map<String, String> columnAliases = new HashMap<>();
        boolean hasAggregate = columnsPart.contains("COUNT(") || columnsPart.contains("SUM(");

        if (columnsPart.equals("*") && !hasAggregate) {
            List<String> sortedKeys = new ArrayList<>(mainTypes.keySet());
            for (String key : sortedKeys) {
                columnNames.add(mainAlias + "." + key);
                columnAliases.put(mainAlias + "." + key, key);
            }
            if (joinTable != null && joinTypes != null) {
                sortedKeys = new ArrayList<>(joinTypes.keySet());
                for (String key : sortedKeys) {
                    columnNames.add(joinAlias + "." + key);
                    columnAliases.put(joinAlias + "." + key, key);
                }
            }
        } else {
            String[] columnDefs = columnsPart.split("\\s*,\\s*");
            for (String colDef : columnDefs) {
                Pattern aliasPattern = Pattern.compile("(?:COUNT\\(\\*\\)|(?:COUNT|SUM)\\s*\\(\\s*(\\w+\\.\\w+|\\w+)\\s*\\)|(\\w+\\.\\w+|\\w+))\\s*(?:AS\\s+(\\w+))?", Pattern.CASE_INSENSITIVE);
                Matcher aliasMatcher = aliasPattern.matcher(colDef.trim());
                if (aliasMatcher.find()) {
                    String aggFunc = aliasMatcher.group(0).toUpperCase();
                    String colName = aliasMatcher.group(1) != null ? aliasMatcher.group(1) : aliasMatcher.group(2);
                    String alias = aliasMatcher.group(3);
                    if (colName != null && !colName.contains(".")) {
                        colName = mainAlias + "." + colName;
                    }
                    String key = colName != null ? colName : aggFunc;
                    columnNames.add(key);
                    columnAliases.put(key, alias != null ? alias : (colName != null ? colName.split("\\.")[1] : aggFunc));
                } else {
                    throw new IllegalArgumentException("Invalid column definition: '" + colDef + "'. Expected format: <alias>.<column> [AS <alias>], COUNT(*), COUNT(<table>.<column>), or SUM(<table>.<column>) [AS <alias>].");
                }
            }
        }

        List<String> groupByColumnList = new ArrayList<>();
        if (groupByColumns != null) {
            String[] groupCols = groupByColumns.split("\\s*,\\s*");
            for (String col : groupCols) {
                if (!col.contains(".")) {
                    col = mainAlias + "." + col;
                }
                groupByColumnList.add(col);
            }
        }

        final String effectiveOrderByColumn = (orderByColumn != null && !orderByColumn.contains(".")) ? mainAlias + "." + orderByColumn : orderByColumn;

        List<NsonObject> resultData = new ArrayList<>();
        if (joinData != null) {
            if (joinType.equals("LEFT")) {
                for (int i = 0; i < mainData.size(); i++) {
                    NsonObject mainRow = mainData.getObject(i);
                    boolean matched = false;
                    for (int j = 0; j < joinData.size(); j++) {
                        NsonObject joinRow = joinData.getObject(j);
                        Object leftValue = leftTable.equals(mainAlias) ? mainRow.get(leftColumn) : joinRow.get(leftColumn);
                        Object rightValue = rightTable.equals(mainAlias) ? mainRow.get(rightColumn) : joinRow.get(rightColumn);
                        if (leftValue != null && leftValue.equals(rightValue)) {
                            NsonObject mergedRow = mergeRows(mainRow, joinRow, mainAlias, joinAlias, mainTypes, joinTypes);
                            resultData.add(mergedRow);
                            matched = true;
                        }
                    }
                    if (!matched) {
                        NsonObject mergedRow = mergeRows(mainRow, null, mainAlias, joinAlias, mainTypes, joinTypes);
                        resultData.add(mergedRow);
                    }
                }
            } else if (joinType.equals("RIGHT")) {
                for (int j = 0; j < joinData.size(); j++) {
                    NsonObject joinRow = joinData.getObject(j);
                    boolean matched = false;
                    for (int i = 0; i < mainData.size(); i++) {
                        NsonObject mainRow = mainData.getObject(i);
                        Object leftValue = leftTable.equals(mainAlias) ? mainRow.get(leftColumn) : joinRow.get(leftColumn);
                        Object rightValue = rightTable.equals(mainAlias) ? mainRow.get(rightColumn) : joinRow.get(rightColumn);
                        if (leftValue != null && leftValue.equals(rightValue)) {
                            NsonObject mergedRow = mergeRows(mainRow, joinRow, mainAlias, joinAlias, mainTypes, joinTypes);
                            resultData.add(mergedRow);
                            matched = true;
                        }
                    }
                    if (!matched) {
                        NsonObject mergedRow = mergeRows(null, joinRow, mainAlias, joinAlias, mainTypes, joinTypes);
                        resultData.add(mergedRow);
                    }
                }
            } else { // INNER JOIN
                for (int i = 0; i < mainData.size(); i++) {
                    NsonObject mainRow = mainData.getObject(i);
                    for (int j = 0; j < joinData.size(); j++) {
                        NsonObject joinRow = joinData.getObject(j);
                        Object leftValue = leftTable.equals(mainAlias) ? mainRow.get(leftColumn) : joinRow.get(leftColumn);
                        Object rightValue = rightTable.equals(mainAlias) ? mainRow.get(rightColumn) : joinRow.get(rightColumn);
                        if (leftValue != null && leftValue.equals(rightValue)) {
                            NsonObject mergedRow = mergeRows(mainRow, joinRow, mainAlias, joinAlias, mainTypes, joinTypes);
                            resultData.add(mergedRow);
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < mainData.size(); i++) {
                NsonObject row = mainData.getObject(i);
                NsonObject wrapped = new NsonObject();
                for (String key : mainTypes.keySet()) {
                    wrapped.put(mainAlias + "." + key, row.get(key));
                }
                resultData.add(wrapped);
            }
        }

        List<NsonObject> filteredData = new ArrayList<>();
        for (NsonObject row : resultData) {
            if (whereClause == null || evaluateWhere(row, whereClause, mainTypes, joinTypes, mainAlias, joinAlias)) {
                filteredData.add(row);
            }
        }

        List<NsonObject> groupedData = filteredData;
        if (!groupByColumnList.isEmpty()) {
            Map<String, List<NsonObject>> groups = new HashMap<>();
            for (NsonObject row : filteredData) {
                StringBuilder keyBuilder = new StringBuilder();
                for (String col : groupByColumnList) {
                    keyBuilder.append(row.get(col) != null ? row.get(col).toString() : "NULL").append("|");
                }
                String key = keyBuilder.toString();
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
            groupedData = new ArrayList<>();
            for (List<NsonObject> group : groups.values()) {
                NsonObject aggRow = new NsonObject();
                for (String col : columnNames) {
                    if (col.equals("COUNT(*)")) {
                        aggRow.put(columnAliases.get(col), group.size());
                    } else if (col.startsWith("SUM(")) {
                        String colName = col.substring(4, col.length() - 1);
                        double sum = group.stream()
                                .mapToDouble(r -> {
                                    Object val = r.get(colName);
                                    return val != null ? Double.parseDouble(val.toString()) : 0.0;
                                })
                                .sum();
                        aggRow.put(columnAliases.get(col), sum);
                    } else {
                        aggRow.put(col, group.get(0).get(col));
                    }
                }
                groupedData.add(aggRow);
            }
        }

        if (effectiveOrderByColumn != null) {
            final String sortDirection = orderByDirection;
            Comparator<NsonObject> comparator = (r1, r2) -> {
                Object v1 = r1.get(effectiveOrderByColumn);
                Object v2 = r2.get(effectiveOrderByColumn);
                if (v1 == null) return v2 == null ? 0 : -1;
                if (v2 == null) return 1;
                try {
                    double d1 = Double.parseDouble(v1.toString());
                    double d2 = Double.parseDouble(v2.toString());
                    return sortDirection.equals("ASC") ? Double.compare(d1, d2) : Double.compare(d2, d1);
                } catch (NumberFormatException e) {
                    return sortDirection.equals("ASC") ? v1.toString().compareTo(v2.toString()) : v2.toString().compareTo(v1.toString());
                }
            };
            groupedData.sort(comparator);
        }

        StringBuilder result = new StringBuilder();
        result.append("+");
        for (String col : columnNames) {
            result.append("-".repeat(20)).append("+");
        }
        result.append("\n|");
        for (String col : columnNames) {
            String alias = columnAliases.get(col);
            result.append(String.format(" %-18s |", alias));
        }
        result.append("\n+");
        for (String col : columnNames) {
            result.append("-".repeat(20)).append("+");
        }
        result.append("\n");

        for (NsonObject row : groupedData) {
            result.append("|");
            for (String col : columnNames) {
                Object val = row.get(col);
                String valStr = (val == null) ? "NULL" : val.toString();
                result.append(String.format(" %-18s |", valStr.length() > 18 ? valStr.substring(0, 15) + "..." : valStr));
            }
            result.append("\n");
        }

        result.append("+");
        for (int i = 0; i < columnNames.size(); i++) {
            result.append("-".repeat(20)).append("+");
        }
        result.append("\n");

        return result.toString();
    }

    private static NsonObject mergeRows(NsonObject mainRow, NsonObject joinRow, String mainTable, String joinTable, NsonObject mainTypes, NsonObject joinTypes) {
        NsonObject mergedRow = new NsonObject();
        if (mainRow != null) {
            for (String key : mainTypes.keySet()) {
                mergedRow.put(mainTable + "." + key, mainRow.get(key));
            }
        }
        if (joinRow != null && joinTypes != null) {
            for (String key : joinTypes.keySet()) {
                mergedRow.put(joinTable + "." + key, joinRow.get(key));
            }
        }
        return mergedRow;
    }

    private static boolean evaluateWhere(NsonObject row, String whereClause, NsonObject mainTypes, NsonObject joinTypes, String mainTable, String joinTable) throws Exception {
        Pattern wherePattern = Pattern.compile(
                "([\\w.]+)\\s*([=><!]=?|<>|LIKE)\\s*('[^']*'|\\d+(\\.\\d+)?)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = wherePattern.matcher(whereClause.trim());

        if (!matcher.find()) {
            throw new IllegalArgumentException("Unsupported WHERE clause: '" + whereClause +
                    "'. Expected format: column [=|>|<|>=|<=|!=|<>|LIKE] 'value'|number");
        }

        String column = matcher.group(1).trim();
        String operator = matcher.group(2).trim().toUpperCase();
        String value = matcher.group(3).replaceAll("^'|'$", "");

        String[] parts = column.split("\\.");
        String tableName = parts.length > 1 ? parts[0] : mainTable;
        String colName = parts.length > 1 ? parts[1] : parts[0];

        NsonObject types = tableName.equals(mainTable) ? mainTypes : joinTypes;
        if (types == null || !types.containsKey(colName)) {
            throw new IllegalArgumentException("Column '" + colName + "' does not exist in table '" + tableName + "'");
        }

        String fullColName = parts.length > 1 ? column : tableName + "." + colName;
        Object rowValue = row.get(fullColName);
        if (rowValue == null) return false;

        String type = types.getString(colName);

        if (type.equals("int") || type.equals("float") || type.equals("double")) {
            try {
                double rowNum = Double.parseDouble(rowValue.toString());
                double valNum = Double.parseDouble(value);
                return switch (operator) {
                    case "=" -> rowNum == valNum;
                    case ">" -> rowNum > valNum;
                    case "<" -> rowNum < valNum;
                    case ">=" -> rowNum >= valNum;
                    case "<=" -> rowNum <= valNum;
                    case "!=", "<>" -> rowNum != valNum;
                    default -> false;
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number format in WHERE clause for column '" + column + "'");
            }
        } else if (operator.equals("LIKE")) {
            String regex = "^" + value.replace("%", ".*").replace("_", ".") + "$";
            return rowValue.toString().matches(regex);
        } else {
            boolean equals = rowValue.toString().equals(value);
            return switch (operator) {
                case "=" -> equals;
                case "!=", "<>" -> !equals;
                default -> false;
            };
        }
    }
}