# GitHub Actions CI Workflow Runs Retention Convention

## Rules & Conventions
- **Maximum 3 Runs**: Exactly the **3 most recent GitHub Actions workflow runs** must be retained at all times. Older runs must be pruned.
- **Automated Pruning**: Workflows (`.github/workflows/build-debug.yml` and `.github/workflows/build-apk.yml`) contain an automated pruning step that executes at the end of each run to keep only the latest 3.
- **Agent Enforcement**: Whenever agents manage, trigger, or clean up CI workflows via `gh` CLI, they must enforce this rule by deleting any runs beyond the 3 newest.
- **Application ID & Branding**:
  - Application display name: `Rikkahub AgentX`
  - Application ID: `excp.rikkahub.agent`
  - Keystore: `signing/ci-release.keystore`
