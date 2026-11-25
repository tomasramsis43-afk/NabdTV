# Bugs found and fixes (branch audit/phase1-fixes)

| # | Problem | Fix | Status |
|---|---------|-----|--------|
| 1 | Room used destructive migration (DB wiped on upgrade) | Explicit MIGRATION_1_2, no destructive fallback | Not Tested on device |
| 2 | `xt()!!` NPE when Xtream login missing | Clear error message | Not Tested |
| 3 | `!!` in pairing and EPG date parsing | Null-safe code | Not Tested |
| 4 | Cached M3U loaded as one String | Line-by-line parseFile + tests | Tests not yet run |
| 5 | EPG download overwrote good file; truncated XML poisoned cache for 6 h | Temp file + atomic swap | Not Tested |
| 6 | Corrupt XMLTV lost the entire guide | Keep partial results | Not Tested |
| 7 | Duplicate EPG programmes | distinctBy start+title | Not Tested |
| 8 | EPG load exception swallowed silently | Diag.note | Not Tested |
| 9 | Lint ran only on push to main | Also on pull_request to main | CI-dependent |
