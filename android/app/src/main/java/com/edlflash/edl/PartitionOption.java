package com.edlflash.edl;

/** 分区选项（从原 MainActivity 内部类抽出为公共类，供引擎与桥接共用）。 */
public class PartitionOption {
    public final String name;
    public final String lun;
    public final String label;
    // 分区几何（来自 GPT 或 rawprogram），供前端显示容量与扇区详情；未知时为 null
    public final String startSector;
    public final String numSectors;
    public final String sectorSize;

    public PartitionOption(String name, String lun) {
        this(name, lun, null, null, null);
    }

    public PartitionOption(String name, String lun, String startSector, String numSectors, String sectorSize) {
        this.name = name;
        this.lun = lun != null ? lun : "0";
        this.label = "LUN" + this.lun + " / " + name;
        this.startSector = startSector;
        this.numSectors = numSectors;
        this.sectorSize = sectorSize;
    }
}
