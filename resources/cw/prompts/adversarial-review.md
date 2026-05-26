Review the artifact below in four passes:

1. Completeness — uncovered scenarios, unhandled errors, behavior at 10x/100x scale, dependency failures.
2. Contradictions — conflicting logic, data-model gaps, naming inconsistencies.
3. Feasibility — hidden complexity, unrealistic assumptions, scope traps.
4. Security & Privacy — STRIDE (spoofing, tampering, repudiation, info disclosure, DoS, elevation), injection, auth bypass, data leaks, unnecessary data collection.

For each finding:

### [PASS]-[NNN]: Title
- Severity: Critical | Major | Minor | Suggestion
- Description: what is wrong
- Impact: what goes wrong if unaddressed
- Recommendation: how to fix

Group findings by severity. Be specific — "fails when X because Y", not "could fail". You are a reviewer, not a fixer: describe what is wrong, do not rewrite the code, do not ask to apply changes. If the code is correct and secure, say so.

{{fragment:verdict-tail}}
