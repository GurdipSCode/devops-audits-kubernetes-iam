package _Self

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.Project
import buildTypes.*
import vcsRoots.*

object Project : Project({
    id("Permiflow")
    name = "Permiflow RBAC Scanner"
    description = "Kubernetes RBAC scanning and audit pipeline using Permiflow"

    // Project-level parameters (inherited by all build configurations)
    params {
        // Kubernetes connection
        password(
            "env.KUBECONFIG_BASE64", 
            "credentialsJSON:kubeconfig-base64",
            label = "Kubeconfig (Base64)",
            description = "Base64-encoded kubeconfig file",
            display = ParameterDisplay.HIDDEN
        )
        param("env.KUBE_CONTEXT", "")
        
        // Permiflow settings
        param("env.PERMIFLOW_NAMESPACES", "")
        param("env.PERMIFLOW_OUTPUT_DIR", "reports")
        param("env.PERMIFLOW_VERSION", "latest")
        
        // Notifications
        password(
            "env.SLACK_WEBHOOK_URL", 
            "credentialsJSON:slack-webhook",
            label = "Slack Webhook URL",
            description = "Webhook URL for Slack notifications",
            display = ParameterDisplay.HIDDEN
        )
        
        // Build behavior
        param("permiflow.fail_on_high_risk", "false")
        param("permiflow.fail_on_drift", "false")
    }

    // VCS Roots
    vcsRoot(PermiflowVcsRoot)
    vcsRoot(KubernetesManifestsVcsRoot)

    // Build Configurations
    buildType(PermiflowScan)
    buildType(PermiflowDiff)
    buildType(PermiflowScheduledScan)

    // Build configuration ordering
    buildTypesOrder = arrayListOf(
        PermiflowScan,
        PermiflowDiff,
        PermiflowScheduledScan
    )

    // Shared resources (prevent concurrent scans to same cluster)
    features {
        feature {
            type = "JetBrains.SharedResources"
            param("name", "KubernetesCluster")
            param("type", "quoted")
            param("quota", "1")
        }
    }

    // Cleanup rules
    cleanup {
        baseRule {
            preventDependencyCleanup = false
        }
        history(days = 30)
        artifacts(days = 14, artifactPatterns = """
            +:**/*
        """.trimIndent())
    }

    // Sub-projects (if needed for multiple clusters)
    subProject(ProductionCluster)
    subProject(StagingCluster)
})

// Sub-project for production cluster
object ProductionCluster : Project({
    id("PermiflowProduction")
    name = "Production Cluster"
    description = "RBAC scanning for production Kubernetes cluster"

    params {
        param("env.KUBE_CONTEXT", "production")
        param("env.PERMIFLOW_NAMESPACES", "")
        param("permiflow.fail_on_high_risk", "true")
    }

    buildType(PermiflowScan)
    buildType(PermiflowScheduledScan)
})

// Sub-project for staging cluster
object StagingCluster : Project({
    id("PermiflowStaging")
    name = "Staging Cluster"
    description = "RBAC scanning for staging Kubernetes cluster"

    params {
        param("env.KUBE_CONTEXT", "staging")
        param("env.PERMIFLOW_NAMESPACES", "staging,development")
    }

    buildType(PermiflowScan)
})
