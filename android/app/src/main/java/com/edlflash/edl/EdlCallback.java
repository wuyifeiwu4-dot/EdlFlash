package com.edlflash.edl;

import java.util.List;

/**
 * 引擎输出事件接口：取代原 MainActivity 对 View 的写入 + Toast + 阻塞确认对话框。
 * 由 RN 原生模块实现并把事件转发到 JS。
 */
public interface EdlCallback {
    /** 追加一行日志；null 表示清屏（对应原 clearLog 末端）。 */
    void onLog(String line);

    /** 进度：percent=0..100；-1 表示不定态。 */
    void onProgress(int percent, String label);

    /** EDL / Root 设备状态文案。 */
    void onDeviceStatus(String status);

    /** 分区列表回填（GPT 读取后）。 */
    void onPartitions(List<PartitionOption> list);

    /** 破坏性持久化分区前的三选一确认，同步返回 CONTINUE/SKIP/CANCEL。 */
    int onConfirmPersist(String warning);

    /** 轻提示（外层可降级为 onLog）。 */
    void onToast(String msg);

    int CONFIRM_CONTINUE = 0;
    int CONFIRM_SKIP = 1;
    int CONFIRM_CANCEL = 2;
}
