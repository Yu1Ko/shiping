package cn.shiping.app;

import android.content.Context;
import android.util.Xml;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.zip.*;

/** Local text extraction with input, expansion and output bounds. No remote document uploads. */
final class DocumentImport {
    static String read(Context context, InputStream input) throws Exception {
        byte[] bytes = BoundedInput.read(input, 5 * 1024 * 1024 + 1);
        if (bytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException("资料不能超过 5 MB，请先精简");
        String result;
        if (bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            PDFBoxResourceLoader.init(context.getApplicationContext());
            try (PDDocument document = PDDocument.load(bytes)) {
                if (document.getNumberOfPages() > 200) throw new IllegalArgumentException("PDF 超过 200 页，请先拆分");
                if (!document.getCurrentAccessPermission().canExtractContent()) throw new IllegalArgumentException("此 PDF 不允许提取文字");
                LimitedWriter writer = new LimitedWriter();
                new PDFTextStripper().writeText(document, writer); result = writer.toString();
            }
        } else if (bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K') result = docx(bytes);
        else result = decode(bytes);
        result = result.replace("\uFEFF", "").trim();
        if (result.length() > 30000) throw new IllegalArgumentException("资料超过 3 万字，请拆分后导入");
        if (result.isEmpty()) throw new IllegalArgumentException("没有提取到文字；扫描版 PDF 请先转成文字，或作为截图识别");
        return result;
    }
    static String decode(byte[] bytes) throws Exception {
        if (bytes.length > 120000) throw new IllegalArgumentException("文本超过 120 KB，请先精简");
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) return new String(bytes, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) return new String(bytes, StandardCharsets.UTF_16BE);
        String value;
        try { value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { value = Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("不支持此格式，请选择 PDF、DOCX、TXT 或 Markdown");
        return value;
    }
    private static String docx(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; int count = 0, total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 512) throw new IllegalArgumentException("DOCX 包含过多条目");
                byte[] content = BoundedInput.read(zip, 2 * 1024 * 1024 + 1);
                total += content.length;
                if (content.length > 2 * 1024 * 1024 || total > 16 * 1024 * 1024) throw new IllegalArgumentException("DOCX 解压内容过大，请精简");
                if (!entry.getName().equals("word/document.xml")) continue;
                String xml = new String(content, StandardCharsets.UTF_8);
                if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IllegalArgumentException("不支持包含外部实体的 DOCX");
                XmlPullParser parser = Xml.newPullParser(); parser.setInput(new StringReader(xml));
                LimitedWriter writer = new LimitedWriter(); boolean inText = false;
                for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    String name = parser.getName();
                    if (event == XmlPullParser.START_TAG) {
                        if ("t".equals(name)) inText = true;
                        if ("tab".equals(name)) writer.write("\t");
                        if ("br".equals(name)) writer.write("\n");
                    } else if (event == XmlPullParser.TEXT && inText) writer.write(parser.getText());
                    else if (event == XmlPullParser.END_TAG) { if ("t".equals(name)) inText = false; if ("p".equals(name)) writer.write("\n"); }
                }
                return writer.toString();
            }
        }
        throw new IllegalArgumentException("文件不是有效的 DOCX 文档");
    }
    private static final class LimitedWriter extends Writer {
        final StringBuilder value = new StringBuilder();
        @Override public void write(char[] chars, int offset, int count) throws IOException {
            if (value.length() + count > 30000) throw new IOException("资料超过 3 万字，请拆分后导入");
            value.append(chars, offset, count);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return value.toString(); }
    }
}
