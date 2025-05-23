package njsql.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*; // Thêm import này

public class FileUtils {

    public static String readFileUtf8(String path) throws IOException {
        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new IOException("File does not exist: " + path);
        }
        return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
    }

    public static void writeFileUtf8(String path, String content) throws IOException {
        Path filePath = Paths.get(path);
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void createZip(String sourceDir, String zipPath) throws IOException {
        Path sourcePath = Paths.get(sourceDir);
        if (!Files.exists(sourcePath) || !Files.isDirectory(sourcePath)) {
            throw new IOException("Source directory does not exist: " + sourceDir);
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath))) {
            Files.walk(sourcePath)
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".nson"))
                    .forEach(path -> {
                        try {
                            String relativePath = sourcePath.relativize(path).toString();
                            ZipEntry ze = new ZipEntry(relativePath);
                            zos.putNextEntry(ze);
                            zos.write(Files.readAllBytes(path));
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException("Error adding file to ZIP: " + path, e);
                        }
                    });
        }
    }

    public static void extractZip(String zipPath, String destDir) throws IOException {
        Path zipFile = Paths.get(zipPath);
        if (!Files.exists(zipFile)) {
            throw new IOException("ZIP file does not exist: " + zipPath);
        }

        Path destPath = Paths.get(destDir);
        Files.createDirectories(destPath);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destPath.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.write(outPath, zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
    }

    public static void deleteDirectory(String path) throws IOException {
        Path dirPath = Paths.get(path);
        if (Files.exists(dirPath)) {
            Files.walk(dirPath)
                    .sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException("Error deleting path: " + p, e);
                        }
                    });
        }
    }

    public static boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    public static void createDirectory(String path) throws IOException {
        Files.createDirectories(Paths.get(path));
    }

    public static void copyDirectory(String sourceDir, String destDir) throws IOException {
        Path sourcePath = Paths.get(sourceDir);
        Path destPath = Paths.get(destDir);
        if (!Files.exists(sourcePath) || !Files.isDirectory(sourcePath)) {
            throw new IOException("Source directory does not exist: " + sourceDir);
        }
        Files.createDirectories(destPath);
        Files.walk(sourcePath)
                .forEach(source -> {
                    try {
                        Path destination = destPath.resolve(sourcePath.relativize(source));
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Error copying: " + source, e);
                    }
                });
    }
}