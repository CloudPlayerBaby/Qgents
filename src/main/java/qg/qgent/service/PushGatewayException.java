package qg.qgent.service;

/** 脱敏后的推送提供方失败；不得携带 Token 或原始响应。 */
public class PushGatewayException extends RuntimeException {
    private final String code;
    private final boolean invalidToken;

    public PushGatewayException(String code, boolean invalidToken) {
        super(code);
        this.code = code;
        this.invalidToken = invalidToken;
    }

    public String getCode() { return code; }
    public boolean isInvalidToken() { return invalidToken; }
}
