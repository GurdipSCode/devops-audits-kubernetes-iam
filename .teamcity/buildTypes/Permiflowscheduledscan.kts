package buildTypes

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.*
import jetbrains.buildServer.configs.kotlin.buildSteps.*
import jetbrains.buildServer.configs.kotlin.triggers.*

object PermiflowScheduledScan : BuildType({
    name = "Permiflow Scheduled Scan"
    description = "Automated daily RBAC scan with drift detection and notifications"

    artifactRules = """
        reports/** => reports.zip
        history/** => history.zip
    """.trimIndent()

    params {
        param("schedule.hour", "6")
        param("schedule.minute", "0")
        param("schedule.timezone", "UTC")
        param("notifications.slack_channel", "#security-alerts")
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
                export KUBECONFIG=${'$'}HOME/.kube/config
                
                if [ -n "${'$'}{KUBE_CONTEXT:-}" ]; then
                    kubectl config use-context "${'$'}KUBE_CONTEXT"
                fi
                
                echo "✓ Kubeconfig configured"
            """.trimIndent()
        }

        script {
            id = "daily_scan"
            name = "Run Daily Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                export KUBECONFIG=${'$'}HOME/.kube/config
                OUTPUT_DIR="reports"
                HISTORY_DIR="history"
                DATE=${'$'}(date +%Y-%m-%d)
                TIMESTAMP=${'$'}(date +%Y-%m-%dT%H:%M:%S%z)
                
                mkdir -p "${'$'}OUTPUT_DIR" "${'$'}HISTORY_DIR"
                
                echo "=== Permiflow Daily Scan ==="
                echo "Date: ${'$'}DATE"
                echo "Timestamp: ${'$'}TIMESTAMP"
                echo ""
                
                # Generate all report formats
                permiflow scan --output "${'$'}OUTPUT_DIR/rbac-scan.md" --format markdown
                permiflow scan --output "${'$'}OUTPUT_DIR/rbac-scan.json" --format json
                permiflow scan --output "${'$'}OUTPUT_DIR/rbac-scan.csv" --format csv
                
                # Archive with date for historical tracking
                cp "${'$'}OUTPUT_DIR/rbac-scan.json" "${'$'}HISTORY_DIR/rbac-scan-${'$'}DATE.json"
                
                # Create metadata file
                cat > "${'$'}HISTORY_DIR/scan-${'$'}DATE-metadata.json" << EOF
                {
                    "date": "${'$'}DATE",
                    "timestamp": "${'$'}TIMESTAMP",
                    "build_id": "%teamcity.build.id%",
                    "build_number": "%build.number%",
                    "agent": "%teamcity.agent.name%"
                }
                EOF
                
                echo "✓ Scan complete"
                echo "✓ Archived to history/rbac-scan-${'$'}DATE.json"
            """.trimIndent()
        }

        script {
            id = "compare_previous"
            name = "Compare with Previous Scan"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                OUTPUT_DIR="reports"
                HISTORY_DIR="history"
                
                # Find yesterday's date (works on both GNU and BSD date)
                YESTERDAY=${'$'}(date -d "yesterday" +%Y-%m-%d 2>/dev/null || date -v-1d +%Y-%m-%d 2>/dev/null || echo "")
                
                if [ -z "${'$'}YESTERDAY" ]; then
                    echo "Could not determine yesterday's date"
                    exit 0
                fi
                
                PREVIOUS_SCAN="${'$'}HISTORY_DIR/rbac-scan-${'$'}YESTERDAY.json"
                
                if [ -f "${'$'}PREVIOUS_SCAN" ]; then
                    echo "Comparing with previous scan from ${'$'}YESTERDAY..."
                    
                    permiflow diff \
                        --baseline "${'$'}PREVIOUS_SCAN" \
                        --current "${'$'}OUTPUT_DIR/rbac-scan.json" \
                        --output "${'$'}OUTPUT_DIR/daily-diff.md" \
                        --format markdown
                    
                    permiflow diff \
                        --baseline "${'$'}PREVIOUS_SCAN" \
                        --current "${'$'}OUTPUT_DIR/rbac-scan.json" \
                        --output "${'$'}OUTPUT_DIR/daily-diff.json" \
                        --format json
                    
                    # Parse and report drift
                    ADDED=${'$'}(jq '.added | length // 0' "${'$'}OUTPUT_DIR/daily-diff.json" 2>/dev/null || echo "0")
                    REMOVED=${'$'}(jq '.removed | length // 0' "${'$'}OUTPUT_DIR/daily-diff.json" 2>/dev/null || echo "0")
                    CHANGED=${'$'}(jq '.changed | length // 0' "${'$'}OUTPUT_DIR/daily-diff.json" 2>/dev/null || echo "0")
                    
                    echo "##teamcity[buildStatisticValue key='permiflow.daily.drift.added' value='${'$'}ADDED']"
                    echo "##teamcity[buildStatisticValue key='permiflow.daily.drift.removed' value='${'$'}REMOVED']"
                    echo "##teamcity[buildStatisticValue key='permiflow.daily.drift.changed' value='${'$'}CHANGED']"
                    
                    TOTAL=${'$'}((ADDED + REMOVED + CHANGED))
                    
                    if [ "${'$'}TOTAL" -gt 0 ]; then
                        echo "##teamcity[message text='📊 Daily drift: +${'$'}ADDED / -${'$'}REMOVED / ~${'$'}CHANGED changes' status='WARNING']"
                    else
                        echo "✓ No changes since yesterday"
                    fi
                else
                    echo "No previous scan found for ${'$'}YESTERDAY (first run or gap in history)"
                fi
            """.trimIndent()
        }

        script {
            id = "analyze_and_report"
            name = "Analyze and Generate Summary"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                OUTPUT_DIR="reports"
                DATE=${'$'}(date +%Y-%m-%d)
                
                if [ ! -f "${'$'}OUTPUT_DIR/rbac-scan.json" ]; then
                    echo "No scan results found"
                    exit 1
                fi
                
                # Parse metrics
                HIGH_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "HIGH")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                MEDIUM_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "MEDIUM")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                LOW_COUNT=${'$'}(jq '[.bindings[]? | select(.risk == "LOW")] | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                TOTAL=${'$'}(jq '.bindings | length // 0' "${'$'}OUTPUT_DIR/rbac-scan.json" 2>/dev/null || echo "0")
                
                # Report to TeamCity
                echo "##teamcity[buildStatisticValue key='permiflow.daily.high_risk' value='${'$'}HIGH_COUNT']"
                echo "##teamcity[buildStatisticValue key='permiflow.daily.medium_risk' value='${'$'}MEDIUM_COUNT']"
                echo "##teamcity[buildStatisticValue key='permiflow.daily.low_risk' value='${'$'}LOW_COUNT']"
                echo "##teamcity[buildStatisticValue key='permiflow.daily.total' value='${'$'}TOTAL']"
                
                echo ""
                echo "╔════════════════════════════════════════╗"
                echo "║     Daily RBAC Scan Summary            ║"
                echo "╠════════════════════════════════════════╣"
                echo "║  Date:          ${'$'}DATE                 ║"
                echo "║  Total Bindings: ${'$'}TOTAL                  "
                echo "║  ────────────────────────────────────  ║"
                echo "║  🔴 HIGH Risk:   ${'$'}HIGH_COUNT                  "
                echo "║  🟡 MEDIUM Risk: ${'$'}MEDIUM_COUNT                  "
                echo "║  🟢 LOW Risk:    ${'$'}LOW_COUNT                  "
                echo "╚════════════════════════════════════════╝"
                echo ""
                
                # Export for notification step
                echo "HIGH_COUNT=${'$'}HIGH_COUNT" >> ${'$'}OUTPUT_DIR/metrics.env
                echo "MEDIUM_COUNT=${'$'}MEDIUM_COUNT" >> ${'$'}OUTPUT_DIR/metrics.env
                echo "LOW_COUNT=${'$'}LOW_COUNT" >> ${'$'}OUTPUT_DIR/metrics.env
                echo "TOTAL=${'$'}TOTAL" >> ${'$'}OUTPUT_DIR/metrics.env
            """.trimIndent()
        }

        script {
            id = "send_notifications"
            name = "Send Notifications"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                OUTPUT_DIR="reports"
                
                # Load metrics
                if [ -f "${'$'}OUTPUT_DIR/metrics.env" ]; then
                    source "${'$'}OUTPUT_DIR/metrics.env"
                else
                    echo "No metrics found, skipping notifications"
                    exit 0
                fi
                
                # Skip if no Slack webhook configured
                if [ -z "${'$'}{SLACK_WEBHOOK_URL:-}" ]; then
                    echo "No Slack webhook configured (env.SLACK_WEBHOOK_URL)"
                    echo "Skipping Slack notifications"
                    exit 0
                fi
                
                DATE=${'$'}(date +%Y-%m-%d)
                BUILD_URL="%teamcity.serverUrl%/viewLog.html?buildId=%teamcity.build.id%"
                
                # Determine message color and emoji based on findings
                if [ "${'$'}HIGH_COUNT" -gt 0 ]; then
                    COLOR="danger"
                    EMOJI="🚨"
                    STATUS="HIGH RISK FINDINGS"
                elif [ "${'$'}MEDIUM_COUNT" -gt 0 ]; then
                    COLOR="warning"
                    EMOJI="⚠️"
                    STATUS="Medium risk findings present"
                else
                    COLOR="good"
                    EMOJI="✅"
                    STATUS="All clear"
                fi
                
                echo "Sending Slack notification..."
                
                curl -X POST -H 'Content-type: application/json' \
                    --data "{
                        \"text\": \"${'$'}EMOJI *Permiflow Daily Scan - ${'$'}DATE*\",
                        \"attachments\": [{
                            \"color\": \"${'$'}COLOR\",
                            \"fields\": [
                                {\"title\": \"Status\", \"value\": \"${'$'}STATUS\", \"short\": false},
                                {\"title\": \"Total Bindings\", \"value\": \"${'$'}TOTAL\", \"short\": true},
                                {\"title\": \"HIGH Risk\", \"value\": \"${'$'}HIGH_COUNT\", \"short\": true},
                                {\"title\": \"MEDIUM Risk\", \"value\": \"${'$'}MEDIUM_COUNT\", \"short\": true},
                                {\"title\": \"LOW Risk\", \"value\": \"${'$'}LOW_COUNT\", \"short\": true}
                            ],
                            \"actions\": [{
                                \"type\": \"button\",
                                \"text\": \"View Build\",
                                \"url\": \"${'$'}BUILD_URL\"
                            }]
                        }]
                    }" \
                    "${'$'}SLACK_WEBHOOK_URL"
                
                echo ""
                echo "✓ Slack notification sent"
            """.trimIndent()
        }

        script {
            id = "cleanup"
            name = "Cleanup"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                #!/bin/bash
                rm -f ${'$'}HOME/.kube/config
                rm -f reports/metrics.env
                echo "✓ Cleaned up credentials"
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
            enableQueueOptimization = false
        }
    }

    features {
        perfmon {}
        
        notifications {
            notifierSettings = emailNotifier {
                email = "security-team@example.com"
            }
            buildStarted = false
            buildFailedToStart = true
            buildFailed = true
            firstBuildErrorOccurs = true
        }
    }

    // Pull history from previous successful build
    dependencies {
        artifacts(PermiflowScheduledScan) {
            buildRule = lastSuccessful()
            artifactRules = "history.zip!** => history/"
            cleanDestination = true
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Linux")
    }
})
