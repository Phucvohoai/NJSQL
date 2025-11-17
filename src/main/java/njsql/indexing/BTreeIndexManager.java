package njsql.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference; // FIX: Thêm import

import java.io.File;
import java.util.*;

public class BTreeIndexManager {

    private static TreeMap<String, List<Integer>> convertToTreeMap(Object obj) {
        TreeMap<String, List<Integer>> treeMap = new TreeMap<>();
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                Object val = entry.getValue();
                if (val instanceof List) {
                    List<Integer> list = new ArrayList<>();
                    for (Object o : (List<?>) val) {
                        if (o instanceof Integer) {
                            list.add((Integer) o);
                        } else if (o instanceof Number) {
                            list.add(((Number) o).intValue());
                        }
                    }
                    treeMap.put(key, list);
                }
            }
        }
        return treeMap;
    }

    /**
     * Creates a new B-Tree index on the specified column of a table.
     */
    public static void createBTreeIndex(String dbPath, String table, String column, String indexName) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        File file = new File(dbPath + "/" + table + ".nson");

        if (!file.exists()) {
            throw new Exception("Table file not found: " + table);
        }

        // FIX 1 (WARNING): Sửa `unchecked conversion`
        Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> indexes = (Map<String, Object>) tableJson.getOrDefault("_indexes", new HashMap<>());

        if (indexes.containsKey(indexName)) {
            throw new Exception("Index '" + indexName + "' already exists.");
        }

        Map<String, String> types = (Map<String, String>) tableJson.get("_types");
        if (types == null || !types.containsKey(column)) {
            throw new Exception("Column '" + column + "' not found in table schema.");
        }
        String columnType = types.get(column);

        List<Map<String, Object>> data = (List<Map<String, Object>>) tableJson.get("data");
        TreeMap<String, List<Integer>> indexMap = new TreeMap<>();

        for (int i = 0; i < data.size(); i++) {
            Object val = data.get(i).get(column);
            if (val == null) continue;

            String key;
            if ("int".equalsIgnoreCase(columnType)) {
                key = String.valueOf(((Number) val).intValue());
            } else {
                key = val.toString();
            }

            indexMap.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        Map<String, Object> newIndex = new HashMap<>();
        newIndex.put("type", "btree");
        newIndex.put("column", column);
        newIndex.put("map", indexMap);

        indexes.put(indexName, newIndex);
        tableJson.put("_indexes", indexes);

        mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableJson);
    }

    /**
     * Drops an existing B-Tree index from a table.
     */
    public static void dropBTreeIndex(String dbPath, String table, String indexName) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        File file = new File(dbPath + "/" + table + ".nson");

        if (!file.exists()) {
            throw new Exception("Table file not found: " + table);
        }

        // FIX 2 (WARNING): Sửa `unchecked conversion`
        Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");

        if (indexes == null || !indexes.containsKey(indexName)) {
            throw new Exception("Index '" + indexName + "' does not exist.");
        }

        indexes.remove(indexName);
        tableJson.put("_indexes", indexes);

        mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableJson);
    }

    /**
     * Updates the B-Tree index after inserting a new record.
     */
    public static void updateIndexOnInsert(String dbPath, String tableName, String column, String indexName, Map<String, Object> record, int position) throws Exception {
        File file = new File(dbPath + "/" + tableName + ".nson");
        ObjectMapper mapper = new ObjectMapper();

        // FIX 3 (WARNING): Sửa `unchecked conversion`
        Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> indexes = (Map<String, Object>) tableJson.getOrDefault("_indexes", new HashMap<>());
        Map<String, Object> index = (Map<String, Object>) indexes.getOrDefault(indexName, new HashMap<>());

        TreeMap<String, List<Integer>> map = index.containsKey("map")
                ? convertToTreeMap(index.get("map"))
                : new TreeMap<>();

        Object valObj = record.get(column);
        if (valObj == null) {
            // Nếu giá trị null, không update index
            return;
        }
        String value = valObj.toString();
        List<Integer> positions = map.computeIfAbsent(value, k -> new ArrayList<>());
        positions.add(position);

        index.put("map", map);
        index.put("column", column);
        indexes.put(indexName, index);
        tableJson.put("_indexes", indexes);

        mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableJson);
        System.out.println("Updated index " + indexName + " for value " + value + " at position " + position);
    }

    /**
     * Updates the B-Tree index after deleting a record.
     */
    public static void updateIndexOnDelete(String dbPath, String table, String column, String indexName, int deletedIndex) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        File file = new File(dbPath + "/" + table + ".nson");

        if (!file.exists()) {
            throw new Exception("Table file not found: " + table);
        }

        // FIX 4 (WARNING): Sửa `unchecked conversion`
        Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");

        if (indexes == null || !indexes.containsKey(indexName)) {
            return;
        }

        Map<String, Object> index = (Map<String, Object>) indexes.get(indexName);
        TreeMap<String, List<Integer>> indexMap = convertToTreeMap(index.get("map"));

        for (List<Integer> positions : indexMap.values()) {
            positions.removeIf(pos -> pos.equals(deletedIndex));
            for (int i = 0; i < positions.size(); i++) {
                if (positions.get(i) > deletedIndex) {
                    positions.set(i, positions.get(i) - 1);
                }
            }
        }

        index.put("map", indexMap);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableJson);
    }

    /**
     * Updates the B-Tree index after updating a record.
     */
    public static void updateIndexOnUpdate(String dbPath, String table, String column, String indexName, int recordIndex, Object newValue) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        File file = new File(dbPath + "/" + table + ".nson");

        if (!file.exists()) {
            throw new Exception("Table file not found: " + table);
        }

        // FIX 5 (WARNING): Sửa `unchecked conversion`
        Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");

        if (indexes == null || !indexes.containsKey(indexName)) {
            return;
        }

        Map<String, Object> index = (Map<String, Object>) indexes.get(indexName);
        TreeMap<String, List<Integer>> indexMap = convertToTreeMap(index.get("map"));

        Map<String, String> types = (Map<String, String>) tableJson.get("_types");
        String columnType = types.get(column);

        List<Map<String, Object>> data = (List<Map<String, Object>>) tableJson.get("data");
        Object oldValue = data.get(recordIndex).get(column);

        if (oldValue != null) {
            String oldKey = "int".equalsIgnoreCase(columnType) ? String.valueOf(((Number) oldValue).intValue()) : oldValue.toString();
            List<Integer> positions = indexMap.get(oldKey);
            if (positions != null) {
                positions.removeIf(pos -> pos.equals(recordIndex));
                if (positions.isEmpty()) {
                    indexMap.remove(oldKey);
                }
            }
        }

        if (newValue != null) {
            String newKey = "int".equalsIgnoreCase(columnType) ? String.valueOf(((Number) newValue).intValue()) : newValue.toString();
            indexMap.computeIfAbsent(newKey, k -> new ArrayList<>()).add(recordIndex);
        }

        index.put("map", indexMap);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableJson);
    }
}