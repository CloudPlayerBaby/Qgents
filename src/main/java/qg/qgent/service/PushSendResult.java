package qg.qgent.service;

/** 推送提供方已受理的真实结果。 */
public class PushSendResult {
    private final String providerMessageId;

    public PushSendResult(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }
}
