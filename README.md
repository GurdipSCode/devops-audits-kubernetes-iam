# devops-audits-kubernetes-iam

[![TeamCity](https://img.shields.io/badge/TeamCity-2023.05+-000000?style=flat&logo=teamcity&logoColor=white)](https://www.jetbrains.com/teamcity/)
[![Permiflow](https://img.shields.io/badge/Permiflow-v0.8.2-blue?style=flat)](https://github.com/tutran-se/permiflow)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-RBAC-326CE5?style=flat&logo=kubernetes&logoColor=white)](https://kubernetes.io/docs/reference/access-authn-authz/rbac/)
[![Kotlin DSL](https://img.shields.io/badge/Kotlin_DSL-1.9+-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://www.jetbrains.com/help/teamcity/kotlin-dsl.html)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat)](LICENSE)
[![Go](https://img.shields.io/badge/Go-1.21+-00ADD8?style=flat&logo=go&logoColor=white)](https://go.dev/)

This repository contains TeamCity pipeline configuration for running [Permiflow](https://github.com/tutran-se/permiflow) - a zero-mutation Kubernetes RBAC scanning tool.

## Overview

Permiflow scans Kubernetes RBAC bindings and generates structured, human-readable reports. This pipeline automates RBAC auditing as part of your CI/CD workflow, enabling:

- Scheduled RBAC scans of your clusters
- Permission drift detection between scans
- Automated report generation (Markdown, JSON, CSV)
- Risk-level alerts for HIGH/MEDIUM findings

## Prerequisites

- TeamCity Server 2023.05+ with Kotlin DSL support
- Kubernetes cluster access (via kubeconfig or service account)
- Go 1.21+ installed on build agents (or use Docker)

## Project Structure

```
.teamcity/
├── settings.kts              # Root project configuration
├── _Self/
│   └── Project.kt            # Main project definition
├── buildTypes/
│   ├── PermiflowScan.kt      # RBAC scan build configuration
│   ├── PermiflowDiff.kt      # Diff comparison build configuration
│   └── PermiflowScheduled.kt # Scheduled scan configuration
└── vcsRoots/
    └── VcsRoot.kt            # VCS root configuration
```

## Setup

### 1. Add Kubernetes Credentials

In TeamCity, navigate to **Project Settings → Parameters** and add:

| Parameter | Type | Description |
|-----------|------|-------------|
| `env.KUBECONFIG_BASE64` | Password | Base64-encoded kubeconfig file |
| `env.KUBE_CONTEXT` | Configuration | Kubernetes context to use |
| `env.SLACK_WEBHOOK_URL` | Password | (Optional) Slack webhook for notifications |

To encode your kubeconfig:

```bash
base64 -w 0 ~/.kube/config
```

### 2. Import the Project

Option A - Versioned Settings:
1. Enable "Synchronization enabled" in **Versioned Settings**
2. Point to this repository
3. TeamCity will auto-import the `.teamcity/` configuration

Option B - Manual Import:
1. Create a new project in TeamCity
2. Go to **Versioned Settings → Import from VCS**
3. Select this repository

### 3. Configure Build Agents

Ensure your agents have:

```bash
# Install Permiflow
go install github.com/tutran-se/permiflow@latest

# Or via Homebrew
brew install tutran-se/tap/permiflow

# Verify installation
permiflow --version
```

## Build Configurations

### Permiflow Scan

Runs a full RBAC scan and generates reports.

**Trigger:** Manual or on VCS change  
**Artifacts:** `reports/` directory with Markdown, JSON, CSV outputs

```bash
# What it runs
permiflow scan --output reports/rbac-scan.md --format markdown
permiflow scan --output reports/rbac-scan.json --format json
permiflow scan --output reports/rbac-scan.csv --format csv
```

### Permiflow Diff

Compares two scans to detect permission drift.

**Trigger:** Manual with parameters  
**Parameters:**
- `baseline_scan` - Path to baseline scan JSON
- `current_scan` - Path to current scan JSON

```bash
# What it runs
permiflow diff --baseline %baseline_scan% --current %current_scan% --output reports/diff.md
```

### Permiflow Scheduled

Automated daily scan with Slack notifications.

**Trigger:** Daily at 06:00 UTC  
**Features:**
- Archives scan history
- Sends alerts on HIGH risk findings
- Compares against previous day's scan

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `KUBECONFIG` | Yes | Path to kubeconfig (auto-generated from base64) |
| `KUBE_CONTEXT` | No | Specific context to use |
| `PERMIFLOW_NAMESPACES` | No | Comma-separated list of namespaces to scan |
| `PERMIFLOW_OUTPUT_DIR` | No | Output directory (default: `reports/`) |

## Customization

### Scanning Specific Namespaces

Edit `buildTypes/PermiflowScan.kt`:

```kotlin
script {
    scriptContent = """
        permiflow scan \
            --namespaces production,staging \
            --output reports/rbac-scan.md
    """.trimIndent()
}
```

### Adding Risk Thresholds

To fail builds on HIGH risk findings:

```kotlin
script {
    scriptContent = """
        permiflow scan --output reports/scan.json --format json
        
        # Check for HIGH risk bindings
        HIGH_COUNT=${'$'}(jq '[.bindings[] | select(.risk == "HIGH")] | length' reports/scan.json)
        if [ "${'$'}HIGH_COUNT" -gt 0 ]; then
            echo "##teamcity[buildProblem description='Found ${'$'}HIGH_COUNT HIGH risk RBAC bindings']"
            exit 1
        fi
    """.trimIndent()
}
```

### Slack Notifications

The scheduled build sends notifications via webhook:

```kotlin
script {
    scriptContent = """
        # Send to Slack on HIGH findings
        if [ "${'$'}HIGH_COUNT" -gt 0 ]; then
            curl -X POST -H 'Content-type: application/json' \
                --data '{"text":"⚠️ Permiflow found '${'$'}HIGH_COUNT' HIGH risk RBAC bindings"}' \
                %env.SLACK_WEBHOOK_URL%
        fi
    """.trimIndent()
}
```

## Artifacts

Each build produces artifacts in the `reports/` directory:

| File | Format | Description |
|------|--------|-------------|
| `rbac-scan.md` | Markdown | Human-readable report |
| `rbac-scan.json` | JSON | Machine-parseable scan data |
| `rbac-scan.csv` | CSV | Spreadsheet-compatible export |
| `diff.md` | Markdown | Permission drift report |

## Troubleshooting

### "Unable to connect to cluster"

1. Verify `KUBECONFIG_BASE64` is correctly encoded
2. Check the build agent can reach the Kubernetes API
3. Ensure the service account has RBAC read permissions:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: permiflow-reader
rules:
  - apiGroups: ["rbac.authorization.k8s.io"]
    resources: ["roles", "rolebindings", "clusterroles", "clusterrolebindings"]
    verbs: ["get", "list"]
```

### "permiflow: command not found"

Ensure Permiflow is installed on the build agent or use the Docker approach:

```kotlin
script {
    scriptContent = """
        docker run --rm \
            -v ${'$'}KUBECONFIG:/root/.kube/config:ro \
            ghcr.io/tutran-se/permiflow:latest \
            scan --output /dev/stdout
    """.trimIndent()
}
```

### Build agent permissions

The agent needs network access to your Kubernetes cluster API server. For private clusters, ensure proper VPN/network configuration.

## Security Considerations

- Store kubeconfig as a **Password** parameter type
- Use a dedicated service account with minimal read-only permissions
- Consider using short-lived tokens instead of long-lived credentials
- The pipeline is read-only and makes no changes to your cluster

## Resources

- [Permiflow Documentation](https://github.com/tutran-se/permiflow/tree/main/docs)
- [TeamCity Kotlin DSL Documentation](https://www.jetbrains.com/help/teamcity/kotlin-dsl.html)
- [Kubernetes RBAC Documentation](https://kubernetes.io/docs/reference/access-authn-authz/rbac/)

## License

MIT
