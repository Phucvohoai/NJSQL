package njsql.utils;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class DBZipper {

    public static void zipDatabase(String username, String dbName) {
        String userPath = "njsql_data/" + username;
        String dbPath = userPath + "/" + dbName;
        String exportPath = userPath + "/exports/" + dbName + ".njsql";

        File dbDir = new File(dbPath);
        if (!dbDir.exists()) {
            System.out.println("\u001B[33m<!> Database not found.\u001B[0m");
            return;
        }

        try {
            Files.createDirectories(Paths.get(userPath + "/exports"));
            FileOutputStream fos = new FileOutputStream(exportPath);
            ZipOutputStream zos = new ZipOutputStream(fos);
            zipFolder(dbDir, "", zos);
            zos.close();
            fos.close();
            System.out.println("\u001B[32m>> Database zipped successfully to: \u001B[0m" + exportPath);
        } catch (IOException e) {
            System.out.println("\u001B[31m>> Error zipping DB: \u001B[0m" + e.getMessage());
        }
    }

    public static void unzipDatabase(String username, String dbName, String zipFilePath) {
        String outputDir = "njsql_data/" + username + "/" + dbName;

        try {
            File destDir = new File(outputDir);
            Files.createDirectories(destDir.toPath());

            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath));
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    FileOutputStream fos = new FileOutputStream(newFile);
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zis.closeEntry();
            }

            zis.close();
            System.out.println(">>\u001B[32m Database unzipped to: \u001B[0m" + outputDir);
        } catch (IOException e) {
            System.out.println(">> \u001B[31mError unzipping DB: \u001B[0m" + e.getMessage());
        }
    }

    // Hàm zipFolder giữ nguyên
    private static void zipFolder(File folder, String parent, ZipOutputStream zos) throws IOException {
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) {
                zipFolder(file, parent + "/" + file.getName(), zos);
            } else {
                FileInputStream fis = new FileInputStream(file);
                String entryName = parent.isEmpty() ? file.getName() : parent + "/" + file.getName();
                ZipEntry zipEntry = new ZipEntry(entryName);
                zos.putNextEntry(zipEntry);

                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }

                zos.closeEntry();
                fis.close();
            }
        }
    }
}
