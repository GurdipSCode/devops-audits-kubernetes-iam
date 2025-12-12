import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.*
import jetbrains.buildServer.configs.kotlin.buildSteps.*
import jetbrains.buildServer.configs.kotlin.triggers.*
import jetbrains.buildServer.configs.kotlin.vcs.*

version = "2024.03"

project {
    description = "Permiflow Kubernetes RBAC Scanning Pipeline"

    params {
        password("env.KUBECONFIG_BASE64", "credentialsJSON:kubeconfig-base64", display = ParameterDisplay.HIDDEN)
        param("env.KUBE_CONTEXT", "")
        param("env.PERMIFLOW_NAMESPACES", "")
        param("env.PERMIFLOW_OUTPUT_DIR", "reports")
        password("env.SLACK_WEBHOOK_URL", "credentialsJSON:slack-webhook", display = ParameterDisplay.HIDDEN)
    }

    vcsRoot(PermiflowVcsRoot)

    buildType(PermiflowScan)
    buildType(PermiflowDiff)
    buildType(PermiflowScheduledScan)

    features {
        feature {
            type = "JetBrains.SharedResources"
            param("name", "KubernetesCluster")
            param("type", "quoted")
            param("quota", "1")
        }
    }
}

object PermiflowVcsRoot : GitVcsRoot({
    name = "Permiflow Configuration"
    url = "https://github.com/your-org/permiflow-config.git"
    branch = "refs/heads/main"
    branchSpec = "+:refs/heads/*"
    authMethod = password {
        userName = "git"
        password = "credentialsJSON:github-token"
    }
})

