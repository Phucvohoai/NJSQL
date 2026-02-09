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

public class SelectHandler {

    // --- 1. CLI HANDLER ---
    public static String handle(String sql, User user) throws Exception {
        NsonObject result = handleForAPI(sql, user);
        if (result.containsKey("error")) return "ERROR: " + result.getString("error");

        NsonArray data = result.getArray("data");
        if (data == null || data.isEmpty()) return "No data found.";

        StringBuilder output = new StringBuilder();
        Set<String> columns = new LinkedHashSet<>(data.getObject(0).keySet());
        List<String> colList = new ArrayList<>(columns);
        Map<String, Integer> colWidths = new HashMap<>();
        
        for (String col : colList) colWidths.put(col, col.length());
        for (Object rowObj : data) {
            NsonObject row = (NsonObject) rowObj;
            for (String col : colList) {
                String val = row.get(col) != null ? row.get(col).toString() : "NULL";
                colWidths.put(col, Math.max(colWidths.get(col), val.length()));
            }
        }
        
        String separator = "+";
        for (String col : colList) separator += "-".repeat(colWidths.get(col) + 2) + "+";
        
        output.append(separator).append("\n|");
        for (String col : colList) output.append(String.format(" %-" + colWidths.get(col) + "s |", col));
        output.append("\n").append(separator).append("\n");
        
        for (Object rowObj : data) {
            NsonObject row = (NsonObject) rowObj;
            output.append("|");
            for (String col : colList) {
                String val = row.get(col) != null ? row.get(col).toString() : "NULL";
                output.append(String.format(" %-" + colWidths.get(col) + "s |", val));
            }
            output.append("\n");
        }
        output.append(separator).append("\n");
        output.append(data.size() + " row(s) returned.");
        return output.toString();
    }

    // --- 2. API HANDLER ---
    public static NsonObject handleForAPI(String sql, User user) {
        NsonObject response = new NsonObject();
        try {
            NsonArray data = executeQuery(sql, user);
            return response.put("status", "success").put("data", data);
        } catch (Exception e) {
            e.printStackTrace();
            return response.put("error", e.getMessage());
        }
    }

    // --- 3. CORE LOGIC (FULL: DISTINCT -> WHERE -> GROUP -> ORDER -> LIMIT) ---
    private static NsonArray executeQuery(String sql, User user) throws Exception {
        String dbName = user.getCurrentDatabase();
        if (dbName == null || dbName.isBlank()) throw new IllegalArgumentException("No database selected.");
        String rootDir = UserManager.getRootDirectory(user.getUsername());

        String normalized = sql.replaceAll("\\s+", " ").trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1).trim();

