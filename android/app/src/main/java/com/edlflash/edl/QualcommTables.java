package com.edlflash.edl;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class QualcommTables {
    static final long[][] SECGEN = {
        new long[]{0x0, 0x0, 0x1900000, 0x100000, 0x0, 0x0},
        new long[]{0x0, 0x0, 0x1E20000, 0x1000, 0x0, 0x0},
        new long[]{0xFC010000, 0x18000, 0xFC4B8000, 0x60F0, 0x200000, 0x18000},
        new long[]{0x100000, 0x1FFB0, 0x70000, 0x6158, 0x200000, 0x18000},
        new long[]{0x100000, 0x1FFB0, 0x58000, 0x1000, 0x200000, 0x18000},
        new long[]{0x100000, 0x1FFB0, 0xA0000, 0x6FFF, 0x200000, 0x18000},
        new long[]{0x100000, 0x1FFB0, 0x700000, 0x6158, 0x200000, 0x18000},
        new long[]{0x300000, 0x3C000, 0x780000, 0x10000, 0x14009003, 0x18000},
        new long[]{0x300000, 0x3C000, 0x1B40000, 0x10000, 0x0, 0x0},
    };

    static final Map<String, Integer> INFO_INDEX = new HashMap<>();
    static final Map<String, Long> SECUREBOOT = new HashMap<>();

    static {
        INFO_INDEX.put("apq8009", 4);
        INFO_INDEX.put("apq8009w", 4);
        INFO_INDEX.put("apq8016", 4);
        INFO_INDEX.put("apq8017", 5);
        INFO_INDEX.put("apq8036", 4);
        INFO_INDEX.put("apq8037", 5);
        INFO_INDEX.put("apq8039", 4);
        INFO_INDEX.put("apq8053", 5);
        INFO_INDEX.put("apq8056", 5);
        INFO_INDEX.put("apq806x", 6);
        INFO_INDEX.put("apq8074", 2);
        INFO_INDEX.put("apq8076", 5);
        INFO_INDEX.put("apq8084", 2);
        INFO_INDEX.put("apq8092", 2);
        INFO_INDEX.put("apq8098", 7);
        INFO_INDEX.put("ipq4018", 4);
        INFO_INDEX.put("ipq4019", 4);
        INFO_INDEX.put("mdm9207", 5);
        INFO_INDEX.put("mdm9250", 5);
        INFO_INDEX.put("mdm9350", 5);
        INFO_INDEX.put("mdm9607", 5);
        INFO_INDEX.put("mdm9650", 5);
        INFO_INDEX.put("mdm9x25", 2);
        INFO_INDEX.put("mdm9x35", 2);
        INFO_INDEX.put("mdm9x40", 4);
        INFO_INDEX.put("mdm9x45", 4);
        INFO_INDEX.put("mdm9x50", 5);
        INFO_INDEX.put("mdm9x55", 5);
        INFO_INDEX.put("mdm9x60", 5);
        INFO_INDEX.put("mdm9x65", 5);
        INFO_INDEX.put("msm8905", 4);
        INFO_INDEX.put("msm8909", 4);
        INFO_INDEX.put("msm8909w", 4);
        INFO_INDEX.put("msm8916", 4);
        INFO_INDEX.put("msm8917", 5);
        INFO_INDEX.put("msm8920", 5);
        INFO_INDEX.put("msm8929", 4);
        INFO_INDEX.put("msm8930", 6);
        INFO_INDEX.put("msm8936", 6);
        INFO_INDEX.put("msm8937", 5);
        INFO_INDEX.put("msm8939", 4);
        INFO_INDEX.put("msm8940", 5);
        INFO_INDEX.put("msm8952", 4);
        INFO_INDEX.put("msm8953", 5);
        INFO_INDEX.put("msm8956", 5);
        INFO_INDEX.put("msm8962", 2);
        INFO_INDEX.put("msm8974", 2);
        INFO_INDEX.put("msm8974ab", 2);
        INFO_INDEX.put("msm8974abv3", 2);
        INFO_INDEX.put("msm8974ac", 2);
        INFO_INDEX.put("msm8974pro", 2);
        INFO_INDEX.put("msm8976", 5);
        INFO_INDEX.put("msm8992", 2);
        INFO_INDEX.put("msm8994", 2);
        INFO_INDEX.put("msm8996", 3);
        INFO_INDEX.put("msm8996au", 3);
        INFO_INDEX.put("msm8996pro", 3);
        INFO_INDEX.put("msm8998", 7);
        INFO_INDEX.put("msm9206", 5);
        INFO_INDEX.put("qca6290", 1);
        INFO_INDEX.put("qca6390", 1);
        INFO_INDEX.put("qca6480", 1);
        INFO_INDEX.put("qca6481", 1);
        INFO_INDEX.put("qca6490", 1);
        INFO_INDEX.put("qca6491", 1);
        INFO_INDEX.put("qcs605", 7);
        INFO_INDEX.put("qdf2432", 0);
        INFO_INDEX.put("sa2150p", 7);
        INFO_INDEX.put("sa515m", 7);
        INFO_INDEX.put("sa8295p", 8);
        INFO_INDEX.put("sa8540p", 8);
        INFO_INDEX.put("sc8280x", 8);
        INFO_INDEX.put("sda632", 5);
        INFO_INDEX.put("sda670", 7);
        INFO_INDEX.put("sda845", 7);
        INFO_INDEX.put("sdm429", 5);
        INFO_INDEX.put("sdm439", 5);
        INFO_INDEX.put("sdm450", 5);
        INFO_INDEX.put("sdm630", 7);
        INFO_INDEX.put("sdm632", 5);
        INFO_INDEX.put("sdm636", 7);
        INFO_INDEX.put("sdm660", 7);
        INFO_INDEX.put("sdm662", 8);
        INFO_INDEX.put("sdm670", 7);
        INFO_INDEX.put("sdm710", 7);
        INFO_INDEX.put("sdm845", 7);
        INFO_INDEX.put("sdm850", 7);
        INFO_INDEX.put("sdx24", 7);
        INFO_INDEX.put("sdx24m", 7);
        INFO_INDEX.put("sdx50m", 5);
        INFO_INDEX.put("sdx55:cd90-pg591", 7);
        INFO_INDEX.put("sdx55:cd90-ph809", 7);
        INFO_INDEX.put("sdx55m", 7);
        INFO_INDEX.put("sm6150", 7);
        INFO_INDEX.put("sm6150_iot_high", 7);
        INFO_INDEX.put("sm6150_iot_low", 7);
        INFO_INDEX.put("sm6150p", 7);
        INFO_INDEX.put("sm6155", 7);
        INFO_INDEX.put("sm6155p", 7);
        INFO_INDEX.put("sm7150", 7);
        INFO_INDEX.put("sm7150p", 7);
        INFO_INDEX.put("sm8150", 7);
        INFO_INDEX.put("sm8150p", 7);
        INFO_INDEX.put("sm8250", 7);
        INFO_INDEX.put("sm8250:cd90-ph805-1a", 7);
        INFO_INDEX.put("sm8250:cd90-ph806-1a", 7);
        INFO_INDEX.put("sm8250p", 7);
        INFO_INDEX.put("sxr1120", 7);
        INFO_INDEX.put("sxr1130", 7);
        INFO_INDEX.put("wcn7850", 7);
        INFO_INDEX.put("wcn7851", 7);
        INFO_INDEX.put("agatti", 8);
        INFO_INDEX.put("agatti_mdm", 8);
        INFO_INDEX.put("agatti_mdm_iot", 8);
        INFO_INDEX.put("bitra", 7);
        INFO_INDEX.put("cedros", 7);
        INFO_INDEX.put("chitwan", 7);
        INFO_INDEX.put("divar", 8);
        INFO_INDEX.put("ipq5018", 5);
        INFO_INDEX.put("ipq6018", 5);
        INFO_INDEX.put("kamorta", 8);
        INFO_INDEX.put("kamorta_iot_apq", 8);
        INFO_INDEX.put("kamorta_iot_modem", 8);
        INFO_INDEX.put("kamorta_p", 8);
        INFO_INDEX.put("lahaina", 7);
        INFO_INDEX.put("lahaina_premier", 7);
        INFO_INDEX.put("mannar", 7);
        INFO_INDEX.put("mannar_p", 7);
        INFO_INDEX.put("nicobar", 8);
        INFO_INDEX.put("nicobar_iot_apq", 8);
        INFO_INDEX.put("nicobar_iot_modem", 8);
        INFO_INDEX.put("olympic_v1", 7);
        INFO_INDEX.put("olympic_v1_hybrid", 7);
        INFO_INDEX.put("olympicle", 8);
        INFO_INDEX.put("qcs2290", 8);
        INFO_INDEX.put("qcs401", 5);
        INFO_INDEX.put("qcs403", 5);
        INFO_INDEX.put("qcs405", 5);
        INFO_INDEX.put("qcs407", 5);
        INFO_INDEX.put("qm215", 5);
        INFO_INDEX.put("rennell", 7);
        INFO_INDEX.put("rennell_premier", 7);
        INFO_INDEX.put("rennell_v1.1", 7);
        INFO_INDEX.put("saipan", 7);
        INFO_INDEX.put("sc7180", 7);
        INFO_INDEX.put("sc7280", 8);
        INFO_INDEX.put("sc8180x", 7);
        INFO_INDEX.put("sd7250", 7);
        INFO_INDEX.put("sm7235", 8);
        INFO_INDEX.put("sm7295", 8);
        INFO_INDEX.put("strait", 8);
        SECUREBOOT.put("apq8009", 0x58098L);
        SECUREBOOT.put("apq8009w", 0x58098L);
        SECUREBOOT.put("apq8016", 0x58098L);
        SECUREBOOT.put("apq8036", 0x58098L);
        SECUREBOOT.put("apq8037", 0xA01D0L);
        SECUREBOOT.put("apq8039", 0x58098L);
        SECUREBOOT.put("apq8052", 0x58098L);
        SECUREBOOT.put("apq8053", 0xA01D0L);
        SECUREBOOT.put("apq8056", 0xA01D0L);
        SECUREBOOT.put("apq8074", 0xFC4B83F8L);
        SECUREBOOT.put("apq8076", 0xA01D0L);
        SECUREBOOT.put("apq8084", 0xFC4B83F8L);
        SECUREBOOT.put("apq8092", 0xFC4B83F8L);
        SECUREBOOT.put("apq8094", 0xFC4B83F8L);
        SECUREBOOT.put("apq8098", 0x780350L);
        SECUREBOOT.put("ipq4018", 0x58098L);
        SECUREBOOT.put("ipq4019", 0x58098L);
        SECUREBOOT.put("mdm9205", 0xA0320L);
        SECUREBOOT.put("mdm9206_mdm9207tx", 0xA01D0L);
        SECUREBOOT.put("mdm9207", 0xA01D0L);
        SECUREBOOT.put("mdm9250", 0xA01D0L);
        SECUREBOOT.put("mdm9350", 0xA01D0L);
        SECUREBOOT.put("mdm9607", 0xA01D0L);
        SECUREBOOT.put("mdm9650", 0xA01D0L);
        SECUREBOOT.put("mdm9x25", 0xFC4B6028L);
        SECUREBOOT.put("mdm9x30", 0xFC4B6028L);
        SECUREBOOT.put("mdm9x35", 0xFC4B6028L);
        SECUREBOOT.put("mdm9x40", 0x58098L);
        SECUREBOOT.put("mdm9x45", 0x58098L);
        SECUREBOOT.put("mdm9x50", 0xA01D0L);
        SECUREBOOT.put("mdm9x55", 0xA01D0L);
        SECUREBOOT.put("mdm9x60", 0xA01D0L);
        SECUREBOOT.put("mdm9x65", 0xA01D0L);
        SECUREBOOT.put("msm8226", 0xFC4B83E8L);
        SECUREBOOT.put("msm8610", 0xFC4B83E8L);
        SECUREBOOT.put("msm8905", 0x58098L);
        SECUREBOOT.put("msm8909", 0x58098L);
        SECUREBOOT.put("msm8909w", 0x58098L);
        SECUREBOOT.put("msm8916", 0x58098L);
        SECUREBOOT.put("msm8917", 0xA01D0L);
        SECUREBOOT.put("msm8920", 0xA01D0L);
        SECUREBOOT.put("msm8929", 0x58098L);
        SECUREBOOT.put("msm8930", 0x700310L);
        SECUREBOOT.put("msm8936", 0x700310L);
        SECUREBOOT.put("msm8937", 0xA01D0L);
        SECUREBOOT.put("msm8939", 0x58098L);
        SECUREBOOT.put("msm8940", 0xA01D0L);
        SECUREBOOT.put("msm8952", 0x58098L);
        SECUREBOOT.put("msm8953", 0xA01D0L);
        SECUREBOOT.put("msm8956", 0xA01D0L);
        SECUREBOOT.put("msm8974", 0xFC4B83F8L);
        SECUREBOOT.put("msm8974ab", 0xFC4B83F8L);
        SECUREBOOT.put("msm8974abv3", 0xFC4B83F8L);
        SECUREBOOT.put("msm8974ac", 0xFC4B83F8L);
        SECUREBOOT.put("msm8976", 0xA01D0L);
        SECUREBOOT.put("msm8992", 0xFC4B83F8L);
        SECUREBOOT.put("msm8994", 0xFC4B83F8L);
        SECUREBOOT.put("msm8996", 0x70378L);
        SECUREBOOT.put("msm8996au", 0x70378L);
        SECUREBOOT.put("msm8996pro", 0x70378L);
        SECUREBOOT.put("msm8998_sdm835", 0x780350L);
        SECUREBOOT.put("qca6290", 0x1E20030L);
        SECUREBOOT.put("qca6390", 0x1E20010L);
        SECUREBOOT.put("qcs605", 0x780350L);
        SECUREBOOT.put("qdf2432", 0x19018C8L);
        SECUREBOOT.put("sa2150p", 0x7804D0L);
        SECUREBOOT.put("sa515m", 0x7804D0L);
        SECUREBOOT.put("sda632", 0xA01D0L);
        SECUREBOOT.put("sda670", 0x780350L);
        SECUREBOOT.put("sda845", 0x780350L);
        SECUREBOOT.put("sdm429", 0xA01D0L);
        SECUREBOOT.put("sdm439", 0xA01D0L);
        SECUREBOOT.put("sdm450", 0xA01D0L);
        SECUREBOOT.put("sdm630", 0x780350L);
        SECUREBOOT.put("sdm632", 0xA01D0L);
        SECUREBOOT.put("sdm636", 0x780350L);
        SECUREBOOT.put("sdm660", 0x780350L);
        SECUREBOOT.put("sdm662", 0x1B40458L);
        SECUREBOOT.put("sdm670", 0x780350L);
        SECUREBOOT.put("sdm710", 0x780350L);
        SECUREBOOT.put("sdm845", 0x780350L);
        SECUREBOOT.put("sdx24", 0x780390L);
        SECUREBOOT.put("sdx24m", 0x780390L);
        SECUREBOOT.put("sdx50m", 0xA01E0L);
        SECUREBOOT.put("sdx55:cd90-pg591", 0x7805E8L);
        SECUREBOOT.put("sdx55:cd90-ph809", 0x7805E8L);
        SECUREBOOT.put("sdx55m", 0x7804D0L);
        SECUREBOOT.put("sm6150", 0x780360L);
        SECUREBOOT.put("sm6150_iot_high", 0x780360L);
        SECUREBOOT.put("sm6150_iot_low", 0x780360L);
        SECUREBOOT.put("sm6150p", 0x780360L);
        SECUREBOOT.put("sm6155", 0x780360L);
        SECUREBOOT.put("sm6155p", 0x780360L);
        SECUREBOOT.put("sm7150", 0x780460L);
        SECUREBOOT.put("sm7150p", 0x780460L);
        SECUREBOOT.put("sm8150", 0x7804D0L);
        SECUREBOOT.put("sm8150p", 0x7804D0L);
        SECUREBOOT.put("sm8250:cd90-ph805-1a", 0x7805E8L);
        SECUREBOOT.put("sm8250:cd90-ph806-1a", 0x7805E8L);
        SECUREBOOT.put("sxr1120", 0x780350L);
        SECUREBOOT.put("sxr1130", 0x780350L);
        SECUREBOOT.put("agatti", 0x1B40458L);
        SECUREBOOT.put("bitra", 0x7804D8L);
        SECUREBOOT.put("bitra_sda", 0x7804D8L);
        SECUREBOOT.put("bitra_sdm", 0x7804D8L);
        SECUREBOOT.put("cedros", 0x780728L);
        SECUREBOOT.put("chitwan", 0x780668L);
        SECUREBOOT.put("divar", 0x1B40458L);
        SECUREBOOT.put("ipq5018", 0xA01D0L);
        SECUREBOOT.put("ipq6018", 0xA01D0L);
        SECUREBOOT.put("kamorta", 0x1B40458L);
        SECUREBOOT.put("kamorta_iot_apq", 0x1B40458L);
        SECUREBOOT.put("kamorta_iot_modem", 0x1B40458L);
        SECUREBOOT.put("kamorta_p", 0x1B40458L);
        SECUREBOOT.put("lahaina", 0x780668L);
        SECUREBOOT.put("lahaina_premier", 0x780668L);
        SECUREBOOT.put("mannar", 0x1B40458L);
        SECUREBOOT.put("mannar_p", 0x1B40458L);
        SECUREBOOT.put("nicobar", 0x1B40458L);
        SECUREBOOT.put("qcs2290", 0x1B40458L);
        SECUREBOOT.put("qcs401", 0xA0310L);
        SECUREBOOT.put("qcs403", 0xA0310L);
        SECUREBOOT.put("qcs404", 0xA0310L);
        SECUREBOOT.put("qcs405", 0xA0310L);
        SECUREBOOT.put("qcs407", 0xA0310L);
        SECUREBOOT.put("qm215", 0xA01D0L);
        SECUREBOOT.put("rennell", 0x780498L);
        SECUREBOOT.put("rennell_v1.1", 0x780498L);
        SECUREBOOT.put("rennell_premier", 0x780498L);
        SECUREBOOT.put("saipan", 0x7805E8L);
        SECUREBOOT.put("sc7180", 0x780498L);
        SECUREBOOT.put("sc8180x", 0x7805E8L);
        SECUREBOOT.put("sd7250", 0x7805E8L);
    }

    static String normalizeTarget(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.toLowerCase(Locale.US);
    }

    static MemRange getInfoRange(String target, int typeIndex) {
        String key = normalizeTarget(target);
        if (key == null) {
            return null;
        }
        Integer idx = INFO_INDEX.get(key);
        if (idx == null || idx < 0 || idx >= SECGEN.length) {
            return null;
        }
        long[] vals = SECGEN[idx];
        int base = typeIndex * 2;
        if (base < 0 || base + 1 >= vals.length) {
            return null;
        }
        long addr = vals[base];
        long size = vals[base + 1];
        if (addr <= 0 || size <= 0) {
            return null;
        }
        return new MemRange(addr, size);
    }

    static Long getSecurebootAddr(String target) {
        String key = normalizeTarget(target);
        if (key == null) {
            return null;
        }
        return SECUREBOOT.get(key);
    }

    static final class MemRange {
        final long addr;
        final long size;

        MemRange(long addr, long size) {
            this.addr = addr;
            this.size = size;
        }
    }
}
