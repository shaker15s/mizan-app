package app.mizan

import app.mizan.domain.authority.ExecutionAuthority
import app.mizan.graph.AuthorityDeps

/**
 * Staging flavor.
 *
 * The only authority here is the MIZAN service. Without an HTTPS service URL
 * the remote authority refuses the write; it never falls back to a local
 * simulation. Kept identical to `src/production` on purpose: the two files
 * are duplicated instead of shared so that neither flavor can drift into the
 * other's behaviour by accident.
 */
fun createAuthority(deps: AuthorityDeps): ExecutionAuthority = RemoteExecutionAuthority(deps)

fun createSimulationDirectory(): SimulationDirectory = NoSimulationDirectory
