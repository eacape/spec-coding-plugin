# Spec Code Marketplace Copy

## Short Description

Spec-driven AI coding workflow for JetBrains IDEs. Plan, implement, verify, and audit AI-assisted changes with structured workflows, grounded context, and reviewable history.

## Overview

Spec Code brings a spec-driven AI coding workflow into JetBrains IDEs.
Instead of relying on ad-hoc prompts and disposable chat logs, it organizes each AI-assisted change as a reviewable workflow from requirements to archive.

The plugin helps teams and individual developers keep AI work grounded in project context.
It combines workflow artifacts, code graph awareness, related-file discovery, editor insights, and execution history so generated changes are easier to inspect, compare, and iterate.

## Key Features

- Structured workflows for requirements, design, tasks, implementation, verification, and archive
- Workflow templates for different scopes, including Full Spec, Quick Task, Design Review, and Direct Implement
- Code graph, source attachments, related-file discovery, and smart context trimming for better prompt grounding
- Editor gutter icons and inline hints for AI changes and Spec associations
- History diff, delta comparison, changeset timeline, and rollback-oriented review
- Claude CLI and Codex CLI integration with model switching and slash-command discovery
- Built-in operation modes, hooks, worktrees, prompt templates, skills, and session history

## What's New in 0.0.8

- Added code graph visualization and graph-aware context trimming for more grounded prompts.
- Added editor gutter icons and inline hints for AI changes and Spec associations.
- Improved workflow delta review, history comparison, and archive audit trail coverage.
