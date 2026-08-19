package qg.qgent.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 全局时间序列化配置：数据库时间统一以 UTC 存储（{@code LocalDateTime.now(ZoneOffset.UTC)}），
 * 但 Jackson 对 {@link LocalDateTime} 的默认序列化是无时区偏移的 ISO 字符串
 * （如 {@code 2026-08-18T15:26:31}），前端 {@code new Date(...)} 会按浏览器本地时区解析，
 * 导致中国（UTC+8）展示比真实时刻少 8 小时。
 * <p>
 * 本配置把 {@link LocalDateTime} 统一序列化为带 {@code Z} 后缀的 UTC ISO8601
 * （与各 Service 的 {@code iso()} 工具一致），前端 {@code new Date("...Z")} 即可正确转本地时区。
 * <p>
 * 注意：{@link LocalDateTime} 语义上无时区，项目约定其值恒为 UTC 时刻，故用
 * {@code toInstant(ZoneOffset.UTC)} 转 Instant 输出。
 */
@Configuration
public class JacksonTimeConfiguration {

    /**
     * LocalDateTime → 带 Z 的 UTC ISO8601（如 {@code 2026-08-18T15:26:31.990054Z}）。
     * SecurityConfig 的全局 ObjectMapper 注册本模块，所有接口响应统一生效。
     */
    @Bean
    SimpleModule utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("qgents-utc-localdatetime");
        module.addSerializer(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(value.toInstant(ZoneOffset.UTC).toString());
            }
        });
        return module;
    }
}
