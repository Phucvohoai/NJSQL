package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust SELECT statement handler that tolerates arbitrary whitespace,
 * line breaks, and optional trailing semicolons.
 */
public class SelectHandler {

    private static final String YELLOW = "\u001B[33m";
    private static final String RESET  = "\u001B[0m";

    /**
     * Executes a SELECT query and returns a formatted result table.
     */
    public static String handle(String sql, User user) throws Exception {
        try {
            String dbName = user.getCurrentDatabase();
            if (dbName == null || dbName.isBlank()) {
                throw new IllegalArgumentException("No database selected. Use `USE <dbname>` first.");
            }

            String rootDir = UserManager.getRootDirectory(user.getUsername());

            // Normalize whitespace – fixes "No match found" issues
            String normalized = sql.replaceAll("\\s+", " ").trim();
            if (normalized.endsWith(";")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }

            // Friendly typo detection
            if (normalized.toLowerCase().contains(" form ")) {
                throw new IllegalArgumentException("Syntax error: Did you mean 'FROM' instead of 'form'?");
            }

            // Parse SELECT ... FROM reliably
            Matcher selectMatcher = Pattern.compile(
                            "^SELECT\\s+(.*?)\\s+FROM\\s+(.*)$",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(normalized);

            if (!selectMatcher.find()) {
                throw new IllegalArgumentException("Syntax error: Invalid or missing 'FROM' clause.");
            }

            String columnsPart = selectMatcher.group(1).trim();
            if (columnsPart.isEmpty()) columnsPart = "*";

            String remainder = selectMatcher.group(2).trim();

            // Table name and optional alias
            Matcher fromMatcher = Pattern.compile("^(\\w+)\\s*(?:(\\w+)\\s*)?(.*)$", Pattern.CASE_INSENSITIVE)
                    .matcher(remainder);
            if (!fromMatcher.find()) {
                throw new IllegalArgumentException("Missing table name after FROM.");
            }

            String mainTable = fromMatcher.group(1);
            String mainAlias = fromMatcher.group(2) != null ? fromMatcher.group(2) : mainTable;
            remainder = fromMatcher.group(3) != null ? fromMatcher.group(3).trim() : "";

            // Optional JOIN
            String joinType = null, joinTable = null, joinAlias = null;
            String leftTable = null, leftColumn = null, rightTable = null, rightColumn = null;

            Matcher joinMatcher = Pattern.compile(
                            "^(LEFT|RIGHT|INNER)?\\s*JOIN\\s+(\\w+)\\s+(\\w+)\\s+ON\\s+([\\w.]+)\\s*=\\s+([\\w.]+)(.*)$",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(remainder);

            if (joinMatcher.find()) {
                joinType    = joinMatcher.group(1) != null ? joinMatcher.group(1).toUpperCase() : "INNER";
                joinTable   = joinMatcher.group(2);
                joinAlias   = joinMatcher.group(3);
                String[] l  = joinMatcher.group(4).split("\\.");
                String[] r  = joinMatcher.group(5).split("\\.");
                leftTable   = l.length > 1 ? l[0] : mainTable;
                leftColumn  = l.length > 1 ? l[1] : l[0];
                rightTable  = r.length > 1 ? r[0] : joinTable;
                rightColumn = r.length > 1 ? r[1] : r[0];
                remainder   = joinMatcher.group(6) != null ? joinMatcher.group(6).trim() : "";
            }

            // WHERE / GROUP BY / ORDER BY
            String whereClause = null, groupByColumns = null, orderByColumn = null;
            String orderDirection = "ASC";

            Matcher whereM = Pattern.compile("^WHERE\\s+(.*?)(?=\\s+GROUP\\s+BY|\\s+ORDER\\s+BY|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(remainder);
            if (whereM.find()) {
                whereClause = whereM.group(1).trim();
                remainder = remainder.substring(whereM.end()).trim();
            }

            Matcher groupM = Pattern.compile("^GROUP\\s+BY\\s+(.*?)(?=\\s+ORDER\\s+BY|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(remainder);
            if (groupM.find()) {
                groupByColumns = groupM.group(1).trim();
                remainder = remainder.substring(groupM.end()).trim();
            }

            Matcher orderM = Pattern.compile("^ORDER\\s+BY\\s+([\\w.]+)(?:\\s+(ASC|DESC))?", Pattern.CASE_INSENSITIVE)
                    .matcher(remainder);
            if (orderM.find()) {
                orderByColumn = orderM.group(1).trim();
                orderDirection = orderM.group(2) != null ? orderM.group(2).toUpperCase() : "ASC";
            }

            // Load main table
            File mainFile = new File(rootDir + "/" + dbName + "/" + mainTable + ".nson");
            if (!mainFile.exists()) {
                throw new IllegalArgumentException("Table '" + mainTable + "' does not exist in database '" + dbName + "'.");
            }

            NsonObject mainTableObj = NsonObject.parse(Files.readString(mainFile.toPath(), StandardCharsets.UTF_8));
            NsonObject mainTypes    = mainTableObj.getObject("_types");
            NsonArray  mainRows     = mainTableObj.getArray("data");

            // Load join table (if any)
            NsonObject joinTypes = null;
            NsonArray  joinRows  = null;
            if (joinTable != null) {
                File joinFile = new File(rootDir + "/" + dbName + "/" + joinTable + ".nson");
                if (!joinFile.exists()) {
                    throw new IllegalArgumentException("Join table '" + joinTable + "' does not exist.");
                }
                NsonObject joinTableObj = NsonObject.parse(Files.readString(joinFile.toPath(), StandardCharsets.UTF_8));
                joinTypes = joinTableObj.getObject("_types");
                joinRows  = joinTableObj.getArray("data");
            }

            // Perform JOIN
            NsonArray joined = new NsonArray();

            if (joinTable != null) {
                final String finalRightColumn = rightColumn;
                Map<Object, List<NsonObject>> rightIndex = new HashMap<>();
                for (int i = 0; i < joinRows.size(); i++) {
                    NsonObject r = joinRows.getObject(i);
                    Object key = r.get(finalRightColumn);
                    rightIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                }

                final String finalLeftColumn = leftColumn;
                for (int i = 0; i < mainRows.size(); i++) {
                    NsonObject mainRow = mainRows.getObject(i);
                    Object key = mainRow.get(finalLeftColumn);
                    List<NsonObject> matches = rightIndex.getOrDefault(key, Collections.emptyList());

                    if (matches.isEmpty() && "LEFT".equals(joinType)) {
                        joined.add(mergeRows(mainRow, null, mainTable, joinTable, mainTypes, joinTypes));
                    } else {
                        for (NsonObject jr : matches) {
                            joined.add(mergeRows(mainRow, jr, mainTable, joinTable, mainTypes, joinTypes));
                        }
                    }
                }
            } else {
                // No JOIN – prefix columns with table name
                for (int i = 0; i < mainRows.size(); i++) {
                    NsonObject src = mainRows.getObject(i);
                    NsonObject dst = new NsonObject();
                    for (String c : mainTypes.keySet()) {
                        dst.put(mainTable + "." + c, src.get(c));
                    }
                    joined.add(dst);
                }
            }

            // Apply WHERE
            final String finalWhereClause = whereClause;
            NsonArray filtered = new NsonArray();
            for (int i = 0; i < joined.size(); i++) {
                NsonObject row = joined.getObject(i);
                if (finalWhereClause == null || evaluateWhere(row, finalWhereClause, mainTypes, joinTypes, mainTable, joinTable)) {
                    filtered.add(row);
                }
            }

            // Resolve selected columns and aliases
            List<String> selectedColumns = new ArrayList<>();
            Map<String, String> aliases = new HashMap<>();

            if ("*".equals(columnsPart)) {
                Set<String> all = new LinkedHashSet<>(mainTypes.keySet());
                if (joinTypes != null) all.addAll(joinTypes.keySet());
                for (String c : all) {
                    String full = mainTypes.containsKey(c) ? mainTable + "." + c : joinTable + "." + c;
                    selectedColumns.add(full);
                    aliases.put(full, c);
                }
            } else {
                for (String part : columnsPart.split("\\s*,\\s*")) {
                    String[] p = part.split("\\s+AS\\s+", 2);
                    String col   = p[0].trim();
                    String alias = p.length > 1 ? p[1].trim() : col;
                    selectedColumns.add(col);
                    aliases.put(col, alias);
                }
            }

            // Projection
            NsonArray projected = new NsonArray();
            for (int i = 0; i < filtered.size(); i++) {
                NsonObject src = filtered.getObject(i);
                NsonObject dst = new NsonObject();
                for (String col : selectedColumns) {
                    Object value = src.get(col);
                    String alias = aliases.getOrDefault(col,
                            col.contains(".") ? col.substring(col.lastIndexOf('.') + 1) : col);
                    dst.put(alias, value);
                }
                projected.add(dst);
            }

            // GROUP BY
            NsonArray resultSet = projected;
            final String finalOrderByColumn = orderByColumn != null ? orderByColumn : (groupByColumns != null ? groupByColumns.split("\\s*,\\s*")[0] : null);

            if (groupByColumns != null) {
                List<String> groupCols = Arrays.asList(groupByColumns.split("\\s*,\\s*"));
                Map<String, List<NsonObject>> groups = new HashMap<>();

                for (int i = 0; i < projected.size(); i++) {
                    NsonObject row = projected.getObject(i);
                    StringBuilder key = new StringBuilder();
                    for (String gc : groupCols) {
                        Object v = row.get(gc);
                        key.append(v == null ? "NULL" : v).append("|");
                    }
                    groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(row);
                }

                resultSet = new NsonArray();
                for (List<NsonObject> g : groups.values()) {
                    NsonObject agg = new NsonObject();
                    for (String col : selectedColumns) {
                        String alias = aliases.getOrDefault(col,
                                col.contains(".") ? col.substring(col.lastIndexOf('.') + 1) : col);

                        if (col.toUpperCase().startsWith("COUNT(") ||
                                col.toUpperCase().startsWith("SUM(") ||
                                col.toUpperCase().startsWith("AVG(")) {

                            String inner = col.substring(col.indexOf('(') + 1, col.indexOf(')')).trim();
                            final String finalInner = inner;
                            double sum = g.stream()
                                    .mapToDouble(r -> {
                                        Object v = r.get(finalInner);
                                        return v == null ? 0.0 : Double.parseDouble(v.toString());
                                    })
                                    .sum();

                            if (col.toUpperCase().startsWith("COUNT(")) {
                                agg.put(alias, g.size());
                            } else if (col.toUpperCase().startsWith("SUM(")) {
                                agg.put(alias, sum);
                            } else {
                                agg.put(alias, g.isEmpty() ? 0.0 : sum / g.size());
                            }
                        } else {
                            agg.put(alias, g.get(0).get(alias));
                        }
                    }
                    resultSet.add(agg);
                }
            }

            // ORDER BY
            if (finalOrderByColumn != null) {
                final String direction = orderDirection;
                Comparator<NsonObject> comp = (a, b) -> {
                    Object va = a.get(finalOrderByColumn);
                    Object vb = b.get(finalOrderByColumn);

                    if (va == null && vb == null) return 0;
                    if (va == null) return "ASC".equals(direction) ? -1 : 1;
                    if (vb == null) return "ASC".equals(direction) ? 1 : -1;

                    try {
                        double da = Double.parseDouble(va.toString());
                        double db = Double.parseDouble(vb.toString());
                        return "ASC".equals(direction) ? Double.compare(da, db) : Double.compare(db, da);
                    } catch (NumberFormatException e) {
                        String sa = va.toString();
                        String sb = vb.toString();
                        return "ASC".equals(direction) ? sa.compareTo(sb) : sb.compareTo(sa);
                    }
                };

                List<NsonObject> sorted = new ArrayList<>();
                for (int i = 0; i < resultSet.size(); i++) sorted.add(resultSet.getObject(i));
                sorted.sort(comp);

                resultSet = new NsonArray();
                resultSet.addAll(sorted);
            }

            // Render table
            StringBuilder output = new StringBuilder();
            if (selectedColumns.isEmpty()) {
                output.append("No columns selected.\n");
                return output.toString();
            }

            int width = 20;
            output.append("+");
            for (int i = 0; i < selectedColumns.size(); i++) output.append("-".repeat(width)).append("+");
            output.append("\n|");

            for (String col : selectedColumns) {
                String alias = aliases.getOrDefault(col,
                        col.contains(".") ? col.substring(col.lastIndexOf('.') + 1) : col);
                String display = alias.length() > 18 ? alias.substring(0, 15) + "..." : alias;
                output.append(String.format(" %-18s |", display));
            }
            output.append("\n+");
            for (int i = 0; i < selectedColumns.size(); i++) output.append("-".repeat(width)).append("+");
            output.append("\n");

            for (int i = 0; i < resultSet.size(); i++) {
                NsonObject row = resultSet.getObject(i);
                output.append("|");
                for (String col : selectedColumns) {
                    String alias = aliases.getOrDefault(col,
                            col.contains(".") ? col.substring(col.lastIndexOf('.') + 1) : col);
                    Object v = row.get(alias);
                    String s = v == null ? "NULL" : v.toString();
                    String display = s.length() > 18 ? s.substring(0, 15) + "..." : s;
                    output.append(String.format(" %-18s |", display));
                }
                output.append("\n");
            }

            output.append("+");
            for (int i = 0; i < selectedColumns.size(); i++) output.append("-".repeat(width)).append("+");
            output.append("\n");

            if (resultSet.isEmpty()) {
                output.append("No data found.\n");
            } else {
                output.append(resultSet.size()).append(" row(s) returned.\n");
            }

            return output.toString();

        } catch (Exception e) {
            System.err.println(YELLOW + ">> DEBUG: Exception in SelectHandler.handle()" + RESET);
            System.err.println(YELLOW + ">> SQL: " + sql + RESET);
            e.printStackTrace();
            throw e;
        }
    }

    /** Merges main and join rows, prefixing columns with table names. */
    private static NsonObject mergeRows(NsonObject mainRow, NsonObject joinRow,
                                        String mainTable, String joinTable,
                                        NsonObject mainTypes, NsonObject joinTypes) {
        NsonObject merged = new NsonObject();
        mainTypes.keySet().forEach(k -> merged.put(mainTable + "." + k, mainRow.get(k)));
        if (joinRow != null && joinTypes != null) {
            joinTypes.keySet().forEach(k -> merged.put(joinTable + "." + k, joinRow.get(k)));
        }
        return merged;
    }

    /** Evaluates simple WHERE conditions. */
    private static boolean evaluateWhere(NsonObject row, String clause,
                                         NsonObject mainTypes, NsonObject joinTypes,
                                         String mainTable, String joinTable) throws Exception {
        if (clause == null || clause.isBlank()) return true;

        Matcher m = Pattern.compile("([\\w.]+)\\s*(=|!=|<>|<|>|<=|>=|LIKE)\\s*('[^']*'|\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE)
                .matcher(clause.trim());

        if (!m.find()) {
            throw new IllegalArgumentException("Unsupported WHERE clause format: " + clause);
        }

        String col = m.group(1).trim();
        String op  = m.group(2).toUpperCase().replace("<>", "!=");
        String val = m.group(3).replaceAll("^'|'$", "");

        String[] parts = col.split("\\.");
        String tbl   = parts.length > 1 ? parts[0] : mainTable;
        String field = parts.length > 1 ? parts[1] : parts[0];

        NsonObject types = tbl.equals(mainTable) ? mainTypes : joinTypes;
        if (types == null || !types.containsKey(field)) {
            throw new IllegalArgumentException("Column not found: " + col);
        }

        Object rowVal = row.get(tbl + "." + field);
        if (rowVal == null) return false;

        String type = types.getString(field);
        if (type.matches("int|float|double")) {
            double a = Double.parseDouble(rowVal.toString());
            double b = Double.parseDouble(val);
            return switch (op) {
                case "="  -> a == b;
                case "!=" -> a != b;
                case ">"  -> a > b;
                case "<"  -> a < b;
                case ">=" -> a >= b;
                case "<=" -> a <= b;
                default   -> false;
            };
        }

        if ("LIKE".equals(op)) {
            String regex = "^" + val.replace("%", ".*").replace("_", ".") + "$";
            return rowVal.toString().matches(regex);
        }

        boolean eq = rowVal.toString().equals(val);
        return "=".equals(op) ? eq : !eq;
    }

    /** API version – returns structured JSON. */
    public static NsonObject handleForAPI(String sql, User user) {
        NsonObject response = new NsonObject();
        try {
            String formatted = handle(sql, user);
            NsonArray data = new NsonArray();

            String[] lines = formatted.split("\n");
            boolean inData = false;
            for (String line : lines) {
                if (line.startsWith("|") && line.contains(" | ")) inData = true;
                else if (inData && line.startsWith("+")) break;
                else if (inData && line.startsWith("|")) {
                    String[] cells = line.split("\\|");
                    NsonObject row = new NsonObject();
                    for (int i = 1; i < cells.length - 1; i++) {
                        String v = cells[i].trim();
                        row.put("col" + i, "NULL".equals(v) ? null : v);
                    }
                    data.add(row);
                }
            }
            response.put("data", data);
            response.put("success", true);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            response.put("success", false);
        }
        return response;
    }
}