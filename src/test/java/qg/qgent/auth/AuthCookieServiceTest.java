package qg.qgent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import qg.qgent.dto.AuthTokensResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 浏览器认证 Cookie 的安全属性与精确清理测试。 */
class AuthCookieServiceTest {

    @Test
    void writesHostOnlyStrictHttpOnlyCookiesWithSeparatedPaths() {
        AuthCookieService service = new AuthCookieService(true, new StandardEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.writeSession(response, new AuthTokensResponse("access", 900, "refresh", 2592000, null));

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies.get(0)).contains("qgents_access_token=access", "Path=/api/v1", "Max-Age=900",
                "HttpOnly", "Secure", "SameSite=Strict").doesNotContain("Domain=");
        assertThat(cookies.get(1)).contains("qgents_refresh_token=refresh", "Path=/api/v1/auth", "Max-Age=2592000",
                "HttpOnly", "Secure", "SameSite=Strict").doesNotContain("Domain=");
    }

    @Test
    void clearsEveryCookieUsingItsOriginalPath() {
        AuthCookieService service = new AuthCookieService(true, new StandardEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clearSession(response);

        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(value ->
                assertThat(value).contains("qgents_access_token=", "Path=/api/v1", "Max-Age=0"));
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(value ->
                assertThat(value).contains("qgents_refresh_token=", "Path=/api/v1/auth", "Max-Age=0"));
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(value ->
                assertThat(value).contains("qgents_csrf_token=", "Path=/api/v1", "Max-Age=0"));
    }
}
