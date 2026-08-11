package qg.qgent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

// 不用这个默认的配置
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableAsync
public class QgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(QgentsApplication.class, args);
    }

}
