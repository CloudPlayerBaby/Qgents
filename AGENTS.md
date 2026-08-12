# AGENTS.md

> Qgents Coding Agent 协作规范
>
> 版本：v1.0
>
> 更新日期：2026-08-12

## 1. 文档作用

本文件约束本仓库及其子目录中的 Coding Agent，目标是让每次修改安全、可验证、可审查、可追踪。

- 子目录可用更近的 `AGENTS.md` 补充模块规则，但不得放宽本文件的安全、权限、隔离和真实性要求。
- 本文件规定长期稳定的工作方式和业务边界，不代替产品文档、技术架构或 API 契约。
- 代码、数据库、文档和测试必须使用同一套领域术语，不为旧模型保留兼容概念。

## 2. 权威来源

出现冲突时，按以下顺序处理：

1. 用户当前明确指令
2. 最新且已确认的业务或 API 契约
3. 当前目录作用域内最近的 `AGENTS.md`
4. 项目技术文档
5. 项目产品文档
6. 现有代码与测试
7. Agent 推测

安全、权限、项目隔离、凭证保护和结果真实性是不可覆盖的底线。

- 草案或未定案内容不能当作已确认契约。
- 不猜测未定义的 API、字段、枚举、权限或基础设施行为。
- 文档明显冲突时，停止受影响部分并请求确认，不自行发明第三套规则。
- 不为了代码更整洁而改变已确认的业务语义。

## 3. 当前领域模型

### 3.1 隔离边界

- Team 是最高协作边界。
- Project 是群聊、任务、Workspace、Repository、Skill、Memory、Diff 和 MR 的主要隔离边界。
- 不同 Project 之间不得共享上下文、Workspace、worktree、Diff、MR、Skill 或 Memory。
- 授权由服务端依据认证身份、团队成员关系、项目成员关系和资源归属判断。
- 不得信任客户端提交的 `userId`、`role`、`ownerId`、`admin` 等字段决定权限。

Team 的唯一 canonical Owner 由 `teams.owner_user_id` 指定，其成员角色必须为 `TEAM_OWNER`。普通成员管理接口不得转移、降级或删除 canonical Owner。

### 3.2 Task 执行层级

唯一执行层级为：

```text
Task -> TaskStep -> TaskRun
```

- Task：用户在项目群中发起的一次完整需求。
- TaskStep：Planner 拆出的内部执行步骤，包含依赖、角色、仓库范围和验收条件。
- TaskRun：某个 TaskStep 的一次执行尝试；重试创建新的 TaskRun。
- TaskStep 和 TaskRun 只记录内部计划、状态、日志、输入和验证结果，不单独向用户产出 Diff 或 MR。
- 用户可见的代码结果归属于整个 Task，在 Task 完成后返回。

禁止重新引入以下旧模型及其表、字段、实体、DTO、状态或 API：

- Deliverable
- TaskDelivery
- WorkPackage
- SubTask
- TaskRunStep
- 与上述模型绑定的 orchestration 兼容层

### 3.3 Workspace 与 Repository

- Workspace 是 Project 内持久化的开发现场，不从属于单个 Task。
- 每个 Task 必须关联且只关联一个 Workspace。
- 新 Task 可以创建新 Workspace，也可以显式引用前序 Task 复用同一 Workspace。
- 复用时必须校验前序 Task、Workspace 和当前 Task 属于同一 Project；不得仅凭聊天上下文静默复用。
- 一个 Workspace 可包含多个 Repository worktree；worktree 归属于 Workspace，不归属于 Task。
- Sandbox 是临时执行环境。Sandbox 销毁后，未提交的 Workspace 修改仍必须保留。
- 同一 Workspace 同一时刻只能有一个有效写入者。写入租约必须通过持久状态、数据库约束或锁保证，不能只依赖进程内判断。
- Workspace repository 必须记录真实 source branch、base commit、head commit，以及 head commit 的来源 Task。

Requirement Group 是讨论与上下文边界，不是 Git Branch，也不与 Branch 天然一对一。创建 Requirement Group 不得自动创建 Git 分支。

### 3.4 Diff、Commit 与 MR

Diff、Commit、Push 和 MR 是四种不同事实，不得混用：

- Diff：Task 在某个 Workspace repository 上生成的不可变代码快照，用于用户审查；它不是 Step 产物，也不是 Commit。
- Commit：用户接受 Diff 后，由受控 Git 执行器基于被审查快照创建的真实 Git 提交。
- Push：真实 Commit 被成功推送至远端分支。
- MR：远端 Git 提供方上真实存在的 Merge Request 或 Pull Request。

Task 的代码结果分为两条路径：

