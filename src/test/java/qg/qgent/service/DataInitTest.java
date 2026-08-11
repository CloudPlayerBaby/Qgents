package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

@SpringBootTest
public class DataInitTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void generateFixedProject() {
        // 固定用户信息和团队信息（从你刚才的授权信息里提取的）
        String userId = "019fef99-d2fe-7bd7-bf13-3c660459e250";
        String teamId = "9d83c012-f2cd-4336-8502-340a0b9061e1";
        
        // 创建一个固定的项目 ID
        String projectId = "11111111-1111-1111-1111-111111111111";
        String projectName = "我的测试绑定项目";
        
        // 1. 如果该项目不存在，先插入项目表 (使用 UNHEX(REPLACE(uuid, '-', '')) 转换为 BINARY(16))
        String insertProjectSql = "INSERT IGNORE INTO projects (id, team_id, name, description, created_by, created_at, updated_at) " +
                                  "VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), ?, '用于测试 GitHub 仓库绑定的固定项目', UNHEX(REPLACE(?, '-', '')), NOW(), NOW())";
        jdbcTemplate.update(insertProjectSql, projectId, teamId, projectName, userId);

        // 2. 将当前用户设置为该项目的管理员 (PROJECT_ADMIN)
        String insertMemberSql = "INSERT IGNORE INTO project_members (project_id, user_id, role) VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), 'PROJECT_ADMIN')";
        jdbcTemplate.update(insertMemberSql, projectId, userId);
        
        System.out.println("✅ 固定项目生成成功！");
        System.out.println("项目 ID (projectId): " + projectId);
        System.out.println("团队 ID (teamId): " + teamId);
        System.out.println("请在 Apifox 中使用该 Project ID 进行后续的仓库绑定测试！");
    }
}
