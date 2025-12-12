package vcsRoots

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.vcs.*

object PermiflowVcsRoot : GitVcsRoot({
    id("PermiflowConfig")
    name = "Permiflow Configuration Repository"
    
    url = "https://github.com/your-org/permiflow-config.git"
    branch = "refs/heads/main"
    
    branchSpec = """
        +:refs/heads/*
        +:refs/tags/*
    """.trimIndent()
    
    // Authentication - use stored credentials
    authMethod = password {
        userName = "git"
        password = "credentialsJSON:github-token"
    }
    
    // Checkout options
    checkoutSubmodules = GitVcsRoot.CheckoutSubmodules.IGNORE
    useMirrors = true
    
    // Polling interval (in seconds)
    pollInterval = 60
})

object KubernetesManifestsVcsRoot : GitVcsRoot({
    id("KubernetesManifests")
    name = "Kubernetes Manifests Repository"
    
    url = "https://github.com/your-org/k8s-manifests.git"
    branch = "refs/heads/main"
    
    branchSpec = """
        +:refs/heads/*
    """.trimIndent()
    
    authMethod = password {
        userName = "git"
        password = "credentialsJSON:github-token"
    }
})
