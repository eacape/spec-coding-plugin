package com.eacape.speccodingplugin.worktree

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class CliGitWorktreeExecutor : GitWorktreeExecutor {
    private val logger = thisLogger()

    override fun addWorktree(
        repoPath: String,
        worktreePath: String,
        branchName: String,
        baseBranch: String,
    ): Result<Unit> {
        return runCommand(
            repoPath = repoPath,
            args = listOf("worktree", "add", worktreePath, "-b", branchName, baseBranch)
        ).map { Unit }
    }

    override fun removeWorktree(repoPath: String, worktreePath: String, force: Boolean): Result<Unit> {
        val args = mutableListOf("worktree", "remove")
        if (force) args += "--force"
        args += worktreePath
        return runCommand(repoPath = repoPath, args = args).map { Unit }
    }

    override fun mergeBranch(repoPath: String, sourceBranch: String, targetBranch: String): Result<GitMergeOutcome> {
        return runCatching {
            runCommand(repoPath, listOf("checkout", targetBranch)).getOrThrow()
            val mergeOutput = runCommand(repoPath, listOf("merge", "--no-ff", sourceBranch)).getOrThrow()
            val hasConflicts = mergeOutput.contains("CONFLICT", ignoreCase = true)
            GitMergeOutcome(
                hasConflicts = hasConflicts,
                statusDescription = mergeOutput.ifBlank { "MERGED" },
            )
        }
    }

    private fun runCommand(repoPath: String, args: List<String>): Result<String> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    val command = listOf("git") + args
                    val process = ProcessBuilder(command)
                        .directory(File(repoPath))
                        .redirectErrorStream(true)
                        .start()

                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        throw IllegalStateException(
                            "Git command failed (${command.joinToString(" ")}): ${output.trim()}"
                        )
                    }

                    output
                }.onFailure { e ->
                    logger.warn("Git command execution failed: ${args.joinToString(" ")}", e)
                }
            }
        }
    }
}