- Diff-first：小改动先返回 Diff。拒绝时不 commit，保留 Workspace；接受时必须先校验当前工作树与 Diff 快照一致，再 commit，并回填真实 SHA。
- MR-first：大功能在 Task 完成、提交已推送且远端 head 可核验后直接进入 MR 审核流程。

补充规则：

- Diff-first 和 MR-first 必须在持久模型中明确区分。
- 一个 Task 涉及多个 Repository 时，每个 Repository 独立创建 MR。
- 手动 MR 可以汇总同一 feature branch 上多个已经确认并推送的 Commit。
- 复用 Workspace 后，不得把前序 Task 的旧 head 当成当前 Task 的产出。
- Diff-first 创建 MR 时，当前 head 必须来自该 Task 已接受并已提交的 Diff。
- 同一 Repository 和 source branch 的活动 MR 创建必须幂等，并防止重复 OPEN MR。
- provider number 必须来自真实 Git 提供方，不得使用本地 `max + 1` 伪造。
- 审批记录、本地镜像、占位编号或待执行状态不得描述为已经 commit、push、创建 MR 或合并成功。

## 4. Agent 分工

完整代码任务不得由一个 Agent 独自完成全部开发、测试和审查工作。

- `ORCHESTRATOR`：理解需求、组织上下文、调度角色、汇总结果。
- `PLANNER`：拆分 TaskStep、依赖关系、仓库访问范围和验收条件。
- `DEVELOPER`：在唯一写入租约下实现修改，生成可验证的 Task 级结果。
- `TESTER`：独立执行适用测试或 Testset，记录真实结果。
- `REVIEWER`：独立检查代码、风险、权限、隔离和契约一致性。

执行要求：

- Developer、Tester、Reviewer 应由不同 Agent 实例或独立执行主体承担，不能只切换角色标签。
- 同一 Workspace 同一时刻只允许一个 Agent 写入；其他 Agent 只读检查或等待移交。
- 不覆盖用户或其他 Agent 的修改；发现重叠时先协调。
- 环境无法提供所需角色时，只完成已授权阶段并明确移交，不伪造后续阶段已完成。
- Developer Done 不等于 Task Done；Tester、Reviewer 和适用 Quality Gate 必须真实完成。

## 5. 开工前检查

修改前完成与任务规模相称的检查：

1. 阅读当前作用域的 `AGENTS.md`。
2. 阅读相关需求、契约、架构和模块文档。
3. 执行 `git status`，识别用户和其他 Agent 的现有修改。
4. 阅读相关代码、测试和调用链，确认现有实现方式。
5. 明确 Task、Workspace、目标 Repository、修改范围和验收条件。
6. 确认可用的构建、测试、静态检查和启动命令。

不要在未检查现有实现时创建平行架构。

## 6. Java 后端规范

- 使用 Java 21，不得为了本机环境降低项目 Java 版本。
- 既定技术栈为 Spring Boot 4.1.0、Spring AI 2.0.0、LangGraph4j 1.8.20、MyBatis-Plus 3.5.17、Redis、MySQL 8.0.16+ 和 RabbitMQ。
- Redis 服务端和 RabbitMQ 版本尚未确认；项目当前未接入 RabbitMQ 客户端，使用前必须确认版本和契约。
- 未经授权，不替换既定框架、基础设施或核心依赖。

代码分层统一为：

```text
Controller -> Service -> Mapper -> Entity
```

- Controller 只处理协议、参数校验、认证信息和响应。
- 业务规则与事务放在 Service。
- 数据访问放在 Mapper，统一使用 MyBatis-Plus。
- 单主键 Mapper 继承 `BaseMapper`；复合主键关联表使用专用 Mapper 方法。
- 不新增 JdbcTemplate、JPA 或另一套 Repository 数据访问体系。
- Entity、DTO、Mapper、Service、Controller 分文件定义，不嵌套成跨层模型。
- 持久化 Entity 和接口 DTO 使用普通 POJO/Lombok，不使用 Java `record`。

接口 DTO 必须：

- 使用 `jakarta.validation.constraints.*` 表达必填、长度和格式限制。
- 使用 `io.swagger.v3.oas.annotations.media.Schema` 描述字段语义和限制。
- 不接收由服务端决定的身份、权限、Git SHA、门禁结果或敏感凭证。

实体和公开接口必须有准确 Javadoc。项目代码、配置和协议中的说明性注释统一使用中文；类名、字段名、协议关键字和通用技术名词可保留英文。注释应说明业务约束、安全边界和非显然逻辑，不重复字段名，不保留失效、乱码或模板化注释。

## 7. 数据库规范

