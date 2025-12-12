package buildTypes

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.*
import jetbrains.buildServer.configs.kotlin.buildSteps.*

object PermiflowScan : BuildType({
    name = "Permiflow RBAC Scan"
    description = "Scan Kubernetes cluster RBAC bindings and generate reports"

    artifactRules = """
        reports/** => reports.zip
    """.trimIndent()

    params {
        param("env.PERMIFLOW_VERSION", "latest")
        param("permiflow.fail_on_high_risk", "false")
    }

    steps {
        script {
            id = "setup_kubeconfig"
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
            id = "install_permiflow"
            name = "Install Permiflow"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                if ! command -v permiflow &> /dev/null; then
                    echo "Installing Permiflow %env.PERMIFLOW_VERSION%..."
                    go install github.com/tutran-se/permiflow@%env.PERMIFLOW_VERSION%
                fi
                
                echo "Permiflow version:"
                permiflow --version
            """.trimIndent()
        }

        script {
            id = "run_scan"
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
                
                echo "=== Running Permiflow RBAC Scan ==="
                echo "Namespaces: ${'$'}{PERMIFLOW_NAMESPACES:-all}"
                echo ""
                
                # Generate Markdown report (human-readable)
                echo "Generating Markdown report..."
                permiflow scan ${'$'}NAMESPACE_FLAG --output "${'$'}OUTPUT_DIR/rbac-scan.md" --format markdown
                
                # Generate JSON report (machine-parseable)
                echo "Generating JSON report..."
                permiflow scan ${'$'}NAMESPACE_FLAG --output "${'$'}OUTPUT_DIR/rbac-scan.json" --format json
                
                # Generate CSV report (spreadsheet-compatible)
                echo "Generating CSV report..."
                permiflow scan ${'$'}NAMESPACE_FLAG --output "${'$'}OUTPUT_DIR/rbac-scan.csv" --format csv
                
                echo ""
                echo "=== Scan Complete ==="
                echo "Reports generated in ${'$'}OUTPUT_DIR/"
                ls -la "${'$'}OUTPUT_DIR/"
            """.trimIndent()
        }

        script {
            id = "analyze_risk"
            name = "Analyze Risk Levels"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                OUTPUT_DIR="%env.PERMIFLOW_OUTPUT_DIR%"
                
                if [ ! -f "${'$'}OUTPUT_DIR/rbac-scan.json" ]; then
                    echo "No scan results found"
                    exit 0
                fi
                
                # Parse risk levels from JSON
                HIGH_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "HIGH")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                MEDIUM_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "MEDIUM")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                LOW_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "LOW")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                TOTAL=${'$'}(jq '.bindings | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                
                # Report to TeamCity statistics
                echo "##teamcity[buildStatisticValue key='permiflow.high_risk' value='${'$'}HIGH_COUNT']"
                echo "##teamcity[buildStatisticValue key='permiflow.medium_risk' value='${'$'}MEDIUM_COUNT']"
                echo "##teamcity[buildStatisticValue key='permiflow.low_risk' value='${'$'}LOW_COUNT']"
                echo "##teamcity[buildStatisticValue key='permiflow.total_bindings' value='${'$'}TOTAL']"
                
                echo ""
                echo "=== Risk Summary ==="
                echo "  Total Bindings: ${'$'}TOTAL"
                echo "  HIGH Risk:      ${'$'}HIGH_COUNT"
                echo "  MEDIUM Risk:    ${'$'}MEDIUM_COUNT"
                echo "  LOW Risk:       ${'$'}LOW_COUNT"
                echo ""
                
                # Alert on HIGH risk findings
                if [ "${'$'}HIGH_COUNT" -gt 0 ]; then
                    echo "##teamcity[message text='⚠️ Found ${'$'}HIGH_COUNT HIGH risk RBAC bindings' status='WARNING']"
                    
                    # Optionally fail the build
                    if [ "%permiflow.fail_on_high_risk%" = "true" ]; then
                        echo "##teamcity[buildProblem description='Found ${'$'}HIGH_COUNT HIGH risk RBAC bindings']"
                        exit 1
                    fi
                fi
            """.trimIndent()
        }

        script {
            id = "cleanup"
            name = "Cleanup Credentials"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                #!/bin/bash
                rm -f ${'$'}HOME/.kube/config
                echo "✓ Cleaned up kubeconfig"
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
