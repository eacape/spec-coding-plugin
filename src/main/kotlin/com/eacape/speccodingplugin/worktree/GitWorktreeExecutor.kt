package com.eacape.speccodingplugin.worktree

/**
 * Git Worktree 底层执行器
 * 默认实现调用系统 git CLI，方便在无 JGit Worktree API 的情况下稳定运行。
 */
interface GitWorktreeExecutor {
    /**
     * Resolve the base branch/ref used for creating a worktree.
     * Default implementation keeps the requested value unchanged.
     */
    fun resolveBaseBranch(repoPath: String, requestedBaseBranch: String): Result<String> {
        return Result.success(requestedBaseBranch)
    }

    fun addWorktree(repoPath: String, worktreePath: String, branchName: String, baseBranch: String): Result<Unit>
    fun removeWorktree(repoPath: String, worktreePath: String, force: Boolean = true): Result<Unit>
    fun mergeBranch(repoPath: String, sourceBranch: String, targetBranch: String): Result<GitMergeOutcome>
}
