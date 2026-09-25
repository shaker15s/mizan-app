package app.mizan

import app.mizan.domain.authority.ExecutionAuthority
import app.mizan.graph.AuthorityDeps

/**
 * Demo flavor only.
 *
 * The simulator is compiled into this flavor and no other: `src/demo` is not
 * on the source path of staging or production, so a build that talks to the
 * service cannot contain a class that invents ERP results.
 */
fun createAuthority(deps: AuthorityDeps): ExecutionAuthority = SimulatedExecutionAuthority(deps)

fun createSimulationDirectory(): SimulationDirectory = MizanSimulationDirectory
