package com.eacape.speccodingplugin.worktree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliGitWorktreeExecutorTest {

    @Test
    fun `addWorktree should build git worktree add command`() {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val executor = CliGitWorktreeExecutor { repoPath, command ->
            calls += repoPath to command
            CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "ok")
        }

        val result = executor.addWorktree(
            repoPath = "D:/repo",
            worktreePath = "D:/repo-worktrees/spec-1",
            branchName = "spec/spec-1-demo",
            baseBranch = "main",
        )

        assertTrue(result.isSuccess)
        assertEquals(1, calls.size)
        assertEquals("D:/repo", calls.first().first)
        assertEquals(
            listOf("git", "worktree", "add", "D:/repo-worktrees/spec-1", "-b", "spec/spec-1-demo", "main"),
            calls.first().second,
        )
    }

    @Test
    fun `removeWorktree should include force flag when enabled`() {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val executor = CliGitWorktreeExecutor { repoPath, command ->
            calls += repoPath to command
            CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "removed")
        }

        val result = executor.removeWorktree(
            repoPath = "D:/repo",
            worktreePath = "D:/repo-worktrees/spec-2",
            force = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("git", "worktree", "remove", "--force", "D:/repo-worktrees/spec-2"),
            calls.first().second,
        )
    }

    @Test
    fun `mergeBranch should run checkout then merge and detect conflicts`() {
        val commands = mutableListOf<List<String>>()
        val outcomes = ArrayDeque(
            listOf(
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "Switched to branch 'main'"),
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "CONFLICT (content): Merge conflict"),
            )
        )

        val executor = CliGitWorktreeExecutor { _, command ->
            commands += command
            outcomes.removeFirst()
        }

        val result = executor.mergeBranch(
            repoPath = "D:/repo",
            sourceBranch = "spec/spec-3",
            targetBranch = "main",
        )

        assertTrue(result.isSuccess)
        val mergeOutcome = result.getOrThrow()
        assertTrue(mergeOutcome.hasConflicts)
        assertTrue(mergeOutcome.statusDescription.contains("CONFLICT"))
        assertEquals(listOf("git", "checkout", "main"), commands[0])
        assertEquals(listOf("git", "merge", "--no-ff", "spec/spec-3"), commands[1])
    }

    @Test
    fun `mergeBranch should use MERGED description when merge output blank`() {
        val outcomes = ArrayDeque(
            listOf(
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "checked out"),
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "   "),
            )
        )

        val executor = CliGitWorktreeExecutor { _, _ -> outcomes.removeFirst() }

        val result = executor.mergeBranch(
            repoPath = "D:/repo",
            sourceBranch = "spec/spec-4",
            targetBranch = "main",
        )

        assertTrue(result.isSuccess)
        val mergeOutcome = result.getOrThrow()
        assertFalse(mergeOutcome.hasConflicts)
        assertEquals("MERGED", mergeOutcome.statusDescription)
    }

    @Test
    fun `addWorktree should expose git stderr output on failure`() {
        val executor = CliGitWorktreeExecutor { _, _ ->
            CliGitWorktreeExecutor.ProcessExecutionResult(
                exitCode = 128,
                output = "fatal: invalid reference: missing-base",
            )
        }

        val result = executor.addWorktree(
            repoPath = "D:/repo",
            worktreePath = "D:/repo-worktrees/spec-5",
            branchName = "spec/spec-5",
            baseBranch = "missing-base",
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Git command failed"))
        assertTrue(message.contains("invalid reference"))
    }
}
