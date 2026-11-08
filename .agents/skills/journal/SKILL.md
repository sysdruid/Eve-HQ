# Skill: Automatic Sprint Journaling

## Purpose
Capture technical decisions and friction immediately upon feature completion to generate raw material for engineering articles.

## Execution Trigger
Fired automatically when a feature, bugfix, or architectural spike is marked complete.

## Instructions
1. Inspect the active conversation context for the feature just implemented.
2. Identify:
   - Primary technical choices made (e.g., KMP common vs JVM target, H2 database settings, Ktor engine configuration).
   - Real friction points (API rate limits, threading/coroutine issues, build setup bugs).
   - The core takeaway worth sharing in a technical article.
3. Write or append to `docs/journal/YYYY-MM-DD-<topic>.md` (under 50 lines).

## Format
# [YYYY-MM-DD] - [Topic]

### Context
- **Objective:** 

### Architecture & Trade-offs
- **Key Choices:** 

### Friction & Gotchas
- **Real Issues Encountered:** 

### Article Hook
- **Publication Takeaway:**