        Matcher selectMatcher = Pattern.compile("^SELECT\\s+(.*?)\\s+FROM\\s+(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(normalized);
        if (!selectMatcher.find()) throw new IllegalArgumentException("Invalid SELECT syntax.");

        String columnsPart = selectMatcher.group(1).trim();
        String remainder = selectMatcher.group(2).trim();

        // [NEW] BƯỚC 0: CHECK DISTINCT
        // Nếu thấy chữ DISTINCT ở đầu -> Bật cờ hiệu và cắt bỏ chữ đó đi để không bị lỗi tên cột
        boolean isDistinct = false;
        if (columnsPart.toUpperCase().startsWith("DISTINCT ")) {
            isDistinct = true;
            columnsPart = columnsPart.substring(9).trim(); // Cắt bỏ "DISTINCT " (9 ký tự)
        }

        Matcher fromMatcher = Pattern.compile("^(\\w+)(?:\\s+(?:AS\\s+)?(?!(?:WHERE|JOIN|GROUP|ORDER|LIMIT)\\b)(\\w+))?\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(remainder);
        if (!fromMatcher.find()) throw new IllegalArgumentException("Missing table name.");

        String mainTable = fromMatcher.group(1);
        remainder = fromMatcher.group(3) != null ? fromMatcher.group(3).trim() : "";

        // Parse WHERE
        String whereClause = null;
        Matcher whereM = Pattern.compile("^WHERE\\s+(.*?)(?=\\s+GROUP\\s+BY|\\s+ORDER\\s+BY|\\s+LIMIT|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(remainder);
        if (whereM.find()) {
            whereClause = whereM.group(1).trim();
            remainder = remainder.substring(whereM.end()).trim();
        }

        // Parse GROUP BY
        String groupByColumn = null;
        Matcher groupM = Pattern.compile("^GROUP\\s+BY\\s+([\\w.]+)(?=\\s+ORDER\\s+BY|\\s+LIMIT|$)", Pattern.CASE_INSENSITIVE).matcher(remainder);
        if (groupM.find()) {
            groupByColumn = groupM.group(1).trim();
            remainder = remainder.substring(groupM.end()).trim();
        }

        // Parse ORDER BY
        Matcher orderM = Pattern.compile("^ORDER\\s+BY\\s+([\\w.]+)(?:\\s+(ASC|DESC))?", Pattern.CASE_INSENSITIVE).matcher(remainder);
        String orderByColumn = null;
        String orderDirection = "ASC";
        if (orderM.find()) {
            orderByColumn = orderM.group(1).trim();
            orderDirection = orderM.group(2) != null ? orderM.group(2).toUpperCase() : "ASC";
            remainder = remainder.substring(orderM.end()).trim(); // Cập nhật remainder để tìm LIMIT sau ORDER
        }

        // Parse LIMIT & OFFSET
        int limit = -1;
        int offset = 0;
        Matcher limitM = Pattern.compile("^LIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?", Pattern.CASE_INSENSITIVE).matcher(remainder);
        if (limitM.find()) {
            limit = Integer.parseInt(limitM.group(1));
            if (limitM.group(2) != null) {
                offset = Integer.parseInt(limitM.group(2));
            }
        }

        // Load Data
        NsonObject mainTableObj = loadTableData(user, dbName, mainTable, rootDir);
        NsonArray mainRows = mainTableObj.getArray("data");

        // 1. FILTER (WHERE)
        List<NsonObject> filteredRows = new ArrayList<>();
        for (int i = 0; i < mainRows.size(); i++) {
            NsonObject row = mainRows.getObject(i);
            if (evaluateExpression(row, whereClause)) {
                filteredRows.add(row);
            }
        }

        // 2. GROUPING
        Map<String, List<NsonObject>> groups = new LinkedHashMap<>();
        if (groupByColumn != null) {
            for (NsonObject row : filteredRows) {
                Object keyObj = getRowValue(row, groupByColumn);
                String key = keyObj != null ? keyObj.toString() : "NULL";
                if (groupByColumn.equals("created_at") && key.length() >= 10) {
                    key = key.substring(0, 10);
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        } else {
            boolean hasAggregate = columnsPart.toUpperCase().matches(".*(SUM|COUNT|AVG|MAX|MIN)\\(.*");
            if (hasAggregate) {
                groups.put("ALL", filteredRows);
            } else {
                for(int i=0; i<filteredRows.size(); i++) groups.put(String.valueOf(i), Collections.singletonList(filteredRows.get(i)));
            }
        }

        // 3. PROJECTION
        NsonArray projected = new NsonArray();
        boolean selectAll = columnsPart.equals("*");
        String[] reqCols = selectAll ? null : splitColumns(columnsPart);

        for (Map.Entry<String, List<NsonObject>> entry : groups.entrySet()) {
            List<NsonObject> groupRows = entry.getValue();
            if (groupRows.isEmpty()) continue;
            
            NsonObject representative = groupRows.get(0);
            NsonObject outRow = new NsonObject();

            if (selectAll) {
                outRow = representative; 
            } else {
                for (String colDef : reqCols) {
                    colDef = colDef.trim();
                    Matcher aggM = Pattern.compile("(SUM|COUNT|AVG|MAX|MIN)\\((.*?)\\)(?:\\s+AS\\s+(\\w+))?", Pattern.CASE_INSENSITIVE).matcher(colDef);
                    
                    if (aggM.find()) {
                        String func = aggM.group(1).toUpperCase();
                        String targetCol = aggM.group(2).trim();
                        String alias = aggM.group(3) != null ? aggM.group(3) : (func + "(" + targetCol + ")");
                        double val = calculateAggregate(func, targetCol, groupRows);
                        if (func.equals("COUNT")) outRow.put(alias, (int)val);
                        else outRow.put(alias, val);
                    } else {
                        String[] parts = colDef.split("(?i)\\s+AS\\s+");
                        String colName = parts[0].trim();
                        String alias = parts.length > 1 ? parts[1].trim() : colName;
                        if (groupByColumn != null && colName.equals(groupByColumn) && groupByColumn.equals("created_at")) {
                             outRow.put(alias, entry.getKey());
                        } else {
                             outRow.put(alias, getRowValue(representative, colName));
                        }
                    }
                }
            }
            projected.add(outRow);
        }

        // [NEW] BƯỚC 3.5: XỬ LÝ DISTINCT (LOẠI BỎ TRÙNG LẶP)
        // Sau khi đã chọn cột xong xuôi, nếu có cờ isDistinct thì lọc lại
        if (isDistinct) {
            NsonArray distinctList = new NsonArray();
            Set<String> seenRows = new HashSet<>();
            
            for (Object obj : projected) {
                NsonObject row = (NsonObject) obj;
                // Dùng toString() của NsonObject để tạo "chữ ký" duy nhất cho dòng đó
                // Nếu dòng này chưa từng thấy -> Thêm vào list
                if (seenRows.add(row.toString())) {
                    distinctList.add(row);
                }
            }
            projected = distinctList;
        }

        // 4. SORTING
        if (orderByColumn != null) {
            final String fCol = orderByColumn;
            final int dir = orderDirection.equals("ASC") ? 1 : -1;
            List<Object> list = new ArrayList<>();
            for(Object o : projected) list.add(o);
            list.sort((a, b) -> {
                NsonObject o1 = (NsonObject) a;
                NsonObject o2 = (NsonObject) b;
                Object v1 = o1.get(fCol);
                Object v2 = o2.get(fCol);
                if (v1 == null && v2 == null) return 0;
                if (v1 == null) return -1 * dir;
                if (v2 == null) return 1 * dir;
                if (isNumeric(v1.toString()) && isNumeric(v2.toString())) {
                    double d1 = Double.parseDouble(v1.toString());
                    double d2 = Double.parseDouble(v2.toString());
                    return Double.compare(d1, d2) * dir;
                }
                return v1.toString().compareTo(v2.toString()) * dir;
            });
            projected = new NsonArray();
            for(Object o : list) projected.add(o);
        }

        // 5. LIMIT & OFFSET
        if (limit != -1) {
            NsonArray limited = new NsonArray();
            int end = Math.min(offset + limit, projected.size());
            for (int i = offset; i < end; i++) {
                if (i >= 0 && i < projected.size()) {
                    limited.add(projected.get(i));
                }
            }
            return limited;
        }

        return projected;
    }

    // --- HELPER METHODS ---
    private static double calculateAggregate(String func, String col, List<NsonObject> rows) {
        if (func.equals("COUNT")) return rows.size();
        double sum = 0; double min = Double.MAX_VALUE; double max = Double.MIN_VALUE; int count = 0;
        for (NsonObject row : rows) {
            Object valObj = getRowValue(row, col);
            if (valObj != null && isNumeric(valObj.toString())) {
                double val = Double.parseDouble(valObj.toString());
                sum += val; if (val < min) min = val; if (val > max) max = val; count++;
            }
        }
        if (count == 0) return 0;
        return switch (func) { case "SUM" -> sum; case "AVG" -> sum / count; case "MAX" -> max; case "MIN" -> min; default -> 0; };
    }

    private static String[] splitColumns(String cols) {
        List<String> result = new ArrayList<>(); int bal = 0; StringBuilder cur = new StringBuilder();
        for (char c : cols.toCharArray()) {
            if (c == '(') bal++; if (c == ')') bal--;
            if (c == ',' && bal == 0) { result.add(cur.toString().trim()); cur.setLength(0); } else cur.append(c);
        }
        result.add(cur.toString().trim()); return result.toArray(new String[0]);
    }

    private static boolean evaluateExpression(NsonObject row, String expr) {
        if (expr == null || expr.isBlank()) return true; expr = expr.trim();
        while (expr.startsWith("(") && expr.endsWith(")")) {
             int bal = 0; boolean wrap = true;
             for(int i=0; i<expr.length()-1; i++) { if(expr.charAt(i)=='(') bal++; else if(expr.charAt(i)==')') bal--; if(bal==0) { wrap=false; break; } }
             if(wrap) expr = expr.substring(1, expr.length() - 1).trim(); else break;
        }
        int orIndex = findSplitIndex(expr, "OR"); if (orIndex != -1) return evaluateExpression(row, expr.substring(0, orIndex)) || evaluateExpression(row, expr.substring(orIndex + 2));
        int andIndex = findSplitIndex(expr, "AND"); if (andIndex != -1) return evaluateExpression(row, expr.substring(0, andIndex)) && evaluateExpression(row, expr.substring(andIndex + 3));
        return checkCondition(row, expr);
    }
    
    private static int findSplitIndex(String expr, String op) {
        String upper = expr.toUpperCase(); int bal = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i); if (c == '(') bal++; else if (c == ')') bal--;
            if (bal == 0 && upper.startsWith(op, i)) {
                boolean startOk = (i == 0) || Character.isWhitespace(upper.charAt(i - 1)) || upper.charAt(i-1) == ')';
                boolean endOk = (i + op.length() >= upper.length()) || Character.isWhitespace(upper.charAt(i + op.length())) || upper.charAt(i+op.length()) == '(';
                if (startOk && endOk) return i;
            }
        }
        return -1;
    }
    
    private static boolean checkCondition(NsonObject row, String cond) {
        cond = cond.trim(); Matcher m = Pattern.compile("([\\w.]+)\\s*(=|!=|<>|<|>|<=|>=|LIKE|IN)\\s*(.*)", Pattern.CASE_INSENSITIVE).matcher(cond);
        if (!m.find()) return false;
        String col = m.group(1).trim(); String op = m.group(2).toUpperCase(); String valStr = m.group(3).trim();
        Object rowVal = getRowValue(row, col); if (rowVal == null) return false;
        if (op.equals("IN")) {
            valStr = valStr.replaceAll("^\\(|\\)$", "");
            for (String p : valStr.split(",")) if (compareValues(rowVal, p.trim().replaceAll("^'|'$", ""), "=")) return true;
            return false;
        }
        if (valStr.startsWith("'") && valStr.endsWith("'")) valStr = valStr.substring(1, valStr.length() - 1);
        return compareValues(rowVal, valStr, op);
    }

    private static boolean compareValues(Object rowVal, String targetVal, String op) {
        String s1 = rowVal.toString();
        if (op.equals("LIKE")) return s1.toLowerCase().contains(targetVal.replace("%", "").toLowerCase());
        if (isNumeric(s1) && isNumeric(targetVal)) {
            double n1 = Double.parseDouble(s1); double n2 = Double.parseDouble(targetVal);
            return switch (op) { case "="->n1==n2; case "!=", "<>"->n1!=n2; case ">"->n1>n2; case "<"->n1<n2; case ">="->n1>=n2; case "<="->n1<=n2; default->false; };
        }
        return switch (op) { case "="->s1.equals(targetVal); case "!=", "<>"->!s1.equals(targetVal); default->false; };
    }

    private static Object getRowValue(NsonObject row, String col) {
        if (row.containsKey(col)) return row.get(col);
        if (col.contains(".")) { String shortCol = col.substring(col.lastIndexOf('.') + 1); if (row.containsKey(shortCol)) return row.get(shortCol); }
        return null;
    }
    
    private static boolean isNumeric(String str) { try { Double.parseDouble(str); return true; } catch(Exception e) { return false; } }

    private static NsonObject loadTableData(User user, String dbName, String tableName, String rootDir) throws Exception {
        String tableKey = dbName + "." + tableName;
        if (RealtimeTableManager.ramTables.containsKey(tableKey)) {
            List<Map<String, Object>> ramData = RealtimeTableManager.ramTables.get(tableKey);
            NsonArray dataArr = new NsonArray(); for (Map<String, Object> map : ramData) { NsonObject row = new NsonObject(); row.putAll(map); dataArr.add(row); }
            NsonObject result = new NsonObject(); result.put("data", dataArr); return result;
        }
        File file = new File(rootDir + "/" + dbName + "/" + tableName + ".nson");
        if (!file.exists()) throw new IllegalArgumentException("Table '" + tableName + "' does not exist.");
        return NsonObject.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
}