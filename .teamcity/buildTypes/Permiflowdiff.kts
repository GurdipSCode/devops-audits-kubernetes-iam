package buildTypes

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.*
import jetbrains.buildServer.configs.kotlin.buildSteps.*

object PermiflowDiff : BuildType({
    name = "Permiflow RBAC Diff"
    description = "Compare two RBAC scans to detect permission drift"

    artifactRules = """
        reports/** => reports.zip
    """.trimIndent()

    params {
        text(
            "baseline_build_id", 
            "", 
            label = "Baseline Build ID",
            description = "TeamCity build ID containing baseline scan",
            display = ParameterDisplay.PROMPT,
            allowEmpty = false
        )
        text(
            "current_build_id", 
            "", 
            label = "Current Build ID",
            description = "TeamCity build ID containing current scan (leave empty for live scan)",
            display = ParameterDisplay.PROMPT,
            allowEmpty = true
        )
        param("permiflow.fail_on_drift", "false")
    }

    steps {
        script {
            id = "setup_kubeconfig"
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
            id = "download_baseline"
            name = "Download Baseline Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                mkdir -p reports/baseline reports/current
                
                BASELINE_ID="%baseline_build_id%"
                
                if [ -z "${'$'}BASELINE_ID" ]; then
                    echo "##teamcity[buildProblem description='Baseline build ID is required']"
                    exit 1
                fi
                
                echo "Downloading baseline scan from build ${'$'}BASELINE_ID..."
                
                # Download using TeamCity REST API
                curl -sSf \
                    -u "%system.teamcity.auth.userId%:%system.teamcity.auth.password%" \
                    "%teamcity.serverUrl%/app/rest/builds/id:${'$'}BASELINE_ID/artifacts/content/reports.zip" \
                    -o baseline.zip
                
                unzip -j baseline.zip "rbac-scan.json" -d reports/baseline/ || {
                    echo "##teamcity[buildProblem description='Failed to extract baseline scan from build ${'$'}BASELINE_ID']"
                    exit 1
                }
                
                echo "✓ Baseline scan downloaded"
            """.trimIndent()
        }

        script {
            id = "get_current_scan"
            name = "Get Current Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                export KUBECONFIG=${'$'}HOME/.kube/config
                CURRENT_ID="%current_build_id%"
                
                if [ -n "${'$'}CURRENT_ID" ]; then
                    echo "Downloading current scan from build ${'$'}CURRENT_ID..."
                    
                    curl -sSf \
                        -u "%system.teamcity.auth.userId%:%system.teamcity.auth.password%" \
                        "%teamcity.serverUrl%/app/rest/builds/id:${'$'}CURRENT_ID/artifacts/content/reports.zip" \
                        -o current.zip
                    
                    unzip -j current.zip "rbac-scan.json" -d reports/current/
                    echo "✓ Current scan downloaded from build ${'$'}CURRENT_ID"
                else
                    echo "Running live scan for current state..."
                    permiflow scan --output reports/current/rbac-scan.json --format json
                    echo "✓ Live scan complete"
                fi
            """.trimIndent()
        }

        script {
            id = "run_diff"
            name = "Run Diff Comparison"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                echo "=== Comparing RBAC Scans ==="
                echo ""
                
                # Generate Markdown diff report
                permiflow diff \
                    --baseline reports/baseline/rbac-scan.json \
                    --current reports/current/rbac-scan.json \
                    --output reports/diff.md \
                    --format markdown
                
                echo "✓ Markdown diff generated"
                
                # Generate JSON diff for programmatic access
                permiflow diff \
                    --baseline reports/baseline/rbac-scan.json \
                    --current reports/current/rbac-scan.json \
                    --output reports/diff.json \
                    --format json
                
                echo "✓ JSON diff generated"
                echo ""
            """.trimIndent()
        }

        script {
            id = "analyze_drift"
            name = "Analyze Permission Drift"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                if [ ! -f "reports/diff.json" ]; then
                    echo "No diff results found"
                    exit 0
                fi
                
                # Parse drift metrics
                ADDED=${'$'}(jq '.added | length // 0' reports/diff.json 2>/dev/null || echo "0")
                REMOVED=${'$'}(jq '.removed | length // 0' reports/diff.json 2>/dev/null || echo "0")
                CHANGED=${'$'}(jq '.changed | length // 0' reports/diff.json 2>/dev/null || echo "0")
                
                # Report to TeamCity
                echo "##teamcity[buildStatisticValue key='permiflow.drift.added' value='${'$'}ADDED']"
                echo "##teamcity[buildStatisticValue key='permiflow.drift.removed' value='${'$'}REMOVED']"
                echo "##teamcity[buildStatisticValue key='permiflow.drift.changed' value='${'$'}CHANGED']"
                
                TOTAL=${'$'}((ADDED + REMOVED + CHANGED))
                
                echo "=== Permission Drift Summary ==="
                echo "  Bindings Added:   ${'$'}ADDED"
                echo "  Bindings Removed: ${'$'}REMOVED"
                echo "  Bindings Changed: ${'$'}CHANGED"
                echo "  ─────────────────────────"
                echo "  Total Changes:    ${'$'}TOTAL"
                echo ""
                
                if [ "${'$'}TOTAL" -gt 0 ]; then
                    echo "##teamcity[message text='⚠️ Detected ${'$'}TOTAL RBAC changes since baseline' status='WARNING']"
                    
                    # Show what changed
                    if [ "${'$'}ADDED" -gt 0 ]; then
                        echo "New bindings added:"
                        jq -r '.added[]? | "  + \(.subject) -> \(.role)"' reports/diff.json 2>/dev/null || true
                    fi
                    
                    if [ "${'$'}REMOVED" -gt 0 ]; then
                        echo "Bindings removed:"
                        jq -r '.removed[]? | "  - \(.subject) -> \(.role)"' reports/diff.json 2>/dev/null || true
                    fi
                    
                    # Optionally fail on drift
                    if [ "%permiflow.fail_on_drift%" = "true" ]; then
                        echo "##teamcity[buildProblem description='RBAC drift detected: ${'$'}TOTAL changes since baseline']"
                        exit 1
                    fi
                else
                    echo "✓ No permission drift detected"
                fi
            """.trimIndent()
        }

        script {
            id = "cleanup"
            name = "Cleanup"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                #!/bin/bash
                rm -f ${'$'}HOME/.kube/config baseline.zip current.zip
                echo "✓ Cleaned up"
            """.trimIndent()
        }
    }

    features {
        perfmon {}
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Linux")
    }
})
