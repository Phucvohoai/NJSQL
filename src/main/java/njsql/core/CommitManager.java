package njsql.core;

import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import njsql.utils.FileUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CommitManager {
    private final String commitDir;

    public CommitManager(String commitDir) {
        this.commitDir = commitDir;
        try {
            FileUtils.createDirectory(commitDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create commit directory: " + commitDir, e);
        }
    }

    public boolean hasConflicts(String serverDbPath, String clientDbPath) throws Exception {
        File[] commitFiles = new File(commitDir).listFiles((dir, name) -> name.endsWith(".nson"));
        return commitFiles != null && commitFiles.length > 0;
    }

    public NsonObject createDiff(String serverDbPath, String clientDbPath) throws Exception {
        NsonObject diff = new NsonObject();
        File serverDir = new File(serverDbPath);
        File clientDir = new File(clientDbPath);

        for (File tableFile : serverDir.listFiles((dir, name) -> name.endsWith(".nson"))) {
            String tableName = tableFile.getName();
            File clientTableFile = new File(clientDbPath + "/" + tableName);
            if (!clientTableFile.exists()) {
                diff.put(tableName, "deleted");
                continue;
            }

            NsonObject serverTable = NsonObject.parse(FileUtils.readFileUtf8(tableFile.getPath()));
            NsonObject clientTable = NsonObject.parse(FileUtils.readFileUtf8(clientTableFile.getPath()));
            NsonObject tableDiff = compareTables(serverTable, clientTable);
            if (!tableDiff.isEmpty()) {
                diff.put(tableName, tableDiff);
            }
        }

        return diff;
    }

    public NsonObject compareTables(NsonObject serverTable, NsonObject clientTable) {
        NsonObject diff = new NsonObject();
        NsonArray serverData = serverTable.getArray("data");
        NsonArray clientData = clientTable.getArray("data");
        NsonObject meta = serverTable.getObject("_meta");
        NsonArray primaryKeys = meta != null ? meta.getArray("primary_key") : new NsonArray();
        String primaryKey = primaryKeys.size() > 0 ? primaryKeys.getString(0) : "id";

        Map<Object, NsonObject> serverMap = new HashMap<>();
        for (int i = 0; i < serverData.size(); i++) {
            NsonObject row = serverData.getObject(i);
            Object key = row.get(primaryKey);
            if (key != null) {
                serverMap.put(key, row);
            }
        }

        NsonArray changes = new NsonArray();
        for (int i = 0; i < clientData.size(); i++) {
            NsonObject clientRow = clientData.getObject(i);
            Object key = clientRow.get(primaryKey);
            if (key == null) continue;

            NsonObject serverRow = serverMap.get(key);
            if (serverRow == null) {
                NsonObject change = new NsonObject();
                change.put("type", "added");
                change.put("after", clientRow);
                changes.add(change);
            } else if (!serverRow.toString().equals(clientRow.toString())) {
                NsonObject change = new NsonObject();
                change.put("type", "modified");
                change.put("before", serverRow);
                change.put("after", clientRow);
                changes.add(change);
            }
        }

        for (Object key : serverMap.keySet()) {
            boolean found = false;
            for (int i = 0; i < clientData.size(); i++) {
                if (key.equals(clientData.getObject(i).get(primaryKey))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                NsonObject change = new NsonObject();
                change.put("type", "deleted");
                change.put("before", serverMap.get(key));
                changes.add(change);
            }
        }

        if (!changes.isEmpty()) {
            diff.put("changes", changes);
        }

        return diff;
    }

    public void saveCommit(NsonObject commit) throws Exception {
        String commitId = commit.getString("id");
        FileUtils.writeFileUtf8(commitDir + "/" + commitId + ".nson", commit.toString(2));
    }

    public NsonObject loadCommit(String commitId) throws Exception {
        String commitPath = commitDir + "/" + commitId + ".nson";
        if (!FileUtils.exists(commitPath)) {
            return null;
        }
        return NsonObject.parse(FileUtils.readFileUtf8(commitPath));
    }

    public void deleteCommit(String commitId) throws Exception {
        String commitPath = commitDir + "/" + commitId + ".nson";
        if (FileUtils.exists(commitPath)) {
            Files.delete(Paths.get(commitPath));
        }
    }

    public void applyChanges(String dbPath, NsonObject changes) throws Exception {
        for (String tableName : changes.keySet()) {
            Object change = changes.get(tableName);
            if (change.equals("deleted")) {
                Files.deleteIfExists(Paths.get(dbPath + "/" + tableName));
            } else {
                NsonObject tableDiff = (NsonObject) change;
                NsonObject table = NsonObject.parse(FileUtils.readFileUtf8(dbPath + "/" + tableName));
                NsonArray currentData = table.getArray("data");
                NsonArray changesArray = tableDiff.getArray("changes");

                for (int i = 0; i < changesArray.size(); i++) {
                    NsonObject changeEntry = changesArray.getObject(i);
                    String type = changeEntry.getString("type");
                    NsonObject after = changeEntry.getObject("after");
                    NsonObject before = changeEntry.getObject("before");
                    NsonObject meta = table.getObject("_meta");
                    String primaryKey = "id";
                    if (meta != null && meta.getArray("primary_key") != null && !meta.getArray("primary_key").isEmpty()) {
                        primaryKey = meta.getArray("primary_key").getString(0);
                    }

                    Object key = null;
                    if (after != null && after.get(primaryKey) != null) {
                        key = after.get(primaryKey);
                    } else if (before != null && before.get(primaryKey) != null) {
                        key = before.get(primaryKey);
                    }

                    if (key == null) {
                        throw new Exception("Primary key '" + primaryKey + "' not found in change entry for table " + tableName);
                    }

                    if (type.equals("added")) {
                        currentData.add(after);
                    } else if (type.equals("modified")) {
                        for (int j = 0; j < currentData.size(); j++) {
                            if (currentData.getObject(j).get(primaryKey) != null &&
                                    currentData.getObject(j).get(primaryKey).equals(key)) {
                                currentData.set(j, after);
                                break;
                            }
                        }
                    } else if (type.equals("deleted")) {
                        for (int j = 0; j < currentData.size(); j++) {
                            if (currentData.getObject(j).get(primaryKey) != null &&
                                    currentData.getObject(j).get(primaryKey).equals(key)) {
                                currentData.remove(j);
                                break;
                            }
                        }
                    }
                }

                table.put("data", currentData);
                FileUtils.writeFileUtf8(dbPath + "/" + tableName, table.toString(2));
            }
        }
    }
}