object PermiflowScan : BuildType({
    name = "Permiflow RBAC Scan"
    description = "Scan Kubernetes cluster RBAC bindings and generate reports"

    artifactRules = """
        reports/** => reports.zip
    """.trimIndent()

    params {
        param("env.PERMIFLOW_VERSION", "latest")
    }

    vcs {
        root(PermiflowVcsRoot)
    }

    steps {
        script {
            name = "Setup Kubeconfig"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                echo "Setting up kubeconfig..."
                mkdir -p ${'$'}HOME/.kube
                echo "${'$'}KUBECONFIG_BASE64" | base64 -d > ${'$'}HOME/.kube/config
                chmod 600 ${'$'}HOME/.kube/config
                export KUBECONFIG=${'$'}HOME/.kube/config
                
                if [ -n "${'$'}{KUBE_CONTEXT:-}" ]; then
                    kubectl config use-context "${'$'}KUBE_CONTEXT"
                fi
                
                echo "Verifying cluster connection..."
                kubectl cluster-info
            """.trimIndent()
        }

        script {
            name = "Install Permiflow"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                if ! command -v permiflow &> /dev/null; then
                    echo "Installing Permiflow..."
                    go install github.com/tutran-se/permiflow@%env.PERMIFLOW_VERSION%
                fi
                
                permiflow --version
            """.trimIndent()
        }

        script {
            name = "Run RBAC Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                export KUBECONFIG=${'$'}HOME/.kube/config
                OUTPUT_DIR="%env.PERMIFLOW_OUTPUT_DIR%"
                mkdir -p "${'$'}OUTPUT_DIR"
                
                NAMESPACE_FLAG=""
                if [ -n "${'$'}{PERMIFLOW_NAMESPACES:-}" ]; then
                    NAMESPACE_FLAG="--namespaces ${'$'}PERMIFLOW_NAMESPACES"
                fi
                
                echo "Running Permiflow scan..."
                
                # Generate Markdown report
                permiflow scan ${'$'}NAMESPACE_FLAG --output "${'$'}OUTPUT_DIR/rbac-scan.md" --format markdown
                echo "##teamcity[publishArtifacts '${'$'}OUTPUT_DIR/rbac-scan.md']"
                
                # Generate JSON report
                permiflow scan ${'$'}NAMESPACE_FLAG --output "${'$'}OUTPUT_DIR/rbac-scan.json" --format json
                echo "##teamcity[publishArtifacts '${'$'}OUTPUT_DIR/rbac-scan.json']"
                
                # Generate CSV report
                permiflow scan ${'$'}NAMESPACE_FLAG --output "${'$'}OUTPUT_DIR/rbac-scan.csv" --format csv
                echo "##teamcity[publishArtifacts '${'$'}OUTPUT_DIR/rbac-scan.csv']"
                
                echo "Scan complete. Reports generated in ${'$'}OUTPUT_DIR/"
            """.trimIndent()
        }

        script {
            name = "Analyze Risk Levels"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                OUTPUT_DIR="%env.PERMIFLOW_OUTPUT_DIR%"
                
                if [ -f "${'$'}OUTPUT_DIR/rbac-scan.json" ]; then
                    HIGH_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "HIGH")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                    MEDIUM_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "MEDIUM")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                    LOW_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "LOW")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                    
                    echo "##teamcity[buildStatisticValue key='permiflow.high_risk' value='${'$'}HIGH_COUNT']"
                    echo "##teamcity[buildStatisticValue key='permiflow.medium_risk' value='${'$'}MEDIUM_COUNT']"
                    echo "##teamcity[buildStatisticValue key='permiflow.low_risk' value='${'$'}LOW_COUNT']"
                    
                    echo "Risk Summary:"
                    echo "  HIGH:   ${'$'}HIGH_COUNT"
                    echo "  MEDIUM: ${'$'}MEDIUM_COUNT"
                    echo "  LOW:    ${'$'}LOW_COUNT"
                    
                    if [ "${'$'}HIGH_COUNT" -gt 0 ]; then
                        echo "##teamcity[message text='Found ${'$'}HIGH_COUNT HIGH risk RBAC bindings' status='WARNING']"
                    fi
                fi
            """.trimIndent()
        }

        script {
            name = "Cleanup"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                #!/bin/bash
                rm -f ${'$'}HOME/.kube/config
                echo "Cleaned up kubeconfig"
            """.trimIndent()
        }
    }

    features {
        perfmon {}
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Linux")
        exists("env.GOPATH")
    }
})

object PermiflowDiff : BuildType({
    name = "Permiflow RBAC Diff"
    description = "Compare two RBAC scans to detect permission drift"

    artifactRules = """
        reports/** => reports.zip
    """.trimIndent()

    params {
        text("baseline_build_id", "", label = "Baseline Build ID", 
            description = "TeamCity build ID containing baseline scan", 
            display = ParameterDisplay.PROMPT)
        text("current_build_id", "", label = "Current Build ID", 
            description = "TeamCity build ID containing current scan (leave empty for latest)", 
            display = ParameterDisplay.PROMPT)
    }

    vcs {
        root(PermiflowVcsRoot)
    }

    steps {
        script {
            name = "Setup Kubeconfig"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                mkdir -p ${'$'}HOME/.kube
                echo "${'$'}KUBECONFIG_BASE64" | base64 -d > ${'$'}HOME/.kube/config
                chmod 600 ${'$'}HOME/.kube/config
            """.trimIndent()
        }

        script {
            name = "Download Baseline Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                mkdir -p reports/baseline reports/current
                
                # Download baseline artifact
                if [ -n "%baseline_build_id%" ]; then
                    echo "Downloading baseline scan from build %baseline_build_id%..."
                    # Using TeamCity REST API to download artifacts
                    curl -sSf -u "%system.teamcity.auth.userId%:%system.teamcity.auth.password%" \
                        "%teamcity.serverUrl%/app/rest/builds/id:%baseline_build_id%/artifacts/content/reports.zip!rbac-scan.json" \
                        -o reports/baseline/rbac-scan.json
                else
                    echo "##teamcity[buildProblem description='Baseline build ID is required']"
                    exit 1
                fi
            """.trimIndent()
        }

        script {
            name = "Run Current Scan or Download"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                export KUBECONFIG=${'$'}HOME/.kube/config
                
                if [ -n "%current_build_id%" ]; then
                    echo "Downloading current scan from build %current_build_id%..."
                    curl -sSf -u "%system.teamcity.auth.userId%:%system.teamcity.auth.password%" \
                        "%teamcity.serverUrl%/app/rest/builds/id:%current_build_id%/artifacts/content/reports.zip!rbac-scan.json" \
                        -o reports/current/rbac-scan.json
                else
                    echo "Running fresh scan for current state..."
                    permiflow scan --output reports/current/rbac-scan.json --format json
                fi
            """.trimIndent()
        }

        script {
            name = "Run Diff"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                echo "Comparing RBAC scans..."
                permiflow diff \
                    --baseline reports/baseline/rbac-scan.json \
                    --current reports/current/rbac-scan.json \
                    --output reports/diff.md
                
                echo "##teamcity[publishArtifacts 'reports/diff.md']"
                
                # Also generate JSON diff for programmatic access
                permiflow diff \
                    --baseline reports/baseline/rbac-scan.json \
                    --current reports/current/rbac-scan.json \
                    --output reports/diff.json \
                    --format json
                
                echo "##teamcity[publishArtifacts 'reports/diff.json']"
                
                echo "Diff complete."
            """.trimIndent()
        }

        script {
            name = "Analyze Drift"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                if [ -f "reports/diff.json" ]; then
                    ADDED=${'$'}(jq '.added | length // 0' reports/diff.json 2>/dev/null || echo "0")
                    REMOVED=${'$'}(jq '.removed | length // 0' reports/diff.json 2>/dev/null || echo "0")
                    CHANGED=${'$'}(jq '.changed | length // 0' reports/diff.json 2>/dev/null || echo "0")
                    
                    echo "##teamcity[buildStatisticValue key='permiflow.drift.added' value='${'$'}ADDED']"
                    echo "##teamcity[buildStatisticValue key='permiflow.drift.removed' value='${'$'}REMOVED']"
                    echo "##teamcity[buildStatisticValue key='permiflow.drift.changed' value='${'$'}CHANGED']"
                    
                    echo "Drift Summary:"
                    echo "  Added:   ${'$'}ADDED"
                    echo "  Removed: ${'$'}REMOVED"
                    echo "  Changed: ${'$'}CHANGED"
                    
                    TOTAL=${'$'}((ADDED + REMOVED + CHANGED))
                    if [ "${'$'}TOTAL" -gt 0 ]; then
                        echo "##teamcity[message text='Detected ${'$'}TOTAL RBAC changes since baseline' status='WARNING']"
                    fi
                fi
            """.trimIndent()
        }

        script {
            name = "Cleanup"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                #!/bin/bash
                rm -f ${'$'}HOME/.kube/config
            """.trimIndent()
        }
    }

    dependencies {
        artifacts(PermiflowScan) {
            buildRule = lastSuccessful()
            artifactRules = "reports.zip!rbac-scan.json => reports/baseline/"
            enabled = false
        }
    }

    features {
        perfmon {}
    }
})

object PermiflowScheduledScan : BuildType({
    name = "Permiflow Scheduled Scan"
    description = "Daily automated RBAC scan with notifications"

    artifactRules = """
        reports/** => reports.zip
        history/** => history.zip
    """.trimIndent()

    vcs {
        root(PermiflowVcsRoot)
    }

    steps {
        script {
            name = "Setup Kubeconfig"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                mkdir -p ${'$'}HOME/.kube
                echo "${'$'}KUBECONFIG_BASE64" | base64 -d > ${'$'}HOME/.kube/config
                chmod 600 ${'$'}HOME/.kube/config
                export KUBECONFIG=${'$'}HOME/.kube/config
                
                if [ -n "${'$'}{KUBE_CONTEXT:-}" ]; then
                    kubectl config use-context "${'$'}KUBE_CONTEXT"
                fi
            """.trimIndent()
        }

        script {
            name = "Run Daily Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                export KUBECONFIG=${'$'}HOME/.kube/config
                OUTPUT_DIR="reports"
                HISTORY_DIR="history"
                DATE=${'$'}(date +%Y-%m-%d)
                
                mkdir -p "${'$'}OUTPUT_DIR" "${'$'}HISTORY_DIR"
                
                echo "Running daily RBAC scan for ${'$'}DATE..."
                
                # Generate all report formats
                permiflow scan --output "${'$'}OUTPUT_DIR/rbac-scan.md" --format markdown
                permiflow scan --output "${'$'}OUTPUT_DIR/rbac-scan.json" --format json
                permiflow scan --output "${'$'}OUTPUT_DIR/rbac-scan.csv" --format csv
                
                # Archive with date
                cp "${'$'}OUTPUT_DIR/rbac-scan.json" "${'$'}HISTORY_DIR/rbac-scan-${'$'}DATE.json"
                
                echo "Scan complete for ${'$'}DATE"
            """.trimIndent()
        }

        script {
            name = "Compare with Previous Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                OUTPUT_DIR="reports"
                YESTERDAY=${'$'}(date -d "yesterday" +%Y-%m-%d 2>/dev/null || date -v-1d +%Y-%m-%d)
                
                # Try to get yesterday's scan from previous build
                PREVIOUS_BUILD_ARTIFACT=""
                if [ -f "history/rbac-scan-${'$'}YESTERDAY.json" ]; then
                    PREVIOUS_BUILD_ARTIFACT="history/rbac-scan-${'$'}YESTERDAY.json"
                fi
                
                if [ -n "${'$'}PREVIOUS_BUILD_ARTIFACT" ] && [ -f "${'$'}PREVIOUS_BUILD_ARTIFACT" ]; then
                    echo "Comparing with previous scan (${'$'}YESTERDAY)..."
                    permiflow diff \
                        --baseline "${'$'}PREVIOUS_BUILD_ARTIFACT" \
                        --current "${'$'}OUTPUT_DIR/rbac-scan.json" \
                        --output "${'$'}OUTPUT_DIR/daily-diff.md"
                    
                    echo "##teamcity[publishArtifacts '${'$'}OUTPUT_DIR/daily-diff.md']"
                else
                    echo "No previous scan found for comparison"
                fi
            """.trimIndent()
        }

        script {
            name = "Send Notifications"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                OUTPUT_DIR="reports"
                
                if [ -z "${'$'}{SLACK_WEBHOOK_URL:-}" ]; then
                    echo "No Slack webhook configured, skipping notifications"
                    exit 0
                fi
                
                if [ -f "${'$'}OUTPUT_DIR/rbac-scan.json" ]; then
                    HIGH_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "HIGH")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                    TOTAL_COUNT=${'$'}(jq '.bindings | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                    
                    if [ "${'$'}HIGH_COUNT" -gt 0 ]; then
                        echo "Sending Slack alert for HIGH risk findings..."
                        curl -X POST -H 'Content-type: application/json' \
                            --data "{
                                \"text\": \"⚠️ *Permiflow Daily Scan Alert*\",
                                \"attachments\": [{
                                    \"color\": \"danger\",
                                    \"fields\": [
                                        {\"title\": \"HIGH Risk Bindings\", \"value\": \"${'$'}HIGH_COUNT\", \"short\": true},
                                        {\"title\": \"Total Bindings\", \"value\": \"${'$'}TOTAL_COUNT\", \"short\": true},
                                        {\"title\": \"Build\", \"value\": \"<${'$'}BUILD_URL|View Details>\", \"short\": false}
                                    ]
                                }]
                            }" \
                            "${'$'}SLACK_WEBHOOK_URL"
                    else
                        echo "No HIGH risk findings. Sending summary..."
                        curl -X POST -H 'Content-type: application/json' \
                            --data "{
                                \"text\": \"✅ *Permiflow Daily Scan Complete*\n${'$'}TOTAL_COUNT bindings scanned, no HIGH risk findings.\"
                            }" \
                            "${'$'}SLACK_WEBHOOK_URL"
                    fi
                fi
            """.trimIndent()
        }

        script {
            name = "Cleanup"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                #!/bin/bash
                rm -f ${'$'}HOME/.kube/config
            """.trimIndent()
        }
    }

    triggers {
        schedule {
            schedulingPolicy = daily {
                hour = 6
                minute = 0
            }
            branchFilter = "+:main"
            triggerBuild = always()
            withPendingChangesOnly = false
        }
    }

    features {
        perfmon {}
        
        notifications {
            notifierSettings = emailNotifier {
                email = "security-team@example.com"
            }
            buildFailedToStart = true
            buildFailed = true
        }
    }

    dependencies {
        artifacts(PermiflowScheduledScan) {
            buildRule = lastSuccessful()
            artifactRules = "history.zip!** => history/"
            cleanDestination = true
        }
    }
})