- 初始化建表脚本只负责全新数据库；已有数据库升级必须使用独立、版本化、可审查的迁移脚本。
- 数据库模型必须与 Entity、Mapper、DTO、Service 和契约同步修改。
- 删除领域概念时，必须同步删除表、列、索引、外键、迁移兼容代码和文档引用。
- 关键归属关系优先由外键、唯一键、CHECK 或复合约束保证，不能只依赖客户端或应用层约定。
- 所有跨 Project 写入必须在服务端校验资源归属；能够用数据库约束表达时同时增加约束。
- 写接口必须处理 `Idempotency-Key`，避免客户端重试造成重复创建或重复状态变更。

## 8. Git、Workspace 与 Sandbox 操作

- Git 分支由 Task 或用户决定，功能分支命名使用 `feat/<short-kebab-name>`。
- Commit 使用 Conventional Commits：`<type>(scope): <description>`。
- 未经用户或 Task 授权，不执行 commit、push、创建 MR、合并 MR 或修改远端状态。
- 未定义受控 Git、GitHub、Sandbox 或执行接口时，不得自行猜测或伪造实现。
- GitHub 长期凭证不得交给 Agent；GitHub 操作必须通过受控服务或短期权限完成。
- Sandbox 使用最小权限，不得影响宿主机、其他 Workspace、其他用户或其他 Project。
- 不挂载 Docker Socket，除非架构已明确要求并具备可信隔离措施。
- 不使用破坏性 Git 命令清除未确认修改。

## 9. 安全要求

- 不主动读取或输出密码、Token、PAT、JWT、Refresh Token、GitHub App 私钥、私有 Agent Prompt、`.env` 或其他 Secret 明文。
- Secret 只能通过已确认的 Secret Manager、受控注入或服务端短期凭证使用。
- Secret 不得进入 Agent 上下文、日志、命令输出、Diff、截图、异常信息或最终反馈。
- 日志与 SSE 输出必须脱敏、有序，并保持 Project 归属。
- 不为了通过检查而删除测试、绕过授权、降低安全策略或伪造数据。

## 10. 修改原则

- 采用最小必要修改，优先复用已有包结构、模型、服务、Mapper、异常和响应格式。
- 未经明确授权，不做框架替换、大规模重构、公共 API 变更、权限模型变更或无关批量重命名。
- 新增依赖前确认现有依赖不能满足；任务结束前移除未使用依赖和配置。
- 环境差异通过环境变量或配置文件表达，不硬编码本机路径、服务器地址或 Secret。
- 源码、配置和文档统一使用 UTF-8；不得因编码或换行符转换批量改写无关文件。
- Maven 缓存、构建产物、日志、IDE 文件、本地数据库和依赖目录不得提交。
- 发现范围外问题时记录并报告，不顺手扩大修改。

## 11. 验证与真实性

修改后按影响范围执行：

1. Build
2. Test / Testset
3. Lint / Static Check（项目存在时）
4. `git diff --check`
5. `git diff` 与 `git status`

涉及 Spring Bean、依赖、配置或启动入口时，还必须验证 ApplicationContext 能创建，并继续检查到应用成功启动或遇到明确的外部依赖阻塞。

最终确认：

- 未误改或覆盖现有修改。
- 未加入 Secret、临时文件或构建产物。
- 未破坏现有权限和 Project 隔离。
- 数据库、实体、接口、服务、测试和文档语义一致。
- TaskStep/TaskRun 没有被描述成独立产出用户 Diff/MR。

无法执行某项检查时，必须说明命令、原因和剩余风险。禁止虚构测试、Review、Dry Run、CQ+1、Quality Gate、Git 操作、远端状态或外部系统结果。

## 12. 阶段移交与最终反馈

Developer 阶段至少满足：

- 当前 Task 范围内的实现已完成。
- 已检查 Git Diff，修改范围可审查。
- 已执行适用构建、测试和静态检查，或明确说明无法执行项。
- 未发现明显权限漏洞或 Secret 泄露。
- 已生成可验证的 Task 级 Diff；只有获得授权且受控执行器真实成功时，才能报告 Commit、Push 或 MR。
- 已准备好移交独立 Tester 和 Reviewer。

最终反馈至少包含：

1. 完成内容
2. 核心修改文件
3. 实际执行的验证及结果
4. 未验证项、风险或 TODO
5. 下一责任角色或移交建议

## 13. 文档归属

- 产品背景和功能范围写入产品文档。
- 领域模型、状态机和 API 写入业务/API 契约。
- Workspace、Sandbox、Task 调度、Git 接缝和部署写入技术架构文档。
- 技术栈、目录结构和开发命令写入模块开发指南或子目录 `AGENTS.md`。
- 本文件只保留跨模块、长期稳定、可执行的 Agent 行为约束。
