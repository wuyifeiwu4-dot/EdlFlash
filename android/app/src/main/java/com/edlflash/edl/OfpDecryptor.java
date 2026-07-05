package com.edlflash.edl;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class OfpDecryptor {
    public interface Logger {
        void log(String message);
    }

    private static final String[][] KEY_SETS = new String[][]{
            {"V1.4.17/1.4.27", "27827963787265EF89D126B69A495A21", "82C50203285A2CE7D8C3E198383CE94C", "422DD5399181E223813CD8ECDF2E4D72"},
            {"V1.6.17", "E11AA7BB558A436A8375FD15DDD4651F", "77DDF6A0696841F6B74782C097835169", "A739742384A44E8BA45207AD5C3700EA"},
            {"V1.5.13", "67657963787565E837D226B69A495D21", "F6C50203515A2CE7D8C3E1F938B7E94C", "42F2D5399137E2B2813CD8ECDF2F4D72"},
            {"V1.6.6/1.6.9/1.6.17/1.6.24/1.6.26/1.7.6", "3C2D518D9BF2E4279DC758CD535147C3", "87C74A29709AC1BF2382276C4E8DF232", "598D92E967265E9BCABE2469FE4A915E"},
            {"V1.7.2", "8FB8FB261930260BE945B841AEFA9FD4", "E529E82B28F5A2F8831D860AE39E425D", "8A09DA60ED36F125D64709973372C1CF"},
            {"V2.0.3", "E8AE288C0192C54BF10C5707E9C4705B", "D64FC385DCD52A3C9B5FBA8650F92EDA", "79051FD8D8B6297E2E4559E997F63B7F"},
            {"MTK-1", "9E4F32639D21357D37D226B69A495D21", "A3D8D358E42F5A9E931DD3917D9A3218", "386935399137416B67416BECF22F519A"},
            {"MTK-2", "892D57E92A4D8A975E3C216B7C9DE189", "D26DF2D9913785B145D18C7219B89F26", "516989E4A1BFC78B365C6BC57D944391"},
            {"MTK-3", "3C4A618D9BF2E4279DC758CD535147C3", "87B13D29709AC1BF2382276C4E8DF232", "59B7A8E967265E9BCABE2469FE4A915E"},
            {"MTK-4", "1C3288822BF824259DC852C1733127D3", "E7918D22799181CF2312176C9E2DF298", "3247F889A7B6DECBCA3E28693E4AAAFE"},
            {"MTK-5", "1E4F32239D65A57D37D2266D9A775D43", "A332D3C3E42F5A3E931DD991729A321D", "3F2A35399A373377674155ECF28FD19A"},
            {"MTK-6", "122D57E92A518AFF5E3C786B7C34E189", "DD6DF2D9543785674522717219989FB0", "12698965A132C76136CC88C5DD94EE91"},
            {"V2.1.x", "D4D2CD61D4D2CD61D4D2CD61D4D2CD61", "D4D2CD61D4D2CD61D4D2CD61D4D2CD61", "D4D2CD61D4D2CD61D4D2CD61D4D2CD61"},
            {"V3.0.x", "2442CE821A4F352D44D2CE8D1A4F352D", "2442CE821A4F352D44D2CE8D1A4F352D", "2442CE821A4F352D44D2CE8D1A4F352D"}
    };

    private final Logger logger;

    public OfpDecryptor(Logger logger) {
        this.logger = logger;
    }

    private void log(String msg) {
        if (logger != null && msg != null && !msg.trim().isEmpty()) {
            logger.log(msg);
        }
    }

    public String decrypt(String ofpFilePath, String outputDir) {
        if (ofpFilePath == null) {
            return null;
        }
        File ofpFile = new File(ofpFilePath);
        if (!ofpFile.exists()) {
            log("File not found: " + ofpFilePath);
            return null;
        }
        if (isZipFile(ofpFile)) {
            log("ZIP file detected, extracting...");
            return extractZip(ofpFile, outputDir);
        }
        File baseDir = ofpFile.getParentFile();
        File extractDir = outputDir == null
                ? new File(baseDir, "extract")
                : new File(outputDir);
        if (extractDir.exists()) {
            deleteRecursive(extractDir);
        }
        if (!extractDir.mkdirs()) {
            log("Failed to create output dir: " + extractDir.getAbsolutePath());
            return null;
        }
        ExtractResult result = extractXml(ofpFile);
        if (!result.valid()) {
            log("Unknown key. Aborting");
            return null;
        }
        File xmlFile = new File(extractDir, "ProFile.xml");
        try (FileOutputStream out = new FileOutputStream(xmlFile)) {
            out.write(result.xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("Failed to write ProFile.xml: " + e.getMessage());
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new BufferedInputStream(new FileInputStream(xmlFile)));
            Element root = doc.getDocumentElement();
            if (root == null) {
                log("Invalid ProFile.xml");
                return extractDir.getAbsolutePath();
            }
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                Element child = (Element) node;
                String tag = stripNamespace(child.getNodeName());
                NodeList items = child.getChildNodes();
                for (int j = 0; j < items.getLength(); j++) {
                    Node item = items.item(j);
                    if (!(item instanceof Element)) {
                        continue;
                    }
                    Element itemEl = (Element) item;
                    // 对齐 OplusEdlTool OfpDecryptor.cs:270-275：始终处理 item 本身【并且】始终下钻处理其
                    // 全部子项。旧版"item 自带 Path/filename 就不下钻"(对齐 bkerler ofp_qc_decrypt.py)会漏掉
                    // 【既带 filename 又含文件子项】的项的子文件→OFP 解不全。processItem 对无 Path/filename
                    // (或无有效偏移)的元素早退，故 item 为纯容器时只是 no-op、子项不会被重复提取。
                    processItem(itemEl, tag, result.pagesize, result.key, result.iv, ofpFile, extractDir);
                    NodeList subItems = itemEl.getChildNodes();
                    for (int k = 0; k < subItems.getLength(); k++) {
                        Node sub = subItems.item(k);
                        if (sub instanceof Element) {
                            processItem((Element) sub, tag, result.pagesize, result.key, result.iv, ofpFile, extractDir);
                        }
                    }
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log("XML parse failed: " + e.getMessage());
        }
        log("Done. Extracted files to " + extractDir.getAbsolutePath());
        return extractDir.getAbsolutePath();
    }

    private void processItem(Element item, String parentTag, int pagesize, byte[] key, byte[] iv,
                             File srcFile, File destDir) {
        String fileName = getAttr(item, "Path");
        if (fileName.isEmpty()) {
            fileName = getAttr(item, "filename");
        }
        if (fileName.isEmpty()) {
            return;
        }
        long start = -1;
        if (!getAttr(item, "FileOffsetInSrc").isEmpty()) {
            start = parseLong(getAttr(item, "FileOffsetInSrc")) * pagesize;
        } else if (!getAttr(item, "SizeInSectorInSrc").isEmpty()) {
            start = parseLong(getAttr(item, "SizeInSectorInSrc")) * pagesize;
        }
        if (start < 0) {
            return;
        }
        long rlength = parseLong(getAttr(item, "SizeInByteInSrc"));
        long length = !getAttr(item, "SizeInSectorInSrc").isEmpty()
                ? parseLong(getAttr(item, "SizeInSectorInSrc")) * pagesize
                : rlength;
        if (length <= 0) {
            return;
        }
        if ("DigestsToSign".equals(parentTag)
                || "ChainedTableOfDigests".equals(parentTag)
                || "Firmware".equals(parentTag)) {
            copyFile(srcFile, destDir, fileName, start, rlength);
        } else {
            long decryptSize = "Sahara".equals(parentTag) ? rlength : 0x40000L;
            decryptFile(key, iv, srcFile, destDir, fileName, start, rlength, decryptSize);
        }
    }

    private ExtractResult extractXml(File file) {
        long size = file.length();
        int pagesize = 0;
        int[] pagesizes = new int[]{0x200, 0x1000};
        int[] offsets = new int[]{0x10, 0x14, 0x0};
        // 逐个 (pagesize, offset) 组合按绝对偏移探测尾部魔数；每次新开流，避免在同一流上累积跳读
        outer:
        for (int ps : pagesizes) {
            for (int off : offsets) {
                if (size < ps) {
                    continue;
                }
                long skip = size - ps + off;
                try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                    skipFully(in, skip);
                    byte[] magicBytes = new byte[4];
                    readExactly(in, magicBytes, 0, 4);
                    int magic = readIntLE(magicBytes, 0);
                    log(String.format(Locale.US, "Checking pagesize 0x%X offset 0x%X: magic=0x%X", ps, off, magic));
                    if (magic == 0x7CEF) {
                        pagesize = ps;
                        break outer;
                    }
                } catch (IOException e) {
                    return ExtractResult.invalid();
                }
            }
        }
        // 固定偏移未命中时的兜底：扫描尾部 0x2000 字节查找魔数 0x7CEF(对齐 OplusEdlTool OfpDecryptor.cs)。
        // 仅采纳标准 pagesize(0x200/0x1000)，不臆造非标准值，避免错误 pagesize 导致提取损坏。
        if (pagesize == 0) {
            int scanSize = (int) Math.min(0x2000L, size);
            if (scanSize >= 4) {
                log("Scanning file tail for magic 0x7CEF...");
                try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                    skipFully(in, size - scanSize);
                    byte[] tail = new byte[scanSize];
                    readExactly(in, tail, 0, scanSize);
                    for (int i = scanSize - 4; i >= 0 && pagesize == 0; i--) {
                        if (readIntLE(tail, i) != 0x7CEF) {
                            continue;
                        }
                        int posFromEnd = scanSize - i;
                        if (posFromEnd <= 0x200) {
                            pagesize = 0x200;
                        } else if (posFromEnd <= 0x1000) {
                            pagesize = 0x1000;
                        }
                        if (pagesize != 0) {
                            log(String.format(Locale.US,
                                    "Found magic at -0x%X from end, pagesize 0x%X", posFromEnd, pagesize));
                        }
                    }
                } catch (IOException e) {
                    return ExtractResult.invalid();
                }
            }
        }
        if (pagesize == 0) {
            log("Unknown pagesize - magic 0x7CEF not found");
            return ExtractResult.invalid();
        }
        long xmlOffset;
        int xmlLength;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            long skip = size - pagesize + 0x14;
            skipFully(in, skip);
            byte[] offsetBytes = new byte[4];
            byte[] lengthBytes = new byte[4];
            readExactly(in, offsetBytes, 0, 4);
            readExactly(in, lengthBytes, 0, 4);
            xmlOffset = (long) readIntLE(offsetBytes, 0) * pagesize;
            xmlLength = readIntLE(lengthBytes, 0);
        } catch (IOException e) {
            return ExtractResult.invalid();
        }
        if (xmlLength < 200) {
            xmlLength = (int) (size - pagesize - xmlOffset - 0x57);
        }
        // 损坏/误判 pagesize 的 OFP 会算出负数或超文件长度的 xmlLength，分配前拦截，避免
        // NegativeArraySizeException/OOM 在解包阶段未捕获崩溃
        if (xmlLength <= 0 || xmlLength > size) {
            log("Invalid XML length: " + xmlLength);
            return ExtractResult.invalid();
        }
        byte[] data = new byte[xmlLength];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            skipFully(in, xmlOffset);
            readExactly(in, data, 0, xmlLength);
        } catch (IOException e) {
            return ExtractResult.invalid();
        }
        for (String[] keyset : KEY_SETS) {
            byte[] mc = hexToBytes(keyset[1]);
            byte[] userkey = hexToBytes(keyset[2]);
            byte[] ivec = hexToBytes(keyset[3]);
            KeyIv keyIv = generateKey(mc, userkey, ivec);
            byte[] dec = aesCfbDecrypt(data, keyIv.key, keyIv.iv);
            String xml = new String(dec, StandardCharsets.UTF_8);
            if (xml.contains("<?xml")) {
                int lastGt = xml.lastIndexOf('>');
                if (lastGt > 0) {
                    xml = xml.substring(0, lastGt + 1);
                }
                return new ExtractResult(pagesize, keyIv.key, keyIv.iv, xml);
            }
        }
        KeyIv keyIv = generateKey1();
        if (keyIv != null) {
            byte[] dec = aesCfbDecrypt(data, keyIv.key, keyIv.iv);
            String xml = new String(dec, StandardCharsets.UTF_8);
            if (xml.contains("<?xml")) {
                int lastGt = xml.lastIndexOf('>');
                if (lastGt > 0) {
                    xml = xml.substring(0, lastGt + 1);
                }
                return new ExtractResult(pagesize, keyIv.key, keyIv.iv, xml);
            }
        }
        log("No matching key found for this OFP file");
        return ExtractResult.invalid();
    }

    private KeyIv generateKey(byte[] mc, byte[] userkey, byte[] ivec) {
        byte[] deobfUser = deobfuscate(userkey, mc);
        byte[] deobfIvec = deobfuscate(ivec, mc);
        String keyHex = md5Hex(deobfUser).substring(0, 16);
        String ivHex = md5Hex(deobfIvec).substring(0, 16);
        return new KeyIv(keyHex.getBytes(StandardCharsets.UTF_8), ivHex.getBytes(StandardCharsets.UTF_8));
    }

    private KeyIv generateKey1() {
        byte[] key1 = hexToBytes("42F2D5399137E2B2813CD8ECDF2F4D72");
        byte[] key2 = hexToBytes("F6C50203515A2CE7D8C3E1F938B7E94C");
        byte[] key3 = hexToBytes("67657963787565E837D226B69A495D21");
        byte[] shuffledKey2 = keyShuffle(key2, key3);
        byte[] shuffledKey1 = keyShuffle(key1, key3);
        String keyHex = md5Hex(shuffledKey2).substring(0, 16);
        String ivHex = md5Hex(shuffledKey1).substring(0, 16);
        return new KeyIv(keyHex.getBytes(StandardCharsets.UTF_8), ivHex.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] aesCfbDecrypt(byte[] data, byte[] key, byte[] iv) {
        int paddedLength = data.length;
        if (paddedLength % 16 != 0) {
            paddedLength = ((paddedLength / 16) + 1) * 16;
        }
        byte[] paddedData = new byte[paddedLength];
        System.arraycopy(data, 0, paddedData, 0, data.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] result = cipher.doFinal(paddedData);
            if (result.length > data.length) {
                byte[] trimmed = new byte[data.length];
                System.arraycopy(result, 0, trimmed, 0, data.length);
                return trimmed;
            }
            return result;
        } catch (Exception e) {
            // provider 故障(不支持 CFB/KeySpec 被拒等)在此暴露，避免误报为"密钥不匹配"；
            // 返回密文以便继续尝试下一 keyset，对齐 OfpDecryptor.cs 的 "Decrypt error:"
            log("  Decrypt error: " + e);
            return data;
        }
    }

    private void copyFile(File srcFile, File destDir, String filename, long start, long length) {
        log("Extracting " + filename);
        File destFile = new File(destDir, filename);
        File parent = destFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(srcFile));
             FileOutputStream out = new FileOutputStream(destFile)) {
            if (start > 0) {
                skipFully(in, start);
            }
            byte[] buffer = new byte[0x100000];
            long remaining = length;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read <= 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            log("Extract failed: " + e.getMessage());
        }
    }

    private void decryptFile(byte[] key, byte[] iv, File srcFile, File destDir, String filename,
                             long start, long rlength, long decryptSize) {
        log("Decrypting " + filename);
        File destFile = new File(destDir, filename);
        File parent = destFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        long size = Math.min(decryptSize, rlength);
        int padded = (int) (size + (4 - size % 4) % 4);
        byte[] data = new byte[padded];
        try (InputStream in = new BufferedInputStream(new FileInputStream(srcFile));
             FileOutputStream out = new FileOutputStream(destFile)) {
            if (start > 0) {
                skipFully(in, start);
            }
            readExactly(in, data, 0, (int) size);
            byte[] outp = aesCfbDecrypt(data, key, iv);
            out.write(outp, 0, (int) size);
            if (rlength > decryptSize) {
                byte[] buffer = new byte[0x100000];
                long remaining = rlength - size;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, toRead);
                    if (read <= 0) {
                        break;
                    }
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        } catch (IOException e) {
            log("Decrypt failed: " + e.getMessage());
        }
    }

    private String extractZip(File zipFile, String outputDir) {
        File baseDir = zipFile.getParentFile();
        File extractDir = outputDir == null
                ? new File(baseDir, "extract")
                : new File(outputDir);
        if (extractDir.exists()) {
            deleteRecursive(extractDir);
        }
        if (!extractDir.mkdirs()) {
            log("Failed to create output dir: " + extractDir.getAbsolutePath());
            return null;
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            String extractRoot = extractDir.getCanonicalPath() + File.separator;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(extractDir, entry.getName());
                // Zip Slip 防护：拒绝条目名经 ../ 逃出解压目录(对齐 .NET 内建防护)
                if (!(outFile.getCanonicalPath() + File.separator).startsWith(extractRoot)) {
                    log("Skipped unsafe zip entry (path traversal): " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[0x100000];
                        int read;
                        while ((read = zis.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
            }
            log("Extracted ZIP to " + extractDir.getAbsolutePath());
            return extractDir.getAbsolutePath();
        } catch (IOException e) {
            log("ZIP extraction failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isZipFile(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] header = new byte[2];
            if (in.read(header) != 2) {
                return false;
            }
            return header[0] == 'P' && header[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private static byte swap(byte ch) {
        int b = ch & 0xff;
        return (byte) (((b & 0x0f) << 4) + ((b & 0xf0) >> 4));
    }

    private static byte[] deobfuscate(byte[] data, byte[] mask) {
        byte[] ret = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            byte xored = (byte) (data[i] ^ mask[i]);
            ret[i] = swap(xored);
        }
        return ret;
    }

    private static byte[] keyShuffle(byte[] key, byte[] hkey) {
        byte[] result = key.clone();
        for (int i = 0; i < 0x10; i += 4) {
            result[i] = swap((byte) (hkey[i] ^ result[i]));
            result[i + 1] = swap((byte) (hkey[i + 1] ^ result[i + 1]));
            result[i + 2] = swap((byte) (hkey[i + 2] ^ result[i + 2]));
            result[i + 3] = swap((byte) (hkey[i + 3] ^ result[i + 3]));
        }
        return result;
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    private static void readExactly(InputStream in, byte[] buffer, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int read = in.read(buffer, off + total, len - total);
            if (read <= 0) {
                throw new IOException("Unexpected EOF");
            }
            total += read;
        }
    }

    // InputStream.skip 不保证跳满，循环补齐到精确字节数，确保后续读取从正确偏移开始
    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Unexpected EOF while skipping");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static String stripNamespace(String name) {
        if (name == null) {
            return "";
        }
        int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static String getAttr(Element element, String name) {
        if (element == null || name == null) {
            return "";
        }
        String value = element.getAttribute(name);
        return value == null ? "" : value.trim();
    }

    private static long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static class KeyIv {
        final byte[] key;
        final byte[] iv;

        KeyIv(byte[] key, byte[] iv) {
            this.key = key;
            this.iv = iv;
        }
    }

    private static class ExtractResult {
        final int pagesize;
        final byte[] key;
        final byte[] iv;
        final String xml;

        ExtractResult(int pagesize, byte[] key, byte[] iv, String xml) {
            this.pagesize = pagesize;
            this.key = key;
            this.iv = iv;
            this.xml = xml;
        }

        boolean valid() {
            return pagesize > 0 && key != null && iv != null && xml != null && !xml.isEmpty();
        }

        static ExtractResult invalid() {
            return new ExtractResult(0, new byte[0], new byte[0], "");
        }
    }
}
