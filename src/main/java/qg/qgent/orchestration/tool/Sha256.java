package qg.qgent.orchestration.tool;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 计算文件原始字节 SHA-256 的小工具（小写十六进制），供本地 Workspace 端口实现使用，
 * 口径与 sandbox-worker 的 FileReadTool.sha256 一致。
 */
final class Sha256 {

    private Sha256() {
    }

    static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("计算文件哈希失败", exception);
        }
    }
}
