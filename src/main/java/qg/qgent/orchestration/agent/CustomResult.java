package qg.qgent.orchestration.agent;

/**
 * 自定义 Agent 最终输出契约：{@code {"success": bool, "summary": "...", "message": "..."}}。
 * success=false 视为质量门禁失败（专项检查/审查未通过），由状态机回 Coding 修复。
 * summary 为结果摘要，message 为给用户的反馈或发现的问题（可空，回退到 summary）。
 */
public record CustomResult(boolean success, String summary, String message) {
}
