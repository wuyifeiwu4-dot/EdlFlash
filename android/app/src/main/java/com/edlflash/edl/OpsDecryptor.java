package com.edlflash.edl;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class OpsDecryptor {
    public interface Logger {
        void log(String message);
    }

    private static final int[] KEY = new int[]{
            0x9ee3b5d1, 0x9d04ea5e, 0xabd51d67, 0xafcbafd2
    };
    private static final byte[] MBOX5 = new byte[]{
            0x60, (byte) 0x8a, 0x3f, 0x2d, 0x68, 0x6b, (byte) 0xd4, 0x23, 0x51, 0x0c, (byte) 0xd0, (byte) 0x95, (byte) 0xbb, 0x40, (byte) 0xe9, 0x76,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00
    };
    private static final byte[] MBOX6 = new byte[]{
            (byte) 0xaa, 0x69, (byte) 0x82, (byte) 0x9e, 0x5d, (byte) 0xde, (byte) 0xb1, 0x3d, 0x30, (byte) 0xbb, (byte) 0x81, (byte) 0xa3, 0x46, 0x65, (byte) 0xa3, (byte) 0xe1,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00
    };
    private static final byte[] MBOX4 = new byte[]{
            (byte) 0xc4, 0x5d, 0x05, 0x71, (byte) 0x99, (byte) 0xdd, (byte) 0xbb, (byte) 0xee, 0x29, (byte) 0xa1, 0x6d, (byte) 0xc7, (byte) 0xad, (byte) 0xbf, (byte) 0xa4, 0x3f,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00
    };
    private static final byte[] SBOX = hexToBytes(
            "c66363a5c66363a5f87c7c84f87c7c84ee777799ee777799f67b7b8df67b7b8d" +
            "fff2f20dfff2f20dd66b6bbdd66b6bbdde6f6fb1de6f6fb191c5c55491c5c554" +
            "60303050603030500201010302010103ce6767a9ce6767a9562b2b7d562b2b7d" +
            "e7fefe19e7fefe19b5d7d762b5d7d7624dababe64dababe6ec76769aec76769a" +
            "8fcaca458fcaca451f82829d1f82829d89c9c94089c9c940fa7d7d87fa7d7d87" +
            "effafa15effafa15b25959ebb25959eb8e4747c98e4747c9fbf0f00bfbf0f00b" +
            "41adadec41adadecb3d4d467b3d4d4675fa2a2fd5fa2a2fd45afafea45afafea" +
            "239c9cbf239c9cbf53a4a4f753a4a4f7e4727296e47272969bc0c05b9bc0c05b" +
            "75b7b7c275b7b7c2e1fdfd1ce1fdfd1c3d9393ae3d9393ae4c26266a4c26266a" +
            "6c36365a6c36365a7e3f3f417e3f3f41f5f7f702f5f7f70283cccc4f83cccc4f" +
            "6834345c6834345c51a5a5f451a5a5f4d1e5e534d1e5e534f9f1f108f9f1f108" +
            "e2717193e2717193abd8d873abd8d87362313153623131532a15153f2a15153f" +
            "0804040c0804040c95c7c75295c7c75246232365462323659dc3c35e9dc3c35e" +
            "3018182830181828379696a1379696a10a05050f0a05050f2f9a9ab52f9a9ab5" +
            "0e0707090e07070924121236241212361b80809b1b80809bdfe2e23ddfe2e23d" +
            "cdebeb26cdebeb264e2727694e2727697fb2b2cd7fb2b2cdea75759fea75759f" +
            "1209091b1209091b1d83839e1d83839e582c2c74582c2c74341a1a2e341a1a2e" +
            "361b1b2d361b1b2ddc6e6eb2dc6e6eb2b45a5aeeb45a5aee5ba0a0fb5ba0a0fb" +
            "a45252f6a45252f6763b3b4d763b3b4db7d6d661b7d6d6617db3b3ce7db3b3ce" +
            "5229297b5229297bdde3e33edde3e33e5e2f2f715e2f2f711384849713848497" +
            "a65353f5a65353f5b9d1d168b9d1d1680000000000000000c1eded2cc1eded2c" +
            "4020206040202060e3fcfc1fe3fcfc1f79b1b1c879b1b1c8b65b5bedb65b5bed" +
            "d46a6abed46a6abe8dcbcb468dcbcb4667bebed967bebed97239394b7239394b" +
            "944a4ade944a4ade984c4cd4984c4cd4b05858e8b05858e885cfcf4a85cfcf4a" +
            "bbd0d06bbbd0d06bc5efef2ac5efef2a4faaaae54faaaae5edfbfb16edfbfb16" +
            "864343c5864343c59a4d4dd79a4d4dd766333355663333551185859411858594" +
            "8a4545cf8a4545cfe9f9f910e9f9f9100402020604020206fe7f7f81fe7f7f81" +
            "a05050f0a05050f0783c3c44783c3c44259f9fba259f9fba4ba8a8e34ba8a8e3" +
            "a25151f3a25151f35da3a3fe5da3a3fe804040c0804040c0058f8f8a058f8f8a" +
            "3f9292ad3f9292ad219d9dbc219d9dbc7038384870383848f1f5f504f1f5f504" +
            "63bcbcdf63bcbcdf77b6b6c177b6b6c1afdada75afdada754221216342212163" +
            "2010103020101030e5ffff1ae5ffff1afdf3f30efdf3f30ebfd2d26dbfd2d26d" +
            "81cdcd4c81cdcd4c180c0c14180c0c142613133526131335c3ecec2fc3ecec2f" +
            "be5f5fe1be5f5fe1359797a2359797a2884444cc884444cc2e1717392e171739" +
            "93c4c45793c4c45755a7a7f255a7a7f2fc7e7e82fc7e7e827a3d3d477a3d3d47" +
            "c86464acc86464acba5d5de7ba5d5de73219192b3219192be6737395e6737395" +
            "c06060a0c06060a019818198198181989e4f4fd19e4f4fd1a3dcdc7fa3dcdc7f" +
            "4422226644222266542a2a7e542a2a7e3b9090ab3b9090ab0b8888830b888883" +
            "8c4646ca8c4646cac7eeee29c7eeee296bb8b8d36bb8b8d32814143c2814143c" +
            "a7dede79a7dede79bc5e5ee2bc5e5ee2160b0b1d160b0b1daddbdb76addbdb76" +
            "dbe0e03bdbe0e03b6432325664323256743a3a4e743a3a4e140a0a1e140a0a1e" +
            "924949db924949db0c06060a0c06060a4824246c4824246cb85c5ce4b85c5ce4" +
            "9fc2c25d9fc2c25dbdd3d36ebdd3d36e43acacef43acacefc46262a6c46262a6" +
            "399191a8399191a8319595a4319595a4d3e4e437d3e4e437f279798bf279798b" +
            "d5e7e732d5e7e7328bc8c8438bc8c8436e3737596e373759da6d6db7da6d6db7" +
            "018d8d8c018d8d8cb1d5d564b1d5d5649c4e4ed29c4e4ed249a9a9e049a9a9e0" +
            "d86c6cb4d86c6cb4ac5656faac5656faf3f4f407f3f4f407cfeaea25cfeaea25" +
            "ca6565afca6565aff47a7a8ef47a7a8e47aeaee947aeaee91008081810080818" +
            "6fbabad56fbabad5f0787888f07878884a25256f4a25256f5c2e2e725c2e2e72" +
            "381c1c24381c1c2457a6a6f157a6a6f173b4b4c773b4b4c797c6c65197c6c651" +
            "cbe8e823cbe8e823a1dddd7ca1dddd7ce874749ce874749c3e1f1f213e1f1f21" +
            "964b4bdd964b4bdd61bdbddc61bdbddc0d8b8b860d8b8b860f8a8a850f8a8a85" +
            "e0707090e07070907c3e3e427c3e3e4271b5b5c471b5b5c4cc6666aacc6666aa" +
            "904848d8904848d80603030506030305f7f6f601f7f6f6011c0e0e121c0e0e12" +
            "c26161a3c26161a36a35355f6a35355fae5757f9ae5757f969b9b9d069b9b9d0" +
            "178686911786869199c1c15899c1c1583a1d1d273a1d1d27279e9eb9279e9eb9" +
            "d9e1e138d9e1e138ebf8f813ebf8f8132b9898b32b9898b32211113322111133" +
            "d26969bbd26969bba9d9d970a9d9d970078e8e89078e8e89339494a7339494a7" +
            "2d9b9bb62d9b9bb63c1e1e223c1e1e221587879215878792c9e9e920c9e9e920" +
            "87cece4987cece49aa5555ffaa5555ff5028287850282878a5dfdf7aa5dfdf7a" +
            "038c8c8f038c8c8f59a1a1f859a1a1f809898980098989801a0d0d171a0d0d17" +
            "65bfbfda65bfbfdad7e6e631d7e6e631844242c6844242c6d06868b8d06868b8" +
            "824141c3824141c3299999b0299999b05a2d2d775a2d2d771e0f0f111e0f0f11" +
            "7bb0b0cb7bb0b0cba85454fca85454fc6dbbbbd66dbbbbd62c16163a2c16163a"
    );

    private byte[] currentMbox = MBOX5;
    private final Logger logger;

    public OpsDecryptor(Logger logger) {
        this.logger = logger;
    }

    private void log(String msg) {
        if (logger != null && msg != null && !msg.trim().isEmpty()) {
            logger.log(msg);
        }
    }

    public String decrypt(String opsFilePath, String outputDir) {
        if (opsFilePath == null) {
            return null;
        }
        File opsFile = new File(opsFilePath);
        if (!opsFile.exists()) {
            log("File not found: " + opsFilePath);
            return null;
        }
        File baseDir = opsFile.getParentFile();
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
        String xml = null;
        for (byte[] mbox : new byte[][]{MBOX5, MBOX6, MBOX4}) {
            currentMbox = mbox;
            xml = extractXml(opsFile);
            if (xml != null) {
                log("Found valid key");
                break;
            }
        }
        if (xml == null) {
            log("Unsupported key!");
            return null;
        }
        File xmlFile = new File(extractDir, "settings.xml");
        try (FileOutputStream out = new FileOutputStream(xmlFile)) {
            out.write(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("Failed to write settings.xml: " + e.getMessage());
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new BufferedInputStream(new FileInputStream(xmlFile)));
            Element root = doc.getDocumentElement();
            if (root == null) {
                log("Invalid settings.xml");
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
                if ("SAHARA".equalsIgnoreCase(tag)) {
                    // 仅取直接子 <File>(对齐参考)，getElementsByTagName 会递归取所有后代而误纳嵌套项
                    for (Element fileElem : directChildElements(child, "File")) {
                        String path = getAttr(fileElem, "Path");
                        if (path.isEmpty()) {
                            continue;
                        }
                        long start = parseLong(getAttr(fileElem, "FileOffsetInSrc")) * 0x200L;
                        long length = parseLong(getAttr(fileElem, "SizeInByteInSrc"));
                        decryptFile(opsFile, extractDir, path, start, length);
                    }
                } else if ("UFS_PROVISION".equalsIgnoreCase(tag)) {
                    for (Element fileElem : directChildElements(child, "File")) {
                        String path = getAttr(fileElem, "Path");
                        if (path.isEmpty()) {
                            continue;
                        }
                        long start = parseLong(getAttr(fileElem, "FileOffsetInSrc")) * 0x200L;
                        long length = parseLong(getAttr(fileElem, "SizeInByteInSrc"));
                        copyFile(opsFile, extractDir, path, start, length);
                    }
                } else if (tag.toLowerCase().contains("program")) {
                    NodeList programNodes = child.getChildNodes();
                    for (int j = 0; j < programNodes.getLength(); j++) {
                        Node programNode = programNodes.item(j);
                        if (!(programNode instanceof Element)) {
                            continue;
                        }
                        extractProgramItem((Element) programNode, opsFile, extractDir);
                    }
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log("XML parse failed: " + e.getMessage());
        }
        log("Done. Extracted files to " + extractDir.getAbsolutePath());
        return extractDir.getAbsolutePath();
    }

    // 只取 parent 的直接子元素中标签名(去命名空间)匹配 tagName 的项；不递归后代。
    private List<Element> directChildElements(Element parent, String tagName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element
                    && tagName.equalsIgnoreCase(stripNamespace(n.getNodeName()))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private void extractProgramItem(Element item, File srcFile, File destDir) {
        String filename = getAttr(item, "filename");
        if (!filename.isEmpty()) {
            long start = parseLong(getAttr(item, "FileOffsetInSrc")) * 0x200L;
            long length = parseLong(getAttr(item, "SizeInByteInSrc"));
            copyFile(srcFile, destDir, filename, start, length);
        }
        NodeList subs = item.getChildNodes();
        for (int i = 0; i < subs.getLength(); i++) {
            Node node = subs.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element sub = (Element) node;
            String subFile = getAttr(sub, "filename");
            if (subFile.isEmpty()) {
                continue;
            }
            long start = parseLong(getAttr(sub, "FileOffsetInSrc")) * 0x200L;
            long length = parseLong(getAttr(sub, "SizeInByteInSrc"));
            copyFile(srcFile, destDir, subFile, start, length);
        }
    }

    private String extractXml(File file) {
        long size = file.length();
        if (size < 0x200L) {
            return null;
        }
        byte[] hdr = new byte[0x200];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            long skip = size - 0x200L;
            if (skip > 0) {
                skipFully(in, skip);
            }
            readExactly(in, hdr, 0, hdr.length);
        } catch (IOException e) {
            return null;
        }
        int xmlLength = readIntLE(hdr, 0x18);
        // 截断/损坏/非 .ops 文件的尾页会给出负数或超文件长度的 xmlLength，分配前拦截，
        // 避免 new byte[] 抛 NegativeArraySizeException/OutOfMemoryError 崩溃整个解包流程
        // （OfpDecryptor.extractXml 已有同款守卫，此前 OPS 路径遗漏）。
        if (xmlLength <= 0 || xmlLength > size) {
            return null;
        }
        int xmlPad = 0x200 - (xmlLength % 0x200);
        if (xmlPad == 0x200) {
            xmlPad = 0;
        }
        int readLen = xmlLength + xmlPad;
        byte[] inp = new byte[readLen];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            long skip = size - 0x200L - readLen;
            if (skip > 0) {
                skipFully(in, skip);
            }
            readExactly(in, inp, 0, inp.length);
        } catch (IOException e) {
            return null;
        }
        byte[] outp = keyCustomDecrypt(inp);
        String result = new String(outp, 0, Math.min(outp.length, xmlLength), StandardCharsets.UTF_8);
        if (result.contains("xml ") || result.contains("<?xml")) {
            return result;
        }
        return null;
    }

    private byte[] keyCustomDecrypt(byte[] inp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(inp.length);
        int[] rkey = KEY.clone();
        int length = inp.length;
        // 对齐 opscrypto.py 的 key_custom(outlength=0)：数据 >= 16 字节时主循环按 ceil(length/16)
        // 个块用 mbox 解密，末尾不足 16 字节的块仍在主循环内处理（越界字节按 0 补齐），不切到 sbox
        // 尾部路径——参考实现里该 sbox 尾部对 outlength=0 的调用从不执行(length 在主循环后变负)。
        // 早期实现用 while(length>0x0f) 只跑整块、再把剩余 1-15 字节走 sbox，导致非 16 倍数长度的
        // 尾部(如多数 SAHARA loader)解密错误。仅当整段不足 16 字节时才走 sbox 尾部(同参考的 <=0xF 分支)。
        if (length > 0x0f) {
            int[] mbox = getMboxAsInt();
            for (int ptr = 0; ptr < length; ptr += 0x10) {
                rkey = keyUpdate(rkey, mbox);
                for (int i = 0; i < 4; i++) {
                    int word = readIntLEPadded(inp, ptr + i * 4);
                    int tmp = rkey[i] ^ word;
                    writeIntLE(out, tmp);
                    rkey[i] = word;
                }
            }
        } else if (length > 0) {
            int[] sboxMbox = getSboxMbox();
            rkey = keyUpdate(rkey, sboxMbox);
            int ptr = 0;
            int m = 0;
            while (length > 0) {
                int word = readIntLEPadded(inp, ptr);
                int value = word ^ rkey[m];
                writeIntLE(out, value);
                rkey[m] = word;
                length -= 4;
                ptr += 4;
                m++;
            }
        }
        return out.toByteArray();
    }

    // 小端读取 4 字节；越界字节按 0 补齐（对齐 opscrypto.py 末块/尾块 int.from_bytes 的零填充语义）
    private static int readIntLEPadded(byte[] data, int offset) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int idx = offset + i;
            if (idx >= 0 && idx < data.length) {
                value |= (data[idx] & 0xff) << (i * 8);
            }
        }
        return value;
    }

    // mbox/sbox 作为 key_update 的 asbox 时按【字节】索引（与 opscrypto.py 一致）：
    // asbox[i] 取第 i 个字节，轮数取 asbox[0x3c]。早期实现误把每 4 字节打包成 32 位字，
    // 使首轮 d=iv1[0]^asbox[0] 即偏离参考(mbox5 应为 0x9ee3b5b1)，导致 .ops 解密整体错误。
    private int[] getMboxAsInt() {
        int[] result = new int[currentMbox.length];
        for (int i = 0; i < currentMbox.length; i++) {
            result[i] = currentMbox[i] & 0xff;
        }
        return result;
    }

    private int[] getSboxMbox() {
        int[] result = new int[SBOX.length];
        for (int i = 0; i < SBOX.length; i++) {
            result[i] = SBOX[i] & 0xff;
        }
        return result;
    }

    private int gsBox(int offset) {
        if (offset < 0 || offset + 4 > SBOX.length) {
            return 0;
        }
        return readIntLE(SBOX, offset);
    }

    private int[] keyUpdate(int[] iv1, int[] asbox) {
        int d = iv1[0] ^ asbox[0];
        int a = iv1[1] ^ asbox[1];
        int b = iv1[2] ^ asbox[2];
        int c = iv1[3] ^ asbox[3];
        int e = gsBox(((b >>> 16) & 0xff) * 8 + 2)
                ^ gsBox(((a >>> 8) & 0xff) * 8 + 3)
                ^ gsBox((c >>> 24) * 8 + 1)
                ^ gsBox((d & 0xff) * 8)
                ^ asbox[4];
        int h = gsBox(((c >>> 16) & 0xff) * 8 + 2)
                ^ gsBox(((b >>> 8) & 0xff) * 8 + 3)
                ^ gsBox((d >>> 24) * 8 + 1)
                ^ gsBox((a & 0xff) * 8)
                ^ asbox[5];
        int i = gsBox(((d >>> 16) & 0xff) * 8 + 2)
                ^ gsBox(((c >>> 8) & 0xff) * 8 + 3)
                ^ gsBox((a >>> 24) * 8 + 1)
                ^ gsBox((b & 0xff) * 8)
                ^ asbox[6];
        a = gsBox(((d >>> 8) & 0xff) * 8 + 3)
                ^ gsBox(((a >>> 16) & 0xff) * 8 + 2)
                ^ gsBox((b >>> 24) * 8 + 1)
                ^ gsBox((c & 0xff) * 8)
                ^ asbox[7];
        int g = 8;
        int rounds = asbox.length > 0x3c ? asbox[0x3c] : 10;
        for (int f = 0; f < rounds - 2; f++) {
            int td = e >>> 24;
            int m = h >>> 16;
            int s = h >>> 24;
            int z = e >>> 16;
            int l = i >>> 24;
            int t = e >>> 8;
            e = gsBox(((i >>> 16) & 0xff) * 8 + 2)
                    ^ gsBox(((h >>> 8) & 0xff) * 8 + 3)
                    ^ gsBox((a >>> 24) * 8 + 1)
                    ^ gsBox((e & 0xff) * 8)
                    ^ (g < asbox.length ? asbox[g] : 0);
            h = gsBox(((a >>> 16) & 0xff) * 8 + 2)
                    ^ gsBox(((i >>> 8) & 0xff) * 8 + 3)
                    ^ gsBox((td * 8 + 1))
                    ^ gsBox((h & 0xff) * 8)
                    ^ (g + 1 < asbox.length ? asbox[g + 1] : 0);
            i = gsBox((z & 0xff) * 8 + 2)
                    ^ gsBox(((a >>> 8) & 0xff) * 8 + 3)
                    ^ gsBox((s * 8 + 1))
                    ^ gsBox((i & 0xff) * 8)
                    ^ (g + 2 < asbox.length ? asbox[g + 2] : 0);
            a = gsBox((t & 0xff) * 8 + 3)
                    ^ gsBox((m & 0xff) * 8 + 2)
                    ^ gsBox((l * 8 + 1))
                    ^ gsBox((a & 0xff) * 8)
                    ^ (g + 3 < asbox.length ? asbox[g + 3] : 0);
            g += 4;
        }
        return new int[]{
                (gsBox(((i >>> 16) & 0xff) * 8) & 0xff0000)
                        ^ (gsBox(((h >>> 8) & 0xff) * 8 + 1) & 0xff00)
                        ^ (gsBox((a >>> 24) * 8 + 3) & 0xff000000)
                        ^ (gsBox((e & 0xff) * 8 + 2) & 0xff)
                        ^ (g < asbox.length ? asbox[g] : 0),
                (gsBox(((a >>> 16) & 0xff) * 8) & 0xff0000)
                        ^ (gsBox(((i >>> 8) & 0xff) * 8 + 1) & 0xff00)
                        ^ (gsBox((e >>> 24) * 8 + 3) & 0xff000000)
                        ^ (gsBox((h & 0xff) * 8 + 2) & 0xff)
                        ^ (g + 3 < asbox.length ? asbox[g + 3] : 0),
                (gsBox(((e >>> 16) & 0xff) * 8) & 0xff0000)
                        ^ (gsBox(((a >>> 8) & 0xff) * 8 + 1) & 0xff00)
                        ^ (gsBox((h >>> 24) * 8 + 3) & 0xff000000)
                        ^ (gsBox((i & 0xff) * 8 + 2) & 0xff)
                        ^ (g + 2 < asbox.length ? asbox[g + 2] : 0),
                (gsBox(((h >>> 16) & 0xff) * 8) & 0xff0000)
                        ^ (gsBox(((e >>> 8) & 0xff) * 8 + 1) & 0xff00)
                        ^ (gsBox((i >>> 24) * 8 + 3) & 0xff000000)
                        ^ (gsBox((a & 0xff) * 8 + 2) & 0xff)
                        ^ (g + 1 < asbox.length ? asbox[g + 1] : 0)
        };
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

    private void decryptFile(File srcFile, File destDir, String filename, long start, long length) {
        log("Decrypting " + filename);
        File destFile = new File(destDir, filename);
        File parent = destFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        int padded = (int) (length + (4 - length % 4) % 4);
        byte[] data = new byte[padded];
        try (InputStream in = new BufferedInputStream(new FileInputStream(srcFile))) {
            if (start > 0) {
                skipFully(in, start);
            }
            readExactly(in, data, 0, (int) length);
        } catch (IOException e) {
            log("Decrypt read failed: " + e.getMessage());
            return;
        }
        byte[] outp = keyCustomDecrypt(data);
        try (FileOutputStream out = new FileOutputStream(destFile)) {
            out.write(outp, 0, (int) Math.min(outp.length, length));
        } catch (IOException e) {
            log("Decrypt write failed: " + e.getMessage());
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

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
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
}
