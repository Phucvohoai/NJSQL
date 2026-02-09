package njsql.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BTreeIndexManager {

    // CACHE: Lưu trữ Index trên RAM.
    // Structure: dbPath/tableName -> (IndexName -> TreeMap)
    private static final Map<String, Map<String, TreeMap<String, List<Integer>>>> memoryCache = new ConcurrentHashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    // --- 1. LOAD INDEX TỪ DISK LÊN RAM ---
    public static synchronized void loadIndexToMemory(String dbPath, String table) {
        String cacheKey = dbPath + "/" + table;
        if (memoryCache.containsKey(cacheKey)) return;

        try {
            File file = new File(dbPath + "/" + table + ".nson");
            if (!file.exists()) return;

            Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");

            Map<String, TreeMap<String, List<Integer>>> tableIndexes = new HashMap<>();

            if (indexes != null) {
                for (String idxName : indexes.keySet()) {
                    Map<String, Object> idxData = (Map<String, Object>) indexes.get(idxName);
                    if (idxData.containsKey("map")) {
                        tableIndexes.put(idxName, convertToTreeMap(idxData.get("map")));
                    }
                }
            }
            memoryCache.put(cacheKey, tableIndexes);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- 2. INSERT (Cập nhật vào RAM) ---
    public static void updateIndexOnInsert(String dbPath, String tableName, String column, String indexName, Map<String, Object> record, int position) {
        String cacheKey = dbPath + "/" + tableName;
        if (!memoryCache.containsKey(cacheKey)) loadIndexToMemory(dbPath, tableName);

        Map<String, TreeMap<String, List<Integer>>> tableIndexes = memoryCache.get(cacheKey);
        if (tableIndexes == null) return;

        TreeMap<String, List<Integer>> indexMap = tableIndexes.computeIfAbsent(indexName, k -> new TreeMap<>());
        Object valObj = record.get(column);
        if (valObj == null) return;

        String value = valObj.toString();
        synchronized (indexMap) {
            indexMap.computeIfAbsent(value, k -> new ArrayList<>()).add(position);
        }
    }

    // --- 3. UPDATE (ĐÃ BỔ SUNG LẠI HÀM NÀY ĐỂ FIX LỖI) ---
    public static void updateIndexOnUpdate(String dbPath, String table, String column, String indexName, int recordIndex, Object newValue) {
        String cacheKey = dbPath + "/" + table;
        if (!memoryCache.containsKey(cacheKey)) loadIndexToMemory(dbPath, table);

        Map<String, TreeMap<String, List<Integer>>> tableIndexes = memoryCache.get(cacheKey);
        if (tableIndexes == null || !tableIndexes.containsKey(indexName)) return;

        TreeMap<String, List<Integer>> indexMap = tableIndexes.get(indexName);

        synchronized (indexMap) {
            // Bước 1: Tìm và xóa giá trị cũ (Old Value) của dòng này
            // Vì ta không lưu old value, ta phải duyệt ngược map (Hơi chậm một chút nhưng an toàn)
            // TODO: Sau này nên truyền oldValue vào hàm này để nhanh hơn.
            String oldKeyFound = null;
            for (Map.Entry<String, List<Integer>> entry : indexMap.entrySet()) {
                if (entry.getValue().contains(recordIndex)) {
                    entry.getValue().remove(Integer.valueOf(recordIndex)); // Xóa ID cũ
                    if (entry.getValue().isEmpty()) {
                        oldKeyFound = entry.getKey(); // Đánh dấu để xóa key rỗng
                    }
                    break; // Đã tìm thấy thì thoát loop
                }
            }
            if (oldKeyFound != null) {
                indexMap.remove(oldKeyFound);
            }

            // Bước 2: Thêm giá trị mới (New Value)
            if (newValue != null) {
                String newKey = newValue.toString();
                indexMap.computeIfAbsent(newKey, k -> new ArrayList<>()).add(recordIndex);
            }
        }
    }

    // --- 4. DELETE (ĐÃ BỔ SUNG LẠI HÀM NÀY) ---
    public static void updateIndexOnDelete(String dbPath, String table, String column, String indexName, int deletedIndex) {
        String cacheKey = dbPath + "/" + table;
        if (!memoryCache.containsKey(cacheKey)) loadIndexToMemory(dbPath, table);

        Map<String, TreeMap<String, List<Integer>>> tableIndexes = memoryCache.get(cacheKey);
        if (tableIndexes == null || !tableIndexes.containsKey(indexName)) return;

        TreeMap<String, List<Integer>> indexMap = tableIndexes.get(indexName);

        synchronized (indexMap) {
            // Xóa ID khỏi Map
            for (Iterator<Map.Entry<String, List<Integer>>> it = indexMap.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, List<Integer>> entry = it.next();
                List<Integer> positions = entry.getValue();

                // Xóa vị trí deletedIndex
                positions.remove(Integer.valueOf(deletedIndex));

                // Shift các index phía sau lên (Vì file bị dồn dòng khi xóa)
                // Lưu ý: Logic này giả định file database dùng List mảng (xóa giữa là dồn).
                for (int i = 0; i < positions.size(); i++) {
                    if (positions.get(i) > deletedIndex) {
                        positions.set(i, positions.get(i) - 1);
                    }
                }

                if (positions.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    // --- 5. FLUSH TO DISK (Lưu xuống ổ cứng) ---
    public static synchronized void flushIndexesToDisk(String dbPath, String tableName) throws Exception {
        String cacheKey = dbPath + "/" + tableName;
        if (!memoryCache.containsKey(cacheKey)) return;

        File file = new File(dbPath + "/" + tableName + ".nson");
        if (!file.exists()) return;

        Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> indexes = (Map<String, Object>) tableJson.getOrDefault("_indexes", new HashMap<>());
        Map<String, TreeMap<String, List<Integer>>> cachedIndexes = memoryCache.get(cacheKey);

        for (Map.Entry<String, TreeMap<String, List<Integer>>> entry : cachedIndexes.entrySet()) {
            String idxName = entry.getKey();
            TreeMap<String, List<Integer>> mapData = entry.getValue();

            Map<String, Object> idxObj = (Map<String, Object>) indexes.getOrDefault(idxName, new HashMap<>());
            idxObj.put("map", mapData);
            if (!idxObj.containsKey("type")) idxObj.put("type", "btree");
            idxObj.put("column", getColumnNameFromIndex(indexes, idxName)); // Helper logic

            indexes.put(idxName, idxObj);
        }

        tableJson.put("_indexes", indexes);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableJson);
        System.out.println("[IO] Flushed indexes for " + tableName);
    }

    // Helper để lấy column name (tránh lỗi null)
    private static String getColumnNameFromIndex(Map<String, Object> indexes, String idxName) {
        Map<String, Object> idx = (Map<String, Object>) indexes.get(idxName);
        return idx != null && idx.containsKey("column") ? idx.get("column").toString() : "unknown";
    }

    public static TreeMap<String, List<Integer>> convertToTreeMap(Object obj) {
        TreeMap<String, List<Integer>> treeMap = new TreeMap<>();
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object val = entry.getValue();
                List<Integer> list = new ArrayList<>();
                if (val instanceof List) {
                    for (Object v : (List<?>) val) {
                        if (v instanceof Integer) list.add((Integer) v);
                        else if (v instanceof Number) list.add(((Number) v).intValue());
                    }
                }
                treeMap.put(entry.getKey().toString(), list);
            }
        }
        return treeMap;
    }

    public static void createBTreeIndex(String dbPath, String table, String column, String indexName) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        File file = new File(dbPath + "/" + table + ".nson");
        if (!file.exists()) throw new Exception("Table not found");
        Map<String, Object> tableJson = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> indexes = (Map<String, Object>) tableJson.getOrDefault("_indexes", new HashMap<>());

        Map<String, Object> newIndex = new HashMap<>();
        newIndex.put("type", "btree");
        newIndex.put("column", column);
        newIndex.put("map", new TreeMap<>());
        indexes.put(indexName, newIndex);
        tableJson.put("_indexes", indexes);
        mapper.writeValue(file, tableJson);

        loadIndexToMemory(dbPath, table);
        // Re-index logic should be called here ideally
    }
}