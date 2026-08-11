package qg.qgent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import qg.qgent.dto.AuthTokensResponse;
import qg.qgent.dto.LoginRequest;
import qg.qgent.dto.RegisterRequest;

import javax.crypto.Cipher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class FixedAccountAuthIntegrationTest {

    @LocalServerPort
    private int port;

    @Value("${app.rsa-private-key}")
    private Resource privateKeyResource;

    @Value("${app.rsa-key-id}")
    private String rsaKeyId;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testFixedAccountAuthFlow() throws Exception {
        // 1. 设置固定测试账号
        String fixedEmail = "dev_fixed_user@qgents.com";
        String rawPassword = "password123456";
        
        // 读取私钥，并动态推导出公钥，用于给密码加密
        String pem = new String(privateKeyResource.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] privKeyBytes = Base64.getDecoder().decode(pem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privKeyBytes));
        RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privKey;
        PublicKey pubKey = kf.generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encrypted = cipher.doFinal(rawPassword.getBytes(StandardCharsets.UTF_8));
        String encryptedPassword = Base64.getEncoder().encodeToString(encrypted);

        HttpClient httpClient = HttpClient.newHttpClient();

        // 2. 尝试注册（如果已存在则忽略失败）
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setEmail(fixedEmail);
        registerReq.setDisplayName("Fixed Dev User");
        registerReq.setPasswordKeyId(rsaKeyId);
        registerReq.setPassword(encryptedPassword);

        HttpRequest registerRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(registerReq)))
                .build();
        httpClient.send(registerRequest, HttpResponse.BodyHandlers.ofString());
        
        // 3. 执行登录获取 Token
        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail(fixedEmail);
        loginReq.setPasswordKeyId(rsaKeyId);
        loginReq.setPassword(encryptedPassword);

        HttpRequest loginRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(loginReq)))
                .build();

        HttpResponse<String> loginResponse = httpClient.send(loginRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, loginResponse.statusCode(), "固定账号登录失败");
        
        JsonNode loginRoot = objectMapper.readTree(loginResponse.body());
        AuthTokensResponse loginTokens = objectMapper.treeToValue(loginRoot.get("data"), AuthTokensResponse.class);
        
        // 4. 为该固定账号检查并创建团队
        byte[] userIdBytes = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", byte[].class, fixedEmail);
        Assertions.assertNotNull(userIdBytes, "用户应该存在");

        byte[] teamIdBytes = null;
        try {
            teamIdBytes = jdbcTemplate.queryForObject("SELECT team_id FROM team_members WHERE user_id = ? LIMIT 1", byte[].class, userIdBytes);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // 没有团队，创建一个
            UUID newTeamUuid = UUID.randomUUID();
            teamIdBytes = new byte[16];
            java.nio.ByteBuffer.wrap(teamIdBytes)
                    .putLong(newTeamUuid.getMostSignificantBits())
                    .putLong(newTeamUuid.getLeastSignificantBits());
                    
            jdbcTemplate.update("INSERT INTO teams (id, owner_user_id, name) VALUES (?, ?, ?)", 
                    teamIdBytes, userIdBytes, "Fixed Dev Team");
            jdbcTemplate.update("INSERT INTO team_members (team_id, user_id, role) VALUES (?, ?, ?)", 
                    teamIdBytes, userIdBytes, "TEAM_OWNER");
        }
        
        // 还原 UUID 以打印
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(teamIdBytes);
        UUID teamUuid = new UUID(bb.getLong(), bb.getLong());

        System.out.println("\n\n=======================================================");
        System.out.println("🚀🚀🚀 [开发环境固定账号] 登录成功！用于 Apifox 测试：");
        System.out.println("-------------------------------------------------------");
        System.out.println("邮箱 (Email): " + fixedEmail);
        System.out.println("密码 (Password): " + rawPassword);
        System.out.println("团队 UUID (teamId): " + teamUuid);
        System.out.println("-------------------------------------------------------");
        System.out.println("👉 Access Token (放到请求头 Authorization: Bearer xxx 中):");
        System.out.println(loginTokens.getAccessToken());
        System.out.println("=======================================================\n\n");
    }
}
