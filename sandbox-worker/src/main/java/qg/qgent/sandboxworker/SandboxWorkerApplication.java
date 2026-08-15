package qg.qgent.sandboxworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动可独立部署的沙箱工作节点执行面。
 */
@SpringBootApplication
public class SandboxWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxWorkerApplication.class, args);
    }
}
