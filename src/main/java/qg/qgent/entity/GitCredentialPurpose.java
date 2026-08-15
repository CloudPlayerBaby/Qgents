package qg.qgent.entity;

/**
 * 一次性 Git 凭据允许执行的唯一远程操作。
 */
public enum GitCredentialPurpose {
    /**
     * 从远程读取指定分支。
     */
    FETCH,
    /**
     * 向远程推送指定分支。
     */
    PUSH
}
