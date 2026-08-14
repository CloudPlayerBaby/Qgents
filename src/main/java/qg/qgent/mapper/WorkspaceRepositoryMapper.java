package qg.qgent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.WorkspaceRepositoryEntity;

import java.util.List;
import java.util.UUID;

/** Data access for composite-key Workspace repository worktrees. */
@Mapper
public interface WorkspaceRepositoryMapper {
    /** Persists a new repository worktree owned by a Workspace. */
    @Insert("insert into workspace_repositories(workspace_id,project_repository_id,workspace_path,base_commit,source_branch) "
            + "values(#{workspaceId},#{repositoryId},#{workspacePath},#{baseCommit},#{sourceBranch})")
    int insertLink(@Param("workspaceId") UUID workspaceId, @Param("repositoryId") UUID repositoryId,
            @Param("workspacePath") String workspacePath, @Param("baseCommit") String baseCommit,
            @Param("sourceBranch") String sourceBranch);

    /** Lists all repository worktrees available in a Workspace. */
    @Select("select workspace_id,project_repository_id,workspace_path,base_commit,source_branch,head_commit,created_at,updated_at "
            + "from workspace_repositories where workspace_id=#{workspaceId}")
    List<WorkspaceRepositoryEntity> selectByWorkspace(UUID workspaceId);

    /** Locks one worktree before accepting a Diff or creating an MR. */
    @Select("select workspace_id,project_repository_id,workspace_path,base_commit,source_branch,head_commit,created_at,updated_at "
            + "from workspace_repositories where workspace_id=#{workspaceId} and project_repository_id=#{repositoryId} for update")
    WorkspaceRepositoryEntity selectForUpdate(@Param("workspaceId") UUID workspaceId,
            @Param("repositoryId") UUID repositoryId);

    /** Writes the real commit created by the controlled Worker. */
    @org.apache.ibatis.annotations.Update("update workspace_repositories set head_commit=#{headCommit} "
            + "where workspace_id=#{workspaceId} and project_repository_id=#{repositoryId}")
    int updateHeadCommit(@Param("workspaceId") UUID workspaceId, @Param("repositoryId") UUID repositoryId,
            @Param("headCommit") String headCommit);
}
