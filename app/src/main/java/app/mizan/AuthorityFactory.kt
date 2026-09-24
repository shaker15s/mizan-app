package app.mizan

import app.mizan.domain.authority.ExecutionAuthority
import app.mizan.graph.AuthorityDeps

fun createAuthority(deps: AuthorityDeps): ExecutionAuthority {
    return if (BuildConfig.DEMO_MODE) {
        SimulatedExecutionAuthority(deps)
    } else {
        RemoteExecutionAuthority(deps)
    }
}
