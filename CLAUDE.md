# CLAUDE.md — Team 4 Claude Code Hackathon

## Project Context

This is a code modernization hackathon project. We are evolving a legacy monolith ("The Patient") toward a modern service architecture using a strangler fig / incremental extraction approach. The goal is a safe, testable migration — not a big-bang rewrite.

Hackathon challenge: https://github.com/carmatthews/claude-code-hackathon/blob/main/01-code-modernization.md

## Key Decisions (update as resolved)

- [ ] Legacy flavor (PHP 5 / Node callbacks / Java 2010 / stored procs)
- [ ] Business domain (inventory/factory vs Northwind Logistics)
- [ ] Target architecture (strangler fig / event-driven / API facade)
- [ ] Waypoints to pursue (see docs/planning.md)

## Directory Layout

- `legacy/` — the generated monolith; treat as read-only once characterization tests pass
- `services/` — extracted services with clean API contracts
- `tests/` — characterization tests (pinning) and contract tests
- `docs/` — ADRs, planning notes, runbooks

## Rules

1. **Never change behavior in `legacy/` without first pinning it in `tests/characterization/`.**
2. **Monolith data models must not leak into `services/` public API shapes.** (Anti-corruption layer — The Fence)
3. **Every extracted service needs a contract test green on the same commit as the extraction.**
4. **ADRs go in `docs/adr/` using the template at `docs/adr/000-template.md`.**

## Subagent Usage

- Use `Explore` subagent to navigate `legacy/` before proposing changes
- Use Task subagents for parallel seam analysis (The Scouts waypoint)
- Pass scope explicitly in each Task prompt — subagents don't inherit coordinator context

## Hook

A `PreToolUse` hook in `.claude/settings.json` blocks writes to `legacy/` after characterization tests exist. Set `CLAUDE_LEGACY_WRITE_ALLOWED=1` to override intentionally.
