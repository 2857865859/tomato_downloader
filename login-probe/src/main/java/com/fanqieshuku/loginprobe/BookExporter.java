package com.fanqieshuku.loginprobe;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BookExporter {
    private final Context context;

    BookExporter(Context context) {
        this.context = context.getApplicationContext();
    }

    Uri export(BiekApiClient.Book book, List<BiekApiClient.Chapter> chapters, File cacheDir,
               String format, Uri customTree, java.util.function.BooleanSupplier stopped) throws Exception {
        checkStopped(stopped);
        String extension = format.equals("EPUB") ? ".epub" : ".txt";
        String fileName = sanitizeFileName(book.title) + extension;
        String mime = format.equals("EPUB") ? "application/epub+zip" : "text/plain";
        Uri outputUri = createOutput(fileName, mime, customTree);
        try {
            try (OutputStream raw = "file".equals(outputUri.getScheme())
                    ? new FileOutputStream(new File(outputUri.getPath()))
                    : context.getContentResolver().openOutputStream(outputUri, "w")) {
                if (raw == null) throw new IOException("无法创建输出文件");
                if (format.equals("EPUB")) writeEpub(raw, book, chapters, cacheDir, stopped);
                else writeTxt(raw, book, chapters, cacheDir, stopped);
            }
            checkStopped(stopped);
            publish(outputUri);
        } catch (Exception error) {
            try { deleteOutput(outputUri); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        return outputUri;
    }

    static void checkStopped(java.util.function.BooleanSupplier stopped) throws InterruptedException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("文件生成已停止");
    }

    void deleteOutput(Uri uri) {
        if ("file".equals(uri.getScheme())) new File(uri.getPath()).delete();
        else context.getContentResolver().delete(uri, null, null);
    }

    private Uri createOutput(String fileName, String mime, Uri customTree) throws Exception {
        if (customTree != null) {
            DocumentFile directory = DocumentFile.fromTreeUri(context, customTree);
            if (directory == null || !directory.canWrite()) throw new IOException("所选目录不可写");
            fileName = availableName(directory, fileName);
            DocumentFile file = directory.createFile(mime, fileName);
            if (file == null) throw new IOException("无法在所选目录创建文件");
            return file.getUri();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "西红柿下载器");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建下载目录");
            File target = new File(directory, fileName);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.DATA, target.getAbsolutePath());
            Uri uri = context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
            if (uri == null) throw new IOException("无法在 Download/西红柿下载器 创建文件");
            return uri;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/西红柿下载器");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法在 Download/西红柿下载器 创建文件");
        return uri;
    }

    private void publish(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || !MediaStore.AUTHORITY.equals(uri.getAuthority())) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        context.getContentResolver().update(uri, values, null, null);
    }

    private static String availableName(DocumentFile directory, String requestedName) {
        if (directory.findFile(requestedName) == null) return requestedName;
        int dot = requestedName.lastIndexOf('.');
        String base = dot > 0 ? requestedName.substring(0, dot) : requestedName;
        String extension = dot > 0 ? requestedName.substring(dot) : "";
        for (int copy = 2; ; copy++) {
            String candidate = base + " (" + copy + ")" + extension;
            if (directory.findFile(candidate) == null) return candidate;
        }
    }

    private void writeTxt(OutputStream output, BiekApiClient.Book book,
                          List<BiekApiClient.Chapter> chapters, File cacheDir, java.util.function.BooleanSupplier stopped) throws Exception {
        output.write(("《" + book.title + "》\n\n作者：" + book.author + "\n\n").getBytes(StandardCharsets.UTF_8));
        for (BiekApiClient.Chapter chapter : chapters) {
            checkStopped(stopped);
            output.write((chapter.title + "\n\n").getBytes(StandardCharsets.UTF_8));
            copy(new File(cacheDir, chapter.index + ".txt"), output);
            output.write("\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeEpub(OutputStream output, BiekApiClient.Book book,
                           List<BiekApiClient.Chapter> chapters, File cacheDir, java.util.function.BooleanSupplier stopped) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            ZipEntry mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setMethod(ZipEntry.STORED);
            mimeEntry.setSize(mimetype.length);
            mimeEntry.setCompressedSize(mimetype.length);
            mimeEntry.setCrc(crc.getValue());
            zip.putNextEntry(mimeEntry);
            zip.write(mimetype);
            zip.closeEntry();

            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            put(zip, "OEBPS/nav.xhtml", nav(book, chapters));
            put(zip, "OEBPS/toc.ncx", ncx(book, chapters));
            put(zip, "OEBPS/content.opf", opf(book, chapters));
            for (BiekApiClient.Chapter chapter : chapters) {
                checkStopped(stopped);
                String body = readUtf8(new File(cacheDir, chapter.index + ".txt"));
                StringBuilder paragraphs = new StringBuilder();
                for (String line : body.split("\\n")) {
                    if (!line.trim().isEmpty()) paragraphs.append("<p>").append(xml(line.trim())).append("</p>");
                }
                put(zip, "OEBPS/chapter" + chapter.index + ".xhtml",
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>" +
                                xml(chapter.title) + "</title></head><body><h1>" + xml(chapter.title) + "</h1>" + paragraphs + "</body></html>");
            }
        }
    }

    private String nav(BiekApiClient.Book book, List<BiekApiClient.Chapter> chapters) {
        StringBuilder items = new StringBuilder();
        for (BiekApiClient.Chapter chapter : chapters) items.append("<li><a href=\"chapter").append(chapter.index).append(".xhtml\">").append(xml(chapter.title)).append("</a></li>");
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>" + xml(book.title) + "</title></head><body><nav epub:type=\"toc\"><ol>" + items + "</ol></nav></body></html>";
    }

    private String ncx(BiekApiClient.Book book, List<BiekApiClient.Chapter> chapters) {
        StringBuilder points = new StringBuilder();
        int play = 1;
        for (BiekApiClient.Chapter chapter : chapters) points.append("<navPoint id=\"c").append(chapter.index).append("\" playOrder=\"").append(play++).append("\"><navLabel><text>").append(xml(chapter.title)).append("</text></navLabel><content src=\"chapter").append(chapter.index).append(".xhtml\"/></navPoint>");
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head/><docTitle><text>" + xml(book.title) + "</text></docTitle><navMap>" + points + "</navMap></ncx>";
    }

    private String opf(BiekApiClient.Book book, List<BiekApiClient.Chapter> chapters) {
        StringBuilder manifest = new StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>");
        StringBuilder spine = new StringBuilder();
        for (BiekApiClient.Chapter chapter : chapters) {
            manifest.append("<item id=\"c").append(chapter.index).append("\" href=\"chapter").append(chapter.index).append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
            spine.append("<itemref idref=\"c").append(chapter.index).append("\"/>");
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"bookid\">" + xml(book.id) + "</dc:identifier><dc:title>" + xml(book.title) + "</dc:title><dc:creator>" + xml(book.author) + "</dc:creator><dc:language>zh-CN</dc:language></metadata><manifest>" + manifest + "</manifest><spine toc=\"ncx\">" + spine + "</spine></package>";
    }

    private static void put(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String readUtf8(File file) throws IOException {
        StringBuilder result = new StringBuilder();
        try (Reader input = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) result.append(buffer, 0, count);
        }
        return result.toString();
    }

    private static void copy(File file, OutputStream output) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
    }

    static String sanitizeFileName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "未命名小说" : safe;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
